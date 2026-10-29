package controllers

// Live butchery forecast (spec 4B): the expected weight of each part of a carcass from its species,
// breed, weight and history, evaluated in Go from the exported ml-engine champion (one Ridge per
// share, clipped at 0.1 % and rescaled to 100 %, so parts + waste = input weight). The dashboard
// shows it while a butchery is entered; a posted operation stores forecast and recorded weights.

import (
	"context"
	"database/sql"
	_ "embed"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"
)

//go:embed aimodel/butchery_ridge.json
var butcheryModelJSON []byte

type butcheryModel struct {
	Model     string             `json:"model"`
	Version   string             `json:"version"`
	Targets   []string           `json:"targets"` // but_pct ... fire_pct
	RefWeight map[string]float64 `json:"species_ref_weight_kg"`
	Floor     float64            `json:"share_floor_pct"`
	Parts     []struct {
		Target string `json:"target"`
		linearModel
	} `json:"parts"`
	HalfWidth map[string][]float64 `json:"interval_half_width_pct"` // share points per part, "80" and "98"
	HistMean  map[string][]float64 `json:"baseline_hist_mean_pct"`  // share per part, by species
}

var butcheryForecastModel = mustLoadButchery(butcheryModelJSON)

func mustLoadButchery(b []byte) *butcheryModel {
	var m butcheryModel
	if err := json.Unmarshal(b, &m); err != nil || len(m.Parts) != len(m.Targets) || len(m.Parts) == 0 {
		panic(fmt.Sprintf("aimodel/butchery_ridge.json: %v", err))
	}
	return &m
}

// cutSKU maps a forecast part to the product recorded for it, per species (the demo has lamb).
var cutSKU = map[string]map[string]string{
	"KUZU": {"but": "KZ-BUT", "sirt": "KZ-SIRT", "omuz": "KZ-OMUZ", "kaburga": "KZ-KABURGA", "kiyma": "KZ-KIYMA", "fire": "KZ-FIRE"},
}

// ButcheryInputs are the carcass facts the model reads; nil numbers take the training median.
type ButcheryInputs struct {
	OpDate            time.Time
	Species           string
	Breed             string
	SupplierCode      string
	OperatorCode      string
	Sex               string
	CarcassWeightKg   float64
	AgeMonths         *float64
	FatScore          *float64
	ConformationScore *float64
	ChillHours        *float64
}

// predictShares returns the six shares (%), summing to 100.
func (m *butcheryModel) predictShares(in ButcheryInputs) []float64 {
	msin, mcos := monthFeatures(in.OpDate)
	vsSpecies := 0.0
	if ref, ok := m.RefWeight[in.Species]; ok {
		vsSpecies = in.CarcassWeightKg - ref
	}
	warm := 0.0 // unknown chill hours count as not warm-cut, as in pandas (NaN < 24 is false)
	if in.ChillHours != nil && *in.ChillHours < 24 {
		warm = 1
	}
	cats := map[string]string{"species": in.Species, "breed": in.Breed, "supplier_code": in.SupplierCode,
		"operator_code": in.OperatorCode, "sex": in.Sex}
	nums := map[string]*float64{"carcass_weight_kg": ptr(in.CarcassWeightKg), "age_months": in.AgeMonths,
		"fat_score": in.FatScore, "conformation_score": in.ConformationScore, "chill_hours": in.ChillHours,
		"month_sin": ptr(msin), "month_cos": ptr(mcos), "weight_vs_species": ptr(vsSpecies), "warm_cut": ptr(warm)}
	shares := make([]float64, len(m.Parts))
	sum := 0.0
	for j, p := range m.Parts {
		shares[j] = math.Max(p.eval(cats, nums), m.Floor)
		sum += shares[j]
	}
	for j := range shares {
		shares[j] = 100 * shares[j] / sum
	}
	return shares
}

type ButcheryPartForecastDto struct {
	Part       string   `json:"part"` // but, sirt, omuz, kaburga, kiyma, fire
	SKU        string   `json:"sku,omitempty"`
	SharePct   float64  `json:"share_pct"`
	Kg         float64  `json:"kg"`
	KgLow      float64  `json:"kg_low"`
	KgHigh     float64  `json:"kg_high"`
	BaselineKg *float64 `json:"baseline_kg,omitempty"`
	ActualKg   *float64 `json:"actual_kg,omitempty"`
	Anomaly    *bool    `json:"anomaly,omitempty"` // actual outside the 98 % band
	band98     float64
}

type ButcheryForecastDto struct {
	Model         string                    `json:"model"`
	ModelVersion  string                    `json:"model_version"`
	Species       string                    `json:"species"`
	WeightKg      float64                   `json:"weight_kg"`
	IntervalLevel float64                   `json:"interval_level"`
	Parts         []ButcheryPartForecastDto `json:"parts"`
}

func forecastButchery(in ButcheryInputs, m *butcheryModel) *ButcheryForecastDto {
	if in.CarcassWeightKg <= 0 {
		return nil
	}
	w := in.CarcassWeightKg
	shares := m.predictShares(in)
	fc := &ButcheryForecastDto{Model: m.Model, ModelVersion: m.Version, Species: in.Species, WeightKg: w, IntervalLevel: 0.8}
	for j, t := range m.Targets {
		part := strings.TrimSuffix(t, "_pct")
		kg, half := shares[j]*w/100, m.HalfWidth["80"][j]*w/100
		p := ButcheryPartForecastDto{Part: part, SKU: cutSKU[in.Species][part], SharePct: round(shares[j], 3),
			Kg: round(kg, 3), KgLow: round(kg-half, 3), KgHigh: round(kg+half, 3), band98: m.HalfWidth["98"][j] * w / 100}
		if base, ok := m.HistMean[in.Species]; ok {
			p.BaselineKg = ptr(round(base[j]*w/100, 3))
		}
		fc.Parts = append(fc.Parts, p)
	}
	return fc
}

// applyButcheryActuals sets the recorded weight of each part (cuts by product, waste by kind; a
// by-product is not one of the six parts) and flags parts outside the 98 % band.
func applyButcheryActuals(fc *ButcheryForecastDto, outs []ButcheryOutputRequest) {
	if fc == nil {
		return
	}
	for i := range fc.Parts {
		p := &fc.Parts[i]
		actual, known := 0.0, p.Part == "fire" || p.SKU != ""
		for _, o := range outs {
			if (p.Part == "fire" && o.Kind == "WASTE") || (p.Part != "fire" && p.SKU != "" && o.SKU == p.SKU) {
				actual += o.WeightKg
			}
		}
		if !known {
			continue
		}
		a := round(actual, 3)
		out := math.Abs(a-p.Kg) > p.band98
		p.ActualKg, p.Anomaly = &a, &out
	}
}

func storeButcheryForecast(ctx context.Context, q sqlRunner, opID int64, fc *ButcheryForecastDto) error {
	const ins = `INSERT INTO ml_prediction (task, model_name, model_version, ref_type, ref_id, target_name,
	             predicted_value, actual_value, interval_low, interval_high, interval_level, is_anomaly)
	             VALUES ('BUTCHERY_YIELD', ?, ?, 'butchery_operation', ?, ?, ?, ?, ?, ?, ?, ?)`
	for _, p := range fc.Parts {
		target := p.Part + "_kg"
		if _, err := q.ExecContext(ctx, ins, championModel, fc.ModelVersion, opID, target, p.Kg, p.ActualKg,
			p.KgLow, p.KgHigh, fc.IntervalLevel, p.Anomaly); err != nil {
			return err
		}
		if p.BaselineKg != nil {
			if _, err := q.ExecContext(ctx, ins, baselineModel, "baseline-1", opID, target, *p.BaselineKg, p.ActualKg,
				nil, nil, nil, nil); err != nil {
				return err
			}
		}
	}
	return nil
}

// loadCarcass reads the carcass facts of a lot; productType tells whether it is a carcass at all.
func loadCarcass(ctx context.Context, q sqlRunner, lotNo string) (in ButcheryInputs, productType string, err error) {
	var age, fat, conf sql.NullFloat64
	err = q.QueryRowContext(ctx,
		`SELECT p.product_type, COALESCE(p.species, ''), COALESCE(l.animal_breed, ''), COALESCE(s.code, ''),
		        l.animal_age_months, COALESCE(l.animal_sex, ''), l.fat_score, l.conformation_score
		 FROM lot l JOIN product p ON p.id = l.product_id LEFT JOIN supplier s ON s.id = l.supplier_id
		 WHERE l.lot_no = ?`, lotNo).
		Scan(&productType, &in.Species, &in.Breed, &in.SupplierCode, &age, &in.Sex, &fat, &conf)
	opt := func(v sql.NullFloat64) *float64 {
		if !v.Valid {
			return nil
		}
		return ptr(v.Float64)
	}
	in.AgeMonths, in.FatScore, in.ConformationScore = opt(age), opt(fat), opt(conf)
	return in, productType, err
}

// ButcheryForecast handles GET /ai/forecast/butchery?input_lot_no=&input_weight_kg=[&op_date=&operator_name=]
func (c *AIController) ButcheryForecast(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", nil)
		return
	}
	q := r.URL.Query()
	weight, err := strconv.ParseFloat(strings.Replace(q.Get("input_weight_kg"), ",", ".", 1), 64)
	fe := fieldErrors{}
	if q.Get("input_lot_no") == "" {
		fe["input_lot_no"] = "required"
	}
	if err != nil || math.IsNaN(weight) || math.IsInf(weight, 0) || weight <= 0 || weight > 500 {
		fe["input_weight_kg"] = "0 < kg <= 500"
	}
	date := nonEmpty(q.Get("op_date"), time.Now().Format("2006-01-02"))
	if !validDate(date) {
		fe["op_date"] = "YYYY-MM-DD"
	}
	if len(fe) > 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fe)
		return
	}
	in, ptype, err := loadCarcass(r.Context(), c.db, q.Get("input_lot_no"))
	if err == sql.ErrNoRows {
		writeAPIError(w, r, http.StatusNotFound, "LOT_NOT_FOUND", "lot "+q.Get("input_lot_no"))
		return
	}
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	if ptype != "CARCASS" {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fieldErrors{"input_lot_no": "must be a carcass lot"})
		return
	}
	in.CarcassWeightKg, in.OperatorCode = weight, q.Get("operator_name")
	in.OpDate, _ = time.Parse("2006-01-02", date)
	writeJSON(w, forecastButchery(in, butcheryForecastModel))
}
