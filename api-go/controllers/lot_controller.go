package controllers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
)

type LotController struct {
	db    *sql.DB
	trace *TraceController
}

func NewLotController(db *sql.DB) *LotController {
	return &LotController{
		db:    db,
		trace: NewTraceController(db),
	}
}

// TraceLot delegates to TraceController backed by MySQL `sp_trace_forward` / `sp_trace_backward`.
func (c *LotController) TraceLot(w http.ResponseWriter, r *http.Request) {
	c.trace.TraceHandler(w, r)
}

// ListStockByLot handles GET /lots
// Queries Ali's MySQL `v_stock_by_lot` view.
func (c *LotController) ListStockByLot(w http.ResponseWriter, r *http.Request) {
	rows, err := c.db.QueryContext(
		r.Context(),
		`SELECT lot_no, sku, product_name, warehouse_code, qty, uom_code, unit_cost, stock_value, status
		 FROM v_stock_by_lot
		 ORDER BY lot_no ASC, warehouse_code ASC`,
	)
	if err != nil {
		http.Error(w, fmt.Sprintf(`{"error":"failed to query v_stock_by_lot: %s"}`, err.Error()), http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type StockLotDto struct {
		LotNo         string  `json:"lot_no"`
		SKU           string  `json:"sku"`
		ProductName   string  `json:"product_name"`
		WarehouseCode string  `json:"warehouse_code"`
		Qty           float64 `json:"qty"`
		UomCode       string  `json:"uom_code"`
		UnitCost      float64 `json:"unit_cost"`
		StockValue    float64 `json:"stock_value"`
		Status        string  `json:"status"`
	}

	var list []StockLotDto
	for rows.Next() {
		var s StockLotDto
		if err := rows.Scan(
			&s.LotNo, &s.SKU, &s.ProductName, &s.WarehouseCode,
			&s.Qty, &s.UomCode, &s.UnitCost, &s.StockValue, &s.Status,
		); err == nil {
			list = append(list, s)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(list)
}
