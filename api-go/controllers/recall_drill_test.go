package controllers

import (
	"reflect"
	"testing"
	"time"
)

func TestDrillVerdictNeedsAllThreeConditions(t *testing.T) {
	for name, c := range map[string]struct {
		balanced  bool
		unreached int
		elapsed   time.Duration
		result    string
		reasons   []string
	}{
		"all met":             {true, 0, 239 * time.Minute, "PASS", []string{}},
		"exactly on target":   {true, 0, 240 * time.Minute, "PASS", []string{}},
		"unexplained qty":     {false, 0, time.Minute, "FAIL", []string{drillUnexplained}},
		"customer missing":    {true, 1, time.Minute, "FAIL", []string{drillNotReached}},
		"one second too late": {true, 0, 240*time.Minute + time.Second, "FAIL", []string{drillOverTime}},
		"everything wrong":    {false, 2, 5 * time.Hour, "FAIL", []string{drillUnexplained, drillNotReached, drillOverTime}},
	} {
		result, reasons := drillVerdict(c.balanced, c.unreached, c.elapsed, 240)
		if result != c.result || !reflect.DeepEqual(reasons, c.reasons) {
			t.Errorf("%s: got %s %v, want %s %v", name, result, reasons, c.result, c.reasons)
		}
	}
}

func TestDrillCustomersKeepTheLowestTierAndSortByTier(t *testing.T) {
	fwd := &TraceResponse{Shipments: []TraceShipmentDto{
		{Tier: 2, CustomerCode: "REST-01", LotNo: "SUC-1", Qty: 5, UomCode: "PAKET"},
		{Tier: 1, CustomerCode: "MKT-C", LotNo: "SUC-1", Qty: 30, UomCode: "PAKET"},
		{Tier: 1, CustomerCode: "MKT-A", LotNo: "SUC-1", Qty: 30, UomCode: "PAKET"},
		{Tier: 1, CustomerCode: "REST-01", LotNo: "SUC-2", Qty: 2, UomCode: "PAKET"}, // also a direct buyer
		{Tier: 1, CustomerCode: "MKT-A", LotNo: "KZB-1", Qty: 2.5, UomCode: "KG"},
	}}
	codes, tiers := drillCustomers(fwd)
	if !reflect.DeepEqual(codes, []string{"MKT-A", "MKT-C", "REST-01"}) || tiers["REST-01"] != 1 {
		t.Fatalf("customers %v tiers %v", codes, tiers)
	}
	lots, qty := drillShipped(&drillSnapshot{Forward: fwd}, "MKT-A")
	if !reflect.DeepEqual(lots, []string{"KZB-1", "SUC-1"}) ||
		!reflect.DeepEqual(qty, []DrillQtyDto{{Qty: 30, Uom: "PAKET"}, {Qty: 2.5, Uom: "KG"}}) {
		t.Fatalf("MKT-A shipped %v %v", lots, qty)
	}
	if lots, qty := drillShipped(&drillSnapshot{}, "MKT-A"); len(lots) != 0 || len(qty) != 0 {
		t.Fatalf("empty snapshot: %v %v", lots, qty)
	}
}

func TestValidateDrill(t *testing.T) {
	ok := RecallDrillRequest{LotNo: "ET-2026-001", DrillNo: "TAT-1", TargetMinutes: 240}
	if fe := validateDrill(&ok); len(fe) != 0 {
		t.Fatalf("valid drill refused: %v", fe)
	}
	bad := RecallDrillRequest{DrillNo: "TAT-0123456789012345678901234567", TargetMinutes: 10081, Note: string(make([]rune, 256))}
	fe := validateDrill(&bad)
	for _, k := range []string{"lot_no", "drill_no", "target_minutes", "note"} {
		if fe[k] == "" {
			t.Errorf("%s not reported: %v", k, fe)
		}
	}
}
