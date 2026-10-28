package controllers

import (
	"database/sql"
	"encoding/json"
	"net/http"
)

type YieldController struct {
	db *sql.DB
}

func NewYieldController(db *sql.DB) *YieldController {
	return &YieldController{db: db}
}

// PredictYield handles GET /yield (stored predictions with actuals and deviation).
// Live predictions are produced by ml-engine and served from MySQL via GET /ai/summary; the
// API does not invent a prediction or a confidence value on request.
func (c *YieldController) PredictYield(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "use GET /yield or GET /ai/summary")
		return
	}
	c.ListPredictionsAndAnomalies(w, r)
}

func (c *YieldController) ListPredictionsAndAnomalies(w http.ResponseWriter, r *http.Request) {
	rows, err := c.db.QueryContext(
		r.Context(),
		`SELECT id, task, model_name, model_version, ref_type, ref_id, target_name,
		        predicted_value, COALESCE(actual_value, 0), COALESCE(deviation, 0), COALESCE(deviation_pct, 0)
		 FROM v_ml_prediction_deviation
		 ORDER BY id ASC`,
	)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer rows.Close()

	type PredictionDeviationDto struct {
		ID             int64   `json:"id"`
		Task           string  `json:"task"`
		ModelName      string  `json:"model_name"`
		ModelVersion   string  `json:"model_version"`
		RefType        string  `json:"ref_type"`
		RefID          int     `json:"ref_id"`
		TargetName     string  `json:"target_name"`
		PredictedValue float64 `json:"predicted_value"`
		ActualValue    float64 `json:"actual_value"`
		Deviation      float64 `json:"deviation"`
		DeviationPct   float64 `json:"deviation_pct"`
	}

	var list []PredictionDeviationDto
	for rows.Next() {
		var d PredictionDeviationDto
		if err := rows.Scan(
			&d.ID, &d.Task, &d.ModelName, &d.ModelVersion,
			&d.RefType, &d.RefID, &d.TargetName,
			&d.PredictedValue, &d.ActualValue, &d.Deviation, &d.DeviationPct,
		); err == nil {
			list = append(list, d)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(list)
}
