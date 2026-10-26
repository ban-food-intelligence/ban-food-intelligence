package models

import "time"

// OutboxItemDto matches the exact JSON structure serialized by Nuri's Kotlin
// SyncWorker.buildSyncBatchJsonPayload() inside the `outbox_items` array.
type OutboxItemDto struct {
	BatchID        string  `json:"batch_id"`
	PredictedYield float64 `json:"predicted_yield"`
	Timestamp      string  `json:"timestamp"` // Monotonic UUIDv7 primary key
	Signature      string  `json:"signature"` // Base64 ASN.1 DER SHA256withECDSA (P-256)
	SignatureAlg   string  `json:"signature_alg"`
	TenantID       string  `json:"tenant_id"`
	DeviceID       string  `json:"device_id"`
	ActorRole      string  `json:"actor_role"`
	RetryCount     int     `json:"retry_count"`
	PublicKeyB64   string  `json:"public_key_b64,omitempty"`
}

// SyncBatchRequest matches the exact envelope sent by Nuri's Kotlin POS (`outbox_items`).
type SyncBatchRequest struct {
	SyncEnvelopeUUIDv7 string          `json:"sync_envelope_uuid_v7"`
	TenantID           string          `json:"tenant_id"`
	DeviceID           string          `json:"device_id"`
	ActorRole          string          `json:"actor_role"`
	RecordCount        int             `json:"record_count"`
	PublicKeyB64       string          `json:"public_key_b64,omitempty"`
	OutboxItems        []OutboxItemDto `json:"outbox_items"`
}

// SyncBatchResponse returns acknowledgement of verified & persisted UUIDv7 operations.
type SyncBatchResponse struct {
	ProcessedCount    int       `json:"processed_count"`
	AcknowledgedIDs   []string  `json:"acknowledged_ids"`
	Status            string    `json:"status"` // SUCCESS, PARTIAL, ERROR
	SignatureVerified bool      `json:"signature_verified"`
	SignatureAlg      string    `json:"signature_alg"`
	ServerTime        time.Time `json:"server_time"`
}
