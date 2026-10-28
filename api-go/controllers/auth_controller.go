package controllers

import (
	"database/sql"
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"
)

const (
	supervisorMaxFailures      = 5
	supervisorMaxFailuresPerIP = 10
	supervisorLockout          = 5 * time.Minute
)

// SupervisorAuthController verifies the supervisor PIN that releases a POS recall lock.
// The PIN is only ever compared against bcrypt hashes in the `supervisor` table.
type SupervisorAuthController struct {
	db       *sql.DB
	mu       sync.Mutex
	attempts map[string]*pinAttempts
	sessions *SessionManager
	now      func() time.Time
}

type pinAttempts struct {
	failures    int
	lockedUntil time.Time
}

type SupervisorVerifyRequest struct {
	PIN        string `json:"pin"`
	DeviceID   string `json:"device_id"`
	LotNo      string `json:"lot_no"`
	RecallCode string `json:"recall_code"`
}

type SupervisorVerifyResponse struct {
	OK          bool      `json:"ok"`
	Username    string    `json:"username"`
	DisplayName string    `json:"display_name"`
	UnlockedAt  time.Time `json:"unlocked_at"`
}

type UnlockAuditEntryDto struct {
	At      time.Time       `json:"at"`
	Action  string          `json:"action"`
	By      string          `json:"by"`
	Details json.RawMessage `json:"details"`
}

func NewSupervisorAuthController(db *sql.DB) *SupervisorAuthController {
	return &SupervisorAuthController{db: db, attempts: map[string]*pinAttempts{}, now: time.Now}
}

// WithSessions lets Session issue operator tokens signed by m.
func (c *SupervisorAuthController) WithSessions(m *SessionManager) *SupervisorAuthController {
	c.sessions = m
	return c
}

// Verify handles POST /auth/supervisor/verify.
//
//	200 {ok, username, display_name, unlocked_at}
//	400 INVALID_INPUT   pin missing / not 4-12 digits
//	401 INVALID_PIN     details: {attempts_left}
//	429 LOCKED_OUT      details: {retry_after_seconds}; after 5 wrong PINs per device for 5 minutes
func (c *SupervisorAuthController) Verify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", nil)
		return
	}
	var req SupervisorVerifyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || !validPIN(req.PIN) {
		writeAPIError(w, r, http.StatusBadRequest, "INVALID_INPUT", "pin must be 4-12 digits")
		return
	}
	details, _ := json.Marshal(map[string]string{
		"device_id": req.DeviceID, "lot_no": req.LotNo, "recall_code": req.RecallCode,
	})
	_, username, display, ok := c.checkPIN(w, r, req.PIN, req.DeviceID, "terminal_lock", "SUPERVISOR_UNLOCK", details)
	if !ok {
		return
	}
	log.Printf("[INFO] [AUTH] supervisor=%s unlocked device=%s lot=%s recall=%s", username, req.DeviceID, req.LotNo, req.RecallCode)
	writeJSON(w, SupervisorVerifyResponse{OK: true, Username: username, DisplayName: display, UnlockedAt: c.now().UTC()})
}

// OperatorSessionResponse is the body of a successful POST /auth/session.
type OperatorSessionResponse struct {
	Token       string    `json:"token"`
	TokenType   string    `json:"token_type"`
	ExpiresAt   time.Time `json:"expires_at"`
	Username    string    `json:"username"`
	DisplayName string    `json:"display_name"`
}

// Session handles POST /auth/session {pin, device_id?}: a supervisor PIN (same lockout as
// Verify) buys a 30-minute bearer token for the back-office write endpoints (recall,
// movements, rework, production, purchases, butchery operations).
func (c *SupervisorAuthController) Session(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", nil)
		return
	}
	var req SupervisorVerifyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || !validPIN(req.PIN) {
		writeAPIError(w, r, http.StatusBadRequest, "INVALID_INPUT", "pin must be 4-12 digits")
		return
	}
	details, _ := json.Marshal(map[string]string{"device_id": req.DeviceID, "client_ip": ClientIP(r)})
	_, username, display, ok := c.checkPIN(w, r, req.PIN, req.DeviceID, "operator_session", "OPERATOR_SESSION", details)
	if !ok {
		return
	}
	token, exp := c.sessions.Issue(username)
	log.Printf("[INFO] [AUTH] operator session for %s from %s", username, ClientIP(r))
	writeJSON(w, OperatorSessionResponse{Token: token, TokenType: "Bearer", ExpiresAt: exp, Username: username, DisplayName: display})
}

type lockKey struct {
	key string
	max int
}

// checkPIN applies the lockout (5 wrong PINs per device, 10 per client IP, so rotating
// device_id does not help), compares the PIN with every active supervisor's bcrypt hash and
// audits the attempt in audit_log under auditTable. On failure it has already written the
// 401/429 response.
func (c *SupervisorAuthController) checkPIN(w http.ResponseWriter, r *http.Request, pin, deviceID, auditTable, action string, details []byte) (int64, string, string, bool) {
	keys := []lockKey{{"ip:" + ClientIP(r), supervisorMaxFailuresPerIP}}
	if deviceID != "" {
		keys = append(keys, lockKey{"dev:" + deviceID, supervisorMaxFailures})
	}
	var wait time.Duration
	for _, k := range keys {
		if d := c.lockedFor(k.key); d > wait {
			wait = d
		}
	}
	if wait > 0 {
		w.Header().Set("Retry-After", itoa(int(wait.Seconds())))
		writeAPIError(w, r, http.StatusTooManyRequests, "LOCKED_OUT", map[string]int{"retry_after_seconds": int(wait.Seconds())})
		return 0, "", "", false
	}

	rows, err := c.db.QueryContext(r.Context(),
		`SELECT id, username, display_name, pin_hash FROM supervisor WHERE is_active = TRUE`)
	if err != nil {
		writeDBError(w, r, err)
		return 0, "", "", false
	}
	var matchID int64
	var username, display string
	for rows.Next() {
		var id int64
		var u, d, hash string
		if err := rows.Scan(&id, &u, &d, &hash); err != nil {
			continue
		}
		if matchID == 0 && bcrypt.CompareHashAndPassword([]byte(hash), []byte(pin)) == nil {
			matchID, username, display = id, u, d
		}
	}
	rows.Close()

	if matchID == 0 {
		left := supervisorMaxFailuresPerIP
		for _, k := range keys {
			if l := c.recordFailureMax(k.key, k.max); l < left {
				left = l
			}
		}
		c.audit(r, auditTable, 0, action+"_DENIED", details, "unknown")
		if left <= 0 {
			w.Header().Set("Retry-After", itoa(int(supervisorLockout.Seconds())))
			writeAPIError(w, r, http.StatusTooManyRequests, "LOCKED_OUT",
				map[string]int{"retry_after_seconds": int(supervisorLockout.Seconds())})
			return 0, "", "", false
		}
		writeAPIError(w, r, http.StatusUnauthorized, "INVALID_PIN", map[string]int{"attempts_left": left})
		return 0, "", "", false
	}
	for _, k := range keys {
		c.reset(k.key)
	}
	c.audit(r, auditTable, matchID, action, details, username)
	return matchID, username, display, true
}

// Audit handles GET /auth/supervisor/audit: the latest unlock attempts (who, when, which lot).
func (c *SupervisorAuthController) Audit(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", nil)
		return
	}
	rows, err := c.db.QueryContext(r.Context(),
		`SELECT changed_at, action, COALESCE(changed_by, ''), COALESCE(new_value, JSON_OBJECT())
		 FROM audit_log WHERE table_name = 'terminal_lock' ORDER BY id DESC LIMIT 50`)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer rows.Close()
	list := []UnlockAuditEntryDto{}
	for rows.Next() {
		var e UnlockAuditEntryDto
		var raw []byte
		if err := rows.Scan(&e.At, &e.Action, &e.By, &raw); err == nil {
			e.Details = json.RawMessage(raw)
			list = append(list, e)
		}
	}
	writeJSON(w, list)
}

func (c *SupervisorAuthController) audit(r *http.Request, table string, supervisorID int64, action string, details []byte, by string) {
	if _, err := c.db.ExecContext(r.Context(),
		`INSERT INTO audit_log (table_name, row_id, action, new_value, changed_by) VALUES (?, ?, ?, ?, ?)`,
		table, supervisorID, action, string(details), by); err != nil {
		log.Printf("[WARN] [AUTH] audit insert failed: %v", err)
	}
}

func (c *SupervisorAuthController) lockedFor(key string) time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	if a, ok := c.attempts[key]; ok && c.now().Before(a.lockedUntil) {
		return a.lockedUntil.Sub(c.now()).Round(time.Second)
	}
	return 0
}

// recordFailure counts a wrong PIN and returns the attempts left before lockout.
func (c *SupervisorAuthController) recordFailure(key string) int {
	return c.recordFailureMax(key, supervisorMaxFailures)
}

func (c *SupervisorAuthController) recordFailureMax(key string, max int) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	a, ok := c.attempts[key]
	if !ok || (!a.lockedUntil.IsZero() && c.now().After(a.lockedUntil)) {
		a = &pinAttempts{}
		c.attempts[key] = a
	}
	a.failures++
	left := max - a.failures
	if left <= 0 {
		a.lockedUntil = c.now().Add(supervisorLockout)
	}
	return left
}

func (c *SupervisorAuthController) reset(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.attempts, key)
}

func validPIN(pin string) bool {
	if len(pin) < 4 || len(pin) > 12 {
		return false
	}
	for _, ch := range pin {
		if ch < '0' || ch > '9' {
			return false
		}
	}
	return true
}

func itoa(n int) string {
	b, _ := json.Marshal(n)
	return string(b)
}
