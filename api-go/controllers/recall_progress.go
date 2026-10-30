package controllers

import (
	"context"
	"database/sql"
	"encoding/json"
	"math"
	"net/http"
	"sort"
	"strings"
	"time"
)

type RecallProgressLine struct {
	Tier            int        `json:"tier"`
	CustomerCode    string     `json:"customer_code"`
	CustomerName    string     `json:"customer_name"`
	LotNo           string     `json:"lot_no"`
	Uom             string     `json:"uom_code"`
	Received        float64    `json:"qty_received"`
	Forwarded       float64    `json:"qty_forwarded"`
	ToRecover       float64    `json:"qty_to_recover"`
	Returned        float64    `json:"qty_returned"`
	Pending         float64    `json:"qty_pending"`
	QuantityWarning bool       `json:"quantity_warning"`
	ContactStatus   string     `json:"contact_status"`
	Note            string     `json:"note"`
	UpdatedBy       string     `json:"updated_by"`
	UpdatedAt       *time.Time `json:"updated_at,omitempty"`
}

type RecallProgressTotal struct {
	Uom       string  `json:"uom_code"`
	ToRecover float64 `json:"qty_to_recover"`
	Returned  float64 `json:"qty_returned"`
	Pending   float64 `json:"qty_pending"`
}

type RecallProgressDto struct {
	RecallCode   string                `json:"recall_code"`
	Status       string                `json:"status"`
	Lines        []RecallProgressLine  `json:"lines"`
	Totals       []RecallProgressTotal `json:"totals"`
	AllRecovered bool                  `json:"all_recovered"`
}

// Each recipient is responsible for receipts minus recorded onward shipments. That way a
// 30-pack delivery followed by a 10-pack tier-2 shipment is 20 + 10 to recover, never 40.
func loadRecallProgress(ctx context.Context, q sqlRunner, code string) (*RecallProgressDto, error) {
	out := &RecallProgressDto{RecallCode: code, Lines: []RecallProgressLine{}, Totals: []RecallProgressTotal{}}
	var id int64
	if err := q.QueryRowContext(ctx, `SELECT id,status FROM recall WHERE recall_no=?`, code).Scan(&id, &out.Status); err != nil {
		return nil, err
	}
	rows, err := q.QueryContext(ctx, `WITH received AS (
        SELECT sh.customer_id,sl.lot_id,MIN(sh.distribution_tier) tier,SUM(sl.qty) qty
        FROM shipment_line sl JOIN shipment sh ON sh.id=sl.shipment_id
        JOIN recall_lot rl ON rl.lot_id=sl.lot_id AND rl.recall_id=?
        GROUP BY sh.customer_id,sl.lot_id
    ), onward AS (
        SELECT sh.from_customer_id customer_id,sl.lot_id,SUM(sl.qty) qty
        FROM shipment_line sl JOIN shipment sh ON sh.id=sl.shipment_id
        WHERE sh.from_customer_id IS NOT NULL GROUP BY sh.from_customer_id,sl.lot_id
    ), returned AS (
        SELECT sm.ref_id customer_id,sm.lot_id,SUM(sm.qty) qty FROM stock_movement sm
        WHERE sm.movement_type='RETURN' AND sm.ref_type='customer' GROUP BY sm.ref_id,sm.lot_id
    )
    SELECT r.tier,c.code,c.name,l.lot_no,l.uom_code,r.qty,COALESCE(o.qty,0),COALESCE(rt.qty,0),
           COALESCE(f.contact_status,'PENDING'),COALESCE(f.note,''),COALESCE(f.updated_by,''),f.updated_at
    FROM received r JOIN customer c ON c.id=r.customer_id JOIN lot l ON l.id=r.lot_id
    LEFT JOIN onward o ON o.customer_id=r.customer_id AND o.lot_id=r.lot_id
    LEFT JOIN returned rt ON rt.customer_id=r.customer_id AND rt.lot_id=r.lot_id
    LEFT JOIN recall_customer_followup f ON f.recall_id=? AND f.customer_id=r.customer_id
    ORDER BY r.tier,c.code,l.lot_no`, id, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	totals := map[string]*RecallProgressTotal{}
	out.AllRecovered = true
	for rows.Next() {
		var l RecallProgressLine
		var updated sql.NullTime
		if err := rows.Scan(&l.Tier, &l.CustomerCode, &l.CustomerName, &l.LotNo, &l.Uom, &l.Received, &l.Forwarded, &l.Returned,
			&l.ContactStatus, &l.Note, &l.UpdatedBy, &updated); err != nil {
			return nil, err
		}
		if updated.Valid {
			l.UpdatedAt = &updated.Time
		}
		l.ToRecover = round3(math.Max(0, l.Received-l.Forwarded))
		l.Pending = round3(math.Max(0, l.ToRecover-l.Returned))
		l.QuantityWarning = round3(l.Forwarded+l.Returned) > l.Received
		if l.Pending > 0 || l.QuantityWarning {
			out.AllRecovered = false
		}
		total := totals[l.Uom]
		if total == nil {
			total = &RecallProgressTotal{Uom: l.Uom}
			totals[l.Uom] = total
		}
		total.ToRecover += l.ToRecover
		total.Returned += l.Returned
		total.Pending += l.Pending
		out.Lines = append(out.Lines, l)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(out.Lines) == 0 {
		out.AllRecovered = false
	}
	for _, v := range totals {
		v.ToRecover = round3(v.ToRecover)
		v.Returned = round3(v.Returned)
		v.Pending = round3(v.Pending)
		out.Totals = append(out.Totals, *v)
	}
	sort.Slice(out.Totals, func(i, j int) bool { return out.Totals[i].Uom < out.Totals[j].Uom })
	return out, nil
}

func (c *RecallController) Progress(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeAPIError(w, r, 405, "METHOD_NOT_ALLOWED", nil)
		return
	}
	code := r.URL.Query().Get("recall_code")
	if code == "" {
		writeAPIError(w, r, 422, "INVALID_INPUT", fieldErrors{"recall_code": "required"})
		return
	}
	result, err := loadRecallProgress(r.Context(), c.db, code)
	if err == sql.ErrNoRows {
		writeAPIError(w, r, 404, "NOT_FOUND", nil)
		return
	}
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	writeJSON(w, result)
}

type RecallFollowupRequest struct {
	RecallCode    string `json:"recall_code"`
	CustomerCode  string `json:"customer_code"`
	ContactStatus string `json:"contact_status"`
	Note          string `json:"note"`
}

// Contact confirmation never changes stock, recovery quantities or lot sale blocking.
func (c *RecallController) Followup(w http.ResponseWriter, r *http.Request) {
	var req RecallFollowupRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	req.Note = strings.TrimSpace(req.Note)
	fe := fieldErrors{}
	if req.RecallCode == "" {
		fe["recall_code"] = "required"
	}
	if req.CustomerCode == "" {
		fe["customer_code"] = "required"
	}
	if req.ContactStatus != "PENDING" && req.ContactStatus != "CONTACTED" && req.ContactStatus != "CONFIRMED" {
		fe["contact_status"] = "PENDING, CONTACTED or CONFIRMED"
	}
	if len([]rune(req.Note)) > 500 {
		fe["note"] = "max 500 characters"
	}
	if len(fe) > 0 {
		writeAPIError(w, r, 422, "INVALID_INPUT", fe)
		return
	}
	tx, err := c.db.BeginTx(r.Context(), nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback()
	var recallID, customerID int64
	var status string
	err = tx.QueryRowContext(r.Context(), `SELECT id,status FROM recall WHERE recall_no=? FOR UPDATE`, req.RecallCode).Scan(&recallID, &status)
	if err == sql.ErrNoRows {
		writeAPIError(w, r, 404, "NOT_FOUND", nil)
		return
	}
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	if status != "OPEN" {
		writeAPIError(w, r, 409, "CONFLICT", "recall is closed")
		return
	}
	err = tx.QueryRowContext(r.Context(), `SELECT c.id FROM customer c WHERE c.code=? AND EXISTS (
        SELECT 1 FROM shipment sh JOIN shipment_line sl ON sl.shipment_id=sh.id
        JOIN recall_lot rl ON rl.lot_id=sl.lot_id AND rl.recall_id=? WHERE sh.customer_id=c.id)`, req.CustomerCode, recallID).Scan(&customerID)
	if err == sql.ErrNoRows {
		writeAPIError(w, r, 404, "NOT_FOUND", "customer has no recorded affected delivery")
		return
	}
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	var previousStatus, previousNote string
	err = tx.QueryRowContext(r.Context(), `SELECT contact_status,note FROM recall_customer_followup WHERE recall_id=? AND customer_id=?`, recallID, customerID).Scan(&previousStatus, &previousNote)
	if err != nil && err != sql.ErrNoRows {
		writeDBError(w, r, err)
		return
	}
	if err == sql.ErrNoRows {
		previousStatus = "PENDING"
	}
	previous, _ := json.Marshal(map[string]string{"contact_status": previousStatus, "note": previousNote})
	actor := nonEmpty(OperatorFrom(r.Context()), "api")
	_, err = tx.ExecContext(r.Context(), `INSERT INTO recall_customer_followup(recall_id,customer_id,contact_status,note,updated_by)
        VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE contact_status=VALUES(contact_status),note=VALUES(note),updated_by=VALUES(updated_by),updated_at=CURRENT_TIMESTAMP(3)`,
		recallID, customerID, req.ContactStatus, req.Note, actor)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	current, _ := json.Marshal(req)
	_, err = tx.ExecContext(r.Context(), `INSERT INTO audit_log(table_name,row_id,action,old_value,new_value,changed_by)
        SELECT 'recall_customer_followup',id,'CONTACT_UPDATE',?,?,? FROM recall_customer_followup WHERE recall_id=? AND customer_id=?`,
		string(previous), string(current), actor, recallID, customerID)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	if err = tx.Commit(); err != nil {
		writeDBError(w, r, err)
		return
	}
	writeJSON(w, map[string]string{"status": "SAVED"})
}
