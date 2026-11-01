package controllers

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// OperatorSessionTTL is how long a back-office token from POST /auth/session stays valid.
const OperatorSessionTTL = 30 * time.Minute

// SessionManager issues and checks stateless operator tokens:
//
//	base64url(username|expiry_unix) "." base64url(HMAC-SHA256(secret, first part))
//
// The secret comes from API_SESSION_SECRET (>= 32 bytes) or is random per process, so a
// restart signs everybody out. Tokens are only issued after a supervisor PIN check.
type SessionManager struct {
	secret []byte
	now    func() time.Time
}

func NewSessionManager() *SessionManager {
	secret := []byte(os.Getenv("API_SESSION_SECRET"))
	if len(secret) < 32 {
		secret = make([]byte, 32)
		if _, err := rand.Read(secret); err != nil {
			panic("crypto/rand unavailable: " + err.Error())
		}
	}
	return &SessionManager{secret: secret, now: time.Now}
}

func (m *SessionManager) sign(payload string) string {
	mac := hmac.New(sha256.New, m.secret)
	mac.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// Roles of back-office users (supervisor.role). Reads stay open to everybody; every write
// endpoint names the roles that may call it (see main.go).
const (
	RoleAdmin    = "ADMIN"    // everything, including device keys
	RoleQuality  = "QUALITY"  // recall, recall follow-up, alerts and their thresholds, POS unlock
	RoleOperator = "OPERATOR" // purchases, production, butchery, movements, shipments, stock counts
	RoleViewer   = "VIEWER"   // may sign in, writes nothing
)

// ValidRole reports whether role is one of the four roles.
func ValidRole(role string) bool {
	switch role {
	case RoleAdmin, RoleQuality, RoleOperator, RoleViewer:
		return true
	}
	return false
}

// Issue returns an ADMIN token for username and its expiry time.
func (m *SessionManager) Issue(username string) (string, time.Time) {
	return m.IssueRole(username, RoleAdmin)
}

// IssueRole returns a token for username with role, and its expiry time. The role is signed
// into the token; a role change takes effect at the next sign-in (tokens live 30 minutes).
func (m *SessionManager) IssueRole(username, role string) (string, time.Time) {
	exp := m.now().Add(OperatorSessionTTL).UTC()
	payload := base64.RawURLEncoding.EncodeToString([]byte(username + "|" + strconv.FormatInt(exp.Unix(), 10) + "|" + role))
	return payload + "." + m.sign(payload), exp
}

// Verify returns the username of a valid, unexpired token.
func (m *SessionManager) Verify(token string) (string, bool) {
	user, _, ok := m.Claims(token)
	return user, ok
}

// Claims returns the username and role of a valid, unexpired token.
func (m *SessionManager) Claims(token string) (string, string, bool) {
	payload, sig, ok := strings.Cut(token, ".")
	if !ok || !hmac.Equal([]byte(sig), []byte(m.sign(payload))) {
		return "", "", false
	}
	raw, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil {
		return "", "", false
	}
	parts := strings.Split(string(raw), "|")
	if len(parts) != 3 || parts[0] == "" || !ValidRole(parts[2]) {
		return "", "", false
	}
	exp, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil || m.now().Unix() >= exp {
		return "", "", false
	}
	return parts[0], parts[2], true
}

type operatorKey struct{}
type roleKey struct{}

// OperatorFrom returns the authenticated operator of a request ("" if none).
func OperatorFrom(ctx context.Context) string {
	u, _ := ctx.Value(operatorKey{}).(string)
	return u
}

// RoleFrom returns the role of the authenticated operator ("" if none).
func RoleFrom(ctx context.Context) string {
	r, _ := ctx.Value(roleKey{}).(string)
	return r
}

// RequireOperator protects the write methods of a handler for every role that may write
// (ADMIN, QUALITY, OPERATOR); a VIEWER gets 403. Reads stay open.
func (m *SessionManager) RequireOperator(next http.HandlerFunc) http.HandlerFunc {
	return m.RequireRoles(RoleAdmin, RoleQuality, RoleOperator)(next)
}

// RequireRoles protects the write methods (POST/PUT/PATCH/DELETE) of a handler: they need
// "Authorization: Bearer <token>" from POST /auth/session (401 AUTH_REQUIRED otherwise) and
// one of roles (403 FORBIDDEN otherwise). ADMIN is always allowed. Reads stay open.
func (m *SessionManager) RequireRoles(roles ...string) func(http.HandlerFunc) http.HandlerFunc {
	allowed := map[string]bool{RoleAdmin: true}
	for _, r := range roles {
		allowed[r] = true
	}
	return func(next http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodGet || r.Method == http.MethodHead || r.Method == http.MethodOptions {
				next(w, r)
				return
			}
			token, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
			user, role, ok := m.Claims(strings.TrimSpace(token))
			if !found || !ok {
				w.Header().Set("WWW-Authenticate", `Bearer realm="ban-food-intelligence"`)
				writeAPIError(w, r, http.StatusUnauthorized, "AUTH_REQUIRED", "POST /auth/session with a supervisor PIN, then send Authorization: Bearer <token>")
				return
			}
			if !allowed[role] {
				need := make([]string, 0, len(allowed))
				for _, x := range []string{RoleAdmin, RoleQuality, RoleOperator, RoleViewer} {
					if allowed[x] {
						need = append(need, x)
					}
				}
				writeAPIError(w, r, http.StatusForbidden, "FORBIDDEN", map[string]interface{}{"role": role, "allowed_roles": need})
				return
			}
			ctx := context.WithValue(context.WithValue(r.Context(), operatorKey{}, user), roleKey{}, role)
			next(w, r.WithContext(ctx))
		}
	}
}

// ClientIP is the TCP peer address. X-Forwarded-For is ignored on purpose: the API is
// not deployed behind a trusted proxy, so the header would let a client pick its own key.
func ClientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
