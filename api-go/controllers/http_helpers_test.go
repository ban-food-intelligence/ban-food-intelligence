package controllers

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestClassifyDBError(t *testing.T) {
	cases := map[string]struct {
		status int
		code   string
	}{
		"Error 1644 (45000): LOT_NOT_FOUND":                     {404, "LOT_NOT_FOUND"},
		"Error 1644 (45000): INSUFFICIENT_STOCK: movement ...":  {409, "INSUFFICIENT_STOCK"},
		"Error 1644 (45000): LOT_BLOCKED: lot is recalled":      {409, "LOT_BLOCKED"},
		"Error 1644 (45000): ORDER_STATE: material must be ...": {409, "ORDER_STATE"},
		"Error 1062 (23000): Duplicate entry 'X' for key":       {409, "CONFLICT"},
		"Error 3819 (HY000): Check constraint 'ck' is violated": {422, "INVALID_INPUT"},
		"dial tcp: connection refused":                          {500, "DB_ERROR"},
	}
	for msg, want := range cases {
		status, code := classifyDBError(errors.New(msg))
		if status != want.status || code != want.code {
			t.Errorf("%q -> %d %s, want %d %s", msg, status, code, want.status, want.code)
		}
	}
}

func TestAPIErrorIsLocalised(t *testing.T) {
	for lang, want := range map[string]string{"tr-TR,tr;q=0.9": "Lot bulunamadı.", "de": "Charge nicht gefunden.", "en-US": "Lot not found.", "": "Lot not found."} {
		req := httptest.NewRequest(http.MethodGet, "/x", nil)
		req.Header.Set("Accept-Language", lang)
		rec := httptest.NewRecorder()
		writeAPIError(rec, req, http.StatusNotFound, "LOT_NOT_FOUND", "detail")
		var body APIError
		_ = json.Unmarshal(rec.Body.Bytes(), &body)
		if rec.Code != 404 || body.Code != "LOT_NOT_FOUND" || body.Message != want || body.Details != "detail" {
			t.Errorf("lang %q: %d %+v", lang, rec.Code, body)
		}
	}
}
