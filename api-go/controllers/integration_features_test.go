package controllers

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func recoveryCase(t *testing.T, db *sql.DB) (lot, code string) {
	t.Helper()
	suffix := runSuffix()
	lot = "RCV-" + suffix
	code = "RCL-RCV-" + suffix
	_, err := db.Exec(`INSERT INTO lot(lot_no,product_id,origin_type,lot_date,expiry_date,initial_qty,uom_code,unit_cost)
        SELECT ?,id,'PRODUCTION',CURRENT_DATE,DATE_ADD(CURRENT_DATE,INTERVAL 30 DAY),30,'PAKET',10 FROM product WHERE sku='MM-SUCUK-1KG'`, lot)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO stock_movement(lot_id,warehouse_id,movement_type,qty,unit_cost)
        SELECT l.id,w.id,'PRODUCTION_RECEIPT',30,10 FROM lot l,warehouse w WHERE l.lot_no=? AND w.code='ANA-DEPO'`, lot)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO shipment(shipment_no,customer_id,from_warehouse_id,ship_date)
        SELECT ?,c.id,w.id,CURRENT_DATE FROM customer c,warehouse w WHERE c.code='MKT-C' AND w.code='ANA-DEPO'`, "SVK-RCV-"+suffix)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO shipment_line(shipment_id,lot_id,qty) SELECT sh.id,l.id,30 FROM shipment sh,lot l WHERE sh.shipment_no=? AND l.lot_no=?`, "SVK-RCV-"+suffix, lot)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO shipment(shipment_no,customer_id,from_customer_id,ship_date)
        SELECT ?,c.id,src.id,CURRENT_DATE FROM customer c,customer src WHERE c.code='REST-01' AND src.code='MKT-C'`, "SVK-RCV2-"+suffix)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO shipment_line(shipment_id,lot_id,qty) SELECT sh.id,l.id,10 FROM shipment sh,lot l WHERE sh.shipment_no=? AND l.lot_no=?`, "SVK-RCV2-"+suffix, lot)
	if err != nil {
		t.Fatal(err)
	}
	status, body := call(t, NewRecallController(db).TriggerRecall, "POST", "/recall", RecallTriggerRequest{RecallCode: code, TriggerLot: lot})
	if status != 201 {
		t.Fatalf("recall %d %s", status, body)
	}
	return
}

func TestIntegrationRecallRecoveryAndFollowup(t *testing.T) {
	db := testDB(t)
	defer db.Close()
	lot, code := recoveryCase(t, db)
	c := NewRecallController(db)
	progress := func() RecallProgressDto {
		t.Helper()
		status, body := call(t, c.Progress, "GET", "/recall/progress?recall_code="+code, nil)
		var p RecallProgressDto
		if err := json.Unmarshal(body, &p); status != 200 || err != nil {
			t.Fatalf("progress %d %s %v", status, body, err)
		}
		return p
	}
	p := progress()
	if len(p.Lines) != 2 || len(p.Totals) != 1 || p.Totals[0].ToRecover != 30 || p.Totals[0].Pending != 30 || p.AllRecovered {
		t.Fatalf("tier-2 double count: %+v", p)
	}
	if p.Lines[0].ToRecover != 20 || p.Lines[0].Forwarded != 10 || p.Lines[1].ToRecover != 10 {
		t.Fatalf("responsibilities %+v", p.Lines)
	}
	// Protected write, then authenticated writes: contact acknowledgement is not a return.
	sessions := NewSessionManager()
	handler := sessions.RequireOperator(c.Followup)
	status, body := call(t, handler, "POST", "/recall/followup", RecallFollowupRequest{})
	if status != 401 {
		t.Fatalf("unprotected followup: %d %s", status, body)
	}
	token, _ := sessions.Issue("recovery-supervisor")
	follow := func(customer, status, note string) int {
		t.Helper()
		payload, _ := json.Marshal(map[string]interface{}{"recall_code": code, "customer_code": customer, "contact_status": status, "note": note, "qty_returned": 999})
		req := httptest.NewRequest("POST", "/recall/followup", bytes.NewReader(payload))
		req.Header.Set("Authorization", "Bearer "+token)
		res := httptest.NewRecorder()
		handler(res, req)
		return res.Code
	}
	if status := follow("MKT-C", "CONTACTED", "Phone call"); status != 200 {
		t.Fatal(status)
	}
	if status := follow("MKT-C", "CONFIRMED", "Shelf removal confirmed"); status != 200 {
		t.Fatal(status)
	}
	if status := follow("MKT-A", "CONTACTED", ""); status != 404 {
		t.Fatalf("unaffected customer accepted: %d", status)
	}
	p = progress()
	if p.Lines[0].ContactStatus != "CONFIRMED" || p.Lines[0].UpdatedBy != "recovery-supervisor" || p.Totals[0].Returned != 0 || p.AllRecovered {
		t.Fatalf("contact changed stock %+v", p)
	}
	var audits int
	if err := db.QueryRow(`SELECT COUNT(*) FROM audit_log a JOIN recall_customer_followup f ON f.id=a.row_id JOIN recall rc ON rc.id=f.recall_id
        WHERE a.table_name='recall_customer_followup' AND rc.recall_no=? AND a.changed_by='recovery-supervisor'`, code).Scan(&audits); err != nil || audits != 2 {
		t.Fatalf("audit: %d %v", audits, err)
	}
	m := NewMovementsController(db)
	// A tier-1 customer cannot also return the ten packs sent to tier 2.
	status, body = call(t, m.Return, "POST", "/movements/return", ReturnRequest{LotNo: lot, Warehouse: "KARANTINA", CustomerCode: "MKT-C", Qty: 21})
	if status != 409 {
		t.Fatalf("onward shipment was counted twice: %d %s", status, body)
	}
	for _, ret := range []struct {
		customer string
		qty      float64
	}{{"MKT-C", 5}, {"MKT-C", 15}, {"REST-01", 10}} {
		status, body = call(t, m.Return, "POST", "/movements/return", ReturnRequest{LotNo: lot, Warehouse: "KARANTINA", CustomerCode: ret.customer, Qty: ret.qty})
		if status != 201 {
			t.Fatalf("return %d %s", status, body)
		}
	}
	p = progress()
	if !p.AllRecovered || p.Totals[0].Returned != 30 || p.Totals[0].Pending != 0 {
		t.Fatalf("recovery %+v", p)
	}
	var lotStatus string
	if err := db.QueryRow(`SELECT status FROM lot WHERE lot_no=?`, lot).Scan(&lotStatus); err != nil || lotStatus != "RECALLED" {
		t.Fatalf("recovery unblocked sale: %s %v", lotStatus, err)
	}
	if _, err := db.Exec(`UPDATE recall SET status='CLOSED' WHERE recall_no=?`, code); err != nil {
		t.Fatal(err)
	}
	if status := follow("MKT-C", "CONTACTED", ""); status != 409 {
		t.Fatalf("closed recall editable: %d", status)
	}
}

func TestIntegrationRecallProgressFlagsImpossibleRecovery(t *testing.T) {
	db := testDB(t)
	defer db.Close()
	lot, code := recoveryCase(t, db)
	// Historical/imported data can be inconsistent even though the API prevents this now.
	for _, customer := range []string{"MKT-C", "REST-01"} {
		_, err := db.Exec(`INSERT INTO stock_movement(lot_id,warehouse_id,movement_type,qty,unit_cost,ref_type,ref_id)
            SELECT l.id,w.id,'RETURN',30,10,'customer',c.id FROM lot l,warehouse w,customer c
            WHERE l.lot_no=? AND w.code='KARANTINA' AND c.code=?`, lot, customer)
		if err != nil {
			t.Fatal(err)
		}
	}
	progress, err := loadRecallProgress(t.Context(), db, code)
	if err != nil || progress.AllRecovered || !progress.Lines[0].QuantityWarning || progress.Totals[0].Pending != 0 {
		t.Fatalf("inconsistent recovery must stay flagged: %+v %v", progress, err)
	}
}

func TestIntegrationAssessmentReportEvidenceAndSnapshot(t *testing.T) {
	db := testDB(t)
	defer db.Close()
	c := NewReportsController(db)
	var movementsBefore, movementsAfter int
	if err := db.QueryRow(`SELECT COUNT(*) FROM stock_movement`).Scan(&movementsBefore); err != nil {
		t.Fatal(err)
	}
	status, body := call(t, c.Assessment, "GET", "/reports/assessment", nil)
	var report AssessmentReport
	if err := json.Unmarshal(body, &report); err != nil || status != 200 {
		t.Fatalf("report %d %s %v", status, body, err)
	}
	if report.Production.EKPDisplay != 18.16 || report.Production.TotalCost != 1543.5 || len(report.Butchery) != 2 || len(report.Trace.Shipments) != 4 || len(report.Predictions) == 0 {
		t.Fatalf("missing evidence %+v", report)
	}
	rejected := report.Butchery[0]
	if rejected.TotalCost != 287.5 || *rejected.DeclaredCostDiff != 63.5 || rejected.WeightCheck.UnrecordedKg != 3 {
		t.Fatalf("findings %+v", rejected)
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM stock_movement`).Scan(&movementsAfter); err != nil || movementsBefore != movementsAfter {
		t.Fatalf("report wrote stock: %d %d %v", movementsBefore, movementsAfter, err)
	}
	status, body = call(t, c.Assessment, "GET", "/reports/assessment?order_no=MISSING", nil)
	if status != 404 {
		t.Fatalf("missing record produced report: %d %s", status, body)
	}
	// A later change on another connection must not split the PDF's evidence across snapshots.
	tx, err := db.BeginTx(t.Context(), &sql.TxOptions{Isolation: sql.LevelRepeatableRead})
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	var declared float64
	if err := tx.QueryRow(`SELECT declared_total_cost FROM butchery_operation WHERE op_no='PRC-2026-000'`).Scan(&declared); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`UPDATE butchery_operation SET declared_total_cost=400 WHERE op_no='PRC-2026-000'`); err != nil {
		t.Fatal(err)
	}
	defer db.Exec(`UPDATE butchery_operation SET declared_total_cost=? WHERE op_no='PRC-2026-000'`, declared)
	r := httptest.NewRequest("GET", "/reports/assessment", nil)
	result, err := loadAssessment(r, tx, "URT-2026-045", "ET-2026-001", "PRC-2026-000", "PRC-2026-001")
	if err != nil || *result.Butchery[0].DeclaredTotalCost != declared {
		t.Fatalf("split snapshot: %v %+v", err, result)
	}
}

func TestFollowupAndReportValidateBeforeDB(t *testing.T) {
	c := NewRecallController(nil)
	status, _ := call(t, c.Followup, "POST", "/recall/followup", RecallFollowupRequest{RecallCode: "X", CustomerCode: "Y", ContactStatus: "DONE"})
	if status != 422 {
		t.Fatal(status)
	}
	status, _ = call(t, c.Progress, "GET", "/recall/progress", nil)
	if status != 422 {
		t.Fatal(status)
	}
	status, _ = call(t, NewReportsController(nil).Assessment, "POST", "/reports/assessment", nil)
	if status != 405 {
		t.Fatal(status)
	}
}
