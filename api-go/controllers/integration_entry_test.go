package controllers

// Integration tests (MYSQL_TEST_DSN) for raw-material entry, butchery recording with
// by-products and both cost keys, and the POS sync path against the real schema.
// Numbers carry a per-run suffix so the tests can run twice without a reset.

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"ban-food-intelligence/api-go/models"
)

func runSuffix() string { return fmt.Sprintf("%06d", time.Now().UnixNano()/1000%1000000) }

func testUUIDv7() string { return testUUIDv7At(time.Now()) }

// testUUIDv7At is a UUIDv7 created at ts: the sale time the API books for an offline sale.
func testUUIDv7At(ts time.Time) string {
	h := fmt.Sprintf("%012x", ts.UnixMilli())
	return fmt.Sprintf("%s-%s-7%03x-8%03x-%012x", h[:8], h[8:12], rand.Intn(0xfff), rand.Intn(0xfff), rand.Int63n(1<<48))
}

func TestIntegrationPurchaseNeedsSupplierAndExpiry(t *testing.T) {
	db := testDB(t)
	e := NewEntryController(db)
	run := runSuffix()

	// A perishable raw material without expiry date is refused (API and DB trigger).
	code, body := call(t, e.Purchase, "POST", "/purchases", PurchaseRequest{
		ReceiptNo: "ALS-IT-" + run, SupplierCode: "TED-ET-01", ReceiptDate: "2026-10-28",
		Lines: []PurchaseLineRequest{{SKU: "HM-ET-DANA", LotNo: "ET-IT-" + run, SupplierLotNo: "B-1", Qty: 50, UnitPrice: 9.7}},
	})
	if code != http.StatusUnprocessableEntity {
		t.Fatalf("missing expiry: want 422, got %d %s", code, body)
	}
	_, err := db.Exec(`INSERT INTO lot (lot_no, product_id, origin_type, supplier_id, lot_date, initial_qty, uom_code, unit_cost)
		SELECT ?, p.id, 'PURCHASE', s.id, '2026-10-28', 1, 'KG', 1 FROM product p, supplier s WHERE p.sku='HM-ET-DANA' AND s.code='TED-ET-01'`, "ET-DB-"+run)
	if err == nil || classifyCode(err) != "INVALID_INPUT" {
		t.Fatalf("DB trigger must refuse a purchased lot without expiry, got %v", err)
	}

	// Unknown supplier -> 404.
	code, _ = call(t, e.Purchase, "POST", "/purchases", PurchaseRequest{
		ReceiptNo: "ALS-IT-X" + run, SupplierCode: "NOPE", ReceiptDate: "2026-10-28",
		Lines: []PurchaseLineRequest{{SKU: "HM-ET-DANA", LotNo: "ET-IT-X" + run, Qty: 1, UnitPrice: 1, ExpiryDate: "2026-11-07"}},
	})
	if code != http.StatusNotFound {
		t.Fatalf("unknown supplier: want 404, got %d", code)
	}

	// A complete entry: lot with supplier, supplier batch, date, qty, expiry; PURCHASE_IN posted.
	code, body = call(t, e.Purchase, "POST", "/purchases", PurchaseRequest{
		ReceiptNo: "ALS-IT-" + run, SupplierCode: "TED-ET-01", ReceiptDate: "2026-10-28", InvoiceNo: "FTR-IT",
		Lines: []PurchaseLineRequest{{SKU: "HM-ET-DANA", LotNo: "ET-IT-" + run, SupplierLotNo: "B-1", Qty: 50, UnitPrice: 9.7, ExpiryDate: "2026-11-07"}},
	})
	if code != http.StatusCreated {
		t.Fatalf("purchase: %d %s", code, body)
	}
	var res PurchaseResultDto
	_ = json.Unmarshal(body, &res)
	if res.Total != 485 {
		t.Fatalf("total %.2f, want 485.00", res.Total)
	}
	var supplier, supplierLot, expiry string
	var stock float64
	if err := db.QueryRow(`SELECT s.code, l.supplier_lot_no, DATE_FORMAT(l.expiry_date, '%Y-%m-%d'), sb.qty
		FROM lot l JOIN supplier s ON s.id = l.supplier_id JOIN stock_balance sb ON sb.lot_id = l.id
		WHERE l.lot_no = ?`, "ET-IT-"+run).Scan(&supplier, &supplierLot, &expiry, &stock); err != nil {
		t.Fatal(err)
	}
	if supplier != "TED-ET-01" || supplierLot != "B-1" || expiry != "2026-11-07" || stock != 50 {
		t.Fatalf("lot stored as %s %s %s %.3f", supplier, supplierLot, expiry, stock)
	}

	// The same receipt number again -> 409 and nothing half-written.
	code, _ = call(t, e.Purchase, "POST", "/purchases", PurchaseRequest{
		ReceiptNo: "ALS-IT-" + run, SupplierCode: "TED-ET-01", ReceiptDate: "2026-10-28",
		Lines: []PurchaseLineRequest{{SKU: "HM-ET-DANA", LotNo: "ET-IT-Y" + run, Qty: 1, UnitPrice: 1, ExpiryDate: "2026-11-07"}},
	})
	if code != http.StatusConflict {
		t.Fatalf("duplicate receipt: want 409, got %d", code)
	}
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM lot WHERE lot_no = ?`, "ET-IT-Y"+run).Scan(&n)
	if n != 0 {
		t.Fatal("failed receipt left a lot behind")
	}
}

func classifyCode(err error) string { _, c := classifyDBError(err); return c }

func TestIntegrationButcheryWithByProductBothKeys(t *testing.T) {
	db := testDB(t)
	e := NewEntryController(db)
	run := runSuffix()
	carcass := "KUZU-IT-" + run

	code, body := call(t, e.Purchase, "POST", "/purchases", PurchaseRequest{
		ReceiptNo: "ALS-KZ-" + run, SupplierCode: "TED-HAY-01", ReceiptDate: "2026-10-27",
		Lines: []PurchaseLineRequest{{SKU: "KZ-KARKAS", LotNo: carcass, SupplierLotNo: "KUPE-TR-10-1", Qty: 24, UnitPrice: 11.8, ExpiryDate: "2026-11-03"}},
	})
	if code != http.StatusCreated {
		t.Fatalf("carcass purchase: %d %s", code, body)
	}

	outputs := func(fire float64) []ButcheryOutputRequest {
		return []ButcheryOutputRequest{
			{SKU: "KZ-BUT", Kind: "CUT", WeightKg: 6.2, LotNo: "KZB-IT-" + run},
			{SKU: "KZ-SIRT", Kind: "CUT", WeightKg: 3.4, LotNo: "KZS-IT-" + run},
			{SKU: "KZ-OMUZ", Kind: "CUT", WeightKg: 4.8, LotNo: "KZO-IT-" + run},
			{SKU: "KZ-KABURGA", Kind: "CUT", WeightKg: 2.9, LotNo: "KZK-IT-" + run},
			{SKU: "KZ-KIYMA", Kind: "CUT", WeightKg: 3.8, LotNo: "KZY-IT-" + run},
			{SKU: "KZ-SAKATAT", Kind: "BY_PRODUCT", WeightKg: 0.8, LotNo: "KZC-IT-" + run},
			{SKU: "KZ-FIRE", Kind: "WASTE", WeightKg: fire},
		}
	}
	req := ButcheryOperationRequest{OpNo: "PRC-IT-" + run, InputLotNo: carcass, InputWeightKg: 24,
		AllocationMethod: "QUANTITY", OpDate: "2026-10-28", OperatorName: "OP-IT", Outputs: outputs(1.6)}

	// 0.5 kg not recorded -> 422 WEIGHT_IMBALANCE, nothing written.
	code, body = call(t, e.ButcheryOperation, "POST", "/butchery/operations", req)
	if code != http.StatusUnprocessableEntity {
		t.Fatalf("imbalance: want 422, got %d %s", code, body)
	}
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM butchery_operation WHERE op_no = ?`, req.OpNo).Scan(&n)
	if n != 0 {
		t.Fatal("rejected operation was written")
	}

	// Balanced (6.2+3.4+4.8+2.9+3.8 cuts + 0.8 by-product + 2.1 waste = 24.0 kg).
	req.Outputs = outputs(2.1)
	req.DryRun = true
	code, body = call(t, e.ButcheryOperation, "POST", "/butchery/operations", req)
	if code != http.StatusOK {
		t.Fatalf("dry run: %d %s", code, body)
	}
	var dry ButcheryOperationResultDto
	_ = json.Unmarshal(body, &dry)
	if dry.Status != "DRY_RUN" || dry.TotalCost != 283.2 {
		t.Fatalf("dry run result %+v", dry)
	}
	for _, key := range []string{"QUANTITY", "VALUE"} {
		if r := dry.Comparison[key]; !r.SumsExact || r.Allocated != 283.2 {
			t.Fatalf("%s key: allocated %.2f exact %v", key, r.Allocated, r.SumsExact)
		}
	}
	_ = db.QueryRow(`SELECT COUNT(*) FROM butchery_operation WHERE op_no = ?`, req.OpNo).Scan(&n)
	if n != 0 {
		t.Fatal("dry run wrote the operation")
	}

	req.DryRun = false
	code, body = call(t, e.ButcheryOperation, "POST", "/butchery/operations", req)
	if code != http.StatusCreated {
		t.Fatalf("post: %d %s", code, body)
	}

	// The reconciliation view agrees: weight and cost balance, cost fully allocated.
	var weightOK, allocOK bool
	var allocated, byProduct float64
	if err := db.QueryRow(`SELECT weight_ok, allocation_ok, allocated_total FROM v_butchery_reconciliation WHERE op_no = ?`, req.OpNo).
		Scan(&weightOK, &allocOK, &allocated); err != nil {
		t.Fatal(err)
	}
	if !weightOK || !allocOK || allocated != 283.2 {
		t.Fatalf("reconciliation: weight_ok=%v allocation_ok=%v allocated=%.2f", weightOK, allocOK, allocated)
	}
	_ = db.QueryRow(`SELECT allocated_cost FROM butchery_output bo JOIN butchery_operation o ON o.id = bo.operation_id
		WHERE o.op_no = ? AND bo.output_kind = 'BY_PRODUCT'`, req.OpNo).Scan(&byProduct)
	// QUANTITY key: the 21.9 kg of cuts and by-product carry the cost, 283.20 x 0.8 / 21.9 = 10.345
	if byProduct < 10.34 || byProduct > 10.35 {
		t.Fatalf("by-product allocated %.2f, want 10.34 or 10.35", byProduct)
	}
	// Six part lots (5 cuts + by-product) linked to the carcass; carcass stock consumed.
	_ = db.QueryRow(`SELECT COUNT(*) FROM lot_link ll JOIN lot p ON p.id = ll.parent_lot_id WHERE p.lot_no = ? AND ll.link_type = 'BUTCHERY'`, carcass).Scan(&n)
	if n != 6 {
		t.Fatalf("want 6 lot links from the carcass, got %d", n)
	}
	var carcassLeft float64
	_ = db.QueryRow(`SELECT sb.qty FROM stock_balance sb JOIN lot l ON l.id = sb.lot_id WHERE l.lot_no = ?`, carcass).Scan(&carcassLeft)
	if carcassLeft != 0 {
		t.Fatalf("carcass balance %.3f, want 0", carcassLeft)
	}

	// Forward trace from the carcass reaches the by-product lot.
	code, body = call(t, NewTraceController(db).TraceHandler, "GET", "/trace/"+carcass+"?direction=forward", nil)
	var tr TraceResponse
	_ = json.Unmarshal(body, &tr)
	found := false
	for _, l := range tr.Lots {
		found = found || l.LotNo == "KZC-IT-"+run
	}
	if code != 200 || !found {
		t.Fatalf("forward trace misses the by-product lot: %d %s", code, body)
	}
}

// signedSale builds one signed POST /sync body for a sale, like the Kotlin SyncWorker.
func signedSale(t *testing.T, pos *testPOS, lot string, qty float64, uuid string) *http.Request {
	return pos.request(t, lot, qty, uuid, true, true)
}

func TestIntegrationSyncIdempotentAndReplayMismatch(t *testing.T) {
	db := testDB(t)
	ctrl := NewSyncController(db)
	pos := newTestPOS(t, "POS-IT-"+runSuffix())
	uuid := testUUIDv7()

	var before int
	_ = db.QueryRow(`SELECT COUNT(*) FROM sale`).Scan(&before)
	for i := 0; i < 2; i++ { // the second send is a network retry of the same signed record
		rec := httptestRecorder(ctrl, signedSale(t, pos, "KZB-2026-001", 0.25, uuid))
		var resp models.SyncBatchResponse
		_ = json.Unmarshal(rec.Body.Bytes(), &resp)
		if rec.Code != 200 || len(resp.AcknowledgedIDs) != 1 {
			t.Fatalf("send %d: %d %s", i+1, rec.Code, rec.Body.String())
		}
	}
	var after, movements int
	_ = db.QueryRow(`SELECT COUNT(*) FROM sale`).Scan(&after)
	_ = db.QueryRow(`SELECT COUNT(*) FROM sale s JOIN sale_line sl ON sl.sale_id = s.id JOIN stock_movement sm ON sm.id = sl.stock_movement_id
		WHERE s.sale_uuid = ? AND sm.movement_type = 'SALE'`, uuid).Scan(&movements)
	if after != before+1 || movements != 1 {
		t.Fatalf("retry duplicated the sale: sales %d -> %d, movements %d", before, after, movements)
	}
	var keys int
	_ = db.QueryRow(`SELECT COUNT(*) FROM pos_device_key WHERE device_id = ?`, pos.device).Scan(&keys)
	if keys != 1 {
		t.Fatal("device key was not pinned in MySQL")
	}

	// Same UUIDv7, different (validly signed) quantity: not silently acknowledged.
	rec := httptestRecorder(ctrl, signedSale(t, pos, "KZB-2026-001", 0.75, uuid))
	var resp models.SyncBatchResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if len(resp.AcknowledgedIDs) != 0 || len(resp.Rejected) != 1 || resp.Rejected[0].Code != "REPLAY_MISMATCH" {
		t.Fatalf("replay with other data: %s", rec.Body.String())
	}

	// A fresh controller (API restart) still knows the pinned key from MySQL.
	other := newTestPOS(t, pos.device)
	rec = httptestRecorder(NewSyncController(db), signedSale(t, other, "KZB-2026-001", 0.25, testUUIDv7()))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("other key after restart: want 403, got %d", rec.Code)
	}
}

// The spec's rule "recalled lots cannot be sold", end to end through the API.
func TestIntegrationSaleOfRecalledLotIsRejected(t *testing.T) {
	db := testDB(t)
	run := runSuffix()
	code, body := call(t, NewRecallController(db).TriggerRecall, "POST", "/recall",
		RecallTriggerRequest{RecallCode: "RCL-IT-S" + run, TriggerLot: "ET-2026-002"})
	if code != http.StatusCreated {
		t.Fatalf("recall: %d %s", code, body)
	}
	pos := newTestPOS(t, "POS-IT-R"+run)
	rec := httptestRecorder(NewSyncController(db), signedSale(t, pos, "SUC-2026-046", 1, testUUIDv7()))
	var resp models.SyncBatchResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if len(resp.Rejected) != 1 || resp.Rejected[0].Code != "LOT_BLOCKED" {
		t.Fatalf("sale of recalled SUC-2026-046 not blocked: %s", rec.Body.String())
	}
}

// Expired goods are not sold. In the seed calendar KZY-2026-001 (minced lamb, 2-day use-by)
// expired yesterday: a sale made today is rejected, while a sale made offline on its last day
// is accepted when it is synced later, because the rule uses the sale's own time.
func TestIntegrationSaleOfExpiredLotIsRejected(t *testing.T) {
	db := testDB(t)
	var expiry time.Time
	if err := db.QueryRow(`SELECT expiry_date FROM lot WHERE lot_no = 'KZY-2026-001'`).Scan(&expiry); err != nil {
		t.Fatal(err)
	}
	if !expiry.Before(time.Now().UTC().Truncate(24 * time.Hour)) {
		t.Fatalf("seed calendar: KZY-2026-001 should be expired, expiry %s", expiry.Format("2006-01-02"))
	}
	pos := newTestPOS(t, "POS-IT-E"+runSuffix())

	rec := httptestRecorder(NewSyncController(db), signedSale(t, pos, "KZY-2026-001", 0.1, testUUIDv7()))
	var resp models.SyncBatchResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if len(resp.Rejected) != 1 || resp.Rejected[0].Code != "LOT_EXPIRED" {
		t.Fatalf("sale of expired KZY-2026-001 not blocked: %s", rec.Body.String())
	}

	lastDay := expiry.Add(12 * time.Hour)
	rec = httptestRecorder(NewSyncController(db), signedSale(t, pos, "KZY-2026-001", 0.1, testUUIDv7At(lastDay)))
	resp = models.SyncBatchResponse{}
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if len(resp.AcknowledgedIDs) != 1 {
		t.Fatalf("offline sale from the last day before expiry was rejected: %s", rec.Body.String())
	}
}

func httptestRecorder(ctrl *SyncController, req *http.Request) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	ctrl.IngestOutbox(rec, req)
	return rec
}
