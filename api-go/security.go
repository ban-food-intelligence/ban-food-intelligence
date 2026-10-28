package main

import (
	"encoding/json"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"ban-food-intelligence/api-go/controllers"
)

// maxBodyBytes caps every request body (the largest real request, a POS batch of 50 sales,
// is about 25 KB).
const maxBodyBytes = 1 << 20

// rateLimiter is a token bucket per client IP: rate requests per second, burst at most.
// The dashboard loads about ten endpoints at once, so the burst is generous.
type rateLimiter struct {
	mu      sync.Mutex
	rate    float64
	burst   float64
	buckets map[string]*bucket
	now     func() time.Time
}

type bucket struct {
	tokens float64
	last   time.Time
}

func newRateLimiter(rate, burst float64) *rateLimiter {
	return &rateLimiter{rate: rate, burst: burst, buckets: map[string]*bucket{}, now: time.Now}
}

// allow takes one token for ip and reports whether the request may proceed.
func (l *rateLimiter) allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b, ok := l.buckets[ip]
	if !ok {
		if len(l.buckets) > 10000 { // forget idle clients instead of growing without bound
			for k, v := range l.buckets {
				if now.Sub(v.last) > time.Minute {
					delete(l.buckets, k)
				}
			}
		}
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[ip] = b
	}
	b.tokens += now.Sub(b.last).Seconds() * l.rate
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func writeError(w http.ResponseWriter, r *http.Request, status int, code string) {
	msgs := map[string]map[string]string{
		"PAYLOAD_TOO_LARGE": {"en": "The request is too large.", "tr": "İstek çok büyük.", "de": "Die Anfrage ist zu groß."},
		"RATE_LIMITED":      {"en": "Too many requests. Slow down.", "tr": "Çok fazla istek. Lütfen yavaşlayın.", "de": "Zu viele Anfragen. Bitte langsamer."},
	}[code]
	lang := "en"
	switch al := strings.ToLower(r.Header.Get("Accept-Language")); {
	case strings.HasPrefix(al, "tr"):
		lang = "tr"
	case strings.HasPrefix(al, "de"):
		lang = "de"
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"code": code, "message": msgs[lang]})
}

// withSecurity adds response hardening headers, the body size cap and the per-IP rate limit.
func withSecurity(limiter *rateLimiter, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-store")
		h.Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")

		if r.Method != http.MethodOptions && !limiter.allow(controllers.ClientIP(r)) {
			h.Set("Retry-After", "1")
			writeError(w, r, http.StatusTooManyRequests, "RATE_LIMITED")
			return
		}
		if r.ContentLength > maxBodyBytes {
			writeError(w, r, http.StatusRequestEntityTooLarge, "PAYLOAD_TOO_LARGE")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
		next.ServeHTTP(w, r)
	})
}

// rateFromEnv reads API_RATE_LIMIT_RPS / API_RATE_LIMIT_BURST (defaults 20 / 120).
func rateFromEnv() (float64, float64) {
	rps, burst := 20.0, 120.0
	if v, err := strconv.ParseFloat(os.Getenv("API_RATE_LIMIT_RPS"), 64); err == nil && v > 0 {
		rps = v
	}
	if v, err := strconv.ParseFloat(os.Getenv("API_RATE_LIMIT_BURST"), 64); err == nil && v >= 1 {
		burst = v
	}
	return rps, burst
}

// localHost reports whether a browser origin host is this machine or a private LAN address
// (the dashboard is opened on localhost or on the PC's LAN IP during the demo).
func localHost(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && (ip.IsLoopback() || ip.IsPrivate())
}

// allowedOrigin reports whether a browser origin may call the API: the React dashboard
// (dev 5173, compose 3000, preview 4173) on localhost or a private LAN address, or an
// explicit list in CORS_ALLOWED_ORIGINS (comma separated).
func allowedOrigin(origin string) bool {
	if origin == "" {
		return false
	}
	if list := os.Getenv("CORS_ALLOWED_ORIGINS"); list != "" {
		for _, o := range strings.Split(list, ",") {
			if strings.TrimSpace(o) == origin {
				return true
			}
		}
		return false
	}
	u, err := url.Parse(origin)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || !localHost(u.Hostname()) {
		return false
	}
	switch u.Port() {
	case "5173", "3000", "4173":
		return true
	}
	return false
}
