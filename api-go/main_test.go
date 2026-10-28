package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAllowedOrigin(t *testing.T) {
	t.Setenv("CORS_ALLOWED_ORIGINS", "")
	for origin, want := range map[string]bool{
		"http://localhost:5173":    true,
		"http://127.0.0.1:5173":    true,
		"http://192.168.1.20:5173": true,
		"http://10.0.0.5:3000":     true,
		"http://localhost:4173":    true,
		"http://evil.example":      false,
		"http://evil.example:8080": false,
		"http://evil.example:5173": false, // dashboard port on a public host
		"http://8.8.8.8:5173":      false,
		"file://localhost:5173":    false,
		"":                         false,
	} {
		if got := allowedOrigin(origin); got != want {
			t.Errorf("allowedOrigin(%q) = %v, want %v", origin, got, want)
		}
	}
	t.Setenv("CORS_ALLOWED_ORIGINS", "https://dash.example, http://localhost:5173")
	if !allowedOrigin("https://dash.example") || allowedOrigin("http://localhost:3000") {
		t.Error("explicit CORS_ALLOWED_ORIGINS list not respected")
	}
}

func TestRateLimiterPerIP(t *testing.T) {
	now := time.Date(2026, 10, 28, 9, 0, 0, 0, time.UTC)
	l := newRateLimiter(2, 3)
	l.now = func() time.Time { return now }
	for i := 0; i < 3; i++ {
		if !l.allow("10.0.0.1") {
			t.Fatalf("burst request %d refused", i)
		}
	}
	if l.allow("10.0.0.1") {
		t.Fatal("4th request in the same instant allowed")
	}
	if !l.allow("10.0.0.2") {
		t.Fatal("another IP must have its own bucket")
	}
	now = now.Add(500 * time.Millisecond) // 2 rps -> one token back
	if !l.allow("10.0.0.1") || l.allow("10.0.0.1") {
		t.Fatal("refill is not 2 tokens per second")
	}
}

func TestSecurityMiddleware(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		buf := make([]byte, maxBodyBytes+10)
		if _, err := r.Body.Read(buf); err != nil && err.Error() == "http: request body too large" {
			w.WriteHeader(http.StatusRequestEntityTooLarge)
			return
		}
		w.WriteHeader(http.StatusOK)
	})
	h := withSecurity(newRateLimiter(1000, 1000), ok)

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/health", nil))
	for _, k := range []string{"X-Content-Type-Options", "X-Frame-Options", "Cache-Control", "Content-Security-Policy"} {
		if rec.Header().Get(k) == "" {
			t.Errorf("missing header %s", k)
		}
	}

	big := strings.Repeat("x", maxBodyBytes+1)
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/sync", strings.NewReader(big)))
	if rec.Code != http.StatusRequestEntityTooLarge || !strings.Contains(rec.Body.String(), "PAYLOAD_TOO_LARGE") {
		t.Fatalf("oversized body: %d %s", rec.Code, rec.Body.String())
	}

	limited := withSecurity(newRateLimiter(0.001, 1), ok)
	rec = httptest.NewRecorder()
	limited.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/lots", nil))
	rec = httptest.NewRecorder()
	limited.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/lots", nil))
	if rec.Code != http.StatusTooManyRequests || rec.Header().Get("Retry-After") == "" {
		t.Fatalf("rate limit: %d", rec.Code)
	}
}
