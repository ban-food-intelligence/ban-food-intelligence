package controllers

import (
	"encoding/json"
	"net/http"
)

// ButcheryCutInput represents a cut product yield from a carcass breakdown
type ButcheryCutInput struct {
	CutSKU           string  `json:"cut_sku"`
	Name             string  `json:"name"`
	YieldWeightKg    float64 `json:"yield_weight_kg"`
	MarketPricePerKg float64 `json:"market_price_per_kg"`
}

// ButcheryReconcileRequest holds parameters for Task 2 Butchery Reconciliation
type ButcheryReconcileRequest struct {
	CarcassLotNumber string             `json:"carcass_lot_number"` // e.g. "ET-2026-001"
	CarcassWeightKg  float64            `json:"carcass_weight_kg"`  // e.g. 450 KG
	CarcassTotalCost float64            `json:"carcass_total_cost"` // e.g. 5400.00 TL
	AllocationMethod string             `json:"allocation_method"`  // "PHYSICAL_QUANTITY" or "NET_REALIZABLE_VALUE"
	Outputs          []ButcheryCutInput `json:"outputs"`
}

// ButcheryCutAllocatedOutput represents the calculated cost and margin per cut
type ButcheryCutAllocatedOutput struct {
	CutSKU           string  `json:"cut_sku"`
	Name             string  `json:"name"`
	YieldWeightKg    float64 `json:"yield_weight_kg"`
	MarketPricePerKg float64 `json:"market_price_per_kg"`
	TotalSalesValue  float64 `json:"total_sales_value"`
	AllocatedCost    float64 `json:"allocated_cost"`
	AllocatedUnitCost float64 `json:"allocated_unit_cost_per_kg"`
	MarginPercentage float64 `json:"margin_percentage"`
}

// ButcheryReconcileResponse returns joint cost allocation results
type ButcheryReconcileResponse struct {
	CarcassLotNumber   string                       `json:"carcass_lot_number"`
	TotalInputWeight   float64                      `json:"total_input_weight_kg"`
	TotalOutputWeight  float64                      `json:"total_output_weight_kg"`
	ButcheryYieldPct   float64                      `json:"butchery_yield_percentage"`
	TotalCarcassCost   float64                      `json:"total_carcass_cost"`
	TotalSalesValue    float64                      `json:"total_sales_value"`
	AllocationMethod   string                       `json:"allocation_method"`
	AllocatedCuts      []ButcheryCutAllocatedOutput `json:"allocated_cuts"`
}

type ButcheryController struct{}

func NewButcheryController() *ButcheryController {
	return &ButcheryController{}
}

// ReconcileButchery handles POST /butchery/reconcile
// Implements Berke's Task 2 Butchery Reconciliation Logic: Physical Quantity vs Net Realizable Value allocation
func (c *ButcheryController) ReconcileButchery(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}

	var req ButcheryReconcileRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid request body"}`, http.StatusBadRequest)
		return
	}

	totalOutputWeight := 0.0
	totalSalesValue := 0.0

	for _, out := range req.Outputs {
		totalOutputWeight += out.YieldWeightKg
		totalSalesValue += out.YieldWeightKg * out.MarketPricePerKg
	}

	var results []ButcheryCutAllocatedOutput

	for _, out := range req.Outputs {
		cutSalesValue := out.YieldWeightKg * out.MarketPricePerKg
		var allocatedCost float64

		if req.AllocationMethod == "PHYSICAL_QUANTITY" {
			// Cost allocated strictly proportional to physical weight
			if totalOutputWeight > 0 {
				allocatedCost = (out.YieldWeightKg / totalOutputWeight) * req.CarcassTotalCost
			}
		} else {
			// Net Realizable Value (Relative Sales Value) method
			if totalSalesValue > 0 {
				allocatedCost = (cutSalesValue / totalSalesValue) * req.CarcassTotalCost
			}
		}

		unitCost := 0.0
		if out.YieldWeightKg > 0 {
			unitCost = allocatedCost / out.YieldWeightKg
		}

		marginPct := 0.0
		if cutSalesValue > 0 {
			marginPct = ((cutSalesValue - allocatedCost) / cutSalesValue) * 100.0
		}

		results = append(results, ButcheryCutAllocatedOutput{
			CutSKU:            out.CutSKU,
			Name:              out.Name,
			YieldWeightKg:     out.YieldWeightKg,
			MarketPricePerKg:  out.MarketPricePerKg,
			TotalSalesValue:   cutSalesValue,
			AllocatedCost:     allocatedCost,
			AllocatedUnitCost: unitCost,
			MarginPercentage:  marginPct,
		})
	}

	yieldPct := 0.0
	if req.CarcassWeightKg > 0 {
		yieldPct = (totalOutputWeight / req.CarcassWeightKg) * 100.0
	}

	resp := ButcheryReconcileResponse{
		CarcassLotNumber:  req.CarcassLotNumber,
		TotalInputWeight:  req.CarcassWeightKg,
		TotalOutputWeight: totalOutputWeight,
		ButcheryYieldPct:  yieldPct,
		TotalCarcassCost:  req.CarcassTotalCost,
		TotalSalesValue:   totalSalesValue,
		AllocationMethod:  req.AllocationMethod,
		AllocatedCuts:     results,
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
