package controllers

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"ban-food-intelligence/api-go/models"
)

// --- operator sessions -------------------------------------------------------------

func TestSessionTokenRoundTripExpiryAndTamper(t *testing.T) {
	now := time.Date(2026, 10, 28, 9, 0, 0, 0, time.UTC)
	m := NewSessionManager()
	m.now = func() time.Time { return now }

	token, exp := m.Issue("kalite_muduru")
	if !exp.Equal(now.Add(OperatorSessionTTL)) {
		t.Fatalf("expiry %v", exp)
	}
	if u, ok := m.Verify(token); !ok || u != "kalite_muduru" {
		t.Fatalf("valid token rejected: %q %v", u, ok)
	}
	// Change the username part: the HMAC no longer matches.
	payload, sig, _ := strings.Cut(token, ".")
	forged := base64.RawURLEncoding.EncodeToString([]byte("admin|9999999999")) + "." + sig
	if _, ok := m.Verify(forged); ok {
		t.Fatal("forged payload accepted")
	}
	if _, ok := m.Verify(payload + ".AAAA"); ok {
		t.Fatal("bad signature accepted")
	}
	if _, ok := NewSessionManager().Verify(token); ok {
		t.Fatal("token from another secret accepted")
	}
	now = now.Add(OperatorSessionTTL)
	if _, ok := m.Verify(token); ok {
		t.Fatal("expired token accepted")
	}
}

func TestRequireOperatorProtectsWritesOnly(t *testing.T) {
	m := NewSessionManager()
	var seen string
	h := m.RequireOperator(func(w http.ResponseWriter, r *http.Request) {
		seen = OperatorFrom(r.Context())
		w.WriteHeader(http.StatusNoContent)
	})

	rec := httptest.NewRecorder()
	h(rec, httptest.NewRequest(http.MethodGet, "/recall", nil))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("GET must stay open, got %d", rec.Code)
	}

	for _, auth := range []string{"", "Bearer nope", "Basic abc"} {
		rec = httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/recall", strings.NewReader("{}"))
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		h(rec, req)
		if rec.Code != http.StatusUnauthorized || !strings.Contains(rec.Body.String(), "AUTH_REQUIRED") {
			t.Fatalf("POST with %q: %d %s", auth, rec.Code, rec.Body.String())
		}
	}

	token, _ := m.Issue("kalite_muduru")
	rec = httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/recall", strings.NewReader("{}"))
	req.Header.Set("Authorization", "Bearer "+token)
	h(rec, req)
	if rec.Code != http.StatusNoContent || seen != "kalite_muduru" {
		t.Fatalf("valid token: %d, operator %q", rec.Code, seen)
	}
}

// Rotating device_id must not reset the PIN lockout: the client IP has its own counter.
func TestPINLockoutCannotBeBypassedByRotatingDeviceID(t *testing.T) {
	c := NewSupervisorAuthController(nil)
	for i := 0; i < supervisorMaxFailuresPerIP; i++ {
		c.recordFailureMax("ip:192.0.2.7", supervisorMaxFailuresPerIP)
	}
	req := httptest.NewRequest(http.MethodPost, "/auth/supervisor/verify",
		strings.NewReader(`{"pin":"123456","device_id":"brand-new-device-42"}`))
	req.RemoteAddr = "192.0.2.7:51000"
	rec := httptest.NewRecorder()
	c.Verify(rec, req) // db is nil: a locked request must be answered before any DB access
	if rec.Code != http.StatusTooManyRequests || rec.Header().Get("Retry-After") == "" {
		t.Fatalf("expected 429 with Retry-After, got %d %s", rec.Code, rec.Body.String())
	}

	// The same lockout applies to operator sessions.
	rec = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodPost, "/auth/session", strings.NewReader(`{"pin":"123456"}`))
	req.RemoteAddr = "192.0.2.7:51001"
	c.WithSessions(NewSessionManager()).Session(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("session: expected 429, got %d", rec.Code)
	}
}

// --- POS sync: pinned keys, mandatory signatures -------------------------------------

type testPOS struct {
	key    *ecdsa.PrivateKey
	pubB64 string
	device string
}

func newTestPOS(t *testing.T, device string) *testPOS {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	spki, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
	return &testPOS{key: k, pubB64: base64.StdEncoding.EncodeToString(spki), device: device}
}

func (p *testPOS) sign(t *testing.T, s string) string {
	t.Helper()
	d := sha256.Sum256([]byte(s))
	sig, err := ecdsa.SignASN1(rand.Reader, p.key, d[:])
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(sig)
}

// request builds a signed POST /sync exactly like SyncWorker.flushBatch for one sale.
func (p *testPOS) request(t *testing.T, lot string, qty float64, uuid string, sendKey, signEnvelope bool) *http.Request {
	t.Helper()
	const tenant, role, envUUID = "ban-food-corp", "ROLE_POS_CASHIER", "0192c880-7b1a-7000-8000-00000000e001"
	item := models.OutboxItemDto{
		BatchID: lot, PredictedYield: qty, Timestamp: uuid, SignatureAlg: "ECDSA-P256-SHA256",
		TenantID: tenant, DeviceID: p.device, ActorRole: role,
	}
	item.Signature = p.sign(t, fmt.Sprintf("v2|%s|%s|%s|%s|%.4f|%s", tenant, p.device, role, lot, qty, uuid))
	body, _ := json.Marshal(models.SyncBatchRequest{SyncEnvelopeUUIDv7: envUUID, TenantID: tenant, DeviceID: p.device,
		ActorRole: role, RecordCount: 1, OutboxItems: []models.OutboxItemDto{item}})
	req := httptest.NewRequest(http.MethodPost, "/sync", bytes.NewReader(body))
	if sendKey {
		req.Header.Set("X-POS-Public-Key", p.pubB64)
	}
	if signEnvelope {
		req.Header.Set("X-Signature", p.sign(t, fmt.Sprintf("%s:%s:%s:%s:%s", tenant, p.device, role, envUUID, body)))
	}
	req.Header.Set("X-Idempotency-UUIDv7", envUUID)
	return req
}

func syncCode(t *testing.T, ctrl *SyncController, req *http.Request) (int, string) {
	t.Helper()
	rec := httptest.NewRecorder()
	ctrl.IngestOutbox(rec, req)
	var e APIError
	_ = json.Unmarshal(rec.Body.Bytes(), &e)
	return rec.Code, e.Code
}

func TestSyncRejectsUnknownDeviceWithoutKey(t *testing.T) {
	pos := newTestPOS(t, "POS-UNKNOWN")
	code, errCode := syncCode(t, NewSyncController(nil), pos.request(t, "KZB-2026-001", 0.5, "0192c880-7b1a-7000-8000-0000000000a1", false, true))
	if code != http.StatusForbidden || errCode != "DEVICE_NOT_ENROLLED" {
		t.Fatalf("got %d %s", code, errCode)
	}
}

func TestSyncRequiresEnvelopeSignature(t *testing.T) {
	pos := newTestPOS(t, "POS-NOSIG")
	code, errCode := syncCode(t, NewSyncController(nil), pos.request(t, "KZB-2026-001", 0.5, "0192c880-7b1a-7000-8000-0000000000a2", true, false))
	if code != http.StatusForbidden || errCode != "SIGNATURE_REJECTED" {
		t.Fatalf("got %d %s", code, errCode)
	}
}

func TestSyncPinsFirstKeyAndRefusesAnotherKey(t *testing.T) {
	ctrl := NewSyncController(nil)
	owner := newTestPOS(t, "POS-PINNED")
	if code, e := syncCode(t, ctrl, owner.request(t, "KZB-2026-001", 0.5, "0192c880-7b1a-7000-8000-0000000000b1", true, true)); code != http.StatusOK {
		t.Fatalf("first sync: %d %s", code, e)
	}
	// Later syncs may omit the key: the pinned one is used.
	if code, e := syncCode(t, ctrl, owner.request(t, "KZB-2026-001", 0.5, "0192c880-7b1a-7000-8000-0000000000b2", false, true)); code != http.StatusOK {
		t.Fatalf("second sync with pinned key: %d %s", code, e)
	}
	// An attacker who knows the device id but has its own key is refused.
	attacker := newTestPOS(t, "POS-PINNED")
	if code, e := syncCode(t, ctrl, attacker.request(t, "KZB-2026-001", 9, "0192c880-7b1a-7000-8000-0000000000b3", true, true)); code != http.StatusForbidden || e != "KEY_MISMATCH" {
		t.Fatalf("attacker key: %d %s", code, e)
	}
	// ...and so is a body signed with its key while omitting the header.
	if code, e := syncCode(t, ctrl, attacker.request(t, "KZB-2026-001", 9, "0192c880-7b1a-7000-8000-0000000000b4", false, true)); code != http.StatusForbidden || e != "SIGNATURE_REJECTED" {
		t.Fatalf("attacker signature: %d %s", code, e)
	}
}

func TestSyncClosedEnrollmentNeedsRegisteredKey(t *testing.T) {
	t.Setenv("POS_ENROLLMENT", "closed")
	pos := newTestPOS(t, "POS-CLOSED")
	ctrl := NewSyncController(nil)
	if code, e := syncCode(t, ctrl, pos.request(t, "KZB-2026-001", 0.5, "0192c880-7b1a-7000-8000-0000000000c1", true, true)); code != http.StatusForbidden || e != "DEVICE_NOT_ENROLLED" {
		t.Fatalf("closed enrollment: %d %s", code, e)
	}
	body, _ := json.Marshal(map[string]string{"device_id": pos.device, "public_key_b64": pos.pubB64})
	rec := httptest.NewRecorder()
	ctrl.RegisterDeviceKey(rec, httptest.NewRequest(http.MethodPost, "/sync/register-key", bytes.NewReader(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("register: %d %s", rec.Code, rec.Body.String())
	}
	if code, e := syncCode(t, ctrl, pos.request(t, "KZB-2026-001", 0.5, "0192c880-7b1a-7000-8000-0000000000c2", false, true)); code != http.StatusOK {
		t.Fatalf("after registration: %d %s", code, e)
	}
}

func TestSyncRejectsTooManyItems(t *testing.T) {
	items := make([]models.OutboxItemDto, maxOutboxItems+1)
	body, _ := json.Marshal(models.SyncBatchRequest{DeviceID: "POS-1", OutboxItems: items})
	rec := httptest.NewRecorder()
	NewSyncController(nil).IngestOutbox(rec, httptest.NewRequest(http.MethodPost, "/sync", bytes.NewReader(body)))
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("got %d", rec.Code)
	}
}

// --- raw-material entry and butchery validation -----------------------------------------

func TestValidatePurchase(t *testing.T) {
	ok := PurchaseRequest{ReceiptNo: "ALS-1", SupplierCode: "TED-ET-01", ReceiptDate: "2026-10-28",
		Lines: []PurchaseLineRequest{{SKU: "HM-ET-DANA", LotNo: "ET-2026-009", Qty: 50, UnitPrice: 9.7, ExpiryDate: "2026-11-07"}}}
	if fe := validatePurchase(&ok); len(fe) != 0 {
		t.Fatalf("valid request rejected: %v", fe)
	}
	bad := PurchaseRequest{ReceiptNo: "", ReceiptDate: "28.10.2026",
		Lines: []PurchaseLineRequest{{SKU: "HM-ET-DANA", LotNo: "X", Qty: 0, UnitPrice: -1, ExpiryDate: "2026-10-01"},
			{SKU: "HM-ET-DANA", LotNo: "X", Qty: 1}}}
	fe := validatePurchase(&bad)
	for _, k := range []string{"receipt_no", "supplier_code", "receipt_date", "lines[0].qty", "lines[0].unit_price", "lines[1].lot_no"} {
		if _, found := fe[k]; !found {
			t.Errorf("missing error for %s: %v", k, fe)
		}
	}
	bad2 := ok
	bad2.Lines = []PurchaseLineRequest{{SKU: "HM-ET-DANA", LotNo: "ET-2026-009", Qty: 1, UnitPrice: 1, ExpiryDate: "2026-10-01"}}
	if _, found := validatePurchase(&bad2)["lines[0].expiry_date"]; !found {
		t.Error("expiry before receipt date accepted")
	}
}

// Both allocation keys distribute the total to the cent, waste carries no cost and a
// by-product takes its share like a cut.
func TestAllocateButcheryBothKeysWithByProduct(t *testing.T) {
	outs := []ButcheryOutputRequest{
		{SKU: "KZ-BUT", Kind: "CUT", WeightKg: 6.4}, {SKU: "KZ-SIRT", Kind: "CUT", WeightKg: 3.3},
		{SKU: "KZ-OMUZ", Kind: "CUT", WeightKg: 5.1}, {SKU: "KZ-KABURGA", Kind: "CUT", WeightKg: 2.9},
		{SKU: "KZ-KIYMA", Kind: "CUT", WeightKg: 4.2}, {SKU: "KZ-SAKATAT", Kind: "BY_PRODUCT", WeightKg: 0.9},
		{SKU: "KZ-FIRE", Kind: "WASTE", WeightKg: 2.2},
	}
	prices := []float64{22, 26, 18, 14, 16, 8, 0}
	lots := make([]string, len(outs))
	const total = 287.53 // 25.0 kg x 11.50 + 0.03 extra, an awkward amount on purpose
	for _, method := range []string{"QUANTITY", "VALUE"} {
		res := allocateButchery(method, total, outs, prices, lots)
		if !res.SumsExact || res.Allocated != total {
			t.Fatalf("%s: allocated %.2f, exact %v", method, res.Allocated, res.SumsExact)
		}
		if res.Lines[6].Cost != 0 {
			t.Errorf("%s: waste got %.2f", method, res.Lines[6].Cost)
		}
		if res.Lines[5].Cost <= 0 {
			t.Errorf("%s: by-product got no cost", method)
		}
	}
	q := allocateButchery("QUANTITY", total, outs, prices, lots)
	v := allocateButchery("VALUE", total, outs, prices, lots)
	// QUANTITY: same cost per kg for every part; VALUE: sırt (26 EUR/kg) costs more per kg than kıyma.
	if d := q.Lines[1].UnitCostKg - q.Lines[4].UnitCostKg; d > 0.001 || d < -0.001 {
		t.Errorf("QUANTITY unit costs differ: %v vs %v", q.Lines[1].UnitCostKg, q.Lines[4].UnitCostKg)
	}
	if v.Lines[1].UnitCostKg <= v.Lines[4].UnitCostKg {
		t.Errorf("VALUE should load sırt more than kıyma: %v vs %v", v.Lines[1].UnitCostKg, v.Lines[4].UnitCostKg)
	}
}
