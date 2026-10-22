package models

// YieldPredictionRequest contains batch sensor metrics for ML yield prediction
type YieldPredictionRequest struct {
	BatchID                  string  `json:"batch_id"`
	RecipeCode               string  `json:"recipe_code"`
	InputWeightKg            float64 `json:"input_weight_kg"`
	TemperatureCelsius       float64 `json:"temperature_celsius"`
	HumidityPercentage       float64 `json:"humidity_percentage"`
	PHLevel                  float64 `json:"ph_level"`
	MoisturePercentage       float64 `json:"moisture_percentage"`
	FatContentPercentage     float64 `json:"fat_content_percentage"`
	ProteinContentPercentage float64 `json:"protein_content_percentage"`
	ProcessingDurationMin    int     `json:"processing_duration_min"`
	ModelTarget              string  `json:"model_target"` // "catboost", "xgboost", "stacking_ensemble"
}

// ModelIndividualScore holds individual predictions from the ML ensemble
type ModelIndividualScore struct {
	ModelName      string  `json:"model_name"`
	PredictedYield float64 `json:"predicted_yield_pct"`
	Confidence     float64 `json:"confidence"`
}

// YieldPredictionResponse returns the final yield prediction and risk indicators
type YieldPredictionResponse struct {
	BatchID              string                 `json:"batch_id"`
	EnsembleYieldPct     float64                `json:"ensemble_yield_pct"`
	ExpectedOutputWeight float64                `json:"expected_output_weight_kg"`
	ConfidenceScore      float64                `json:"confidence_score"`
	AnomalyDetected      bool                   `json:"anomaly_detected"`
	IndividualScores     []ModelIndividualScore `json:"individual_scores"`
}
