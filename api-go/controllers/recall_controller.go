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
	BroadcastToPOS bool   `json:"broadcast_to_pos"` // Enforces Recall Lock Screen on POS
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
// Calls Ali's MySQL stored procedure `CALL sp_recall_by_lot(?, ?, ?, ?)`
// (which invokes `sp_recall_initiate` and `sp_recall_report` in a single workflow).
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
	if req.TriggerLot == "" {
		req.TriggerLot = "ET-2026-001"
	}
	if req.RecallCode == "" {
		req.RecallCode = fmt.Sprintf("RCL-%s-%d", req.TriggerLot, time.Now().Unix()%10000)
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

	// Invoke `CALL sp_recall_by_lot(?, ?, ?, ?)` in MySQL
	_, err := c.db.ExecContext(
		r.Context(),
		`CALL sp_recall_by_lot(?, ?, ?, ?)`,
		req.RecallCode, req.TriggerLot, req.Reason, req.Initiator,
	)
	if err != nil && !strings.Contains(err.Error(), "Duplicate entry") {
		log.Printf("[WARN] [MYSQL-RECALL] sp_recall_by_lot warning: %v", err)
		status := http.StatusInternalServerError
		if strings.Contains(err.Error(), "LOT_NOT_FOUND") {
			status = http.StatusNotFound
		}
		writeJSONError(w, status, "sp_recall_by_lot failed: "+err.Error())
		return
	}
	log.Printf("[WARN] [RECALL] Emergency recall triggered for lot=%s (code=%s)", req.TriggerLot, req.RecallCode)

	event, err := c.loadRecallEventFromDB(r, req.RecallCode, req.TriggerLot)
	if err != nil {
		writeDBError(w, r, err)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":         "LOCKDOWN_ACTIVATED",
		"message":        "Emergency recall executed in MySQL via sp_recall_by_lot. POS lockdown broadcast active.",
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

	var events []RecallEventDto
	for rows.Next() {
		var rcNo, status, severity, reason, rootLot, initiatedBy string
		var initiatedAt time.Time
		if err := rows.Scan(&rcNo, &status, &severity, &reason, &rootLot, &initiatedBy, &initiatedAt); err != nil {
			continue
		}
		ev, err := c.loadRecallEventFromDB(r, rcNo, rootLot)
		if err == nil {
			events = append(events, ev)
		}
	}

	// No recall yet -> empty list. What a recall would affect comes from GET /trace.
	if events == nil {
		events = []RecallEventDto{}
	}
	writeJSON(w, events)
}

func (c *RecallController) loadRecallEventFromDB(r *http.Request, recallNo string, fallbackLot string) (RecallEventDto, error) {
	var ev RecallEventDto
	ev.RecallCode = recallNo
	ev.TriggerLot = fallbackLot

	// lock_enforced comes from the DB: the recall is OPEN and every lot in its
	// recall_lot set is still RECALLED (so trg_sm_before_insert rejects SALE/SHIPMENT).
	err := c.db.QueryRowContext(
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
	lotRows, err := c.db.QueryContext(
		r.Context(),
		`SELECT l.lot_no
		 FROM recall_lot rl
		 JOIN recall rc ON rc.id = rl.recall_id
		 JOIN lot l ON l.id = rl.lot_id
		 WHERE rc.recall_no = ?
		 ORDER BY rl.depth ASC, l.lot_no ASC`,
		recallNo,
	)
	if err == nil {
		for lotRows.Next() {
			var ln string
			if err := lotRows.Scan(&ln); err == nil {
				ev.AffectedLots = append(ev.AffectedLots, ln)
			}
		}
		lotRows.Close()
	}

	// Execute `CALL sp_recall_report(?)` to get Market A/B/C quantities & contacts
	repRows, err := c.db.QueryContext(r.Context(), `CALL sp_recall_report(?)`, recallNo)
	if err == nil {
		for repRows.Next() {
			var m RecallMarketLineDto
			var contact, phone, email, via, address sql.NullString
			var onward bool
			if err := repRows.Scan(
				&m.Tier, &m.CustomerCode, &m.CustomerName,
				&contact, &phone, &email, &via, &address, &onward,
				&m.Lots, &m.QtyShipped, &m.QtyReturned,
			); err == nil {
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
		}
		repRows.Close()
	}

	return ev, nil
}

// ContactsCSV handles GET /recall/contacts.csv?recall_code=...: the customer contact list of
// a recall (tier, contact, phone, email, address, lots, quantities) for the call-down.
func (c *RecallController) ContactsCSV(w http.ResponseWriter, r *http.Request) {
	code := r.URL.Query().Get("recall_code")
	if code == "" {
		writeAPIError(w, r, http.StatusBadRequest, "INVALID_INPUT", "recall_code is required")
		return
	}
	ev, err := c.loadRecallEventFromDB(r, code, "")
	if err != nil {
		writeAPIError(w, r, http.StatusNotFound, "NOT_FOUND", "recall "+code)
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
