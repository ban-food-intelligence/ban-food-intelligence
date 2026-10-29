package controllers

import (
	"encoding/json"
	"math"
	"os"
	"testing"
	"time"
)

// The Go evaluation of the exported Ridge must give the same yield as sklearn's pipeline
// (golden rows written by ml-engine/export_live_model.py: the two seeded orders, 31 held-out
// orders incl. missing fat %, summer months and 500 g packs).
func TestLiveYieldMatchesTheTrainedModel(t *testing.T) {
	raw, err := os.ReadFile("aimodel/production_ridge_golden.json")
	if err != nil {
		t.Fatal(err)
	}
	var golden []struct {
		RowID  string `json:"row_id"`
		Inputs struct {
			OrderDate         string   `json:"order_date"`
			ProductSKU        string   `json:"product_sku"`
			SubcontractorCode string   `json:"subcontractor_code"`
			MeatSupplierCode  string   `json:"meat_supplier_code"`
			PackSizeKg        float64  `json:"pack_size_kg"`
			MeatKg            float64  `json:"meat_kg"`
			SpiceKg           float64  `json:"spice_kg"`
			CasingKg          float64  `json:"casing_kg"`
			MeatFatPct        *float64 `json:"meat_fat_pct"`
			MeatAgeDays       *float64 `json:"meat_age_days"`
			DryingDays        *float64 `json:"drying_days"`
			PlannedQty        float64  `json:"planned_qty"`
		} `json:"inputs"`
		YieldPct float64 `json:"yield_pct"`
	}
	if err := json.Unmarshal(raw, &golden); err != nil || len(golden) < 30 {
		t.Fatalf("golden file: %v (%d rows)", err, len(golden))
	}
	for _, g := range golden {
		d, _ := time.Parse("2006-01-02", g.Inputs.OrderDate)
		got := productionModel.predictYield(ProductionInputs{
			OrderDate: d, ProductSKU: g.Inputs.ProductSKU, SubcontractorCode: g.Inputs.SubcontractorCode,
			MeatSupplierCode: g.Inputs.MeatSupplierCode, PackSizeKg: g.Inputs.PackSizeKg, MeatKg: g.Inputs.MeatKg,
			SpiceKg: g.Inputs.SpiceKg, CasingKg: g.Inputs.CasingKg, MeatFatPct: g.Inputs.MeatFatPct,
			MeatAgeDays: g.Inputs.MeatAgeDays, DryingDays: g.Inputs.DryingDays, PlannedQty: g.Inputs.PlannedQty,
		})
		if math.Abs(got-g.YieldPct) > 1e-9 {
			t.Errorf("%s: Go %.12f, sklearn %.12f", g.RowID, got, g.YieldPct)
		}
	}
}

// URT-2026-045 (spec: 100 kg meat, 2 kg spice, 5 kg casing, 85 x 5.50 EUR service) gives the
// seeded forecast: 84.6 packages, EKP 18.2172 EUR, 80 % interval 83.2 - 86.0.
func TestForecastOfTheSpecOrder(t *testing.T) {
	fat, age, drying := 22.0, 1.0, 5.0
	o := &orderInputs{
		in: ProductionInputs{OrderDate: time.Date(2026, 10, 2, 0, 0, 0, 0, time.UTC), ProductSKU: "MM-SUCUK-1KG",
			SubcontractorCode: "FAS-01", MeatSupplierCode: "TED-ET-01", PackSizeKg: 1, MeatKg: 100, SpiceKg: 2, CasingKg: 5,
			MeatFatPct: &fat, MeatAgeDays: &age, DryingDays: &drying, PlannedQty: 85},
		serviceUnit: 5.5, materialCost: 1076,
	}
	fc := forecastOrder(o, productionModel)
	if fc == nil || fc.Packages != 84.6 || *fc.EKP != 18.2172 || *fc.PackagesLow != 83.2 || *fc.PackagesHigh != 86.0 {
		t.Fatalf("forecast %+v", fc)
	}
	if *fc.EKPLow >= *fc.EKP || *fc.EKPHigh <= *fc.EKP || fc.BaselinePackages == nil {
		t.Fatalf("EKP bounds or baseline wrong: %+v", fc)
	}
	o.in.MeatKg = 0
	if forecastOrder(o, productionModel) != nil {
		t.Fatal("an order without meat has nothing to forecast")
	}
}
