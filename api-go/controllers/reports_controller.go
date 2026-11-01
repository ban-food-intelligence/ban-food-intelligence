package controllers

import (
	"database/sql"
	"net/http"
	"time"
)

// ReportsController serves the read-only reports the task requires beyond
// production / butchery / recall: purchases, lot movements and POS sales.
type ReportsController struct {
	db *sql.DB
}

func NewReportsController(db *sql.DB) *ReportsController {
	return &ReportsController{db: db}
}

type PurchaseReportLineDto struct {
	ReceiptNo    string  `json:"receipt_no"`
	ReceiptDate  string  `json:"receipt_date"`
	SupplierCode string  `json:"supplier_code"`
	SupplierName string  `json:"supplier_name"`
	Warehouse    string  `json:"warehouse_code"`
	InvoiceNo    string  `json:"invoice_no"`
	LotNo        string  `json:"lot_no"`
	SupplierLot  string  `json:"supplier_lot_no"`
	SKU          string  `json:"sku"`
	ProductName  string  `json:"product_name"`
	Qty          float64 `json:"qty"`
	UomCode      string  `json:"uom_code"`
	UnitPrice    float64 `json:"unit_price"`
	LineTotal    float64 `json:"line_total"`
	ExpiryDate   string  `json:"expiry_date"`
}

type MovementReportLineDto struct {
	MovedAt      time.Time `json:"moved_at"`
	LotNo        string    `json:"lot_no"`
	ProductName  string    `json:"product_name"`
	Warehouse    string    `json:"warehouse_code"`
	MovementType string    `json:"movement_type"`
	Qty          float64   `json:"qty"`
	UomCode      string    `json:"uom_code"`
	UnitCost     float64   `json:"unit_cost"`
	BalanceAfter float64   `json:"balance_after"`
	RefType      string    `json:"ref_type"`
	RefID        int64     `json:"ref_id"`
	Note         string    `json:"note"`
}

type SaleReportLineDto struct {
	SaleUUID    string    `json:"sale_uuid"`
	SoldAt      time.Time `json:"sold_at"`
	DeviceID    string    `json:"device_id"`
	Store       string    `json:"store_code"`
	LineNo      int       `json:"line_no"`
	LotNo       string    `json:"lot_no"`
	ProductName string    `json:"product_name"`
	Qty         float64   `json:"qty"`
	UomCode     string    `json:"uom_code"`
	UnitPrice   float64   `json:"unit_price"`
	LineTotal   float64   `json:"line_total"`
	Cost        float64   `json:"cost"`
	GrossProfit float64   `json:"gross_profit"`
	Voided      bool      `json:"voided"`                // POST /sales/void: stock went back, keep the line for the history
	VoidReason  string    `json:"void_reason,omitempty"` // why it was voided
}

// Purchases handles GET /purchases: every purchase receipt line with supplier, lot and value.
func (c *ReportsController) Purchases(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	rows, err := c.db.QueryContext(r.Context(),
		`SELECT pr.receipt_no, DATE_FORMAT(pr.receipt_date, '%Y-%m-%d'), s.code, s.name, w.code,
		        COALESCE(pr.invoice_no, ''), l.lot_no, COALESCE(l.supplier_lot_no, ''), p.sku, p.name,
		        prl.qty, l.uom_code, prl.unit_price, prl.line_total,
		        COALESCE(DATE_FORMAT(l.expiry_date, '%Y-%m-%d'), '')
		 FROM purchase_receipt_line prl
		 JOIN purchase_receipt pr ON pr.id = prl.receipt_id
		 JOIN supplier s  ON s.id = pr.supplier_id
		 JOIN warehouse w ON w.id = pr.warehouse_id
		 JOIN lot l       ON l.id = prl.lot_id
		 JOIN product p   ON p.id = prl.product_id
		 ORDER BY pr.receipt_date, pr.receipt_no, l.lot_no`)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "purchase report query failed: "+err.Error())
		return
	}
	defer rows.Close()

	list := []PurchaseReportLineDto{}
	for rows.Next() {
		var d PurchaseReportLineDto
		if err := rows.Scan(&d.ReceiptNo, &d.ReceiptDate, &d.SupplierCode, &d.SupplierName, &d.Warehouse,
			&d.InvoiceNo, &d.LotNo, &d.SupplierLot, &d.SKU, &d.ProductName,
			&d.Qty, &d.UomCode, &d.UnitPrice, &d.LineTotal, &d.ExpiryDate); err != nil {
			writeJSONError(w, http.StatusInternalServerError, "purchase report scan failed: "+err.Error())
			return
		}
		list = append(list, d)
	}
	writeJSON(w, list)
}

// Movements handles GET /movements[?lot_no=]: the append-only stock ledger with the
// running balance per lot and warehouse after each movement.
func (c *ReportsController) Movements(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	lotNo := r.URL.Query().Get("lot_no")
	rows, err := c.db.QueryContext(r.Context(),
		`SELECT moved_at, lot_no, product_name, warehouse_code, movement_type, qty, uom_code, unit_cost,
		        balance_after, ref_type, ref_id, note
		 FROM (
		   SELECT sm.id, sm.moved_at, l.lot_no, p.name AS product_name, w.code AS warehouse_code,
		          sm.movement_type, sm.qty, l.uom_code, sm.unit_cost,
		          SUM(sm.qty) OVER (PARTITION BY sm.lot_id, sm.warehouse_id ORDER BY sm.moved_at, sm.id) AS balance_after,
		          COALESCE(sm.ref_type, '') AS ref_type, COALESCE(sm.ref_id, 0) AS ref_id, COALESCE(sm.note, '') AS note
		   FROM stock_movement sm
		   JOIN lot l       ON l.id = sm.lot_id
		   JOIN product p   ON p.id = l.product_id
		   JOIN warehouse w ON w.id = sm.warehouse_id
		 ) m
		 WHERE ? = '' OR lot_no = ?
		 ORDER BY moved_at, id`, lotNo, lotNo)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "movement report query failed: "+err.Error())
		return
	}
	defer rows.Close()

	list := []MovementReportLineDto{}
	for rows.Next() {
		var d MovementReportLineDto
		if err := rows.Scan(&d.MovedAt, &d.LotNo, &d.ProductName, &d.Warehouse, &d.MovementType, &d.Qty,
			&d.UomCode, &d.UnitCost, &d.BalanceAfter, &d.RefType, &d.RefID, &d.Note); err != nil {
			writeJSONError(w, http.StatusInternalServerError, "movement report scan failed: "+err.Error())
			return
		}
		list = append(list, d)
	}
	if lotNo != "" && len(list) == 0 {
		writeJSONError(w, http.StatusNotFound, "no movements for lot "+lotNo)
		return
	}
	writeJSON(w, list)
}

// Sales handles GET /sales: POS sale lines with revenue, cost (lot unit cost) and gross profit.
func (c *ReportsController) Sales(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	rows, err := c.db.QueryContext(r.Context(), salesReportQuery(true))
	if err != nil && missingTable(err, "sale_void") {
		// A database created before migrations/20261101_sale_void.sql has no voids yet: keep the
		// report working (every line not voided) instead of failing the Sales and Overview pages.
		rows, err = c.db.QueryContext(r.Context(), salesReportQuery(false))
	}
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "sales report query failed: "+err.Error())
		return
	}
	defer rows.Close()

	list := []SaleReportLineDto{}
	for rows.Next() {
		var d SaleReportLineDto
		if err := rows.Scan(&d.SaleUUID, &d.SoldAt, &d.DeviceID, &d.Store, &d.LineNo, &d.LotNo, &d.ProductName,
			&d.Qty, &d.UomCode, &d.UnitPrice, &d.LineTotal, &d.Cost, &d.GrossProfit, &d.Voided, &d.VoidReason); err != nil {
			writeJSONError(w, http.StatusInternalServerError, "sales report scan failed: "+err.Error())
			return
		}
		list = append(list, d)
	}
	writeJSON(w, list)
}

// salesReportQuery is the GET /sales query; withVoids=false leaves out sale_void (older databases).
func salesReportQuery(withVoids bool) string {
	void, join := "FALSE, ''", ""
	if withVoids {
		void, join = "sv.sale_id IS NOT NULL, COALESCE(sv.reason, '')", "LEFT JOIN sale_void sv ON sv.sale_id = s.id"
	}
	return `SELECT s.sale_uuid, s.sold_at, s.device_id, w.code, sl.line_no, l.lot_no, p.name,
	        sl.qty, l.uom_code, sl.unit_price, sl.line_total,
	        ROUND(sl.qty * l.unit_cost, 2),
	        ROUND(sl.line_total - sl.qty * l.unit_cost, 2),
	        ` + void + `
	 FROM sale s
	 JOIN sale_line sl ON sl.sale_id = s.id
	 ` + join + `
	 JOIN lot l        ON l.id = sl.lot_id
	 JOIN product p    ON p.id = l.product_id
	 JOIN warehouse w  ON w.id = s.warehouse_id
	 ORDER BY s.sold_at, s.id, sl.line_no`
}
