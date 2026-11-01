package controllers

// Integration test (MYSQL_TEST_DSN) for invoice reading: the model's lines are matched to the seed
// suppliers and products, doubts become warnings and nothing is written.

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestIntegrationInvoiceReadSuggestsAReceiptAndWritesNothing(t *testing.T) {
	db := testDB(t)
	srv, _ := fakeVision(t, http.StatusOK, `{"supplier":"ÖRNEK ET TEDARİK A.Ş.","invoice_number":"FTR-2026-0912","invoice_date":"30.10.2026","items":[
		{"product_name":"DANA ET SUCUKLUK","quantity":"120,5","unit":"kg","lot_number":"TED-B-4411","unit_cost":"9,80","currency":"EUR","expiry_date":"2026-11-08"},
		{"product_name":"Sucuk baharatı","quantity":12,"unit":"KG","lot_number":"","unit_cost":21.5,"currency":"EUR","expiry_date":null},
		{"product_name":"Doğal bağırsak","quantity":30,"unit":"adet","lot_number":"BG-7","unit_cost":0,"currency":"TRY","expiry_date":"2027-04-01"},
		{"product_name":"Zeytinyağı 5 litre","quantity":2,"unit":"adet","lot_number":"","unit_cost":11,"currency":"EUR"}]}`)
	ctrl := NewInvoiceController(db, &InvoiceReader{Endpoint: srv.URL, Model: "test-vl", APIKey: "nvapi-test", Client: srv.Client()})
	sessions := NewSessionManager()
	token, _ := sessions.Issue("ocr-supervisor")
	count := func() (n int) {
		_ = db.QueryRow(`SELECT (SELECT COUNT(*) FROM purchase_receipt) + (SELECT COUNT(*) FROM lot) + (SELECT COUNT(*) FROM stock_movement)`).Scan(&n)
		return
	}
	before := count()

	payload, _ := json.Marshal(InvoiceReadRequest{ImageBase64: "data:image/png;base64," + base64.StdEncoding.EncodeToString(tinyPNG)})
	if code, _ := call(t, sessions.RequireOperator(ctrl.Read), "POST", "/purchases/invoice", InvoiceReadRequest{}); code != http.StatusUnauthorized {
		t.Fatalf("read without session: %d", code)
	}
	r := httptest.NewRequest("POST", "/purchases/invoice", bytes.NewReader(payload))
	r.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	sessions.RequireOperator(ctrl.Read)(rec, r)
	var s InvoiceSuggestionDto
	_ = json.Unmarshal(rec.Body.Bytes(), &s)
	if rec.Code != http.StatusOK || s.SupplierCode != "TED-ET-01" || s.SupplierMatch != "EXACT" || s.InvoiceNo != "FTR-2026-0912" ||
		s.InvoiceDate != "2026-10-30" || s.Model != "test-vl" || len(s.Lines) != 4 {
		t.Fatalf("read: %d %s", rec.Code, rec.Body.Bytes())
	}
	type want struct {
		sku, match string
		qty, price float64
		warnings   []string
	}
	for i, w := range []want{
		{"HM-ET-DANA", "EXACT", 120.5, 9.8, nil},
		{"HM-BAHARAT", "PARTIAL", 12, 21.5, []string{"EXPIRY_MISSING"}},
		{"HM-BAGIRSAK", "EXACT", 30, 0, []string{"UNIT_DIFFERS", "NOT_EUR"}},
		{"", "NONE", 2, 11, []string{"NO_PRODUCT_MATCH"}},
	} {
		l := s.Lines[i]
		if l.SKU != w.sku || l.Match != w.match || l.Qty == nil || *l.Qty != w.qty || l.UnitPrice == nil || *l.UnitPrice != w.price ||
			len(l.Warnings) != len(w.warnings) {
			t.Fatalf("line %d: got %+v, want %+v", i, l, w)
		}
		for j := range w.warnings {
			if l.Warnings[j] != w.warnings[j] {
				t.Fatalf("line %d warnings %v, want %v", i, l.Warnings, w.warnings)
			}
		}
	}
	if l := s.Lines[0]; l.SupplierLotNo != "TED-B-4411" || l.ExpiryDate != "2026-11-08" || l.Unit != "KG" {
		t.Fatalf("first line details %+v", l)
	}
	if after := count(); after != before {
		t.Fatalf("reading an invoice wrote %d rows", after-before)
	}
}
