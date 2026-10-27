package main

import "testing"

func TestAllowedOrigin(t *testing.T) {
	t.Setenv("CORS_ALLOWED_ORIGINS", "")
	for origin, want := range map[string]bool{
		"http://localhost:5173":    true,
		"http://192.168.1.20:5173": true,
		"http://localhost:3000":    true,
		"http://localhost:4173":    true,
		"http://evil.example":      false,
		"http://evil.example:8080": false,
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
