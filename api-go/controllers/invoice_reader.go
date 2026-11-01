package controllers

// Invoice reading (fatura okuma): a photo of a supplier invoice is read by a vision-language model
// called from the server, so the API key never reaches a browser or a phone. The answer is a
// SUGGESTION for the receipt form: supplier and products are matched to the master data by plain
// name rules (no AI), every doubt is reported as a warning, and nothing is written. The supervisor
// checks the fields and saves the receipt with POST /purchases as usual.

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"
)

const (
	defaultVisionEndpoint = "https://integrate.api.nvidia.com/v1/chat/completions"
	defaultVisionModel    = "nvidia/nemotron-3.5-super-vl-120b-a12b" // the model the Android client used
	maxInvoiceImageBytes  = 768 << 10                                // the dashboard sends a downscaled JPEG
	visionTimeout         = 90 * time.Second
)

// InvoiceReader calls an OpenAI-compatible chat-completions endpoint with an image.
type InvoiceReader struct {
	Endpoint string
	Model    string
	APIKey   string
	Client   *http.Client
	slots    chan struct{} // at most two reads at a time: each call costs money and takes seconds
}

// NewInvoiceReaderFromEnv: NVIDIA_API_KEY (required to read), NVIDIA_VISION_MODEL, NVIDIA_VISION_ENDPOINT.
func NewInvoiceReaderFromEnv() *InvoiceReader {
	return &InvoiceReader{
		Endpoint: nonEmpty(strings.TrimSpace(os.Getenv("NVIDIA_VISION_ENDPOINT")), defaultVisionEndpoint),
		Model:    nonEmpty(strings.TrimSpace(os.Getenv("NVIDIA_VISION_MODEL")), defaultVisionModel),
		APIKey:   strings.TrimSpace(os.Getenv("NVIDIA_API_KEY")),
		Client:   &http.Client{Timeout: visionTimeout},
	}
}

func (r *InvoiceReader) Configured() bool { return r != nil && r.APIKey != "" }

func (r *InvoiceReader) acquire() bool {
	if r.slots == nil {
		r.slots = make(chan struct{}, 2)
	}
	select {
	case r.slots <- struct{}{}:
		return true
	default:
		return false
	}
}

func (r *InvoiceReader) release() { <-r.slots }

const invoiceSystemPrompt = `You are a strict JSON API. Return ONLY valid JSON. No markdown, no code fences, no explanations, ` +
	`no reasoning text. The first character of your reply must be { and the last character must be }.`

const invoicePrompt = `You are an invoice data-extraction engine for a food-business ERP.
Read the attached supplier invoice or delivery note image and extract every product line.
Reply with a single JSON object in exactly this shape:
{
  "supplier": string or null,
  "invoice_number": string or null,
  "invoice_date": "YYYY-MM-DD" or null,
  "items": [
    {
      "product_name": string,
      "quantity": number,
      "unit": string,
      "lot_number": string,
      "unit_cost": number,
      "currency": string,
      "expiry_date": "YYYY-MM-DD" or null
    }
  ]
}
Rules:
- Use a dot as decimal separator in numbers (e.g. 12.5, not 12,5).
- "unit" is the unit of measure as printed (e.g. KG, ADET, PAKET).
- "unit_cost" is the net price per unit, without VAT.
- If a lot number or an expiry date is not printed for a line, use "" or null. Never invent one.
- Do not include totals, VAT lines or shipping lines as items.`

// invoiceExtraction is the model's JSON answer; numbers may come as strings.
type invoiceExtraction struct {
	Supplier      *string `json:"supplier"`
	InvoiceNumber *string `json:"invoice_number"`
	InvoiceDate   *string `json:"invoice_date"`
	Items         []struct {
		ProductName string      `json:"product_name"`
		Quantity    interface{} `json:"quantity"`
		Unit        string      `json:"unit"`
		LotNumber   string      `json:"lot_number"`
		UnitCost    interface{} `json:"unit_cost"`
		Currency    string      `json:"currency"`
		ExpiryDate  *string     `json:"expiry_date"`
	} `json:"items"`
}

type visionError struct {
	code   string // OCR_FAILED | OCR_UNREADABLE
	detail string
}

func (e visionError) Error() string { return e.code + ": " + e.detail }

// Read sends one image and returns the parsed extraction.
func (r *InvoiceReader) Read(ctx context.Context, image []byte, mime string) (*invoiceExtraction, error) {
	body, _ := json.Marshal(map[string]interface{}{
		"model": r.Model, "temperature": 0.0, "top_p": 1.0, "max_tokens": 2048, "stream": false,
		"messages": []interface{}{
			map[string]interface{}{"role": "system", "content": invoiceSystemPrompt},
			map[string]interface{}{"role": "user", "content": []interface{}{
				map[string]interface{}{"type": "text", "text": invoicePrompt},
				map[string]interface{}{"type": "image_url", "image_url": map[string]string{
					"url": "data:" + mime + ";base64," + base64.StdEncoding.EncodeToString(image)}},
			}},
		},
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.Endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, visionError{"OCR_FAILED", "bad endpoint"}
	}
	req.Header.Set("Authorization", "Bearer "+r.APIKey)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	resp, err := r.Client.Do(req)
	if err != nil {
		return nil, visionError{"OCR_FAILED", "vision service unreachable or too slow"}
	}
	defer resp.Body.Close()
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if resp.StatusCode != http.StatusOK {
		return nil, visionError{"OCR_FAILED", fmt.Sprintf("vision service answered HTTP %d", resp.StatusCode)}
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil || len(out.Choices) == 0 {
		return nil, visionError{"OCR_FAILED", "unexpected vision service response"}
	}
	return parseInvoiceContent(out.Choices[0].Message.Content)
}

// parseInvoiceContent finds the JSON object in the model's text (some models think aloud or add
// code fences first) and keeps the lines that have a product name.
func parseInvoiceContent(content string) (*invoiceExtraction, error) {
	if i := strings.LastIndex(content, "</think>"); i >= 0 {
		content = content[i+len("</think>"):]
	}
	start, end := strings.Index(content, "{"), strings.LastIndex(content, "}")
	if start < 0 || end <= start {
		return nil, visionError{"OCR_UNREADABLE", "the answer contained no JSON object"}
	}
	var ex invoiceExtraction
	if err := json.Unmarshal([]byte(content[start:end+1]), &ex); err != nil {
		return nil, visionError{"OCR_UNREADABLE", "the answer was not valid JSON"}
	}
	items := ex.Items[:0]
	for _, it := range ex.Items {
		if strings.TrimSpace(it.ProductName) != "" {
			items = append(items, it)
		}
	}
	ex.Items = items
	if len(ex.Items) == 0 {
		return nil, visionError{"OCR_UNREADABLE", "no product line could be read"}
	}
	return &ex, nil
}

var notNumber = regexp.MustCompile(`[^0-9,.\-]`)

// parseAmount reads 12.5, "12,5", "1.234,50" or "€ 9.70"; nil when there is no number.
func parseAmount(v interface{}) *float64 {
	var f float64
	switch x := v.(type) {
	case float64:
		f = x
	case string:
		s := notNumber.ReplaceAllString(x, "")
		if strings.Contains(s, ",") && strings.Contains(s, ".") {
			if strings.LastIndex(s, ",") > strings.LastIndex(s, ".") { // 1.234,50
				s = strings.ReplaceAll(strings.ReplaceAll(s, ".", ""), ",", ".")
			} else { // 1,234.50
				s = strings.ReplaceAll(s, ",", "")
			}
		} else {
			s = strings.ReplaceAll(s, ",", ".")
		}
		p, err := strconv.ParseFloat(s, 64)
		if err != nil {
			return nil
		}
		f = p
	default:
		return nil
	}
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return nil
	}
	return &f
}

// isoDate accepts 2026-11-05, 05.11.2026 and 05/11/2026; "" for anything else or an impossible date.
func isoDate(v *string) string {
	if v == nil {
		return ""
	}
	s := strings.TrimSpace(*v)
	for _, layout := range []string{"2006-01-02", "02.01.2006", "02/01/2006", "2.1.2006"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t.Format("2006-01-02")
		}
	}
	return ""
}

// unitCode maps a printed unit to KG / ADET / PAKET; the printed text upper-cased otherwise.
func unitCode(u string) string {
	switch n := strings.ToUpper(strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(u), "."))); n {
	case "KG", "KGS", "KILO", "KILOGRAM", "KİLO", "KİLOGRAM":
		return "KG"
	case "ADET", "AD", "PCS", "PC", "PIECE", "STK", "STÜCK":
		return "ADET"
	case "PAKET", "PKT", "PACK", "PCK":
		return "PAKET"
	default:
		return n
	}
}

var asciiFold = strings.NewReplacer("ç", "c", "ğ", "g", "ı", "i", "i̇", "i", "ö", "o", "ş", "s", "ü", "u", "â", "a", "î", "i", "û", "u", "ä", "a")

// nameStopwords are company forms and filler that say nothing about who or what it is.
var nameStopwords = map[string]bool{"ltd": true, "sti": true, "as": true, "a": true, "s": true, "san": true, "tic": true,
	"ve": true, "gmbh": true, "co": true, "kg": true, "inc": true, "llc": true, "the": true}

var onlyDigits = regexp.MustCompile(`^[0-9]+$`)

// nameTokens: lower-cased, Turkish letters folded to ASCII, punctuation dropped, company forms,
// single letters and bare numbers (pack sizes, quantities) removed.
func nameTokens(s string) []string {
	s = asciiFold.Replace(strings.ToLower(strings.ReplaceAll(s, "İ", "i")))
	words := strings.FieldsFunc(s, func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) })
	out := []string{}
	for _, w := range words {
		if !nameStopwords[w] && len(w) > 1 && !onlyDigits.MatchString(w) {
			out = append(out, w)
		}
	}
	return out
}

// tokenMatch: equal, or one a prefix of the other (et / eti, baharat / baharati), both at least 2 letters.
func tokenMatch(a, b string) bool {
	if a == b {
		return true
	}
	if len(a) < 2 || len(b) < 2 {
		return false
	}
	return strings.HasPrefix(a, b) || strings.HasPrefix(b, a)
}

// nameScore is a weighted Dice coefficient of two token lists with prefix-tolerant matching: a
// word shared by many master records ("Örnek", "Gıda") counts less than a distinctive one.
func nameScore(a, b []string, weight func(string) float64) float64 {
	sum := func(ts []string) (s float64) {
		for _, t := range ts {
			s += weight(t)
		}
		return
	}
	total := sum(a) + sum(b)
	if total == 0 {
		return 0
	}
	used := make([]bool, len(b))
	common := 0.0
	for _, x := range a {
		for j, y := range b {
			if !used[j] && tokenMatch(x, y) {
				used[j] = true
				common += weight(x) + weight(y)
				break
			}
		}
	}
	return common / total
}

type masterName struct{ Code, Name, Extra string }

// matchName returns the best master record for a printed name: EXACT (same words or the code is
// printed), PARTIAL (score >= 0.5), or NONE (nothing close, or two records equally close).
func matchName(printed string, master []masterName) (masterName, string) {
	upper := strings.ToUpper(printed)
	for _, m := range master {
		if m.Code != "" && strings.Contains(upper, strings.ToUpper(m.Code)) {
			return m, "EXACT"
		}
	}
	names := make([][]string, len(master))
	for i, m := range master {
		names[i] = nameTokens(m.Name)
	}
	// inverse document frequency over the master names; a word no record has weighs the most
	weight := func(tok string) float64 {
		df := 0
		for _, n := range names {
			for _, x := range n {
				if tokenMatch(tok, x) {
					df++
					break
				}
			}
		}
		return math.Log(1 + float64(len(master))/math.Max(1, float64(df)))
	}
	p := nameTokens(printed)
	best, bestScore, tie := masterName{}, 0.0, false
	for i, m := range master {
		s := nameScore(p, names[i], weight)
		if s > bestScore+1e-9 {
			best, bestScore, tie = m, s, false
		} else if math.Abs(s-bestScore) < 1e-9 && s > 0 {
			tie = true
		}
	}
	switch {
	case bestScore < 0.5 || tie:
		return masterName{}, "NONE"
	case strings.Join(p, " ") == strings.Join(nameTokens(best.Name), " "):
		return best, "EXACT"
	default:
		return best, "PARTIAL"
	}
}

type InvoiceReadRequest struct {
	ImageBase64 string `json:"image_base64"` // JPEG, PNG or WebP, at most 768 KB decoded
}

type InvoiceSuggestionLine struct {
	ProductName   string   `json:"product_name"` // as printed
	SKU           string   `json:"sku"`          // matched product, "" when none
	SKUName       string   `json:"sku_name,omitempty"`
	Match         string   `json:"match"` // EXACT | PARTIAL | NONE
	Qty           *float64 `json:"qty"`
	Unit          string   `json:"unit"` // printed unit, normalised
	UnitPrice     *float64 `json:"unit_price"`
	Currency      string   `json:"currency"`
	SupplierLotNo string   `json:"supplier_lot_no"` // printed batch number, never invented
	ExpiryDate    string   `json:"expiry_date"`     // YYYY-MM-DD when printed
	Warnings      []string `json:"warnings"`
}

type InvoiceSuggestionDto struct {
	SupplierName  string                  `json:"supplier_name"` // as printed
	SupplierCode  string                  `json:"supplier_code"`
	SupplierMatch string                  `json:"supplier_match"`
	InvoiceNo     string                  `json:"invoice_no"`
	InvoiceDate   string                  `json:"invoice_date"`
	Lines         []InvoiceSuggestionLine `json:"lines"`
	Model         string                  `json:"model"`
	LatencyMs     int64                   `json:"latency_ms"`
}

type InvoiceController struct {
	db     *sql.DB
	reader *InvoiceReader
}

func NewInvoiceController(db *sql.DB, reader *InvoiceReader) *InvoiceController {
	return &InvoiceController{db: db, reader: reader}
}

// Status handles GET /purchases/invoice: whether this server can read invoices (no secret shown).
func (c *InvoiceController) Status(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", nil)
		return
	}
	model := ""
	if c.reader.Configured() {
		model = c.reader.Model
	}
	writeJSON(w, map[string]interface{}{"configured": c.reader.Configured(), "model": model})
}

// Read handles POST /purchases/invoice (supervisor session): one image in, suggestions out,
// nothing written. 503 OCR_NOT_CONFIGURED without a key, 422 for something that is not a
// readable image, 502 OCR_FAILED when the vision service fails, 429 while two reads are running.
func (c *InvoiceController) Read(w http.ResponseWriter, r *http.Request) {
	if !c.reader.Configured() {
		writeAPIError(w, r, http.StatusServiceUnavailable, "OCR_NOT_CONFIGURED", "set NVIDIA_API_KEY for the API to read invoices")
		return
	}
	var req InvoiceReadRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	data := req.ImageBase64
	if i := strings.Index(data, ","); strings.HasPrefix(data, "data:") && i > 0 {
		data = data[i+1:]
	}
	image, err := base64.StdEncoding.DecodeString(strings.TrimSpace(data))
	if err != nil || len(image) == 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fieldErrors{"image_base64": "base64 image required"})
		return
	}
	if len(image) > maxInvoiceImageBytes {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fieldErrors{"image_base64": "at most 768 KB; send a smaller photo"})
		return
	}
	mime := http.DetectContentType(image)
	if mime != "image/jpeg" && mime != "image/png" && mime != "image/webp" {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fieldErrors{"image_base64": "JPEG, PNG or WebP only"})
		return
	}
	if !c.reader.acquire() {
		w.Header().Set("Retry-After", "5")
		writeAPIError(w, r, http.StatusTooManyRequests, "RATE_LIMITED", "two invoices are being read; try again in a few seconds")
		return
	}
	defer c.reader.release()
	// the model can take longer than the server's default write timeout
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(visionTimeout + 10*time.Second))
	began := time.Now()
	ex, err := c.reader.Read(r.Context(), image, mime)
	latency := time.Since(began).Milliseconds()
	var ve visionError
	if errors.As(err, &ve) {
		log.Printf("[WARN] [OCR] %s (%d bytes, %d ms)", ve.Error(), len(image), latency)
		status := http.StatusBadGateway
		if ve.code == "OCR_UNREADABLE" {
			status = http.StatusUnprocessableEntity
		}
		writeAPIError(w, r, status, ve.code, ve.detail)
		return
	}
	if err != nil {
		writeAPIError(w, r, http.StatusBadGateway, "OCR_FAILED", nil)
		return
	}
	out, err := suggestReceipt(r.Context(), c.db, ex)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	out.Model, out.LatencyMs = c.reader.Model, latency
	log.Printf("[INFO] [OCR] invoice read by %s: %d lines, %d bytes, %d ms (operator %s)", c.reader.Model, len(out.Lines), len(image), latency, OperatorFrom(r.Context()))
	writeJSON(w, out)
}

// suggestReceipt matches the extraction to the suppliers and products a receipt may use.
func suggestReceipt(ctx context.Context, q sqlRunner, ex *invoiceExtraction) (*InvoiceSuggestionDto, error) {
	suppliers, err := loadMasterNames(ctx, q, `SELECT code, name, '' FROM supplier WHERE supplier_type IN ('RAW_MATERIAL','LIVESTOCK') ORDER BY code`)
	if err != nil {
		return nil, err
	}
	products, err := loadMasterNames(ctx, q, `SELECT sku, name, CONCAT(uom_code, '|', COALESCE(shelf_life_days, ''))
		FROM product WHERE product_type IN ('RAW_MATERIAL','CARCASS') AND is_active ORDER BY sku`)
	if err != nil {
		return nil, err
	}
	str := func(p *string) string {
		if p == nil {
			return ""
		}
		return strings.TrimSpace(*p)
	}
	out := &InvoiceSuggestionDto{SupplierName: str(ex.Supplier), InvoiceNo: str(ex.InvoiceNumber), InvoiceDate: isoDate(ex.InvoiceDate),
		SupplierMatch: "NONE", Lines: []InvoiceSuggestionLine{}}
	if out.SupplierName != "" {
		m, level := matchName(out.SupplierName, suppliers)
		out.SupplierCode, out.SupplierMatch = m.Code, level
	}
	for _, it := range ex.Items {
		l := InvoiceSuggestionLine{ProductName: strings.TrimSpace(it.ProductName), Qty: parseAmount(it.Quantity), Unit: unitCode(it.Unit),
			UnitPrice: parseAmount(it.UnitCost), Currency: strings.ToUpper(strings.TrimSpace(it.Currency)),
			SupplierLotNo: clip(strings.TrimSpace(it.LotNumber), 50), ExpiryDate: isoDate(it.ExpiryDate), Warnings: []string{}}
		m, level := matchName(l.ProductName, products)
		l.SKU, l.SKUName, l.Match = m.Code, m.Name, level
		uom, shelfLife, _ := strings.Cut(m.Extra, "|")
		switch {
		case level == "NONE":
			l.Warnings = append(l.Warnings, "NO_PRODUCT_MATCH")
		case l.Unit != "" && l.Unit != uom:
			l.Warnings = append(l.Warnings, "UNIT_DIFFERS")
		}
		if l.Qty == nil || *l.Qty <= 0 {
			l.Qty = nil
			l.Warnings = append(l.Warnings, "QTY_MISSING")
		}
		if l.UnitPrice == nil || *l.UnitPrice < 0 {
			l.UnitPrice = nil
			l.Warnings = append(l.Warnings, "PRICE_MISSING")
		}
		if l.Currency != "" && l.Currency != "EUR" && l.Currency != "€" {
			l.Warnings = append(l.Warnings, "NOT_EUR")
		}
		if level != "NONE" && shelfLife != "" && l.ExpiryDate == "" {
			l.Warnings = append(l.Warnings, "EXPIRY_MISSING")
		}
		out.Lines = append(out.Lines, l)
	}
	return out, nil
}

func loadMasterNames(ctx context.Context, q sqlRunner, query string) ([]masterName, error) {
	rows, err := q.QueryContext(ctx, query)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []masterName{}
	for rows.Next() {
		var m masterName
		if err := rows.Scan(&m.Code, &m.Name, &m.Extra); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Code < out[j].Code })
	return out, rows.Err()
}
