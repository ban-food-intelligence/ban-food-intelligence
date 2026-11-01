package controllers

// Recall drill (geri çağırma tatbikatı): a documented traceability test of one lot. Starting it
// traces the lot forward and backward and takes the mass balance as found, and lists every
// recorded customer to be reached. Nothing is blocked: lot status, stock and recall records stay
// as they are. The result is computed, never chosen: PASS when every quantity is explained by
// documents, every customer was reached and the drill closed within its target time; otherwise
// FAIL with the reasons, and a failed drill needs a corrective action (findings). Triggers keep
// the record immutable and refuse a PASS that misses any of the three conditions.

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
)

// defaultDrillTargetMinutes: 4 hours, a common target for a traceability test in food-safety audits.
const defaultDrillTargetMinutes = 240

const (
	drillUnexplained = "UNEXPLAINED_QTY"
	drillNotReached  = "CUSTOMER_NOT_REACHED"
	drillOverTime    = "OVER_TIME"
)

type RecallDrillRequest struct {
	DrillNo       string `json:"drill_no"` // default TAT-<UTC timestamp>
	LotNo         string `json:"lot_no"`
	TargetMinutes int    `json:"target_minutes"` // default 240, 1..10080 (one week)
	Note          string `json:"note"`
}

type RecallDrillContactRequest struct {
	DrillNo      string `json:"drill_no"`
	CustomerCode string `json:"customer_code"`
	Note         string `json:"note"`
}

type RecallDrillCloseRequest struct {
	DrillNo  string `json:"drill_no"`
	Findings string `json:"findings"` // corrective action; required when the drill fails
}

type DrillQtyDto struct {
	Qty float64 `json:"qty"`
	Uom string  `json:"uom_code"`
}

type RecallDrillContactDto struct {
	Tier           int           `json:"tier"`
	CustomerCode   string        `json:"customer_code"`
	CustomerName   string        `json:"customer_name"`
	ContactPerson  string        `json:"contact_person"`
	Phone          string        `json:"phone"`
	Email          string        `json:"email"`
	Lots           []string      `json:"lots"`
	Quantities     []DrillQtyDto `json:"quantities"` // shipped to this customer, per unit, as found at the start
	ReachedAt      *time.Time    `json:"reached_at,omitempty"`
	ReachedBy      string        `json:"reached_by,omitempty"`
	MinutesToReach *float64      `json:"minutes_to_reach,omitempty"`
	Note           string        `json:"note,omitempty"`
}

// drillSnapshot is the trace as found when the drill started.
type drillSnapshot struct {
	Forward  *TraceResponse `json:"forward"`
	Backward *TraceResponse `json:"backward"`
}

type RecallDrillDto struct {
	DrillNo        string                  `json:"drill_no"`
	LotNo          string                  `json:"lot_no"`
	TargetMinutes  int                     `json:"target_minutes"`
	Note           string                  `json:"note,omitempty"`
	StartedBy      string                  `json:"started_by"`
	StartedAt      time.Time               `json:"started_at"`
	TraceMs        int                     `json:"trace_ms"`
	LotsForward    int                     `json:"lots_forward"`
	LotsBackward   int                     `json:"lots_backward"`
	Suppliers      int                     `json:"suppliers"`
	Balanced       bool                    `json:"balanced"`
	MassBalance    []MassBalanceTotalDto   `json:"mass_balance_totals"`
	Contacts       []RecallDrillContactDto `json:"contacts"`
	Reached        int                     `json:"customers_reached"`
	Status         string                  `json:"status"`
	ElapsedSeconds float64                 `json:"elapsed_seconds"` // open: until now (server clock); closed: until closing
	ClosedBy       string                  `json:"closed_by,omitempty"`
	ClosedAt       *time.Time              `json:"closed_at,omitempty"`
	Result         string                  `json:"result,omitempty"`
	FailReasons    []string                `json:"fail_reasons"`
	Findings       string                  `json:"findings,omitempty"`
	Snapshot       *drillSnapshot          `json:"snapshot,omitempty"` // GET ?drill_no= only
}

// drillVerdict computes the result of a drill closed after elapsed.
func drillVerdict(balanced bool, unreached int, elapsed time.Duration, targetMinutes int) (string, []string) {
	reasons := []string{}
	if !balanced {
		reasons = append(reasons, drillUnexplained)
	}
	if unreached > 0 {
		reasons = append(reasons, drillNotReached)
	}
	if elapsed > time.Duration(targetMinutes)*time.Minute {
		reasons = append(reasons, drillOverTime)
	}
	if len(reasons) == 0 {
		return "PASS", reasons
	}
	return "FAIL", reasons
}

// drillCustomers: every customer of the forward trace's recorded shipments with its lowest tier.
func drillCustomers(fwd *TraceResponse) ([]string, map[string]int) {
	tiers := map[string]int{}
	var codes []string
	for _, s := range fwd.Shipments {
		if t, ok := tiers[s.CustomerCode]; !ok {
			tiers[s.CustomerCode] = s.Tier
			codes = append(codes, s.CustomerCode)
		} else if s.Tier < t {
			tiers[s.CustomerCode] = s.Tier
		}
	}
	sort.Slice(codes, func(i, j int) bool {
		if tiers[codes[i]] != tiers[codes[j]] {
			return tiers[codes[i]] < tiers[codes[j]]
		}
		return codes[i] < codes[j]
	})
	return codes, tiers
}

func validateDrill(req *RecallDrillRequest) fieldErrors {
	fe := fieldErrors{}
	if req.LotNo == "" || len(req.LotNo) > 30 {
		fe["lot_no"] = "required, max 30 characters"
	}
	if len(req.DrillNo) > 30 {
		fe["drill_no"] = "max 30 characters"
	}
	if req.TargetMinutes < 1 || req.TargetMinutes > 10080 {
		fe["target_minutes"] = "1 to 10080 minutes"
	}
	if len([]rune(req.Note)) > 255 {
		fe["note"] = "max 255 characters"
	}
	return fe
}

// StartDrill handles POST /recall/drills (supervisor session): traces the lot both ways inside one
// transaction, stores the snapshot and the customers to reach, and returns the open drill (201).
func (c *RecallController) StartDrill(w http.ResponseWriter, r *http.Request) {
	var req RecallDrillRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	req.LotNo = strings.TrimSpace(req.LotNo)
	req.DrillNo = strings.TrimSpace(nonEmpty(req.DrillNo, "TAT-"+time.Now().UTC().Format("20060102-150405")))
	req.Note = strings.TrimSpace(req.Note)
	if req.TargetMinutes == 0 {
		req.TargetMinutes = defaultDrillTargetMinutes
	}
	if fe := validateDrill(&req); len(fe) > 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fe)
		return
	}
	ctx := r.Context()
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback()
	lot, _, err := lotID(ctx, tx, req.LotNo)
	var nf notFoundError
	if errors.As(err, &nf) {
		writeAPIError(w, r, http.StatusNotFound, "LOT_NOT_FOUND", "lot "+req.LotNo)
		return
	}
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	began := time.Now()
	fwd, err := traceLot(r, tx, req.LotNo, "forward")
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	bwd, err := traceLot(r, tx, req.LotNo, "backward")
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	traceMs := time.Since(began).Milliseconds()
	suppliers := map[string]bool{}
	for _, l := range bwd.Lots {
		if l.SupplierCode != "" {
			suppliers[l.SupplierCode] = true
		}
	}
	balanced := fwd.MassBalance == nil || fwd.MassBalance.Balanced
	snapshot, err := json.Marshal(drillSnapshot{Forward: fwd, Backward: bwd})
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	actor := nonEmpty(OperatorFrom(ctx), "api")
	res, err := tx.ExecContext(ctx,
		`INSERT INTO recall_drill (drill_no, root_lot_id, target_minutes, note, started_by, trace_ms,
		                           lots_forward, lots_backward, suppliers, balanced, snapshot)
		 VALUES (?, ?, ?, NULLIF(?, ''), ?, ?, ?, ?, ?, ?, ?)`,
		req.DrillNo, lot, req.TargetMinutes, req.Note, actor, traceMs, len(fwd.Lots), len(bwd.Lots), len(suppliers), balanced, string(snapshot))
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	drillID, _ := res.LastInsertId()
	codes, tiers := drillCustomers(fwd)
	for _, code := range codes {
		if _, err := tx.ExecContext(ctx, `INSERT INTO recall_drill_contact (drill_id, customer_id, tier) SELECT ?, id, ? FROM customer WHERE code = ?`,
			drillID, tiers[code], code); err != nil {
			writeDBError(w, r, err)
			return
		}
	}
	audit, _ := json.Marshal(map[string]interface{}{"drill_no": req.DrillNo, "lot_no": req.LotNo, "target_minutes": req.TargetMinutes,
		"balanced": balanced, "customers": codes})
	if err := drillAudit(ctx, tx, "recall_drill", drillID, "DRILL_START", audit, actor); err != nil {
		writeDBError(w, r, err)
		return
	}
	if err := tx.Commit(); err != nil {
		writeDBError(w, r, err)
		return
	}
	c.writeDrill(w, r, http.StatusCreated, req.DrillNo, false)
}

// DrillContact handles POST /recall/drills/contact (supervisor session): marks a customer of the
// drill reached, once, at the server time. 404 unknown drill or a customer not on its list, 409 a
// closed drill or a customer already reached.
func (c *RecallController) DrillContact(w http.ResponseWriter, r *http.Request) {
	var req RecallDrillContactRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	req.DrillNo, req.CustomerCode, req.Note = strings.TrimSpace(req.DrillNo), strings.TrimSpace(req.CustomerCode), strings.TrimSpace(req.Note)
	fe := fieldErrors{}
	if req.DrillNo == "" {
		fe["drill_no"] = "required"
	}
	if req.CustomerCode == "" {
		fe["customer_code"] = "required"
	}
	if len([]rune(req.Note)) > 255 {
		fe["note"] = "max 255 characters"
	}
	if len(fe) > 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fe)
		return
	}
	ctx := r.Context()
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback()
	drillID, ok := c.openDrill(w, r, tx, req.DrillNo)
	if !ok {
		return
	}
	var contactID int64
	var reached sql.NullTime
	err = tx.QueryRowContext(ctx, `SELECT dc.id, dc.reached_at FROM recall_drill_contact dc JOIN customer cu ON cu.id = dc.customer_id
		 WHERE dc.drill_id = ? AND cu.code = ? FOR UPDATE`, drillID, req.CustomerCode).Scan(&contactID, &reached)
	if err == sql.ErrNoRows {
		writeAPIError(w, r, http.StatusNotFound, "NOT_FOUND", "customer "+req.CustomerCode+" is not on this drill's list")
		return
	}
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	if reached.Valid {
		writeAPIError(w, r, http.StatusConflict, "CONFLICT", "customer "+req.CustomerCode+" was already reached")
		return
	}
	actor := nonEmpty(OperatorFrom(ctx), "api")
	if _, err := tx.ExecContext(ctx, `UPDATE recall_drill_contact SET reached_at = CURRENT_TIMESTAMP(3), reached_by = ?, note = NULLIF(?, '') WHERE id = ?`,
		actor, req.Note, contactID); err != nil {
		writeDBError(w, r, err)
		return
	}
	audit, _ := json.Marshal(req)
	if err := drillAudit(ctx, tx, "recall_drill_contact", contactID, "DRILL_CONTACT", audit, actor); err != nil {
		writeDBError(w, r, err)
		return
	}
	if err := tx.Commit(); err != nil {
		writeDBError(w, r, err)
		return
	}
	c.writeDrill(w, r, http.StatusOK, req.DrillNo, false)
}

// CloseDrill handles POST /recall/drills/close (supervisor session): computes the result at the
// server time and closes the drill for good. A failing drill without findings is 422 and stays open.
func (c *RecallController) CloseDrill(w http.ResponseWriter, r *http.Request) {
	var req RecallDrillCloseRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	req.DrillNo, req.Findings = strings.TrimSpace(req.DrillNo), strings.TrimSpace(req.Findings)
	fe := fieldErrors{}
	if req.DrillNo == "" {
		fe["drill_no"] = "required"
	}
	if len([]rune(req.Findings)) > 1000 {
		fe["findings"] = "max 1000 characters"
	}
	if len(fe) > 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fe)
		return
	}
	ctx := r.Context()
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback()
	drillID, ok := c.openDrill(w, r, tx, req.DrillNo)
	if !ok {
		return
	}
	var balanced bool
	var target, unreached int
	var elapsedSec float64
	if err := tx.QueryRowContext(ctx,
		`SELECT d.balanced, d.target_minutes, TIMESTAMPDIFF(MICROSECOND, d.started_at, CURRENT_TIMESTAMP(3)) / 1000000,
		        (SELECT COUNT(*) FROM recall_drill_contact dc WHERE dc.drill_id = d.id AND dc.reached_at IS NULL)
		 FROM recall_drill d WHERE d.id = ?`, drillID).Scan(&balanced, &target, &elapsedSec, &unreached); err != nil {
		writeDBError(w, r, err)
		return
	}
	result, reasons := drillVerdict(balanced, unreached, time.Duration(elapsedSec*float64(time.Second)), target)
	if result == "FAIL" && req.Findings == "" {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT",
			fieldErrors{"findings": "required for a failed drill (" + strings.Join(reasons, ", ") + ")"})
		return
	}
	actor := nonEmpty(OperatorFrom(ctx), "api")
	if _, err := tx.ExecContext(ctx,
		`UPDATE recall_drill SET status = 'CLOSED', closed_by = ?, closed_at = CURRENT_TIMESTAMP(3), result = ?,
		        fail_reasons = NULLIF(?, ''), findings = NULLIF(?, '') WHERE id = ?`,
		actor, result, strings.Join(reasons, ","), req.Findings, drillID); err != nil {
		writeDBError(w, r, err)
		return
	}
	audit, _ := json.Marshal(map[string]interface{}{"drill_no": req.DrillNo, "result": result, "fail_reasons": reasons, "findings": req.Findings})
	if err := drillAudit(ctx, tx, "recall_drill", drillID, "DRILL_CLOSE", audit, actor); err != nil {
		writeDBError(w, r, err)
		return
	}
	if err := tx.Commit(); err != nil {
		writeDBError(w, r, err)
		return
	}
	c.writeDrill(w, r, http.StatusOK, req.DrillNo, false)
}

// ListDrills handles GET /recall/drills (the latest 20) and GET /recall/drills?drill_no= (one, with
// the trace snapshot taken at its start).
func (c *RecallController) ListDrills(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeAPIError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", nil)
		return
	}
	if no := r.URL.Query().Get("drill_no"); no != "" {
		c.writeDrill(w, r, http.StatusOK, no, true)
		return
	}
	out, err := loadDrills(r.Context(), c.db, "", 20, false)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	writeJSON(w, out)
}

// openDrill locks an open drill: 404 unknown, 409 closed (the response is written then).
func (c *RecallController) openDrill(w http.ResponseWriter, r *http.Request, tx *sql.Tx, drillNo string) (int64, bool) {
	var id int64
	var status string
	err := tx.QueryRowContext(r.Context(), `SELECT id, status FROM recall_drill WHERE drill_no = ? FOR UPDATE`, drillNo).Scan(&id, &status)
	if err == sql.ErrNoRows {
		writeAPIError(w, r, http.StatusNotFound, "NOT_FOUND", "drill "+drillNo)
		return 0, false
	}
	if err != nil {
		writeDBError(w, r, err)
		return 0, false
	}
	if status != "OPEN" {
		writeAPIError(w, r, http.StatusConflict, "CONFLICT", "drill "+drillNo+" is closed")
		return 0, false
	}
	return id, true
}

func (c *RecallController) writeDrill(w http.ResponseWriter, r *http.Request, status int, drillNo string, withSnapshot bool) {
	drills, err := loadDrills(r.Context(), c.db, drillNo, 1, withSnapshot)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	if len(drills) == 0 {
		writeAPIError(w, r, http.StatusNotFound, "NOT_FOUND", "drill "+drillNo)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(drills[0])
}

func drillAudit(ctx context.Context, tx *sql.Tx, table string, rowID int64, action string, value []byte, actor string) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO audit_log (table_name, row_id, action, new_value, changed_by) VALUES (?, ?, ?, ?, ?)`,
		table, rowID, action, string(value), actor)
	return err
}

// loadDrills reads drills (one when drillNo is set, else the latest limit) with their customers.
// Lots and quantities per customer come from the snapshot, i.e. as found at the start.
func loadDrills(ctx context.Context, q sqlRunner, drillNo string, limit int, withSnapshot bool) ([]RecallDrillDto, error) {
	rows, err := q.QueryContext(ctx,
		`SELECT d.id, d.drill_no, l.lot_no, d.target_minutes, COALESCE(d.note, ''), d.started_by, d.started_at, d.trace_ms,
		        d.lots_forward, d.lots_backward, d.suppliers, d.balanced, d.snapshot, d.status, COALESCE(d.closed_by, ''), d.closed_at,
		        COALESCE(d.result, ''), COALESCE(d.fail_reasons, ''), COALESCE(d.findings, ''),
		        TIMESTAMPDIFF(MICROSECOND, d.started_at, COALESCE(d.closed_at, CURRENT_TIMESTAMP(3))) / 1000000
		 FROM recall_drill d JOIN lot l ON l.id = d.root_lot_id
		 WHERE ? = '' OR d.drill_no = ?
		 ORDER BY d.started_at DESC, d.id DESC LIMIT ?`, drillNo, drillNo, limit)
	if err != nil {
		return nil, err
	}
	out := []RecallDrillDto{}
	ids := []interface{}{}
	index := map[int64]int{}
	snaps := map[int64]*drillSnapshot{}
	for rows.Next() {
		var d RecallDrillDto
		var id int64
		var raw []byte
		var closed sql.NullTime
		var reasons string
		if err := rows.Scan(&id, &d.DrillNo, &d.LotNo, &d.TargetMinutes, &d.Note, &d.StartedBy, &d.StartedAt, &d.TraceMs,
			&d.LotsForward, &d.LotsBackward, &d.Suppliers, &d.Balanced, &raw, &d.Status, &d.ClosedBy, &closed,
			&d.Result, &reasons, &d.Findings, &d.ElapsedSeconds); err != nil {
			rows.Close()
			return nil, err
		}
		if closed.Valid {
			d.ClosedAt = &closed.Time
		}
		d.FailReasons = []string{}
		if reasons != "" {
			d.FailReasons = strings.Split(reasons, ",")
		}
		d.ElapsedSeconds = round(d.ElapsedSeconds, 3)
		var snap drillSnapshot
		if err := json.Unmarshal(raw, &snap); err != nil {
			rows.Close()
			return nil, fmt.Errorf("drill %s snapshot: %w", d.DrillNo, err)
		}
		d.MassBalance = []MassBalanceTotalDto{}
		if snap.Forward != nil && snap.Forward.MassBalance != nil {
			d.MassBalance = snap.Forward.MassBalance.Totals
		}
		if withSnapshot {
			d.Snapshot = &snap
		}
		d.Contacts = []RecallDrillContactDto{}
		snaps[id] = &snap
		index[id] = len(out)
		ids = append(ids, id)
		out = append(out, d)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return out, nil
	}
	crows, err := q.QueryContext(ctx,
		`SELECT dc.drill_id, dc.tier, cu.code, cu.name, COALESCE(cu.contact_person, ''), COALESCE(cu.phone, ''), COALESCE(cu.email, ''),
		        dc.reached_at, COALESCE(dc.reached_by, ''), COALESCE(dc.note, ''),
		        TIMESTAMPDIFF(MICROSECOND, d.started_at, dc.reached_at) / 60000000
		 FROM recall_drill_contact dc JOIN customer cu ON cu.id = dc.customer_id JOIN recall_drill d ON d.id = dc.drill_id
		 WHERE dc.drill_id IN (?`+strings.Repeat(",?", len(ids)-1)+`)
		 ORDER BY dc.drill_id, dc.tier, cu.code`, ids...)
	if err != nil {
		return nil, err
	}
	defer crows.Close()
	for crows.Next() {
		var id int64
		var ct RecallDrillContactDto
		var reached sql.NullTime
		var minutes sql.NullFloat64
		if err := crows.Scan(&id, &ct.Tier, &ct.CustomerCode, &ct.CustomerName, &ct.ContactPerson, &ct.Phone, &ct.Email,
			&reached, &ct.ReachedBy, &ct.Note, &minutes); err != nil {
			return nil, err
		}
		if reached.Valid {
			ct.ReachedAt = &reached.Time
			ct.MinutesToReach = ptr(round(minutes.Float64, 2))
		}
		ct.Lots, ct.Quantities = drillShipped(snaps[id], ct.CustomerCode)
		d := &out[index[id]]
		d.Contacts = append(d.Contacts, ct)
		if reached.Valid {
			d.Reached++
		}
	}
	return out, crows.Err()
}

// drillShipped: the lots and per-unit quantities the snapshot's shipments record for a customer.
func drillShipped(snap *drillSnapshot, customer string) ([]string, []DrillQtyDto) {
	lots, qty := []string{}, []DrillQtyDto{}
	if snap == nil || snap.Forward == nil {
		return lots, qty
	}
	seenLot, byUom := map[string]bool{}, map[string]int{}
	for _, s := range snap.Forward.Shipments {
		if s.CustomerCode != customer {
			continue
		}
		if !seenLot[s.LotNo] {
			seenLot[s.LotNo] = true
			lots = append(lots, s.LotNo)
		}
		i, ok := byUom[s.UomCode]
		if !ok {
			i = len(qty)
			byUom[s.UomCode] = i
			qty = append(qty, DrillQtyDto{Uom: s.UomCode})
		}
		qty[i].Qty = round3(qty[i].Qty + s.Qty)
	}
	sort.Strings(lots)
	return lots, qty
}
