package controllers

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestReviewRecallNeedsExplicitLot(t *testing.T) {
	ctrl := NewRecallController(nil)
	code, body := call(t, ctrl.TriggerRecall, "POST", "/recall", RecallTriggerRequest{})
	if code != 422 {
		t.Fatalf("missing lot: %d %s", code, body)
	}
}

func TestIntegrationReviewRecallWithOneConnection(t *testing.T) {
	db := testDB(t)
	defer db.Close()
	db.SetMaxOpenConns(1)
	ctrl := NewRecallController(db)
	code, body := call(t, ctrl.TriggerRecall, "POST", "/recall", RecallTriggerRequest{RecallCode: "RCL-POOL-" + runSuffix(), TriggerLot: "ET-2026-001"})
	if code != 201 {
		t.Fatalf("recall: %d %s", code, body)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	req := httptest.NewRequest("GET", "/recall", nil).WithContext(ctx)
	rec := httptest.NewRecorder()
	ctrl.GetActiveRecalls(rec, req)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "affected_lots") {
		t.Fatalf("recall read: %d %s", rec.Code, rec.Body.String())
	}
}

func TestIntegrationReviewReturnCannotInventStock(t *testing.T) {
	db := testDB(t)
	ctrl := NewMovementsController(db)
	for _, req := range []ReturnRequest{
		{LotNo: "SUC-2026-045", CustomerCode: "MKT-A", Warehouse: "KARANTINA", Qty: 1000},
		{LotNo: "KZB-2026-001", CustomerCode: "MKT-A", Warehouse: "KARANTINA", Qty: 1},
	} {
		code, body := call(t, ctrl.Return, "POST", "/movements/return", req)
		if code != 409 || !strings.Contains(string(body), "CONFLICT") {
			t.Errorf("return beyond delivered quantity: %d %s", code, body)
		}
	}
}

func TestIntegrationReviewConcurrentReturns(t *testing.T) {
	db := testDB(t)
	ctrl := NewMovementsController(db)
	results := make(chan int, 2)
	start := make(chan struct{})
	for _, wh := range []string{"KARANTINA", "ANA-DEPO"} {
		go func(warehouse string) {
			<-start
			code, _ := call(t, ctrl.Return, "POST", "/movements/return", ReturnRequest{
				LotNo: "SUC-2026-045", CustomerCode: "MKT-C", Warehouse: warehouse, Qty: 30})
			results <- code
		}(wh)
	}
	close(start)
	one, two := <-results, <-results
	if !((one == 201 && two == 409) || (one == 409 && two == 201)) {
		t.Fatalf("concurrent returns: %d %d", one, two)
	}
}

func TestIntegrationReviewProductionDispatchGuards(t *testing.T) {
	db := testDB(t)
	entry := NewEntryController(db)
	suffix := runSuffix()
	today := time.Now().UTC().Format("2006-01-02")
	for _, tc := range []struct {
		name, sku, status, expiry string
		want                      int
	}{
		{"blocked", "HM-ET-DANA", "BLOCKED", "2099-01-01", 409},
		{"expired", "HM-ET-DANA", "ACTIVE", "2000-01-01", 409},
		{"carcass", "KZ-KARKAS", "ACTIVE", "2099-01-01", 422},
	} {
		t.Run(tc.name, func(t *testing.T) {
			lot := "RV-" + tc.name + suffix
			_, err := db.Exec(`INSERT INTO lot (lot_no,product_id,origin_type,supplier_id,lot_date,expiry_date,initial_qty,uom_code,unit_cost,status)
                SELECT ?,p.id,'PURCHASE',s.id,'1999-01-01',?,5,'KG',10,? FROM product p,supplier s
                WHERE p.sku=? AND s.code='TED-ET-01'`, lot, tc.expiry, tc.status, tc.sku)
			if err != nil {
				t.Fatal(err)
			}
			_, err = db.Exec(`INSERT INTO stock_movement(lot_id,warehouse_id,movement_type,qty,unit_cost)
                SELECT l.id,w.id,'PURCHASE_IN',5,10 FROM lot l,warehouse w WHERE l.lot_no=? AND w.code='ANA-DEPO'`, lot)
			if err != nil {
				t.Fatal(err)
			}
			req := ProductionOrderRequest{OrderNo: "URT-" + tc.name + suffix, ProductSKU: "MM-SUCUK-1KG", SubcontractorCode: "FAS-01",
				PlannedQty: 5, ServiceUnitPrice: 1, OrderDate: today, Inputs: []ProductionInputRequest{{LotNo: lot, Qty: 1}}}
			code, body := call(t, entry.ProductionOrder, "POST", "/production/orders", req)
			if code != tc.want {
				t.Fatalf("want %d, got %d %s", tc.want, code, body)
			}
			var n int
			if err := db.QueryRow(`SELECT COUNT(*) FROM production_order WHERE order_no=?`, req.OrderNo).Scan(&n); err != nil || n != 0 {
				t.Fatalf("rejected order left data: %d %v", n, err)
			}
		})
	}
}

func TestIntegrationReviewExpiredButchery(t *testing.T) {
	db := testDB(t)
	entry := NewEntryController(db)
	suffix := runSuffix()
	lot := "RV-KZ-" + suffix
	_, err := db.Exec(`INSERT INTO lot(lot_no,product_id,origin_type,supplier_id,lot_date,expiry_date,initial_qty,uom_code,unit_cost)
        SELECT ?,p.id,'PURCHASE',s.id,'1999-01-01','2000-01-01',5,'KG',10 FROM product p,supplier s
        WHERE p.sku='KZ-KARKAS' AND s.code='TED-HAY-01'`, lot)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO stock_movement(lot_id,warehouse_id,movement_type,qty,unit_cost)
        SELECT l.id,w.id,'PURCHASE_IN',5,10 FROM lot l,warehouse w WHERE l.lot_no=? AND w.code='ANA-DEPO'`, lot)
	if err != nil {
		t.Fatal(err)
	}
	req := ButcheryOperationRequest{OpNo: "PRC-RV-" + suffix, InputLotNo: lot, InputWeightKg: 5, AllocationMethod: "QUANTITY",
		OpDate: time.Now().UTC().Format("2006-01-02"), Outputs: []ButcheryOutputRequest{
			{SKU: "KZ-BUT", Kind: "CUT", WeightKg: 4, LotNo: "KZB-RV-" + suffix}, {SKU: "KZ-FIRE", Kind: "WASTE", WeightKg: 1}}}
	for _, dry := range []bool{true, false} {
		req.DryRun = dry
		code, body := call(t, entry.ButcheryOperation, "POST", "/butchery/operations", req)
		if code != 409 || !strings.Contains(string(body), "LOT_EXPIRED") {
			t.Errorf("dry=%v: %d %s", dry, code, body)
		}
	}
}

func TestIntegrationReviewRecallDuplicateIsConflict(t *testing.T) {
	db := testDB(t)
	ctrl := NewRecallController(db)
	req := RecallTriggerRequest{RecallCode: "RCL-RV-" + runSuffix(), TriggerLot: "ET-2026-001"}
	code, body := call(t, ctrl.TriggerRecall, "POST", "/recall", req)
	if code != 201 {
		t.Fatalf("initial recall: %d %s", code, body)
	}
	code, body = call(t, ctrl.TriggerRecall, "POST", "/recall", req)
	if code != 409 {
		t.Fatalf("duplicate recall must not claim creation: %d %s", code, body)
	}
}

func TestReviewProductionReceiptValidation(t *testing.T) {
	ctrl := NewProductionController(nil)
	// Validate before opening the DB. These requests currently return a misleading success or
	// reach the database with an impossible expiry / incomplete receipt.
	for _, req := range []CompleteProductionRequest{
		{OrderNo: "URT-X", ActualQty: -1, OutputLotNo: "X"},
		{OrderNo: "URT-X", ActualQty: 1},
		{OrderNo: "URT-X", ActualQty: 1, OutputLotNo: "X", LotDate: "2026-10-29", ExpiryDate: "2026-10-28"},
	} {
		// The nil DB turns a failure to validate into a panic, which is reported per case.
		func() {
			defer func() {
				if p := recover(); p != nil {
					t.Errorf("invalid receipt reached DB: %+v", req)
				}
			}()
			code, body := call(t, ctrl.CalculateOrCompleteProduction, "POST", "/production", req)
			if code != http.StatusUnprocessableEntity {
				t.Errorf("invalid receipt: %d %s", code, body)
			}
		}()
	}
}
