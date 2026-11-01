package controllers

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// tinyPNG is enough for http.DetectContentType to see an image.
var tinyPNG = append([]byte("\x89PNG\r\n\x1a\n"), make([]byte, 64)...)

func TestParseInvoiceContentToleratesThinkingAndFences(t *testing.T) {
	ex, err := parseInvoiceContent("<think>reading the table…</think>\n```json\n" +
		`{"supplier":"Örnek Et Tedarik A.Ş.","invoice_number":"F-77","items":[` +
		`{"product_name":"Dana eti","quantity":"50,5","unit":"kg","lot_number":"B-12","unit_cost":9.7,"currency":"EUR"},` +
		`{"product_name":"  ","quantity":1}]}` + "\n```")
	if err != nil || len(ex.Items) != 1 || ex.Items[0].ProductName != "Dana eti" || *ex.Supplier != "Örnek Et Tedarik A.Ş." {
		t.Fatalf("parsed %+v, %v", ex, err)
	}
	for name, content := range map[string]string{
		"no json":     "I cannot read this image.",
		"broken json": `{"items": [`,
		"no lines":    `{"supplier":"X","items":[]}`,
	} {
		var ve visionError
		if _, err := parseInvoiceContent(content); !errors.As(err, &ve) || ve.code != "OCR_UNREADABLE" {
			t.Errorf("%s: want OCR_UNREADABLE, got %v", name, err)
		}
	}
}

func TestParseAmountDatesAndUnits(t *testing.T) {
	for in, want := range map[interface{}]float64{12.5: 12.5, "12,5": 12.5, "1.234,50": 1234.5, "1,234.50": 1234.5, "€ 9.70": 9.7, "50 kg": 50} {
		if got := parseAmount(in); got == nil || *got != want {
			t.Errorf("parseAmount(%v) = %v, want %v", in, got, want)
		}
	}
	for _, in := range []interface{}{"abc", nil, true, ""} {
		if got := parseAmount(in); got != nil {
			t.Errorf("parseAmount(%v) = %v, want nil", in, *got)
		}
	}
	s := func(v string) *string { return &v }
	for in, want := range map[string]string{"2026-11-05": "2026-11-05", "05.11.2026": "2026-11-05", "05/11/2026": "2026-11-05",
		"5.11.2026": "2026-11-05", "31.02.2026": "", "next week": ""} {
		if got := isoDate(s(in)); got != want {
			t.Errorf("isoDate(%q) = %q, want %q", in, got, want)
		}
	}
	if isoDate(nil) != "" {
		t.Error("isoDate(nil) not empty")
	}
	for in, want := range map[string]string{"kg": "KG", "Kilo": "KG", "KG.": "KG", "ad": "ADET", "Stück": "ADET", "pkt": "PAKET", "kasa": "KASA"} {
		if got := unitCode(in); got != want {
			t.Errorf("unitCode(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestMatchNameUsesWordsNotAI(t *testing.T) {
	suppliers := []masterName{{"TED-ET-01", "Örnek Et Tedarik A.Ş.", ""}, {"TED-BAH-01", "Örnek Baharat Ltd.", ""}, {"TED-BAG-01", "Örnek Doğal Bağırsak Ltd.", ""}}
	products := []masterName{{"HM-ET-DANA", "Dana et (sucukluk)", "KG|10"}, {"HM-BAHARAT", "Sucuk baharat karışımı", "KG|365"},
		{"HM-BAGIRSAK", "Doğal bağırsak", "KG|180"}, {"KZ-KARKAS", "Bütün kuzu karkas", "KG|7"}}
	for printed, want := range map[string][2]string{
		"ÖRNEK ET TEDARİK A.Ş.":  {"TED-ET-01", "EXACT"},
		"Ornek Baharat Ltd. Sti": {"TED-BAH-01", "EXACT"},
		"Örnek Gıda":             {"", "NONE"}, // only the common word
	} {
		if m, level := matchName(printed, suppliers); m.Code != want[0] || level != want[1] {
			t.Errorf("supplier %q: got %s %s, want %v", printed, m.Code, level, want)
		}
	}
	for printed, want := range map[string][2]string{
		"Dana eti":           {"HM-ET-DANA", "PARTIAL"},
		"Sucuk baharatı":     {"HM-BAHARAT", "PARTIAL"},
		"DOĞAL BAĞIRSAK":     {"HM-BAGIRSAK", "EXACT"},
		"Kuzu karkas":        {"KZ-KARKAS", "PARTIAL"},
		"HM-ET-DANA 50 kg":   {"HM-ET-DANA", "EXACT"}, // the SKU is printed
		"Zeytinyağı 5 litre": {"", "NONE"},
		"":                   {"", "NONE"},
	} {
		if m, level := matchName(printed, products); m.Code != want[0] || level != want[1] {
			t.Errorf("product %q: got %s %s, want %v", printed, m.Code, level, want)
		}
	}
	// two records equally close: no guess
	if m, level := matchName("Kuzu", []masterName{{"KZ-BUT", "Kuzu but", ""}, {"KZ-OMUZ", "Kuzu omuz", ""}}); level != "NONE" || m.Code != "" {
		t.Fatalf("ambiguous: %s %s", m.Code, level)
	}
}

// fakeVision answers like the NVIDIA endpoint and records what it was sent.
func fakeVision(t *testing.T, status int, content string) (*httptest.Server, *map[string]interface{}) {
	t.Helper()
	got := map[string]interface{}{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got["auth"] = r.Header.Get("Authorization")
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &got)
		w.WriteHeader(status)
		if status == http.StatusOK {
			_ = json.NewEncoder(w).Encode(map[string]interface{}{"choices": []interface{}{map[string]interface{}{
				"message": map[string]string{"role": "assistant", "content": content}, "finish_reason": "stop"}}})
		} else {
			_, _ = w.Write([]byte(`{"detail":"secret upstream detail"}`))
		}
	}))
	t.Cleanup(srv.Close)
	return srv, &got
}

func TestInvoiceReaderSendsTheImageWithTheServerKey(t *testing.T) {
	srv, got := fakeVision(t, http.StatusOK, `{"supplier":"S","invoice_number":"F-1","items":[{"product_name":"Dana eti","quantity":2,"unit":"KG","lot_number":"","unit_cost":9.7,"currency":"EUR"}]}`)
	rd := &InvoiceReader{Endpoint: srv.URL, Model: "test-vl", APIKey: "nvapi-test", Client: srv.Client()}
	ex, err := rd.Read(context.Background(), tinyPNG, "image/png")
	if err != nil || len(ex.Items) != 1 {
		t.Fatalf("read: %+v %v", ex, err)
	}
	msgs, _ := (*got)["messages"].([]interface{})
	raw, _ := json.Marshal(msgs)
	if (*got)["auth"] != "Bearer nvapi-test" || (*got)["model"] != "test-vl" || (*got)["temperature"] != 0.0 ||
		!strings.Contains(string(raw), "data:image/png;base64,"+base64.StdEncoding.EncodeToString(tinyPNG)) {
		t.Fatalf("request sent: auth=%v model=%v msgs=%.200s", (*got)["auth"], (*got)["model"], raw)
	}

	for status, want := range map[int]string{http.StatusUnauthorized: "HTTP 401", http.StatusTooManyRequests: "HTTP 429"} {
		srv, _ := fakeVision(t, status, "")
		rd := &InvoiceReader{Endpoint: srv.URL, Model: "m", APIKey: "k", Client: srv.Client()}
		_, err := rd.Read(context.Background(), tinyPNG, "image/png")
		var ve visionError
		if !errors.As(err, &ve) || ve.code != "OCR_FAILED" || !strings.Contains(ve.detail, want) || strings.Contains(ve.detail, "secret") {
			t.Errorf("upstream %d: %v", status, err)
		}
	}
}

func TestInvoiceReadRefusesBeforeCallingTheModel(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer srv.Close()

	off := NewInvoiceController(nil, &InvoiceReader{Endpoint: srv.URL})
	if code, body := call(t, off.Read, "POST", "/purchases/invoice", InvoiceReadRequest{ImageBase64: base64.StdEncoding.EncodeToString(tinyPNG)}); code != http.StatusServiceUnavailable ||
		!strings.Contains(string(body), "OCR_NOT_CONFIGURED") {
		t.Fatalf("no key: %d %s", code, body)
	}
	code, body := call(t, off.Status, "GET", "/purchases/invoice", nil)
	if code != http.StatusOK || strings.TrimSpace(string(body)) != `{"configured":false,"model":""}` {
		t.Fatalf("status without key: %d %s", code, body)
	}

	on := NewInvoiceController(nil, &InvoiceReader{Endpoint: srv.URL, Model: "m", APIKey: "nvapi-secret", Client: srv.Client()})
	if _, body := call(t, on.Status, "GET", "/purchases/invoice", nil); strings.Contains(string(body), "nvapi") || !strings.Contains(string(body), `"configured":true`) {
		t.Fatalf("status leaks or misreports: %s", body)
	}
	for name, img := range map[string]string{
		"not base64": "%%%",
		"not image":  base64.StdEncoding.EncodeToString([]byte("just some text, not a photo")),
		"too large":  base64.StdEncoding.EncodeToString(append(append([]byte{}, tinyPNG...), make([]byte, maxInvoiceImageBytes)...)),
		"empty":      "",
	} {
		if code, body := call(t, on.Read, "POST", "/purchases/invoice", InvoiceReadRequest{ImageBase64: img}); code != http.StatusUnprocessableEntity {
			t.Errorf("%s: want 422, got %d %s", name, code, body)
		}
	}
	if calls != 0 {
		t.Fatalf("the model was called %d times for refused requests", calls)
	}
}
