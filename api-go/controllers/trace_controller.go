package controllers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"ban-food-intelligence/api-go/models"
)

type TraceController struct {
	db *sql.DB
}

func NewTraceController(db *sql.DB) *TraceController {
	return &TraceController{db: db}
}

// TraceHandler handles GET /trace/{lotNumber}?direction=forward|backward
// Calls Ali's MySQL `sp_trace_forward` or `sp_trace_backward` (`WITH RECURSIVE`).
func (c *TraceController) TraceHandler(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	lotNumber := "ET-2026-001"
	if len(parts) >= 2 && parts[len(parts)-1] != "trace" && parts[len(parts)-1] != "" {
		lotNumber = parts[len(parts)-1]
	}

	direction := strings.ToLower(r.URL.Query().Get("direction"))
	if direction == "" {
		if strings.HasPrefix(lotNumber, "ET-") {
			direction = "forward"
		} else {
			direction = "backward"
		}
	}

	var procCall string
	if direction == "forward" {
		procCall = `CALL sp_trace_forward(?)`
	} else {
		direction = "backward"
		procCall = `CALL sp_trace_backward(?)`
	}

	rows, err := c.db.QueryContext(r.Context(), procCall, lotNumber)
	if err != nil {
		http.Error(w, fmt.Sprintf(`{"error":"trace stored procedure failed for %s: %s"}`, lotNumber, err.Error()), http.StatusNotFound)
		return
	}
	defer rows.Close()

	var nodes []*models.LotTraceNode
	if direction == "forward" {
		for rows.Next() {
			var depth int
			var lNo, sku, prodName, origin, uom, status, path string
			var initQty float64
			var exp sql.NullTime
			if err := rows.Scan(&depth, &lNo, &sku, &prodName, &origin, &initQty, &uom, &status, &exp, &path); err == nil {
				nodes = append(nodes, &models.LotTraceNode{
					Depth:            depth,
					CurrentLotNumber: lNo,
					ProductName:      prodName,
					ProductCategory:  origin,
					Quantity:         initQty,
					Status:           status,
					TracePath:        path,
				})
			}
		}
	} else {
		for rows.Next() {
			var depth int
			var lNo, sku, prodName, origin, uom, path string
			var lotDate, exp sql.NullTime
			var initQty, unitCost float64
			var supCode, supName, supLot, supContact, supPhone sql.NullString
			if err := rows.Scan(
				&depth, &lNo, &sku, &prodName, &origin,
				&lotDate, &exp, &initQty, &uom, &unitCost,
				&supCode, &supName, &supLot, &supContact, &supPhone, &path,
			); err == nil {
				nodes = append(nodes, &models.LotTraceNode{
					Depth:            depth,
					CurrentLotNumber: lNo,
					ProductName:      prodName,
					ProductCategory:  origin,
					Quantity:         initQty,
					Status:           supName.String,
					TracePath:        path,
				})
			}
		}
	}

	resp := models.TraceResponse{
		RootLotNumber:  lotNumber,
		Direction:      direction,
		Nodes:          nodes,
		TotalAncestors: len(nodes),
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
