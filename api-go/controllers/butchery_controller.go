package controllers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
)

type ButcheryCutInput struct {
	CutSKU           string  `json:"cut_sku"`
	Name             string  `json:"name"`
	YieldWeightKg    float64 `json:"yield_weight_kg"`
	MarketPricePerKg float64 `json:"market_price_per_kg"`
}

type ButcheryReconcileRequest struct {
	OperationNo      string             `json:"op_no,omitempty"`
	CarcassLotNumber string             `json:"carcass_lot_number"`
	CarcassWeightKg  float64            `json:"carcass_weight_kg"`
	CarcassTotalCost float64            `json:"carcass_total_cost"`
	AllocationMethod string             `json:"allocation_method"` // "QUANTITY", "VALUE", "PHYSICAL_QUANTITY", "NET_REALIZABLE_VALUE"
	Outputs          []ButcheryCutInput `json:"outputs"`
}

type ButcheryCutAllocatedOutput struct {
	CutSKU            string  `json:"cut_sku"`
	Name              string  `json:"name"`
	LotNumber         string  `json:"lot_no,omitempty"`
	OutputKind        string  `json:"output_kind,omitempty"`
	YieldWeightKg     float64 `json:"yield_weight_kg"`
	YieldPct          float64 `json:"yield_pct"`
	MarketPricePerKg  float64 `json:"market_price_per_kg"`
	TotalSalesValue   float64 `json:"total_sales_value"`
	AllocationBasis   float64 `json:"allocation_basis"`
	AllocatedCost     float64 `json:"allocated_cost"`
	AllocatedUnitCost float64 `json:"allocated_unit_cost_per_kg"`
	MarginPercentage  float64 `json:"margin_percentage"`
}

type ButcheryOperationSummaryDto struct {
	OpNo              string                       `json:"op_no"`
	Species           string                       `json:"species"`
	AllocationMethod  string                       `json:"allocation_method"`
	Status            string                       `json:"status"`
	InputWeightKg     float64                      `json:"input_weight_kg"`
	OutputWeightKg    float64                      `json:"output_weight_kg"`
	CutWeightKg       float64                      `json:"cut_weight_kg"`
	WasteWeightKg     float64                      `json:"waste_weight_kg"`
	WeightDiffKg      float64                      `json:"weight_diff_kg"`
	PurchaseUnitPrice float64                      `json:"purchase_unit_price"`
	PurchaseCost      float64                      `json:"purchase_cost"`
	AdditionalCost    float64                      `json:"additional_cost"`
	TotalCost         float64                      `json:"total_cost"`
	DeclaredTotalCost *float64                     `json:"declared_total_cost,omitempty"`
	DeclaredCostDiff  *float64                     `json:"declared_cost_diff,omitempty"`
	AllocatedTotal    float64                      `json:"allocated_total"`
	AllocationDiff    float64                      `json:"allocation_diff"`
	WeightOk          bool                         `json:"weight_ok"`
	DeclaredCostOk    bool                         `json:"declared_cost_ok"`
	AllocationOk      bool                         `json:"allocation_ok"`
	Cuts              []ButcheryCutAllocatedOutput `json:"allocated_cuts"`
}

type ButcheryController struct {
	db *sql.DB
}

func NewButcheryController(db *sql.DB) *ButcheryController {
	return &ButcheryController{db: db}
}

// ReconcileButchery handles GET /butchery, POST /butchery, and POST /butchery/reconcile
// Calls Ali's MySQL stored procedure `CALL sp_execute_butchery(...)` and queries
// `v_butchery_reconciliation` + `v_butchery_cost_allocation`.
func (c *ButcheryController) ReconcileButchery(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		c.ListButcheryOperations(w, r)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}

	var req ButcheryReconcileRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid request body"}`, http.StatusBadRequest)
		return
	}

	allocMethod := "VALUE"
	if req.AllocationMethod == "PHYSICAL_QUANTITY" || req.AllocationMethod == "QUANTITY" {
		allocMethod = "QUANTITY"
	}

	opNo := req.OperationNo
	if opNo == "" {
		if allocMethod == "QUANTITY" {
			opNo = "PRC-2026-002"
		} else {
			opNo = "PRC-2026-001"
		}
	}

	// Execute MySQL stored procedure `CALL sp_execute_butchery(?, ?)`
	rows, err := c.db.QueryContext(r.Context(), `CALL sp_execute_butchery(?, ?)`, opNo, allocMethod)
	if err != nil {
		log.Printf("[ERROR] [MYSQL-BUTCHERY] sp_execute_butchery error: %v", err)
		http.Error(w, fmt.Sprintf(`{"error":"sp_execute_butchery failed: %s"}`, err.Error()), http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var summary ButcheryOperationSummaryDto
	if rows.Next() {
		var declaredCost sql.NullFloat64
		var declaredDiff sql.NullFloat64
		if err := rows.Scan(
			&summary.OpNo, &summary.Species, &summary.AllocationMethod, &summary.Status,
			&summary.InputWeightKg, &summary.OutputWeightKg, &summary.CutWeightKg,
			&summary.WasteWeightKg, &summary.WeightDiffKg,
			&summary.PurchaseUnitPrice, &summary.PurchaseCost, &summary.AdditionalCost,
			&summary.TotalCost, &declaredCost, &declaredDiff,
			&summary.AllocatedTotal, &summary.AllocationDiff,
			&summary.WeightOk, &summary.DeclaredCostOk, &summary.AllocationOk,
		); err != nil {
			http.Error(w, fmt.Sprintf(`{"error":"scan sp_execute_butchery failed: %s"}`, err.Error()), http.StatusInternalServerError)
			return
		}
		if declaredCost.Valid {
			v := declaredCost.Float64
			summary.DeclaredTotalCost = &v
		}
		if declaredDiff.Valid {
			v := declaredDiff.Float64
			summary.DeclaredCostDiff = &v
			if v != 0 || !summary.DeclaredCostOk {
				log.Printf("[WARN] [BUTCHERY] Discrepancy Flagged on op=%s: calculated=%.2f EUR vs declared=%.2f EUR (diff=%+.2f EUR)", summary.OpNo, summary.TotalCost, declaredCost.Float64, v)
			}
		}
	}
	rows.Close()

	summary.Cuts = c.fetchCutAllocations(r, summary.OpNo)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(summary)
}

// ListButcheryOperations handles GET /butchery
func (c *ButcheryController) ListButcheryOperations(w http.ResponseWriter, r *http.Request) {
	rows, err := c.db.QueryContext(
		r.Context(),
		`SELECT op_no, species, allocation_method, status,
		        input_weight_kg, output_weight_kg, cut_weight_kg, waste_weight_kg, weight_diff_kg,
		        purchase_unit_price, purchase_cost, additional_cost, total_cost,
		        declared_total_cost, declared_cost_diff, allocated_total, allocation_diff,
		        weight_ok, declared_cost_ok, allocation_ok
		 FROM v_butchery_reconciliation
		 ORDER BY op_no ASC`,
	)
	if err != nil {
		http.Error(w, fmt.Sprintf(`{"error":"query v_butchery_reconciliation failed: %s"}`, err.Error()), http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var ops []ButcheryOperationSummaryDto
	for rows.Next() {
		var s ButcheryOperationSummaryDto
		var declaredCost sql.NullFloat64
		var declaredDiff sql.NullFloat64
		if err := rows.Scan(
			&s.OpNo, &s.Species, &s.AllocationMethod, &s.Status,
			&s.InputWeightKg, &s.OutputWeightKg, &s.CutWeightKg, &s.WasteWeightKg, &s.WeightDiffKg,
			&s.PurchaseUnitPrice, &s.PurchaseCost, &s.AdditionalCost, &s.TotalCost,
			&declaredCost, &declaredDiff, &s.AllocatedTotal, &s.AllocationDiff,
			&s.WeightOk, &s.DeclaredCostOk, &s.AllocationOk,
		); err != nil {
			http.Error(w, fmt.Sprintf(`{"error":"scan v_butchery_reconciliation failed: %s"}`, err.Error()), http.StatusInternalServerError)
			return
		}
		if declaredCost.Valid {
			v := declaredCost.Float64
			s.DeclaredTotalCost = &v
		}
		if declaredDiff.Valid {
			v := declaredDiff.Float64
			s.DeclaredCostDiff = &v
			if v != 0 || !s.DeclaredCostOk {
				log.Printf("[WARN] [BUTCHERY] Discrepancy Flagged on op=%s: calculated=%.2f EUR vs declared=%.2f EUR (diff=%+.2f EUR)", s.OpNo, s.TotalCost, declaredCost.Float64, v)
			}
		}
		s.Cuts = c.fetchCutAllocations(r, s.OpNo)
		ops = append(ops, s)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(ops)
}

func (c *ButcheryController) fetchCutAllocations(r *http.Request, opNo string) []ButcheryCutAllocatedOutput {
	rows, err := c.db.QueryContext(
		r.Context(),
		`SELECT p.sku, v.product_name, COALESCE(v.lot_no, ''), v.output_kind,
		        v.weight_kg, v.yield_pct, COALESCE(p.sale_price, 0),
		        v.allocation_basis, v.allocated_cost, v.unit_cost
		 FROM v_butchery_cost_allocation v
		 JOIN product p ON p.name = v.product_name
		 WHERE v.op_no = ?
		 ORDER BY v.weight_kg DESC`,
		opNo,
	)
	if err != nil {
		return nil
	}
	defer rows.Close()

	var cuts []ButcheryCutAllocatedOutput
	for rows.Next() {
		var cut ButcheryCutAllocatedOutput
		if err := rows.Scan(
			&cut.CutSKU, &cut.Name, &cut.LotNumber, &cut.OutputKind,
			&cut.YieldWeightKg, &cut.YieldPct, &cut.MarketPricePerKg,
			&cut.AllocationBasis, &cut.AllocatedCost, &cut.AllocatedUnitCost,
		); err == nil {
			cut.TotalSalesValue = cut.YieldWeightKg * cut.MarketPricePerKg
			if cut.TotalSalesValue > 0 {
				cut.MarginPercentage = ((cut.TotalSalesValue - cut.AllocatedCost) / cut.TotalSalesValue) * 100.0
			}
			cuts = append(cuts, cut)
		}
	}
	return cuts
}
