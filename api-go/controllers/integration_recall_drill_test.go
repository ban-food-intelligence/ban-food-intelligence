package controllers

// Integration test (MYSQL_TEST_DSN) for recall drills: nothing is blocked or moved, the customers
// of the trace are reached once each, the result is computed and the record never changes.

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestIntegrationRecallDrillTimesTheTraceWithoutBlockingAnything(t *testing.T) {
	db := testDB(t)
	run := runSuffix()
	lot, gap := "DRL-"+run, "DRL-U-"+run
	shipmentLot(t, db, lot, 20, -3, 30)
	today := time.Now().UTC().Format("2006-01-02")
	sc := NewShipmentController(db)
	for _, s := range []ShipmentRequest{
		{ShipmentNo: "SVK-DRL-A-" + run, CustomerCode: "MKT-A", ShipDate: today, Lines: []ShipmentLineRequest{{LotNo: lot, Qty: 8}}},
		{ShipmentNo: "SVK-DRL-B-" + run, CustomerCode: "MKT-B", ShipDate: today, Lines: []ShipmentLineRequest{{LotNo: lot, Qty: 5}}},
		{ShipmentNo: "SVK-DRL-R-" + run, CustomerCode: "REST-01", FromCustomerCode: "MKT-B", ShipDate: today, Lines: []ShipmentLineRequest{{LotNo: lot, Qty: 2}}},
	} {
		if code, body := shipAs(t, sc, s); code != http.StatusCreated {
			t.Fatalf("shipment %s: %d %s", s.ShipmentNo, code, body)
		}
	}
	// a lot whose declared quantity was never fully booked in: 2 packs no document explains
	if _, err := db.Exec(`INSERT INTO lot(lot_no,product_id,origin_type,lot_date,expiry_date,initial_qty,uom_code,unit_cost)
        SELECT ?,id,'PRODUCTION',CURRENT_DATE,DATE_ADD(CURRENT_DATE,INTERVAL 30 DAY),10,'PAKET',18.16 FROM product WHERE sku='MM-SUCUK-1KG'`, gap); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO stock_movement(lot_id,warehouse_id,movement_type,qty,unit_cost)
        SELECT l.id,w.id,'PRODUCTION_RECEIPT',8,18.16 FROM lot l,warehouse w WHERE l.lot_no=? AND w.code='ANA-DEPO'`, gap); err != nil {
		t.Fatal(err)
	}

	sessions := NewSessionManager()
	rc := NewRecallController(db)
	token, _ := sessions.Issue("drill-supervisor")
	post := func(h http.HandlerFunc, path string, body interface{}) (int, RecallDrillDto, []byte) {
		t.Helper()
		payload, _ := json.Marshal(body)
		r := httptest.NewRequest("POST", path, bytes.NewReader(payload))
		r.Header.Set("Authorization", "Bearer "+token)
		rec := httptest.NewRecorder()
		sessions.RequireOperator(h)(rec, r)
		var d RecallDrillDto
		_ = json.Unmarshal(rec.Body.Bytes(), &d)
		return rec.Code, d, rec.Body.Bytes()
	}
	state := func() (status string, movements, recalls int) {
		t.Helper()
		_ = db.QueryRow(`SELECT status FROM lot WHERE lot_no = ?`, lot).Scan(&status)
		_ = db.QueryRow(`SELECT COUNT(*) FROM stock_movement sm JOIN lot l ON l.id = sm.lot_id WHERE l.lot_no = ?`, lot).Scan(&movements)
		_ = db.QueryRow(`SELECT COUNT(*) FROM recall rc JOIN lot l ON l.id = rc.root_lot_id WHERE l.lot_no = ?`, lot).Scan(&recalls)
		return
	}
	statusBefore, movesBefore, _ := state()

	if code, _ := call(t, sessions.RequireOperator(rc.StartDrill), "POST", "/recall/drills", RecallDrillRequest{LotNo: lot}); code != http.StatusUnauthorized {
		t.Fatalf("drill without session: %d", code)
	}
	if code, _, body := post(rc.StartDrill, "/recall/drills", RecallDrillRequest{LotNo: lot, TargetMinutes: 20000}); code != http.StatusUnprocessableEntity {
		t.Fatalf("target too long: %d %s", code, body)
	}
	if code, _, body := post(rc.StartDrill, "/recall/drills", RecallDrillRequest{LotNo: "NO-LOT-" + run}); code != http.StatusNotFound {
		t.Fatalf("unknown lot: %d %s", code, body)
	}

	no := "TAT-IT-" + run
	code, d, body := post(rc.StartDrill, "/recall/drills", RecallDrillRequest{DrillNo: no, LotNo: lot, Note: "Yıllık test"})
	if code != http.StatusCreated || d.Status != "OPEN" || d.TargetMinutes != 240 || d.StartedBy != "drill-supervisor" || !d.Balanced ||
		d.LotsForward != 1 || d.LotsBackward != 1 || d.Reached != 0 || d.Snapshot != nil {
		t.Fatalf("start: %d %s", code, body)
	}
	got := map[string]int{}
	for _, c := range d.Contacts {
		got[c.CustomerCode] = c.Tier
	}
	if len(d.Contacts) != 3 || got["MKT-A"] != 1 || got["MKT-B"] != 1 || got["REST-01"] != 2 {
		t.Fatalf("contacts %+v", d.Contacts)
	}
	if c := d.Contacts[0]; c.CustomerCode != "MKT-A" || len(c.Quantities) != 1 || c.Quantities[0].Qty != 8 || c.Lots[0] != lot {
		t.Fatalf("MKT-A line %+v", c)
	}
	if len(d.MassBalance) != 1 || d.MassBalance[0].AtCustomers != 13 || d.MassBalance[0].InStock != 7 {
		t.Fatalf("mass balance totals %+v", d.MassBalance)
	}
	if st, mv, rcl := state(); st != statusBefore || mv != movesBefore || rcl != 0 {
		t.Fatalf("a drill changed the lot: status %s->%s, movements %d->%d, recalls %d", statusBefore, st, movesBefore, mv, rcl)
	}
	if code, _, _ := post(rc.StartDrill, "/recall/drills", RecallDrillRequest{DrillNo: no, LotNo: lot}); code != http.StatusConflict {
		t.Fatalf("duplicate drill no: %d", code)
	}

	// closing with customers still to reach fails, so it needs a corrective action first
	code, _, body = post(rc.CloseDrill, "/recall/drills/close", RecallDrillCloseRequest{DrillNo: no})
	if code != http.StatusUnprocessableEntity || !strings.Contains(string(body), drillNotReached) {
		t.Fatalf("close unreached without findings: %d %s", code, body)
	}
	if code, _, body := post(rc.DrillContact, "/recall/drills/contact", RecallDrillContactRequest{DrillNo: no, CustomerCode: "MKT-C"}); code != http.StatusNotFound {
		t.Fatalf("customer not on the list: %d %s", code, body)
	}
	code, d, body = post(rc.DrillContact, "/recall/drills/contact", RecallDrillContactRequest{DrillNo: no, CustomerCode: "MKT-A", Note: "Şube müdürü"})
	if code != http.StatusOK || d.Reached != 1 || d.Contacts[0].ReachedBy != "drill-supervisor" || d.Contacts[0].ReachedAt == nil ||
		d.Contacts[0].MinutesToReach == nil || d.Contacts[0].Note != "Şube müdürü" {
		t.Fatalf("contact MKT-A: %d %s", code, body)
	}
	if code, _, _ := post(rc.DrillContact, "/recall/drills/contact", RecallDrillContactRequest{DrillNo: no, CustomerCode: "MKT-A"}); code != http.StatusConflict {
		t.Fatalf("MKT-A twice: %d", code)
	}
	for _, cust := range []string{"MKT-B", "REST-01"} {
		if code, _, body := post(rc.DrillContact, "/recall/drills/contact", RecallDrillContactRequest{DrillNo: no, CustomerCode: cust}); code != http.StatusOK {
			t.Fatalf("contact %s: %d %s", cust, code, body)
		}
	}
	code, d, body = post(rc.CloseDrill, "/recall/drills/close", RecallDrillCloseRequest{DrillNo: no})
	if code != http.StatusOK || d.Status != "CLOSED" || d.Result != "PASS" || len(d.FailReasons) != 0 || d.Reached != 3 ||
		d.ClosedBy != "drill-supervisor" || d.ClosedAt == nil || d.ElapsedSeconds <= 0 || d.ElapsedSeconds > 600 {
		t.Fatalf("close: %d %s", code, body)
	}
	if code, _, _ := post(rc.DrillContact, "/recall/drills/contact", RecallDrillContactRequest{DrillNo: no, CustomerCode: "MKT-A"}); code != http.StatusConflict {
		t.Fatalf("contact on a closed drill: %d", code)
	}
	if code, _, _ := post(rc.CloseDrill, "/recall/drills/close", RecallDrillCloseRequest{DrillNo: no, Findings: "x"}); code != http.StatusConflict {
		t.Fatalf("closing twice: %d", code)
	}
	if st, mv, rcl := state(); st != statusBefore || mv != movesBefore || rcl != 0 {
		t.Fatalf("the drill changed the lot: status %s, movements %d, recalls %d", st, mv, rcl)
	}
	for _, sql := range []string{
		`UPDATE recall_drill SET findings = 'later' WHERE drill_no = ?`,
		`DELETE FROM recall_drill WHERE drill_no = ?`,
		`UPDATE recall_drill_contact SET note = 'later' WHERE drill_id = (SELECT id FROM recall_drill WHERE drill_no = ?)`,
		`DELETE FROM recall_drill_contact WHERE drill_id = (SELECT id FROM recall_drill WHERE drill_no = ?)`,
	} {
		if _, err := db.Exec(sql, no); err == nil || !strings.Contains(err.Error(), "IMMUTABLE") {
			t.Fatalf("%s: want IMMUTABLE, got %v", sql, err)
		}
	}
	var audits int
	_ = db.QueryRow(`SELECT COUNT(*) FROM audit_log WHERE action LIKE 'DRILL_%' AND JSON_UNQUOTE(JSON_EXTRACT(new_value, '$.drill_no')) = ?`, no).Scan(&audits)
	if audits != 5 {
		t.Fatalf("audit entries: %d, want start + 3 contacts + close", audits)
	}

	// read back: the list and the detail with the snapshot taken at the start
	code, listBody := call(t, rc.ListDrills, "GET", "/recall/drills", nil)
	var list []RecallDrillDto
	_ = json.Unmarshal(listBody, &list)
	if code != http.StatusOK || len(list) == 0 || list[0].Snapshot != nil {
		t.Fatalf("list: %d %s", code, listBody)
	}
	code, detailBody := call(t, rc.ListDrills, "GET", "/recall/drills?drill_no="+no, nil)
	var detail RecallDrillDto
	_ = json.Unmarshal(detailBody, &detail)
	if code != http.StatusOK || detail.Snapshot == nil || detail.Snapshot.Forward == nil || len(detail.Snapshot.Forward.Shipments) != 3 ||
		detail.Snapshot.Backward == nil || detail.Result != "PASS" {
		t.Fatalf("detail: %d %.300s", code, detailBody)
	}
	if code, _ := call(t, rc.ListDrills, "GET", "/recall/drills?drill_no=NOPE-"+run, nil); code != http.StatusNotFound {
		t.Fatalf("unknown drill: %d", code)
	}

	// an undocumented gap fails the drill even with nobody to call, and the DB refuses a forced PASS
	gapNo := "TAT-IT-U-" + run
	code, d, body = post(rc.StartDrill, "/recall/drills", RecallDrillRequest{DrillNo: gapNo, LotNo: gap, TargetMinutes: 60})
	if code != http.StatusCreated || d.Balanced || len(d.Contacts) != 0 || d.MassBalance[0].Unexplained != 2 {
		t.Fatalf("start gap drill: %d %s", code, body)
	}
	if _, err := db.Exec(`UPDATE recall_drill SET status = 'CLOSED', closed_by = 'x', closed_at = NOW(3), result = 'PASS' WHERE drill_no = ?`, gapNo); err == nil ||
		!strings.Contains(err.Error(), "INVALID_INPUT") {
		t.Fatalf("forced PASS of an unbalanced drill: %v", err)
	}
	code, _, body = post(rc.CloseDrill, "/recall/drills/close", RecallDrillCloseRequest{DrillNo: gapNo})
	if code != http.StatusUnprocessableEntity || !strings.Contains(string(body), drillUnexplained) {
		t.Fatalf("close gap drill without findings: %d %s", code, body)
	}
	code, d, body = post(rc.CloseDrill, "/recall/drills/close", RecallDrillCloseRequest{DrillNo: gapNo, Findings: "Kabul kaydı eksik; giriş kontrolü eklendi"})
	if code != http.StatusOK || d.Result != "FAIL" || len(d.FailReasons) != 1 || d.FailReasons[0] != drillUnexplained || d.Findings == "" {
		t.Fatalf("close gap drill: %d %s", code, body)
	}
}
