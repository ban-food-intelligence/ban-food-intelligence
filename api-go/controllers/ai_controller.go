package controllers

import (
	"database/sql"
	"math"
	"net/http"
	"sort"
)

// AIController serves the mandatory AI studies (spec section 4): prediction vs actual vs
// deviation for production (A) and butchery (B), next to a simple baseline, plus the
// held-out error metrics. Predictions come from ml-engine (synthetic training data);
// traceability and recall never use them.
type AIController struct {
	db *sql.DB
}

func NewAIController(db *sql.DB) *AIController {
	return &AIController{db: db}
}

const championModel = "ali_ridge"
const baselineModel = "baseline_hist_mean"

type AIMetricDto struct {
	Task      string  `json:"task"`
	Target    string  `json:"target"`
	Unit      string  `json:"unit"`
	Model     string  `json:"model"`
	Role      string  `json:"role"` // MODEL | BASELINE
	EvalSplit string  `json:"eval_split"`
	NRows     int     `json:"n_rows"`
	MAE       float64 `json:"mae"`
	MAPEPct   float64 `json:"mape_pct"`
}

type AIComparisonDto struct {
	Task              string   `json:"task"`   // PRODUCTION | BUTCHERY
	RefNo             string   `json:"ref_no"` // URT-2026-045 / PRC-2026-001
	Target            string   `json:"target"` // actual_qty, ekp, but_kg ...
	Actual            *float64 `json:"actual"`
	ModelPred         *float64 `json:"model_pred"`
	ModelDev          *float64 `json:"model_dev"`
	ModelDevPct       *float64 `json:"model_dev_pct"`
	BaselinePred      *float64 `json:"baseline_pred"`
	BaselineDev       *float64 `json:"baseline_dev"`
	BaselineDevPct    *float64 `json:"baseline_dev_pct"`
	ModelCloserThanBL *bool    `json:"model_closer_than_baseline"`
}

type AISummaryDto struct {
	Model       string            `json:"model"`
	Baseline    string            `json:"baseline"`
	DataNote    string            `json:"data_note"`
	Metrics     []AIMetricDto     `json:"metrics"`
	Comparisons []AIComparisonDto `json:"comparisons"`
}

// Summary handles GET /ai/summary.
func (c *AIController) Summary(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", nil)
		return
	}
	resp := AISummaryDto{
		Model:    championModel,
		Baseline: baselineModel,
		DataNote: "Models are trained on synthetic, anonymised history (ml-engine/generator_ali.py). Metrics are measured on the latest 20% of dates (time split). Small and synthetic data: indicative only.",
		Metrics:  []AIMetricDto{}, Comparisons: []AIComparisonDto{},
	}

	rows, err := c.db.QueryContext(r.Context(),
		`SELECT task, target_name, unit, model_name, role, eval_split, n_rows, mae, mape_pct
		 FROM ml_model_metric ORDER BY task, target_name, role DESC, mae`)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	for rows.Next() {
		var m AIMetricDto
		if err := rows.Scan(&m.Task, &m.Target, &m.Unit, &m.Model, &m.Role, &m.EvalSplit, &m.NRows, &m.MAE, &m.MAPEPct); err == nil {
			resp.Metrics = append(resp.Metrics, m)
		}
	}
	rows.Close()

	prow, err := c.db.QueryContext(r.Context(),
		`SELECT IF(mp.ref_type = 'production_order', 'PRODUCTION', 'BUTCHERY'),
		        COALESCE(po.order_no, bo.op_no), mp.target_name, mp.model_name, mp.predicted_value, mp.actual_value
		 FROM ml_prediction mp
		 LEFT JOIN production_order po   ON mp.ref_type = 'production_order'   AND po.id = mp.ref_id
		 LEFT JOIN butchery_operation bo ON mp.ref_type = 'butchery_operation' AND bo.id = mp.ref_id
		 WHERE mp.model_name IN (?, ?)`, championModel, baselineModel)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer prow.Close()

	byKey := map[string]*AIComparisonDto{}
	for prow.Next() {
		var task, ref, target, model string
		var pred float64
		var actual sql.NullFloat64
		if err := prow.Scan(&task, &ref, &target, &model, &pred, &actual); err != nil {
			continue
		}
		key := task + "|" + ref + "|" + target
		cmp, ok := byKey[key]
		if !ok {
			cmp = &AIComparisonDto{Task: task, RefNo: ref, Target: target}
			byKey[key] = cmp
		}
		if actual.Valid {
			a := actual.Float64
			cmp.Actual = &a
		}
		p := pred
		if model == championModel {
			cmp.ModelPred = &p
		} else {
			cmp.BaselinePred = &p
		}
	}
	for _, cmp := range byKey {
		if cmp.Actual != nil {
			cmp.ModelDev, cmp.ModelDevPct = deviation(cmp.ModelPred, *cmp.Actual)
			cmp.BaselineDev, cmp.BaselineDevPct = deviation(cmp.BaselinePred, *cmp.Actual)
			if cmp.ModelDev != nil && cmp.BaselineDev != nil {
				closer := math.Abs(*cmp.ModelDev) <= math.Abs(*cmp.BaselineDev)
				cmp.ModelCloserThanBL = &closer
			}
		}
		resp.Comparisons = append(resp.Comparisons, *cmp)
	}
	order := map[string]int{"actual_qty": 0, "ekp": 1, "but_kg": 2, "sirt_kg": 3, "omuz_kg": 4, "kaburga_kg": 5, "kiyma_kg": 6, "fire_kg": 7}
	sort.Slice(resp.Comparisons, func(i, j int) bool {
		a, b := resp.Comparisons[i], resp.Comparisons[j]
		if a.Task != b.Task {
			return a.Task > b.Task // PRODUCTION first
		}
		if a.RefNo != b.RefNo {
			return a.RefNo < b.RefNo
		}
		return order[a.Target] < order[b.Target]
	})
	writeJSON(w, resp)
}

// deviation returns actual - predicted (abs) and its share of the actual (%).
func deviation(pred *float64, actual float64) (*float64, *float64) {
	if pred == nil {
		return nil, nil
	}
	d := math.Round((actual-*pred)*10000) / 10000
	if actual == 0 {
		return &d, nil
	}
	pct := math.Round(10000*(actual-*pred)/actual) / 100
	return &d, &pct
}
