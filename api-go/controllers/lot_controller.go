package controllers

import (
	"encoding/json"
	"net/http"
	"strings"

	"ban-food-intelligence/api-go/models"
)

// LotController handles lot traceability and inventory inquiries
type LotController struct{}

func NewLotController() *LotController {
	return &LotController{}
}

// TraceLot handles GET /api/v1/lots/trace/{lotNumber}
// Invokes Ali's MySQL WITH RECURSIVE TraceLotBackward or returns structured tree
func (c *LotController) TraceLot(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(r.URL.Path, "/")
	if len(parts) < 5 || parts[4] == "" {
		http.Error(w, `{"error":"lot number is required"}`, http.StatusBadRequest)
		return
	}
	lotNumber := parts[4]

	// Simulated recursive lot trace matching ET-2026-001 scenario
	parent1 := "WIP-2026-042"
	parent2 := "RM-2026-010"

	resp := models.TraceResponse{
		RootLotNumber:  lotNumber,
		Direction:      "backward",
		TotalAncestors: 3,
		Nodes: []*models.LotTraceNode{
			{
				Depth:            0,
				CurrentLotNumber: lotNumber,
				ProductName:      "Premium Smoked Beef Biltong 500g",
				ProductCategory:  "FINISHED_GOOD",
				Quantity:         250.0,
				Status:           "RELEASED",
				TracePath:        lotNumber,
			},
			{
				Depth:            1,
				CurrentLotNumber: "WIP-2026-042",
				ProductName:      "Marinated Flank Strips",
				ProductCategory:  "INTERMEDIATE",
				Quantity:         370.0,
				Status:           "CONSUMED",
				ParentLotNumber:  &parent1,
				QuantityConsumed: 370.0,
				TracePath:        lotNumber + " <- WIP-2026-042",
			},
			{
				Depth:            2,
				CurrentLotNumber: "RM-2026-010",
				ProductName:      "Grade A Raw Beef Flank",
				ProductCategory:  "RAW_MATERIAL",
				Quantity:         500.0,
				Status:           "RELEASED",
				ParentLotNumber:  &parent2,
				QuantityConsumed: 350.0,
				TracePath:        lotNumber + " <- WIP-2026-042 <- RM-2026-010",
			},
		},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
