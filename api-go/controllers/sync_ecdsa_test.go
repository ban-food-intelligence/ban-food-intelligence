package controllers

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"ban-food-intelligence/api-go/models"
)

func TestECDSAP256OutboxItemsVerification(t *testing.T) {
	// 1. Generate a real NIST P-256 (secp256r1) keypair simulating Android Keystore
	privKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate P-256 keypair: %v", err)
	}
	spkiBytes, err := x509.MarshalPKIXPublicKey(&privKey.PublicKey)
	if err != nil {
		t.Fatalf("failed to marshal X.509 SPKI public key: %v", err)
	}
	pubKeyB64 := base64.StdEncoding.EncodeToString(spkiBytes)
	fpHash := sha256.Sum256(spkiBytes)
	fpHex := hex.EncodeToString(fpHash[:])

	// 2. Build canonical record matching KeystoreManager.buildCanonicalYieldPayload()
	tenantID := "ban-food-corp"
	deviceID := "POS-TERMINAL-01"
	actorRole := "ROLE_POS_CASHIER"
	batchID := "SUC-2026-045"
	predictedYield := 0.8500
	uuidV7 := "0192c880-7b1a-7000-8000-000000000001"

	canonicalRecord := fmt.Sprintf(
		"v2|%s|%s|%s|%s|%.4f|%s",
		tenantID, deviceID, actorRole, batchID, predictedYield, uuidV7,
	)
	recDigest := sha256.Sum256([]byte(canonicalRecord))
	recSigDER, err := ecdsa.SignASN1(rand.Reader, privKey, recDigest[:])
	if err != nil {
		t.Fatalf("failed to sign record: %v", err)
	}
	recSigB64 := base64.StdEncoding.EncodeToString(recSigDER)

	// 3. Build SyncBatchRequest with `outbox_items`
	envelopeUUID := "0192c880-7b1a-7000-8000-999999999999"
	batchReq := models.SyncBatchRequest{
		SyncEnvelopeUUIDv7: envelopeUUID,
		TenantID:           tenantID,
		DeviceID:           deviceID,
		ActorRole:          actorRole,
		RecordCount:        1,
		OutboxItems: []models.OutboxItemDto{
			{
				BatchID:        batchID,
				PredictedYield: predictedYield,
				Timestamp:      uuidV7,
				Signature:      recSigB64,
				SignatureAlg:   "ECDSA-P256-SHA256",
				TenantID:       tenantID,
				DeviceID:       deviceID,
				ActorRole:      actorRole,
				RetryCount:     0,
			},
		},
	}

	rawBody, err := json.Marshal(batchReq)
	if err != nil {
		t.Fatalf("failed to marshal batch request: %v", err)
	}

	// 4. Sign the outer envelope matching SyncWorker.flushBatch()
	envelopeCanonical := fmt.Sprintf("%s:%s:%s:%s:%s", tenantID, deviceID, actorRole, envelopeUUID, string(rawBody))
	envDigest := sha256.Sum256([]byte(envelopeCanonical))
	envSigDER, err := ecdsa.SignASN1(rand.Reader, privKey, envDigest[:])
	if err != nil {
		t.Fatalf("failed to sign envelope: %v", err)
	}
	envSigB64 := base64.StdEncoding.EncodeToString(envSigDER)

	// 5. Execute POST /sync against SyncController
	ctrl := NewSyncController(nil)
	req := httptest.NewRequest(http.MethodPost, "/sync", bytes.NewReader(rawBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-POS-Public-Key", pubKeyB64)
	req.Header.Set("X-Key-Fingerprint", fpHex)
	req.Header.Set("X-Signature", envSigB64)
	req.Header.Set("X-Signature-Alg", "ECDSA-P256-SHA256")
	req.Header.Set("X-Idempotency-UUIDv7", envelopeUUID)

	rec := httptest.NewRecorder()
	ctrl.IngestOutbox(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200 OK, got %d: %s", rec.Code, rec.Body.String())
	}

	var resp models.SyncBatchResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if !resp.SignatureVerified || resp.ProcessedCount != 1 || resp.AcknowledgedIDs[0] != uuidV7 {
		t.Fatalf("unexpected sync response: %+v", resp)
	}

	// 6. Verify tampered payload is rejected with HTTP 403 Forbidden
	batchReq.OutboxItems[0].PredictedYield = 0.9900
	tamperedBody, _ := json.Marshal(batchReq)
	tamperedReq := httptest.NewRequest(http.MethodPost, "/sync", bytes.NewReader(tamperedBody))
	tamperedReq.Header.Set("X-POS-Public-Key", pubKeyB64)
	tamperedRec := httptest.NewRecorder()
	ctrl.IngestOutbox(tamperedRec, tamperedReq)

	if tamperedRec.Code != http.StatusForbidden {
		t.Fatalf("expected HTTP 403 Forbidden for tampered ECDSA payload, got %d", tamperedRec.Code)
	}
}
