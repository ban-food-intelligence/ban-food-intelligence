package controllers

import (
	"fmt"
	"math"
	"net/http"
	"strings"
)

// WeightTolerance is the accepted gap between input weight and recorded outputs
// (scale resolution); anything above it is reported as unrecorded loss.
const WeightToleranceKg = 0.010

// WeightCheckDto explains the mass balance of a butchery operation:
// input = cuts + by-products + recorded waste + unrecorded.
type WeightCheckDto struct {
	InputKg      float64 `json:"input_kg"`
	CutsKg       float64 `json:"cuts_kg"`
	ByProductKg  float64 `json:"by_product_kg"`
	WasteKg      float64 `json:"waste_kg"`
	UnrecordedKg float64 `json:"unrecorded_kg"`
	ToleranceKg  float64 `json:"tolerance_kg"`
	Status       string  `json:"status"` // BALANCED | UNRECORDED_LOSS | OVER_RECORDED
}

// ButcheryFindingDto is one detected inconsistency (or confirmation) with a readable
// explanation in the request language.
type ButcheryFindingDto struct {
	Code     string             `json:"code"`
	Severity string             `json:"severity"` // ERROR | WARNING | OK
	Message  string             `json:"message"`
	Values   map[string]float64 `json:"values"`
}

func analyseButchery(r *http.Request, s *ButcheryOperationSummaryDto) {
	wc := WeightCheckDto{InputKg: s.InputWeightKg, ToleranceKg: WeightToleranceKg}
	for _, c := range s.Cuts {
		switch c.OutputKind {
		case "WASTE":
			wc.WasteKg += c.YieldWeightKg
		case "BY_PRODUCT":
			wc.ByProductKg += c.YieldWeightKg
		default:
			wc.CutsKg += c.YieldWeightKg
		}
	}
	wc.UnrecordedKg = round3(wc.InputKg - wc.CutsKg - wc.ByProductKg - wc.WasteKg)
	switch {
	case wc.UnrecordedKg > WeightToleranceKg:
		wc.Status = "UNRECORDED_LOSS"
	case wc.UnrecordedKg < -WeightToleranceKg:
		wc.Status = "OVER_RECORDED"
	default:
		wc.Status = "BALANCED"
	}
	s.WeightCheck = wc

	l := lang(r)
	var findings []ButcheryFindingDto
	if s.DeclaredTotalCost != nil && math.Abs(*s.DeclaredTotalCost-s.TotalCost) >= 0.005 {
		findings = append(findings, ButcheryFindingDto{
			Code: "DECLARED_COST_MISMATCH", Severity: "ERROR",
			Message: tr(l, "declared_cost", *s.DeclaredTotalCost, s.InputWeightKg, s.PurchaseUnitPrice, s.TotalCost, *s.DeclaredTotalCost-s.TotalCost),
			Values: map[string]float64{
				"declared_total_cost": *s.DeclaredTotalCost, "computed_total_cost": s.TotalCost,
				"difference": round2(*s.DeclaredTotalCost - s.TotalCost),
			},
		})
	}
	switch wc.Status {
	case "UNRECORDED_LOSS", "OVER_RECORDED":
		findings = append(findings, ButcheryFindingDto{
			Code: "WEIGHT_IMBALANCE", Severity: "ERROR",
			Message: tr(l, "weight_gap", wc.InputKg, wc.CutsKg+wc.ByProductKg, wc.WasteKg, wc.UnrecordedKg),
			Values:  map[string]float64{"input_kg": wc.InputKg, "outputs_kg": round3(wc.CutsKg + wc.ByProductKg), "waste_kg": wc.WasteKg, "unrecorded_kg": wc.UnrecordedKg},
		})
	default:
		findings = append(findings, ButcheryFindingDto{
			Code: "WEIGHT_BALANCED", Severity: "OK",
			Message: tr(l, "weight_ok", wc.InputKg, wc.CutsKg+wc.ByProductKg, wc.WasteKg),
			Values:  map[string]float64{"input_kg": wc.InputKg, "outputs_kg": round3(wc.CutsKg + wc.ByProductKg), "waste_kg": wc.WasteKg},
		})
	}
	if s.Status == "POSTED" {
		if math.Abs(s.AllocationDiff) < 0.005 {
			findings = append(findings, ButcheryFindingDto{
				Code: "ALLOCATION_BALANCED", Severity: "OK",
				Message: tr(l, "alloc_ok", s.AllocatedTotal, s.TotalCost),
				Values:  map[string]float64{"allocated_total": s.AllocatedTotal, "total_cost": s.TotalCost},
			})
		} else {
			findings = append(findings, ButcheryFindingDto{
				Code: "ALLOCATION_MISMATCH", Severity: "ERROR",
				Message: tr(l, "alloc_bad", s.AllocatedTotal, s.TotalCost),
				Values:  map[string]float64{"allocated_total": s.AllocatedTotal, "total_cost": s.TotalCost},
			})
		}
	}
	s.Findings = findings
}

var butcheryTexts = map[string]map[string]string{
	"declared_cost": {
		"en": "Declared cost %s EUR does not match %s kg x %s EUR = %s EUR. The difference of %s EUR has no supporting document, so it is not used.",
		"tr": "Beyan edilen maliyet %s €, %s kg × %s € = %s € ile uyuşmuyor. %s € fark belgeyle desteklenmediği için kullanılmaz.",
		"de": "Der angegebene Wert %s € stimmt nicht mit %s kg × %s € = %s € überein. Die Differenz von %s € ist nicht belegt und wird nicht verwendet.",
	},
	"weight_gap": {
		"en": "Input %s kg, but parts %s kg and recorded waste %s kg leave %s kg unrecorded. Weigh and record it as waste or by-product.",
		"tr": "Giriş %s kg; parçalar %s kg ve kayıtlı fire %s kg ile %s kg kayıtsız kalıyor. Tartıp fire veya yan ürün olarak kaydedin.",
		"de": "Eingang %s kg, aber Teile %s kg und erfasster Ausschuss %s kg lassen %s kg unerfasst. Wiegen und als Ausschuss oder Nebenprodukt erfassen.",
	},
	"weight_ok": {
		"en": "Weight balance OK: %s kg = %s kg parts + %s kg waste.",
		"tr": "Ağırlık dengesi tamam: %s kg = %s kg parça + %s kg fire.",
		"de": "Gewichtsbilanz stimmt: %s kg = %s kg Teile + %s kg Ausschuss.",
	},
	"alloc_ok": {
		"en": "Allocated %s EUR equals the total cost %s EUR.",
		"tr": "Dağıtılan %s €, toplam maliyet %s € ile eşit.",
		"de": "Verteilte %s € entsprechen den Gesamtkosten %s €.",
	},
	"alloc_bad": {
		"en": "Allocated %s EUR differs from the total cost %s EUR.",
		"tr": "Dağıtılan %s €, toplam maliyet %s € ile farklı.",
		"de": "Verteilte %s € weichen von den Gesamtkosten %s € ab.",
	},
}

func tr(l, key string, args ...interface{}) string {
	t := butcheryTexts[key][l]
	if t == "" {
		t = butcheryTexts[key]["en"]
	}
	out := make([]interface{}, len(args))
	for i, a := range args {
		if f, ok := a.(float64); ok {
			out[i] = localNum(l, f)
		} else {
			out[i] = a
		}
	}
	return fmt.Sprintf(t, out...)
}

// localNum formats 2 decimals with the language's separator: 287,50 (tr/de), 287.50 (en).
func localNum(l string, v float64) string {
	s := fmt.Sprintf("%.2f", v)
	if l == "tr" || l == "de" {
		s = strings.Replace(s, ".", ",", 1)
	}
	return s
}

// AllocationLineDto is one line of a computed cost allocation preview.
type AllocationLineDto struct {
	CutSKU        string  `json:"cut_sku"`
	Name          string  `json:"name"`
	LotNo         string  `json:"lot_no,omitempty"`
	OutputKind    string  `json:"output_kind"`
	WeightKg      float64 `json:"weight_kg"`
	SalePrice     float64 `json:"sale_price"`
	Basis         float64 `json:"basis"`
	SharePct      float64 `json:"share_pct"`
	AllocatedCost float64 `json:"allocated_cost"`
	UnitCost      float64 `json:"unit_cost_per_kg"`
}

type AllocationPreviewDto struct {
	OpNo      string              `json:"op_no"`
	Method    string              `json:"method"`
	TotalCost float64             `json:"total_cost"`
	Allocated float64             `json:"allocated_total"`
	SumsExact bool                `json:"sums_exactly"`
	Lines     []AllocationLineDto `json:"lines"`
}

// Allocation handles GET /butchery/allocation?op_no=PRC-2026-001&method=QUANTITY|VALUE:
// the cost split computed with AllocateCost (cents always sum to the total cost).
func (c *ButcheryController) Allocation(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", nil)
		return
	}
	opNo := r.URL.Query().Get("op_no")
	method := strings.ToUpper(r.URL.Query().Get("method"))
	if method == "" {
		method = "VALUE"
	}
	if opNo == "" || (method != "VALUE" && method != "QUANTITY") {
		writeAPIError(w, r, http.StatusBadRequest, "INVALID_INPUT", "op_no is required; method is QUANTITY or VALUE")
		return
	}
	var total float64
	if err := c.db.QueryRowContext(r.Context(), `SELECT total_cost FROM butchery_operation WHERE op_no = ?`, opNo).Scan(&total); err != nil {
		writeAPIError(w, r, http.StatusNotFound, "NOT_FOUND", "butchery operation "+opNo)
		return
	}
	cuts := c.fetchCutAllocations(r, opNo)
	bases := make([]float64, len(cuts))
	var basisSum float64
	for i, cut := range cuts {
		if cut.OutputKind == "WASTE" {
			continue
		}
		if method == "QUANTITY" {
			bases[i] = cut.YieldWeightKg
		} else {
			bases[i] = cut.YieldWeightKg * cut.MarketPricePerKg
		}
		basisSum += bases[i]
	}
	alloc := AllocateCost(total, bases)
	resp := AllocationPreviewDto{OpNo: opNo, Method: method, TotalCost: total}
	var cents int64
	for i, cut := range cuts {
		line := AllocationLineDto{
			CutSKU: cut.CutSKU, Name: cut.Name, LotNo: cut.LotNumber, OutputKind: cut.OutputKind,
			WeightKg: cut.YieldWeightKg, SalePrice: cut.MarketPricePerKg, Basis: round2(bases[i]), AllocatedCost: alloc[i],
		}
		if basisSum > 0 {
			line.SharePct = round2(100 * bases[i] / basisSum)
		}
		if cut.YieldWeightKg > 0 && alloc[i] > 0 {
			line.UnitCost = math.Round(alloc[i]/cut.YieldWeightKg*10000) / 10000
		}
		cents += int64(math.Round(alloc[i] * 100))
		resp.Lines = append(resp.Lines, line)
	}
	resp.Allocated = float64(cents) / 100
	resp.SumsExact = cents == int64(math.Round(total*100))
	writeJSON(w, resp)
}

func round2(v float64) float64 { return math.Round(v*100) / 100 }
func round3(v float64) float64 { return math.Round(v*1000) / 1000 }
