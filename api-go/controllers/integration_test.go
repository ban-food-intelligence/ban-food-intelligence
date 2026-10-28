package controllers

// Integration tests against a real MySQL loaded with schema.sql + seed.sql.
// They run only when MYSQL_TEST_DSN is set, e.g. (inside the compose network):
//
//	MYSQL_TEST_DSN='root:ban@tcp(mysql:3306)/food_intel?parseTime=true&multiStatements=true' go test ./...
//
// They write data (movements, a recall, a rework lot): run ./scripts/demo-reset.sh afterwards.

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	_ "github.com/go-sql-driver/mysql"
)

func testDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := os.Getenv("MYSQL_TEST_DSN")
	if dsn == "" {
		t.Skip("MYSQL_TEST_DSN not set")
	}
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Ping(); err != nil {
		t.Fatal(err)
	}
	return db
}

func call(t *testing.T, h http.HandlerFunc, method, url string, body interface{}) (int, []byte) {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req := httptest.NewRequest(method, url, &buf)
	rec := httptest.NewRecorder()
	h(rec, req)
	return rec.Code, rec.Body.Bytes()
}

func near(a, b float64) bool { return math.Abs(a-b) < 0.005 }

func TestIntegrationTask1EKP(t *testing.T) {
	db := testDB(t)
	code, body := call(t, NewProductionController(db).ListOrders, "GET", "/production?order_no=URT-2026-045", nil)
	if code != 200 {
		t.Fatalf("status %d: %s", code, body)
	}
	var orders []ProductionOrderDto
	_ = json.Unmarshal(body, &orders)
	if len(orders) != 1 {
		t.Fatalf("want 1 order, got %d", len(orders))
	}
	o := orders[0]
	if !near(o.TotalCost, 1543.50) || !near(o.EKPDisplay, 18.16) || !near(o.ActualQty, 85) || !near(o.QtyVariance, 0) {
		t.Fatalf("unexpected cost: total %.2f ekp %.2f qty %.0f variance %.0f", o.TotalCost, o.EKPDisplay, o.ActualQty, o.QtyVariance)
	}
	if !near(o.MaterialCost, 1076.00) || !near(o.ServiceCost, 467.50) {
		t.Fatalf("material %.2f service %.2f", o.MaterialCost, o.ServiceCost)
	}
	wantMargin := 100 * (1 - (1543.50/85)/o.VKP)
	if math.Abs(o.MarginPct-wantMargin) > 0.01 || !near(o.GrossProfitPerUnit, o.VKP-1543.50/85) {
		t.Fatalf("margin %.2f (want %.2f), gp/unit %.2f", o.MarginPct, wantMargin, o.GrossProfitPerUnit)
	}
}

func TestIntegrationButcheryFindingsAndAllocation(t *testing.T) {
	db := testDB(t)
	ctrl := NewButcheryController(db)
	code, body := call(t, ctrl.ReconcileButchery, "GET", "/butchery", nil)
	if code != 200 {
		t.Fatalf("status %d", code)
	}
	var ops []ButcheryOperationSummaryDto
	_ = json.Unmarshal(body, &ops)
	byOp := map[string]ButcheryOperationSummaryDto{}
	for _, o := range ops {
		byOp[o.OpNo] = o
	}
	codes := func(o ButcheryOperationSummaryDto) string {
		var c []string
		for _, f := range o.Findings {
			c = append(c, f.Code)
		}
		return strings.Join(c, ",")
	}

	rej := byOp["PRC-2026-000"]
	if rej.Status != "REJECTED" || !strings.Contains(codes(rej), "DECLARED_COST_MISMATCH") || !strings.Contains(codes(rej), "WEIGHT_IMBALANCE") {
		t.Fatalf("PRC-2026-000 findings: %s", codes(rej))
	}
	if !near(rej.WeightCheck.UnrecordedKg, 3) || !near(rej.WeightCheck.CutsKg, 22) {
		t.Fatalf("PRC-2026-000 weight check %+v", rej.WeightCheck)
	}
	for _, f := range rej.Findings {
		if f.Code == "DECLARED_COST_MISMATCH" && (!near(f.Values["difference"], 63.50) || !near(f.Values["computed_total_cost"], 287.50)) {
			t.Fatalf("cost finding values %+v", f.Values)
		}
	}

	posted := byOp["PRC-2026-001"]
	if posted.WeightCheck.Status != "BALANCED" || !strings.Contains(codes(posted), "ALLOCATION_BALANCED") {
		t.Fatalf("PRC-2026-001 %+v %s", posted.WeightCheck, codes(posted))
	}

	for _, method := range []string{"VALUE", "QUANTITY"} {
		code, body := call(t, ctrl.Allocation, "GET", "/butchery/allocation?op_no=PRC-2026-001&method="+method, nil)
		var p AllocationPreviewDto
		_ = json.Unmarshal(body, &p)
		if code != 200 || !p.SumsExact || !near(p.Allocated, 287.50) {
			t.Fatalf("%s allocation: status %d sums %v allocated %.2f", method, code, p.SumsExact, p.Allocated)
		}
	}
}

func TestIntegrationTrace(t *testing.T) {
	db := testDB(t)
	tc := NewTraceController(db)

	code, body := call(t, tc.TraceHandler, "GET", "/trace/ET-2026-001?direction=forward", nil)
	var fwd TraceResponse
	_ = json.Unmarshal(body, &fwd)
	if code != 200 {
		t.Fatalf("forward status %d %s", code, body)
	}
	lots := map[string]bool{}
	for _, l := range fwd.Lots {
		lots[l.LotNo] = true
	}
	if !lots["SUC-2026-045"] || lots["SUC-2026-046"] {
		t.Fatalf("forward lots wrong: %v", lots)
	}
	ship := map[string]float64{}
	for _, s := range fwd.Shipments {
		ship[s.CustomerCode] += s.Qty
	}
	if ship["MKT-A"] != 30 || ship["MKT-B"] != 25 || ship["MKT-C"] != 30 || ship["REST-01"] != 10 {
		t.Fatalf("shipments %v", ship)
	}
	if len(fwd.Links) != 1 || fwd.Links[0].DocumentNo != "URT-2026-045" || fwd.Links[0].QtyConsumed != 100 || fwd.Links[0].QtyProduced != 85 {
		t.Fatalf("forward links %+v", fwd.Links)
	}

	code, body = call(t, tc.TraceHandler, "GET", "/trace/SUC-2026-045?direction=backward", nil)
	var bwd TraceResponse
	_ = json.Unmarshal(body, &bwd)
	suppliers := map[string]string{}
	for _, l := range bwd.Lots {
		suppliers[l.LotNo] = l.SupplierCode
	}
	if code != 200 || suppliers["ET-2026-001"] != "TED-ET-01" || suppliers["BAH-2026-001"] == "" || suppliers["BAG-2026-001"] == "" || len(bwd.Links) != 3 {
		t.Fatalf("backward: status %d suppliers %v links %d", code, suppliers, len(bwd.Links))
	}

	_, body = call(t, tc.TraceHandler, "GET", "/trace/KUZU-2026-001?direction=forward", nil)
	var carcass TraceResponse
	_ = json.Unmarshal(body, &carcass)
	if len(carcass.Links) != 5 {
		t.Fatalf("butchery trace should link 5 part lots, got %d", len(carcass.Links))
	}

	code, _ = call(t, tc.TraceHandler, "GET", "/trace/NOPE-1?direction=forward", nil)
	if code != 404 {
		t.Fatalf("unknown lot: want 404, got %d", code)
	}
}

func TestIntegrationMovements(t *testing.T) {
	db := testDB(t)
	m := NewMovementsController(db)

	code, body := call(t, m.Transfer, "POST", "/movements/transfer",
		TransferRequest{LotNo: "KZO-2026-001", FromWarehouse: "ANA-DEPO", ToWarehouse: "MAGAZA-01", Qty: 2})
	var res MovementResultDto
	_ = json.Unmarshal(body, &res)
	if code != 201 || !near(res.Balances["ANA-DEPO"], 3) || !near(res.Balances["MAGAZA-01"], 2) {
		t.Fatalf("transfer: %d %s", code, body)
	}

	code, body = call(t, m.Waste, "POST", "/movements/waste",
		WasteRequest{LotNo: "KZO-2026-001", Warehouse: "MAGAZA-01", Qty: 0.5, Reason: "trim loss"})
	_ = json.Unmarshal(body, &res)
	if code != 201 || !near(res.Balances["MAGAZA-01"], 1.5) {
		t.Fatalf("waste: %d %s", code, body)
	}

	code, body = call(t, m.Return, "POST", "/movements/return",
		ReturnRequest{LotNo: "SUC-2026-045", Warehouse: "KARANTINA", CustomerCode: "MKT-A", Qty: 5})
	if code != 201 {
		t.Fatalf("return: %d %s", code, body)
	}

	code, body = call(t, m.Rework, "POST", "/rework",
		ReworkRequest{InputLotNo: "KZK-2026-001", Warehouse: "ANA-DEPO", QtyConsumed: 1, OutputLotNo: "KZK-RW-TEST", OutputQty: 0.9})
	if code != 201 {
		t.Fatalf("rework: %d %s", code, body)
	}
	_, tbody := call(t, NewTraceController(db).TraceHandler, "GET", "/trace/KZK-RW-TEST?direction=backward", nil)
	if !strings.Contains(string(tbody), `"link_type":"REWORK"`) || !strings.Contains(string(tbody), "KUZU-2026-001") {
		t.Fatalf("rework lot must trace back to the carcass: %s", tbody)
	}

	code, body = call(t, m.Waste, "POST", "/movements/waste",
		WasteRequest{LotNo: "KZO-2026-001", Warehouse: "MAGAZA-01", Qty: 99, Reason: "too much"})
	var apiErr APIError
	_ = json.Unmarshal(body, &apiErr)
	if code != 409 || apiErr.Code != "INSUFFICIENT_STOCK" {
		t.Fatalf("oversell must be 409 INSUFFICIENT_STOCK, got %d %s", code, body)
	}

	code, _ = call(t, m.Transfer, "POST", "/movements/transfer", TransferRequest{LotNo: "NOPE", FromWarehouse: "ANA-DEPO", ToWarehouse: "MAGAZA-01", Qty: 1})
	if code != 404 {
		t.Fatalf("unknown lot: want 404, got %d", code)
	}
}

func TestIntegrationRecallContactsCSV(t *testing.T) {
	db := testDB(t)
	rc := NewRecallController(db)
	code, body := call(t, rc.TriggerRecall, "POST", "/recall", RecallTriggerRequest{RecallCode: "RCL-IT-001", TriggerLot: "ET-2026-001"})
	if code != 201 {
		t.Fatalf("recall: %d %s", code, body)
	}
	code, body = call(t, rc.ContactsCSV, "GET", "/recall/contacts.csv?recall_code=RCL-IT-001", nil)
	csv := string(body)
	if code != 200 || !strings.Contains(csv, "Market A") || !strings.Contains(csv, "Kadıköy") || !strings.Contains(csv, "Örnek Lokanta") {
		t.Fatalf("csv: %d\n%s", code, csv)
	}
	if !strings.Contains(csv, "MKT-C") || !strings.Contains(csv, ",RECORDED") || !strings.Contains(csv, "NOT_RECORDED") {
		t.Fatalf("second tier flags missing:\n%s", csv)
	}
}

func TestIntegrationAISummaryIntervals(t *testing.T) {
	db := testDB(t)
	status, body := call(t, NewAIController(db).Summary, http.MethodGet, "/ai/summary", nil)
	if status != http.StatusOK {
		t.Fatalf("status %d: %s", status, body)
	}
	var s AISummaryDto
	if err := json.Unmarshal(body, &s); err != nil {
		t.Fatal(err)
	}
	found := 0
	for _, c := range s.Comparisons {
		if c.IntervalLow == nil || c.IntervalHigh == nil || c.IntervalLevel == nil || c.ModelPred == nil {
			t.Fatalf("%s %s: interval missing", c.RefNo, c.Target)
		}
		if *c.IntervalLow > *c.ModelPred || *c.ModelPred > *c.IntervalHigh || !near(*c.IntervalLevel, 0.8) {
			t.Errorf("%s %s: interval %v-%v does not contain %v", c.RefNo, c.Target, *c.IntervalLow, *c.IntervalHigh, *c.ModelPred)
		}
		// ekp has no anomaly flag of its own; packages and part weights do
		if (c.Target == "ekp") != (c.Anomaly == nil) {
			t.Errorf("%s %s: anomaly presence wrong", c.RefNo, c.Target)
		}
		if c.RefNo == "URT-2026-045" && c.Target == "actual_qty" {
			found++
			if !near(*c.IntervalLow, 83.2) || !near(*c.IntervalHigh, 86.0) || *c.Anomaly || *c.OutsideInterval {
				t.Errorf("URT-2026-045 packages: got %+v", c)
			}
		}
	}
	if found != 1 {
		t.Fatalf("URT-2026-045 packages row missing")
	}
}
