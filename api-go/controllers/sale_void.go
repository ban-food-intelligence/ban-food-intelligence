package controllers

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

// SaleVoidController corrects a wrong POS sale without deleting anything and without a table of
// its own: the sale and its SALE movements stay in the append-only ledger, each line gets a
// RETURN (+) movement back into the warehouse it was sold from (ref_type 'sale_void', ref_id =
// sale id), and the void is written to the append-only audit_log (SALE_VOID) with who did it and
// why. Both are written in one transaction; the RETURN movements mark the sale as voided, so a
// sale is voided once.
type SaleVoidController struct {
	db *sql.DB
}

func NewSaleVoidController(db *sql.DB) *SaleVoidController {
	return &SaleVoidController{db: db}
}

type SaleVoidRequest struct {
	SaleUUID string `json:"sale_uuid"`
	Reason   string `json:"reason"`
}

type SaleVoidLineDto struct {
	LineNo    int     `json:"line_no"`
	LotNo     string  `json:"lot_no"`
	Warehouse string  `json:"warehouse"`
	Qty       float64 `json:"qty"`
	Balance   float64 `json:"balance_after"`
}

type SaleVoidDto struct {
	SaleUUID string            `json:"sale_uuid"`
	Reason   string            `json:"reason"`
	VoidedBy string            `json:"voided_by"`
	VoidedAt time.Time         `json:"voided_at"`
	Lines    []SaleVoidLineDto `json:"lines"`
}

// maxVoidReason keeps the movement note "void of POS sale <uuid>: <reason>" within
// stock_movement.note (255 characters); the audit_log row keeps the reason as typed.
const maxVoidReason = 200

// Void handles POST /sales/void {sale_uuid, reason}.
//
//	201 voided; stock is back in the store
//	404 NOT_FOUND   unknown sale_uuid
//	409 CONFLICT    the sale was already voided (or has no lines)
//	422 INVALID_INPUT  sale_uuid or a reason (3-200 characters) missing
func (c *SaleVoidController) Void(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req SaleVoidRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	req.SaleUUID = strings.TrimSpace(req.SaleUUID)
	req.Reason = strings.TrimSpace(req.Reason)
	if n := len([]rune(req.Reason)); req.SaleUUID == "" || n < 3 || n > maxVoidReason {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT",
			map[string]string{"sale_uuid": "required", "reason": "required, 3-200 characters"})
		return
	}
	ctx := r.Context()
	actor := nonEmpty(OperatorFrom(ctx), "api")

	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback()

	// The sale row lock serialises two voids of the same sale.
	var saleID int64
	if err := tx.QueryRowContext(ctx, `SELECT id FROM sale WHERE sale_uuid = ? FOR UPDATE`, req.SaleUUID).Scan(&saleID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeAPIError(w, r, http.StatusNotFound, "NOT_FOUND", map[string]string{"sale_uuid": req.SaleUUID})
			return
		}
		writeDBError(w, r, err)
		return
	}
	// An earlier void left its RETURN movements in the ledger (a locking read sees the latest commit).
	var voided int
	if err := tx.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM stock_movement WHERE ref_type = 'sale_void' AND ref_id = ? FOR UPDATE`, saleID).Scan(&voided); err != nil {
		writeDBError(w, r, err)
		return
	}
	if voided > 0 {
		writeAPIError(w, r, http.StatusConflict, "CONFLICT", map[string]string{"sale_uuid": "already voided"})
		return
	}

	rows, err := tx.QueryContext(ctx,
		`SELECT sl.line_no, sl.lot_id, l.lot_no, sm.warehouse_id, wh.code, sl.qty, sm.unit_cost
		   FROM sale_line sl
		   JOIN stock_movement sm ON sm.id = sl.stock_movement_id
		   JOIN lot l             ON l.id = sl.lot_id
		   JOIN warehouse wh      ON wh.id = sm.warehouse_id
		  WHERE sl.sale_id = ? ORDER BY sl.line_no`, saleID)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	type line struct {
		no          int
		lot, wh     int64
		lotNo, code string
		qty, cost   float64
	}
	var lines []line
	for rows.Next() {
		var l line
		if err := rows.Scan(&l.no, &l.lot, &l.lotNo, &l.wh, &l.code, &l.qty, &l.cost); err != nil {
			rows.Close()
			writeDBError(w, r, err)
			return
		}
		lines = append(lines, l)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		writeDBError(w, r, err)
		return
	}
	if len(lines) == 0 {
		writeAPIError(w, r, http.StatusConflict, "CONFLICT", map[string]string{"sale_uuid": "no lines to void"})
		return
	}

	out := SaleVoidDto{SaleUUID: req.SaleUUID, Reason: req.Reason, VoidedBy: actor, Lines: []SaleVoidLineDto{}}
	for _, l := range lines {
		if err := insertMovement(ctx, tx, l.lot, l.wh, "RETURN", l.qty, l.cost, "sale_void", &saleID,
			"void of POS sale "+req.SaleUUID+": "+req.Reason); err != nil {
			writeDBError(w, r, err)
			return
		}
		bal := balances(ctx, tx, l.lot, l.code)[l.code]
		out.Lines = append(out.Lines, SaleVoidLineDto{LineNo: l.no, LotNo: l.lotNo, Warehouse: l.code, Qty: l.qty, Balance: bal})
	}
	newValue, _ := json.Marshal(map[string]interface{}{"sale_uuid": req.SaleUUID, "reason": req.Reason, "lines": out.Lines})
	res, err := tx.ExecContext(ctx,
		`INSERT INTO audit_log (table_name, row_id, action, old_value, new_value, changed_by) VALUES ('sale', ?, 'SALE_VOID', NULL, ?, ?)`,
		saleID, string(newValue), actor)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	auditID, err := res.LastInsertId()
	if err == nil {
		err = tx.QueryRowContext(ctx, `SELECT changed_at FROM audit_log WHERE id = ?`, auditID).Scan(&out.VoidedAt)
	}
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	if err := tx.Commit(); err != nil {
		writeDBError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(out)
}
