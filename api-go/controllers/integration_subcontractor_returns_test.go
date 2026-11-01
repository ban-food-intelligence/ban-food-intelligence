package controllers

// Integration tests (MYSQL_TEST_DSN) for the quantity variance on the input side of an
// external production order: leftover raw material the subcontractor sends back with the
// finished goods. Only the consumed quantity is costed in the EKP and linked to the finished
// lot; the returned quantity goes back to stock. Also: unknown orders and butchery operations
// are 404, not an all-zero 200.

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"
)

func TestIntegrationProductionReceiptWithSubcontractorReturn(t *testing.T) {
	db := testDB(t)
	e := NewEntryController(db)
	p := NewProductionController(db)
	run := runSuffix()
	today := time.Now().Format("2006-01-02")
	in10 := time.Now().AddDate(0, 0, 10).Format("2006-01-02")
	in90 := time.Now().AddDate(0, 3, 0).Format("2006-01-02")

	buy := func(supplier, sku, lot string, qty, price float64) {
		code, body := call(t, e.Purchase, "POST", "/purchases", PurchaseRequest{
			ReceiptNo: "ALS-RET-" + lot, SupplierCode: supplier, ReceiptDate: today,
			Lines: []PurchaseLineRequest{{SKU: sku, LotNo: lot, Qty: qty, UnitPrice: price, ExpiryDate: in10}},
		})
		if code != http.StatusCreated {
			t.Fatalf("purchase %s: %d %s", lot, code, body)
		}
	}
	meat, spice, casing, other := "ET-RET-"+run, "BAH-RET-"+run, "BAG-RET-"+run, "ET-OTH-"+run
	buy("TED-ET-01", "HM-ET-DANA", meat, 103, 9.7)
	buy("TED-BAH-01", "HM-BAHARAT", spice, 2, 24)
	buy("TED-BAG-01", "HM-BAGIRSAK", casing, 5, 11.6)
	buy("TED-ET-01", "HM-ET-DANA", other, 10, 9.7)

	// 103 kg meat sent, 3 kg come back: the spec example (100 kg consumed) must still give 18.16 €.
	order := ProductionOrderRequest{
		OrderNo: "URT-RET-" + run, ProductSKU: "MM-SUCUK-1KG", SubcontractorCode: "FAS-01", PlannedQty: 85,
		ServiceUnitPrice: 5.5, OrderDate: today,
		Inputs: []ProductionInputRequest{{LotNo: meat, Qty: 103}, {LotNo: spice, Qty: 2}, {LotNo: casing, Qty: 5}},
	}
	if code, body := call(t, e.ProductionOrder, "POST", "/production/orders", order); code != http.StatusCreated {
		t.Fatalf("create order: %d %s", code, body)
	}

	receipt := func(returns []ProductionInputRequest) CompleteProductionRequest {
		return CompleteProductionRequest{
			OrderNo: order.OrderNo, ActualQty: 85, OutputLotNo: "SUC-RET-" + run, LotDate: today,
			ExpiryDate: in90, InvoiceNo: "FTR-RET-" + run, ReturnedInputs: returns,
		}
	}
	stock := func(lot, wh string) float64 {
		var q float64
		_ = db.QueryRow(`SELECT COALESCE(SUM(sb.qty), 0) FROM stock_balance sb JOIN lot l ON l.id = sb.lot_id
			JOIN warehouse w ON w.id = sb.warehouse_id WHERE l.lot_no = ? AND w.code = ?`, lot, wh).Scan(&q)
		return q
	}
	untouched := func(step string) {
		t.Helper()
		var status string
		var lots int
		_ = db.QueryRow(`SELECT status FROM production_order WHERE order_no = ?`, order.OrderNo).Scan(&status)
		_ = db.QueryRow(`SELECT COUNT(*) FROM lot WHERE lot_no = ?`, "SUC-RET-"+run).Scan(&lots)
		if status != "MATERIAL_SENT" || lots != 0 || stock(meat, "FASON-01") != 103 || stock(meat, "ANA-DEPO") != 0 {
			t.Fatalf("%s left data behind: status %s, %d output lots, meat FASON %.3f / ANA-DEPO %.3f",
				step, status, lots, stock(meat, "FASON-01"), stock(meat, "ANA-DEPO"))
		}
	}

	// Refused before anything is written.
	cases := []struct {
		name    string
		returns []ProductionInputRequest
		want    int
	}{
		{"more than sent", []ProductionInputRequest{{LotNo: meat, Qty: 104}}, http.StatusUnprocessableEntity},
		{"lot not on the order", []ProductionInputRequest{{LotNo: other, Qty: 1}}, http.StatusUnprocessableEntity},
		{"negative qty", []ProductionInputRequest{{LotNo: meat, Qty: -3}}, http.StatusUnprocessableEntity},
		{"lot listed twice", []ProductionInputRequest{{LotNo: meat, Qty: 1}, {LotNo: meat, Qty: 1}}, http.StatusUnprocessableEntity},
		// a valid return followed by a failing one: the first is rolled back too
		{"second line fails", []ProductionInputRequest{{LotNo: meat, Qty: 3}, {LotNo: spice, Qty: 5}}, http.StatusUnprocessableEntity},
	}
	for _, tc := range cases {
		if code, body := call(t, p.CalculateOrCompleteProduction, "POST", "/production", receipt(tc.returns)); code != tc.want {
			t.Fatalf("%s: want %d, got %d %s", tc.name, tc.want, code, body)
		}
		untouched(tc.name)
	}
	if code, body := call(t, p.CalculateOrCompleteProduction, "POST", "/production",
		CompleteProductionRequest{OrderNo: order.OrderNo, ReturnedInputs: []ProductionInputRequest{{LotNo: meat, Qty: 3}}}); code != http.StatusUnprocessableEntity {
		t.Fatalf("return without a receipt: want 422, got %d %s", code, body)
	}
	untouched("return without a receipt")

	// The receipt with 3 kg meat back.
	code, body := call(t, p.CalculateOrCompleteProduction, "POST", "/production", receipt([]ProductionInputRequest{{LotNo: meat, Qty: 3}}))
	var done ProductionOrderDto
	_ = json.Unmarshal(body, &done)
	if code != http.StatusOK || done.Status != "RECEIVED" || !near(done.MaterialCost, 1076) || !near(done.TotalCost, 1543.5) ||
		!near(done.EKPDisplay, 18.16) {
		t.Fatalf("receipt with return: %d %s", code, body)
	}
	if stock(meat, "FASON-01") != 0 || stock(meat, "ANA-DEPO") != 3 {
		t.Fatalf("returned meat not back in stock: FASON %.3f, ANA-DEPO %.3f", stock(meat, "FASON-01"), stock(meat, "ANA-DEPO"))
	}
	var sent, returned, consumed, linked float64
	_ = db.QueryRow(`SELECT pi.qty_sent, pi.qty_returned, pi.qty_consumed, ll.qty_consumed
		FROM production_input pi JOIN production_order po ON po.id = pi.production_order_id
		JOIN lot l ON l.id = pi.lot_id
		JOIN lot_link ll ON ll.parent_lot_id = pi.lot_id AND ll.ref_type = 'production_order' AND ll.ref_id = po.id
		WHERE po.order_no = ? AND l.lot_no = ?`, order.OrderNo, meat).Scan(&sent, &returned, &consumed, &linked)
	if sent != 103 || returned != 3 || consumed != 100 || linked != 100 {
		t.Fatalf("input quantities: sent %.3f, returned %.3f, consumed %.3f, linked %.3f", sent, returned, consumed, linked)
	}
	var moves int
	_ = db.QueryRow(`SELECT COUNT(*) FROM stock_movement sm JOIN lot l ON l.id = sm.lot_id
		WHERE l.lot_no = ? AND sm.movement_type IN ('TRANSFER_OUT','TRANSFER_IN') AND sm.note LIKE 'Fasondan iade %'`, meat).Scan(&moves)
	if moves != 2 {
		t.Fatalf("want a TRANSFER_OUT/IN pair for the return, got %d movements", moves)
	}
	if code, body := call(t, p.CalculateOrCompleteProduction, "POST", "/production", receipt(nil)); code != http.StatusConflict {
		t.Fatalf("receiving twice: want 409, got %d %s", code, body)
	}
}

func TestIntegrationUnknownOrderAndOperationAreNotFound(t *testing.T) {
	db := testDB(t)
	p := NewProductionController(db)
	run := runSuffix()
	if code, body := call(t, p.CalculateOrCompleteProduction, "POST", "/production", CompleteProductionRequest{OrderNo: "NO-ORDER-" + run}); code != http.StatusNotFound {
		t.Fatalf("cost of unknown order: want 404, got %d %s", code, body)
	}
	if code, body := call(t, p.CalculateOrCompleteProduction, "POST", "/production",
		CompleteProductionRequest{OrderNo: "NO-ORDER-" + run, ActualQty: 5, OutputLotNo: "X-" + run}); code != http.StatusNotFound {
		t.Fatalf("receipt of unknown order: want 404, got %d %s", code, body)
	}
	if code, body := call(t, NewButcheryController(db).ReconcileButchery, "POST", "/butchery", ButcheryReconcileRequest{OperationNo: "NO-OP-" + run}); code != http.StatusNotFound {
		t.Fatalf("unknown butchery operation: want 404, got %d %s", code, body)
	}
	if code, body := call(t, NewButcheryController(db).ReconcileButchery, "POST", "/butchery", ButcheryReconcileRequest{OperationNo: "PRC-2026-001"}); code != http.StatusOK {
		t.Fatalf("seeded butchery operation: want 200, got %d %s", code, body)
	}
}
