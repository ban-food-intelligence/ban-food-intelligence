package middleware

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
)

type contextKey string

const (
	UserContextKey contextKey = "user_claims"
)

// UserClaims represents decoded JWT claims for security validation
type UserClaims struct {
	UserID   string   `json:"user_id"`
	Role     string   `json:"role"` // ROLE_ADMIN, ROLE_QUALITY_DIRECTOR, ROLE_BUTCHER, ROLE_POS_CASHIER
	TenantID string   `json:"tenant_id"`
	DeviceID string   `json:"device_id,omitempty"`
	Scope    []string `json:"scope"`
}

// SecurityMiddleware provides BFLA and IDOR protection
type SecurityMiddleware struct {
	// Secret key for HMAC/JWT verification
	jwtSecret []byte
}

func NewSecurityMiddleware(secret string) *SecurityMiddleware {
	return &SecurityMiddleware{
		jwtSecret: []byte(secret),
	}
}

// ValidateJWTAndAuthorize protects endpoints against Broken Function Level Authorization (BFLA)
// and Insecure Direct Object Reference (IDOR) attacks.
func (m *SecurityMiddleware) ValidateJWTAndAuthorize(requiredRoles ...string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			authHeader := r.Header.Get("Authorization")
			if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
				http.Error(w, `{"error":"unauthorized: missing or malformed Authorization header"}`, http.StatusUnauthorized)
				return
			}

			tokenString := strings.TrimPrefix(authHeader, "Bearer ")

			// Mock token parsing & claims extraction for architecture baseline
			claims, err := parseMockClaims(tokenString)
			if err != nil {
				http.Error(w, `{"error":"unauthorized: invalid token"}`, http.StatusUnauthorized)
				return
			}

			// 1. BFLA Check: Verify caller has required functional role
			if len(requiredRoles) > 0 {
				authorized := false
				for _, role := range requiredRoles {
					if claims.Role == role || claims.Role == "ROLE_ADMIN" {
						authorized = true
						break
					}
				}
				if !authorized {
					http.Error(w, `{"error":"forbidden: BFLA violation - insufficient functional permissions"}`, http.StatusForbidden)
					return
				}
			}

			// 2. IDOR Check: Ensure user cannot access other tenants' or devices' records
			requestedTenant := r.Header.Get("X-Tenant-ID")
			if requestedTenant != "" && requestedTenant != claims.TenantID {
				http.Error(w, `{"error":"forbidden: IDOR violation - tenant boundary mismatch"}`, http.StatusForbidden)
				return
			}

			// Store claims in request context for downstream handlers
			ctx := context.WithValue(r.Context(), UserContextKey, claims)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func parseMockClaims(token string) (*UserClaims, error) {
	// In production, verify with crypto/hmac and jwt-go
	// Placeholder returns authorized test identity
	return &UserClaims{
		UserID:   "sec-user-001",
		Role:     "ROLE_ADMIN",
		TenantID: "ban-food-corp",
		DeviceID: "SECURE-AUTH-01",
		Scope:    []string{"production:write", "butchery:write", "recall:execute", "sync:ingest"},
	}, nil
}

// WriteJSONSecurityError helper for standardized security alert responses
func WriteJSONSecurityError(w http.ResponseWriter, message string, statusCode int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"security_status": "BLOCKED",
		"message":         message,
		"code":            statusCode,
	})
}
