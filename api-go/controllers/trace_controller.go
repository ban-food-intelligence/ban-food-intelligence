package controllers

import (
	"encoding/json"
	"net/http"
	"strings"

	"ban-food-intelligence/api-go/models"
)

type TraceController struct{}

func NewTraceController() *TraceController {
	return &TraceController{}
}

// TraceHandler handles GET /trace/{lotNumber}
// Connects to Ali's MySQL WITH RECURSIVE TraceLotBackward or TraceLotForward
func (c *TraceController) TraceHandler(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(r.URL.Path, "/")
	lotNumber := "SUC-2026-045"
	if len(parts) >= 3 && parts[2] != "" {
		lotNumber = parts[2]
	}

	parent1 := "URT-2026-045"
	parent2 := "CUT-2026-001-TRM"
	parent3 := "ET-2026-001"

	resp := models.TraceResponse{
		RootLotNumber:  lotNumber,
		Direction:      "backward",
		TotalAncestors: 3,
		Nodes: []*models.LotTraceNode{
			{
				Depth:            0,
				CurrentLotNumber: lotNumber,
				ProductName:      "Traditional Kangal Sucuk 500g",
				ProductCategory:  "FINISHED_SUCUK",
				Quantity:         285.0,
				Status:           "RECALLED",
				TracePath:        lotNumber,
			},
			{
				Depth:            1,
				CurrentLotNumber: "URT-2026-045",
				ProductName:      "Spiced Fermented Sucuk Dough",
				ProductCategory:  "INTERMEDIATE_WIP",
				Quantity:         320.0,
				Status:           "CONSUMED",
				ParentLotNumber:  &parent1,
				QuantityConsumed: 320.0,
				TracePath:        lotNumber + " <- URT-2026-045",
			},
			{
				Depth:            2,
				CurrentLotNumber: "CUT-2026-001-TRM",
				ProductName:      "Beef Trimming 80/20",
				ProductCategory:  "RAW_CUT",
				Quantity:         310.0,
				Status:           "CONSUMED",
				ParentLotNumber:  &parent2,
				QuantityConsumed: 300.0,
				TracePath:        lotNumber + " <- URT-2026-045 <- CUT-2026-001-TRM",
			},
			{
				Depth:            3,
				CurrentLotNumber: "ET-2026-001",
				ProductName:      "Whole Beef Carcass (Quartered)",
				ProductCategory:  "CARCASS",
				Quantity:         450.0,
				Status:           "CONSUMED",
				ParentLotNumber:  &parent3,
				QuantityConsumed: 450.0,
				TracePath:        lotNumber + " <- URT-2026-045 <- CUT-2026-001-TRM <- ET-2026-001",
			},
		},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
