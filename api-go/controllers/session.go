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

// Issue returns a token for username and its expiry time.
func (m *SessionManager) Issue(username string) (string, time.Time) {
	exp := m.now().Add(OperatorSessionTTL).UTC()
	payload := base64.RawURLEncoding.EncodeToString([]byte(username + "|" + strconv.FormatInt(exp.Unix(), 10)))
	return payload + "." + m.sign(payload), exp
}

// Verify returns the username of a valid, unexpired token.
func (m *SessionManager) Verify(token string) (string, bool) {
	payload, sig, ok := strings.Cut(token, ".")
	if !ok || !hmac.Equal([]byte(sig), []byte(m.sign(payload))) {
		return "", false
	}
	raw, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil {
		return "", false
	}
	user, expStr, ok := strings.Cut(string(raw), "|")
	exp, err := strconv.ParseInt(expStr, 10, 64)
	if !ok || err != nil || user == "" || m.now().Unix() >= exp {
		return "", false
	}
	return user, true
}

type operatorKey struct{}

// OperatorFrom returns the authenticated operator of a request ("" if none).
func OperatorFrom(ctx context.Context) string {
	u, _ := ctx.Value(operatorKey{}).(string)
	return u
}

// RequireOperator protects the write methods of a handler: POST/PUT/PATCH/DELETE need
// "Authorization: Bearer <token>" from POST /auth/session, reads stay open.
func (m *SessionManager) RequireOperator(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet || r.Method == http.MethodHead || r.Method == http.MethodOptions {
			next(w, r)
			return
		}
		token, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		user, ok := m.Verify(strings.TrimSpace(token))
		if !found || !ok {
			w.Header().Set("WWW-Authenticate", `Bearer realm="ban-food-intelligence"`)
			writeAPIError(w, r, http.StatusUnauthorized, "AUTH_REQUIRED", "POST /auth/session with a supervisor PIN, then send Authorization: Bearer <token>")
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), operatorKey{}, user)))
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
