package controllers

import (
	"database/sql"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"
)

type RecallTriggerRequest struct {
	RecallCode     string `json:"recall_code"`      // e.g. "RCL-2026-001"
	TriggerLot     string `json:"trigger_lot"`      // e.g. "ET-2026-001"
	Severity       string `json:"severity"`         // "CLASS_I"
	Reason         string `json:"reason"`           // e.g. "Salmonella / Quality Issue"
	Initiator      string `json:"initiator"`        // e.g. "kalite_muduru"
	BroadcastToPOS bool   `json:"broadcast_to_pos"` // Legacy field; POS currently learns recalls by polling.
	// DryRun previews the recall: the real procedure and report run inside a transaction that
	// is rolled back, so the preview equals what saving would do and nothing is written.
	DryRun bool `json:"dry_run"`
}

// RecallStockDto is stock of an affected lot that the recall blocks, per warehouse.
type RecallStockDto struct {
	LotNo     string  `json:"lot_no"`
	Product   string  `json:"product_name"`
	Warehouse string  `json:"warehouse_code"`
	Qty       float64 `json:"qty"`
	Uom       string  `json:"uom_code"`
}

type RecallMarketLineDto struct {
	Tier          int    `json:"tier"`
	CustomerCode  string `json:"customer_code"`
	CustomerName  string `json:"customer_name"`
	ContactPerson string `json:"contact_person"`
	Phone         string `json:"phone"`
	Email         string `json:"email"`
	ReceivedVia   string `json:"received_via,omitempty"`
	Address       string `json:"address"`
	// SecondTier: RECORDED when this customer's onward shipments are recorded (they are listed
	// as tier-2 rows), NOT_RECORDED otherwise (spec: show tier 2 only when recorded).
	SecondTier  string  `json:"second_tier"`
	Lots        string  `json:"lots"`
	QtyShipped  float64 `json:"qty_shipped"`
	QtyReturned float64 `json:"qty_returned"`
}

type RecallEventDto struct {
	RecallCode      string                `json:"recall_code"`
	Status          string                `json:"status"`
	Severity        string                `json:"severity"`
	Reason          string                `json:"reason"`
	TriggerLot      string                `json:"trigger_lot"`
	AffectedLots    []string              `json:"affected_lots"`
	TargetMarkets   []string              `json:"target_markets"`
	AffectedMarkets []RecallMarketLineDto `json:"affected_markets"`
	BlockedStock    []RecallStockDto      `json:"blocked_stock"` // stock of affected lots still in our warehouses
	LockEnforced    bool                  `json:"lock_enforced"`
	InitiatedBy     string                `json:"initiated_by"`
	Timestamp       time.Time             `json:"timestamp"`
}

type RecallController struct {
	db *sql.DB
}

func NewRecallController(db *sql.DB) *RecallController {
	return &RecallController{db: db}
}

// TriggerRecall handles POST /recall
// Calls sp_recall_initiate; the idempotent report wrapper cannot distinguish duplicate POSTs.
func (c *RecallController) TriggerRecall(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		c.GetActiveRecalls(w, r)
		return
	}
	if r.Method != http.MethodPost {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", nil)
		return
	}

	var req RecallTriggerRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeAPIError(w, r, http.StatusBadRequest, "INVALID_INPUT", "invalid request payload")
		return
	}
	req.TriggerLot = strings.TrimSpace(req.TriggerLot)
	fe := fieldErrors{}
	if req.TriggerLot == "" || len(req.TriggerLot) > 30 {
		fe["trigger_lot"] = "required, max 30 characters"
	}
	if len(req.RecallCode) > 30 {
		fe["recall_code"] = "max 30 characters"
	}
	if len(req.Reason) > 500 {
		fe["reason"] = "max 500 characters"
	}
	if req.Severity != "" && req.Severity != "CLASS_I" {
		fe["severity"] = "currently CLASS_I only"
	}
	if len(fe) > 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fe)
		return
	}
	if req.RecallCode == "" {
		req.RecallCode = fmt.Sprintf("RCL-%d", time.Now().UnixNano())
	}
	if req.Reason == "" {
		req.Reason = "Kalite kontrol / mikrobiyolojik uygunsuzluk tespiti"
	}
	if u := OperatorFrom(r.Context()); u != "" {
		req.Initiator = u // the authenticated supervisor, not a free-text field
	}
	if req.Initiator == "" {
		req.Initiator = "kalite_muduru"
	}

	if req.DryRun {
		c.previewRecall(w, r, req)
		return
	}

	// Insert once. A duplicate code is a conflict, including under concurrent requests.
	_, err := c.db.ExecContext(
		r.Context(),
		`CALL sp_recall_initiate(?, ?, ?, ?)`,
		req.RecallCode, req.TriggerLot, req.Reason, req.Initiator,
	)
	if err != nil {
		log.Printf("[WARN] [MYSQL-RECALL] sp_recall_initiate warning: %v", err)
		writeDBError(w, r, err)
		return
	}
	log.Printf("[WARN] [RECALL] Emergency recall triggered for lot=%s (code=%s)", req.TriggerLot, req.RecallCode)

	event, err := c.loadRecallEventFromDB(r, c.db, req.RecallCode, req.TriggerLot)
	if err != nil {
		writeDBError(w, r, err)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":         "LOCKDOWN_ACTIVATED",
		"message":        "Recall recorded and affected lots blocked. POS terminals refresh recalls by polling.",
		"recall_summary": event,
	})
}

// GetActiveRecalls handles GET /recall
func (c *RecallController) GetActiveRecalls(w http.ResponseWriter, r *http.Request) {
	rows, err := c.db.QueryContext(
		r.Context(),
		`SELECT rc.recall_no, rc.status, rc.severity, rc.reason, l.lot_no, rc.initiated_by, rc.initiated_at
		 FROM recall rc
		 JOIN lot l ON l.id = rc.root_lot_id
		 ORDER BY rc.initiated_at DESC`,
	)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer rows.Close()

	type recallRef struct{ code, lot string }
	var refs []recallRef
	for rows.Next() {
		var rcNo, status, severity, reason, rootLot, initiatedBy string
		var initiatedAt time.Time
		if err := rows.Scan(&rcNo, &status, &severity, &reason, &rootLot, &initiatedBy, &initiatedAt); err != nil {
			writeDBError(w, r, err)
			return
		}
		refs = append(refs, recallRef{rcNo, rootLot})
	}
	if err := rows.Err(); err != nil {
		writeDBError(w, r, err)
		return
	}
	rows.Close() // details queries must not wait for a connection held by the outer rows
	events := []RecallEventDto{}
	for _, ref := range refs {
		ev, err := c.loadRecallEventFromDB(r, c.db, ref.code, ref.lot)
		if err != nil {
			writeDBError(w, r, err)
			return
		}
		events = append(events, ev)
	}
	writeJSON(w, events)
}

// previewRecall answers POST /recall {"dry_run": true}: 200 {"status":"DRY_RUN","recall_summary"}
// with the lots that would be blocked, the customers to call and the stock to pull. The
// recall is applied inside a transaction that is always rolled back.
func (c *RecallController) previewRecall(w http.ResponseWriter, r *http.Request, req RecallTriggerRequest) {
	ctx := r.Context()
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback() // a preview never commits
	if _, err := tx.ExecContext(ctx, `CALL sp_recall_apply(?, ?, ?, ?)`,
		req.RecallCode, req.TriggerLot, req.Reason, req.Initiator); err != nil {
		writeDBError(w, r, err)
		return
	}
	ev, err := c.loadRecallEventFromDB(r, tx, req.RecallCode, req.TriggerLot)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	writeJSON(w, map[string]interface{}{
		"status":         "DRY_RUN",
		"message":        "Preview only: nothing was blocked or recorded.",
		"recall_summary": ev,
	})
}

func (c *RecallController) loadRecallEventFromDB(r *http.Request, q sqlRunner, recallNo string, fallbackLot string) (RecallEventDto, error) {
	var ev RecallEventDto
	ev.RecallCode = recallNo
	ev.TriggerLot = fallbackLot

	// lock_enforced comes from the DB: the recall is OPEN and every lot in its
	// recall_lot set is still RECALLED (so trg_sm_before_insert rejects SALE/SHIPMENT).
	err := q.QueryRowContext(
		r.Context(),
		`SELECT rc.recall_no, rc.status, rc.severity, rc.reason, l.lot_no, rc.initiated_by, rc.initiated_at,
		        (rc.status = 'OPEN' AND NOT EXISTS (
		           SELECT 1 FROM recall_lot rl JOIN lot rlt ON rlt.id = rl.lot_id
		            WHERE rl.recall_id = rc.id AND rlt.status <> 'RECALLED')) AS lock_enforced
		 FROM recall rc
		 JOIN lot l ON l.id = rc.root_lot_id
		 WHERE rc.recall_no = ?`,
		recallNo,
	).Scan(&ev.RecallCode, &ev.Status, &ev.Severity, &ev.Reason, &ev.TriggerLot, &ev.InitiatedBy, &ev.Timestamp, &ev.LockEnforced)
	if err != nil {
		return ev, err
	}

	// Affected lots from recall_lot
	lotRows, err := q.QueryContext(
		r.Context(),
		`SELECT l.lot_no
		 FROM recall_lot rl
		 JOIN recall rc ON rc.id = rl.recall_id
		 JOIN lot l ON l.id = rl.lot_id
		 WHERE rc.recall_no = ?
		 ORDER BY rl.depth ASC, l.lot_no ASC`,
		recallNo,
	)
	if err != nil {
		return ev, err
	}
	defer lotRows.Close()
	for lotRows.Next() {
		var ln string
		if err := lotRows.Scan(&ln); err != nil {
			return ev, err
		}
		ev.AffectedLots = append(ev.AffectedLots, ln)
	}
	if err := lotRows.Err(); err != nil {
		return ev, err
	}
	lotRows.Close()

	// Execute `CALL sp_recall_report(?)` to get Market A/B/C quantities & contacts
	repRows, err := q.QueryContext(r.Context(), `CALL sp_recall_report(?)`, recallNo)
	if err != nil {
		return ev, err
	}
	defer repRows.Close()
	for repRows.Next() {
		var m RecallMarketLineDto
		var contact, phone, email, via, address sql.NullString
		var onward bool
		if err := repRows.Scan(
			&m.Tier, &m.CustomerCode, &m.CustomerName,
			&contact, &phone, &email, &via, &address, &onward,
			&m.Lots, &m.QtyShipped, &m.QtyReturned,
		); err != nil {
			return ev, err
		}
		m.ContactPerson = contact.String
		m.Phone = phone.String
		m.Email = email.String
		m.ReceivedVia = via.String
		m.Address = address.String
		m.SecondTier = "NOT_RECORDED"
		if onward {
			m.SecondTier = "RECORDED"
		}
		ev.AffectedMarkets = append(ev.AffectedMarkets, m)
		ev.TargetMarkets = append(ev.TargetMarkets, fmt.Sprintf("%s (%s) - %.0f PAKET", m.CustomerName, m.CustomerCode, m.QtyShipped))
	}
	if err := repRows.Err(); err != nil {
		return ev, err
	}
	repRows.Close()

	// Stock of the affected lots still in our warehouses: blocked by the recall, to be pulled.
	ev.BlockedStock = []RecallStockDto{}
	stockRows, err := q.QueryContext(r.Context(),
		`SELECT l.lot_no, p.name, w.code, sb.qty, l.uom_code
		 FROM recall_lot rl
		 JOIN recall rc ON rc.id = rl.recall_id
		 JOIN lot l ON l.id = rl.lot_id
		 JOIN product p ON p.id = l.product_id
		 JOIN stock_balance sb ON sb.lot_id = l.id AND sb.qty > 0
		 JOIN warehouse w ON w.id = sb.warehouse_id
		 WHERE rc.recall_no = ?
		 ORDER BY rl.depth, l.lot_no, w.code`, recallNo)
	if err != nil {
		return ev, err
	}
	defer stockRows.Close()
	for stockRows.Next() {
		var st RecallStockDto
		if err := stockRows.Scan(&st.LotNo, &st.Product, &st.Warehouse, &st.Qty, &st.Uom); err != nil {
			return ev, err
		}
		ev.BlockedStock = append(ev.BlockedStock, st)
	}
	return ev, stockRows.Err()
}

// ContactsCSV handles GET /recall/contacts.csv?recall_code=...: the customer contact list of
// a recall (tier, contact, phone, email, address, lots, quantities) for the call-down.
func (c *RecallController) ContactsCSV(w http.ResponseWriter, r *http.Request) {
	code := r.URL.Query().Get("recall_code")
	if code == "" {
		writeAPIError(w, r, http.StatusBadRequest, "INVALID_INPUT", "recall_code is required")
		return
	}
	ev, err := c.loadRecallEventFromDB(r, c.db, code, "")
	if err != nil {
		if err == sql.ErrNoRows {
			writeAPIError(w, r, http.StatusNotFound, "NOT_FOUND", "recall "+code)
		} else {
			writeDBError(w, r, err)
		}
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s-contacts.csv"`, code))
	cw := csv.NewWriter(w)
	_, _ = w.Write([]byte{0xEF, 0xBB, 0xBF}) // UTF-8 BOM so Excel shows Turkish characters
	_ = cw.Write([]string{"recall_code", "root_lot", "tier", "customer_code", "customer_name", "contact_person", "phone", "email", "address", "received_via", "lots", "qty_shipped", "qty_returned", "second_tier"})
	for _, m := range ev.AffectedMarkets {
		_ = cw.Write([]string{
			ev.RecallCode, ev.TriggerLot, fmt.Sprint(m.Tier), m.CustomerCode, m.CustomerName, m.ContactPerson,
			m.Phone, m.Email, m.Address, m.ReceivedVia, m.Lots,
			fmt.Sprintf("%.3f", m.QtyShipped), fmt.Sprintf("%.3f", m.QtyReturned), m.SecondTier,
		})
	}
	cw.Flush()
}
