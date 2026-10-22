package controllers

import (
	"encoding/json"
	"net/http"
	"time"
)

type ProductionOrderDto struct {
	OrderID         string    `json:"order_id"`
	OrderNumber     string    `json:"order_number"`
	TargetProductSKU string   `json:"target_product_sku"`
	PlannedQtyKg    float64   `json:"planned_qty_kg"`
	ActualQtyKg     float64   `json:"actual_qty_kg"`
	YieldPct        float64   `json:"yield_pct"`
	OutputLotNumber string    `json:"output_lot_number"`
	Status          string    `json:"status"`
	CreatedAt       time.Time `json:"created_at"`
}

type ProductionController struct{}

func NewProductionController() *ProductionController {
	return &ProductionController{}
}

// ListOrders handles GET /production
func (c *ProductionController) ListOrders(w http.ResponseWriter, r *http.Request) {
	orders := []ProductionOrderDto{
		{
			OrderID:         "po-stage-wip45",
			OrderNumber:     "PO-2026-SUCUK-WIP-45",
			TargetProductSKU: "WIP-SUCUK-DOUGH",
			PlannedQtyKg:    320.0,
			ActualQtyKg:     320.0,
			YieldPct:        100.0,
			OutputLotNumber: "URT-2026-045",
			Status:          "COMPLETED",
			CreatedAt:       time.Now().Add(-72 * time.Hour),
		},
		{
			OrderID:         "po-stage-suc45",
			OrderNumber:     "PO-2026-SUCUK-DRY-45",
			TargetProductSKU: "FG-KANGAL-SUCUK",
			PlannedQtyKg:    285.0,
			ActualQtyKg:     285.0,
			YieldPct:        89.06,
			OutputLotNumber: "SUC-2026-045",
			Status:          "COMPLETED",
			CreatedAt:       time.Now().Add(-24 * time.Hour),
		},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(orders)
}
