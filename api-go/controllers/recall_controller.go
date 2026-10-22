package controllers

import (
	"encoding/json"
	"net/http"
	"time"
)

type RecallTriggerRequest struct {
	RecallCode     string   `json:"recall_code"`      // e.g. "RCL-2026-ET001-SALMONELLA"
	TriggerLot     string   `json:"trigger_lot"`      // "ET-2026-001"
	Severity       string   `json:"severity"`         // "CLASS_I_CRITICAL"
	Reason         string   `json:"reason"`
	Initiator      string   `json:"initiator"`
	BroadcastToPOS bool     `json:"broadcast_to_pos"` // Enforces Red Recall Lock Screen on POS
}

type RecallEventDto struct {
	RecallCode     string    `json:"recall_code"`
	Status         string    `json:"status"` // "ACTIVE_LOCKDOWN"
	TriggerLot     string    `json:"trigger_lot"`
	AffectedLots   []string  `json:"affected_lots"`
	TargetMarkets  []string  `json:"target_markets"`
	LockEnforced   bool      `json:"lock_enforced"`
	Timestamp      time.Time `json:"timestamp"`
}

type RecallController struct{}

func NewRecallController() *RecallController {
	return &RecallController{}
}

// TriggerRecall handles POST /recall
func (c *RecallController) TriggerRecall(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}

	var req RecallTriggerRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid request payload"}`, http.StatusBadRequest)
		return
	}

	event := RecallEventDto{
		RecallCode:    req.RecallCode,
		Status:        "ACTIVE_LOCKDOWN",
		TriggerLot:    req.TriggerLot,
		AffectedLots:  []string{"ET-2026-001", "CUT-2026-001-TRM", "URT-2026-045", "SUC-2026-045"},
		TargetMarkets: []string{"Market A (MKT-A)", "Market B (MKT-B)", "Market C (MKT-C)"},
		LockEnforced:  true,
		Timestamp:     time.Now().UTC(),
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":         "LOCKDOWN_ACTIVATED",
		"message":        "Emergency recall initiated. POS red screen lock broadcast dispatched.",
		"recall_summary": event,
	})
}

// GetActiveRecalls handles GET /recall
func (c *RecallController) GetActiveRecalls(w http.ResponseWriter, r *http.Request) {
	active := []RecallEventDto{
		{
			RecallCode:    "RCL-2026-ET001-SALMONELLA",
			Status:        "ACTIVE_LOCKDOWN",
			TriggerLot:    "ET-2026-001",
			AffectedLots:  []string{"ET-2026-001", "URT-2026-045", "SUC-2026-045"},
			TargetMarkets: []string{"Market A", "Market B", "Market C"},
			LockEnforced:  true,
			Timestamp:     time.Now().Add(-2 * time.Hour).UTC(),
		},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(active)
}
