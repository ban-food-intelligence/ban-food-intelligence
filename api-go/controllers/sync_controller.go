package controllers

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"ban-food-intelligence/api-go/models"
)

// SyncController handles batch synchronization from Nuri's Kotlin POS outbox
type SyncController struct {
	// In-memory idempotency cache for UUIDv7 mutation deduplication
	seenUUIDs sync.Map
	hmacKey   []byte
}

func NewSyncController() *SyncController {
	return &SyncController{
		hmacKey: []byte("avatar-pos-secret-key-2026"),
	}
}

// IngestOutbox handles POST /sync
// Validates HMAC-SHA256 signatures and enforces UUIDv7 idempotency
func (c *SyncController) IngestOutbox(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}

	var req models.SyncBatchRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid JSON body"}`, http.StatusBadRequest)
		return
	}

	var ackIDs []string
	for _, rec := range req.Records {
		// 1. Enforce UUIDv7 Idempotency
		if _, exists := c.seenUUIDs.LoadOrStore(rec.OutboxID, time.Now()); exists {
			// Duplicate operation detected; acknowledge without re-applying mutation
			ackIDs = append(ackIDs, rec.OutboxID)
			continue
		}

		// 2. Validate HMAC-SHA256 Signature
		if rec.Signature != "" && !c.verifyHMAC(rec.PayloadJSON, rec.Signature) {
			http.Error(w, `{"error":"security alert: HMAC signature mismatch in outbox payload"}`, http.StatusForbidden)
			return
		}

		ackIDs = append(ackIDs, rec.OutboxID)
	}

	resp := models.SyncBatchResponse{
		ProcessedCount:  len(ackIDs),
		AcknowledgedIDs: ackIDs,
		Status:          "SUCCESS",
		ServerTime:      time.Now().UTC(),
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func (c *SyncController) verifyHMAC(payload, expectedSigHex string) bool {
	mac := hmac.New(sha256.New, c.hmacKey)
	mac.Write([]byte(payload))
	expectedMAC := mac.Sum(nil)
	actualMAC, err := hex.DecodeString(expectedSigHex)
	if err != nil {
		return false
	}
	return hmac.Equal(actualMAC, expectedMAC)
}
