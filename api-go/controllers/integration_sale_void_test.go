package controllers

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

// A wrong POS sale is corrected by a reversal, never by deleting it: the SALE movement stays,
// a RETURN movement puts the packs back into the store, the void is kept in the append-only
// ledger and audit_log (no table of its own), the sales report flags the line, and a second
// void of the same sale is refused.
func TestIntegrationSaleVoidReversesWithoutDeleting(t *testing.T) {
	db := testDB(t)
	defer db.Close()
	run := runSuffix()
	lot := "VOID-" + run
	shipmentLot(t, db, lot, 5, -1, 30)
	if code, body := call(t, NewMovementsController(db).Transfer, "POST", "/movements/transfer",
		TransferRequest{LotNo: lot, FromWarehouse: "ANA-DEPO", ToWarehouse: "MAGAZA-01", Qty: 3}); code != http.StatusCreated {
		t.Fatalf("transfer to the shop: %d %s", code, body)
	}

	uuid := testUUIDv7()
	pos := newTestPOS(t, "POS-VOID-"+run)
	if rec := httptestRecorder(NewSyncController(db), signedSale(t, pos, lot, 2, uuid)); rec.Code != http.StatusOK {
		t.Fatalf("sale: %d %s", rec.Code, rec.Body.String())
	}
	shop := func() float64 {
		var q float64
		if err := db.QueryRow(`SELECT COALESCE(SUM(sb.qty),0) FROM stock_balance sb JOIN lot l ON l.id = sb.lot_id
			JOIN warehouse w ON w.id = sb.warehouse_id WHERE l.lot_no = ? AND w.code = 'MAGAZA-01'`, lot).Scan(&q); err != nil {
			t.Fatal(err)
		}
		return q
	}
	if got := shop(); got != 1 {
		t.Fatalf("shop after sale = %v, want 1", got)
	}

	ctrl := NewSaleVoidController(db)
	if code, body := call(t, ctrl.Void, "POST", "/sales/void", SaleVoidRequest{SaleUUID: uuid, Reason: ""}); code != http.StatusUnprocessableEntity {
		t.Fatalf("void without a reason: %d %s, want 422", code, body)
	}
	// The reason is part of the movement note (255 characters): more than 200 is refused up front.
	if code, body := call(t, ctrl.Void, "POST", "/sales/void", SaleVoidRequest{SaleUUID: uuid, Reason: strings.Repeat("ş", 201)}); code != http.StatusUnprocessableEntity {
		t.Fatalf("void with a 201-character reason: %d %s, want 422", code, body)
	}
	if code, body := call(t, ctrl.Void, "POST", "/sales/void", SaleVoidRequest{SaleUUID: testUUIDv7(), Reason: "yanlış ürün"}); code != http.StatusNotFound {
		t.Fatalf("void of an unknown sale: %d %s, want 404", code, body)
	}
	code, body := call(t, ctrl.Void, "POST", "/sales/void", SaleVoidRequest{SaleUUID: uuid, Reason: "Kasada yanlış ürün okutuldu"})
	if code != http.StatusCreated {
		t.Fatalf("void: %d %s", code, body)
	}
	var res SaleVoidDto
	if err := json.Unmarshal(body, &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Lines) != 1 || res.Lines[0].Qty != 2 || res.Lines[0].Warehouse != "MAGAZA-01" || res.Lines[0].Balance != 3 {
		t.Fatalf("void result = %+v, want 1 line of 2 back in MAGAZA-01 (balance 3)", res)
	}
	if got := shop(); got != 3 {
		t.Fatalf("shop after void = %v, want 3", got)
	}

	// Nothing deleted: the SALE movement stays and a RETURN of the same size follows it.
	var sales, returns int
	var returned float64
	if err := db.QueryRow(`SELECT SUM(sm.movement_type = 'SALE'), SUM(sm.movement_type = 'RETURN' AND sm.ref_type = 'sale_void'),
		COALESCE(SUM(IF(sm.movement_type = 'RETURN', sm.qty, 0)), 0)
		FROM stock_movement sm JOIN lot l ON l.id = sm.lot_id WHERE l.lot_no = ?`, lot).Scan(&sales, &returns, &returned); err != nil {
		t.Fatal(err)
	}
	if sales != 1 || returns != 1 || returned != 2 {
		t.Fatalf("ledger: %d SALE, %d sale_void RETURN of %v; want 1, 1, 2", sales, returns, returned)
	}
	var audits int
	if err := db.QueryRow(`SELECT COUNT(*) FROM audit_log a JOIN sale s ON s.id = a.row_id
		WHERE a.table_name = 'sale' AND a.action = 'SALE_VOID' AND s.sale_uuid = ?`, uuid).Scan(&audits); err != nil {
		t.Fatal(err)
	}
	if audits != 1 {
		t.Fatalf("audit_log SALE_VOID rows = %d, want 1", audits)
	}

	// The sales report keeps the line and flags it.
	_, body = call(t, NewReportsController(db).Sales, "GET", "/sales", nil)
	var lines []SaleReportLineDto
	if err := json.Unmarshal(body, &lines); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, l := range lines {
		if l.SaleUUID == uuid {
			found = true
			if !l.Voided || l.VoidReason != "Kasada yanlış ürün okutuldu" {
				t.Fatalf("report line = %+v, want voided with its reason", l)
			}
		}
	}
	if !found {
		t.Fatal("voided sale missing from GET /sales")
	}

	// A second void would return the packs twice: refused, stock unchanged.
	if code, body := call(t, ctrl.Void, "POST", "/sales/void", SaleVoidRequest{SaleUUID: uuid, Reason: "tekrar"}); code != http.StatusConflict {
		t.Fatalf("second void: %d %s, want 409", code, body)
	}
	if got := shop(); got != 3 {
		t.Fatalf("shop after refused second void = %v, want 3", got)
	}
	// The void lives in append-only records: neither its RETURN movement nor its audit row can go.
	if _, err := db.Exec(`DELETE FROM stock_movement WHERE ref_type = 'sale_void' AND ref_id = (SELECT id FROM sale WHERE sale_uuid = ?)`, uuid); err == nil ||
		!strings.Contains(err.Error(), "IMMUTABLE") {
		t.Fatalf("DELETE of the void's RETURN movement: %v, want IMMUTABLE", err)
	}
	if _, err := db.Exec(`DELETE a FROM audit_log a JOIN sale s ON s.id = a.row_id
		WHERE a.table_name = 'sale' AND a.action = 'SALE_VOID' AND s.sale_uuid = ?`, uuid); err == nil {
		t.Fatal("DELETE of the SALE_VOID audit row succeeded, want it refused")
	}
}
