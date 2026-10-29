package controllers

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"
)

func TestButcheryForecastRejectsInvalidInputs(t *testing.T) {
	ctrl := NewAIController(nil) // invalid requests must never reach the database
	for _, weight := range []string{"NaN", "Inf", "-1", "0", "501", "oops", ""} {
		code, body := call(t, ctrl.ButcheryForecast, "GET", "/ai/forecast/butchery?input_lot_no=KZ&input_weight_kg="+weight, nil)
		if code != http.StatusUnprocessableEntity {
			t.Errorf("%q: %d %s", weight, code, body)
		}
	}
	code, _ := call(t, ctrl.ButcheryForecast, "POST", "/ai/forecast/butchery", nil)
	if code != http.StatusMethodNotAllowed {
		t.Fatal(code)
	}
}

func TestIntegrationButcheryLiveForecast(t *testing.T) {
	db := testDB(t)
	entry, ai := NewEntryController(db), NewAIController(db)
	suffix := runSuffix()
	carcass := "KZ-AI-" + suffix
	today := time.Now().UTC().Format("2006-01-02")
	code, body := call(t, entry.Purchase, "POST", "/purchases", PurchaseRequest{
		ReceiptNo: "ALS-AI-" + suffix, SupplierCode: "TED-HAY-01", ReceiptDate: today,
		Lines: []PurchaseLineRequest{{SKU: "KZ-KARKAS", LotNo: carcass, Qty: 25, UnitPrice: 11.5,
			ExpiryDate: time.Now().UTC().AddDate(0, 0, 7).Format("2006-01-02")}},
	})
	if code != 201 {
		t.Fatalf("purchase: %d %s", code, body)
	}
	path := "/ai/forecast/butchery?input_lot_no=" + carcass + "&input_weight_kg=25&op_date=" + today + "&operator_name=OP-1"
	code, body = call(t, ai.ButcheryForecast, "GET", path, nil)
	var forecast ButcheryForecastDto
	if err := json.Unmarshal(body, &forecast); err != nil || code != 200 || len(forecast.Parts) != 6 {
		t.Fatalf("forecast: %d %s %v", code, body, err)
	}
	for _, p := range forecast.Parts {
		if p.ActualKg != nil || p.Anomaly != nil || p.BaselineKg == nil || p.KgLow >= p.Kg || p.KgHigh <= p.Kg {
			t.Fatalf("preview part: %+v", p)
		}
	}
	for _, tc := range []struct {
		lot  string
		code int
	}{{"MISSING", 404}, {"ET-2026-001", 422}} {
		code, body = call(t, ai.ButcheryForecast, "GET", "/ai/forecast/butchery?input_lot_no="+tc.lot+"&input_weight_kg=25", nil)
		if code != tc.code {
			t.Fatalf("lot %s: %d %s", tc.lot, code, body)
		}
	}
	// Deliberately unusual leg and back weights: keep the mass balance, trigger the anomaly.
	req := ButcheryOperationRequest{OpNo: "PRC-AI-" + suffix, InputLotNo: carcass, InputWeightKg: 25,
		AllocationMethod: "VALUE", OpDate: today, OperatorName: "OP-1", DryRun: true,
		Outputs: []ButcheryOutputRequest{
			{SKU: "KZ-BUT", Kind: "CUT", WeightKg: 0.5, LotNo: "KZB-AI-" + suffix},
			{SKU: "KZ-SIRT", Kind: "CUT", WeightKg: 9.5, LotNo: "KZS-AI-" + suffix},
			{SKU: "KZ-OMUZ", Kind: "CUT", WeightKg: 5, LotNo: "KZO-AI-" + suffix},
			{SKU: "KZ-KABURGA", Kind: "CUT", WeightKg: 3, LotNo: "KZK-AI-" + suffix},
			{SKU: "KZ-KIYMA", Kind: "CUT", WeightKg: 4, LotNo: "KZY-AI-" + suffix},
			{SKU: "KZ-FIRE", Kind: "WASTE", WeightKg: 3},
		}}
	code, body = call(t, entry.ButcheryOperation, "POST", "/butchery/operations", req)
	var result ButcheryOperationResultDto
	if err := json.Unmarshal(body, &result); err != nil || code != 200 || result.Forecast == nil {
		t.Fatalf("dry run: %d %s %v", code, body, err)
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM butchery_operation WHERE op_no=?`, req.OpNo).Scan(&n); err != nil || n != 0 {
		t.Fatalf("dry run wrote data: %d %v", n, err)
	}
	req.DryRun = false
	code, body = call(t, entry.ButcheryOperation, "POST", "/butchery/operations", req)
	if err := json.Unmarshal(body, &result); err != nil || code != 201 || result.Forecast == nil {
		t.Fatalf("post: %d %s %v", code, body, err)
	}
	for i, p := range result.Forecast.Parts {
		if p.Kg != forecast.Parts[i].Kg || p.ActualKg == nil || p.Anomaly == nil {
			t.Fatalf("part %+v", p)
		}
		var pred, actual, lo, hi, level float64
		var anomaly bool
		err := db.QueryRow(`SELECT predicted_value, actual_value, interval_low, interval_high, interval_level, is_anomaly
            FROM ml_prediction mp JOIN butchery_operation bo ON bo.id=mp.ref_id
            WHERE bo.op_no=? AND mp.ref_type='butchery_operation' AND mp.model_name=? AND mp.target_name=?`,
			req.OpNo, championModel, p.Part+"_kg").Scan(&pred, &actual, &lo, &hi, &level, &anomaly)
		if err != nil || pred != p.Kg || actual != *p.ActualKg || lo != p.KgLow || hi != p.KgHigh || level != 0.8 || anomaly != *p.Anomaly {
			t.Fatalf("stored part %s: %g %g %g %g %g %v (%v), response %+v", p.Part, pred, actual, lo, hi, level, anomaly, err, p)
		}
	}
	if !*result.Forecast.Parts[0].Anomaly {
		t.Fatal("unusual leg weight must be flagged")
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM ml_prediction mp JOIN butchery_operation bo ON bo.id=mp.ref_id
        WHERE bo.op_no=? AND mp.ref_type='butchery_operation'`, req.OpNo).Scan(&n); err != nil || n != 12 {
		t.Fatalf("want six model and six baseline rows: %d %v", n, err)
	}
	code, body = call(t, ai.Summary, "GET", "/ai/summary", nil)
	var summary AISummaryDto
	if err := json.Unmarshal(body, &summary); err != nil || code != 200 {
		t.Fatalf("summary: %d %s", code, body)
	}
	n = 0
	for _, row := range summary.Comparisons {
		if row.RefNo == req.OpNo {
			n++
			if row.Actual == nil || row.ModelPred == nil || row.BaselinePred == nil || row.Anomaly == nil {
				t.Fatalf("summary row %+v", row)
			}
		}
	}
	if n != 6 {
		t.Fatalf("summary has %d part rows", n)
	}
	// A failed duplicate operation must leave neither an operation nor additional predictions.
	req.OpNo += "X"
	code, body = call(t, entry.ButcheryOperation, "POST", "/butchery/operations", req)
	if code != 409 {
		t.Fatalf("duplicate part lot: %d %s", code, body)
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM butchery_operation WHERE op_no=?`, req.OpNo).Scan(&n); err != nil || n != 0 {
		t.Fatalf("failed operation was not rolled back: %d %v", n, err)
	}
}
