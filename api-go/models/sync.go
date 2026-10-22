package models

import "time"

// SyncRecord represents an operation submitted by Nuri's mobile Kotlin POS
type SyncRecord struct {
	OutboxID    string    `json:"outbox_id"`
	DeviceID    string    `json:"device_id"`
	Action      string    `json:"action"` // e.g. "SALE_TRANSACTION", "INVENTORY_CONSUMPTION", "WASTE_LOG"
	PayloadJSON string    `json:"payload_json"`
	CreatedAt   time.Time `json:"created_at"`
	Signature   string    `json:"signature"` // HMAC-SHA256 for tampering prevention
}

// SyncBatchRequest holds a collection of offline outbox events
type SyncBatchRequest struct {
	DeviceID   string       `json:"device_id"`
	BatchID    string       `json:"batch_id"`
	Records    []SyncRecord `json:"records"`
	ClientTime time.Time    `json:"client_time"`
}

// SyncBatchResponse returns acknowledgement of synced operations
type SyncBatchResponse struct {
	ProcessedCount int      `json:"processed_count"`
	AcknowledgedIDs []string `json:"acknowledged_ids"`
	Status         string   `json:"status"` // SUCCESS, PARTIAL, ERROR
	ServerTime     time.Time `json:"server_time"`
}
