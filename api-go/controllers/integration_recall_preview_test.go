package controllers

// Integration test (MYSQL_TEST_DSN): POST /recall with "dry_run": true shows what the recall
// would block and whom to call, from the real procedure and report, and writes nothing.

import (
	"encoding/json"
	"net/http"
	"testing"
)

func TestIntegrationRecallPreviewWritesNothing(t *testing.T) {
	db := testDB(t)
	c := NewRecallController(db)
	run := runSuffix()

	snapshot := func() (recalls, recallLots, recalled int, status string) {
		_ = db.QueryRow(`SELECT COUNT(*) FROM recall`).Scan(&recalls)
		_ = db.QueryRow(`SELECT COUNT(*) FROM recall_lot`).Scan(&recallLots)
		_ = db.QueryRow(`SELECT COUNT(*) FROM lot WHERE status = 'RECALLED'`).Scan(&recalled)
		_ = db.QueryRow(`SELECT status FROM lot WHERE lot_no = 'SUC-2026-045'`).Scan(&status)
		return
	}
	r0, rl0, rc0, st0 := snapshot()

	code, body := call(t, c.TriggerRecall, "POST", "/recall", RecallTriggerRequest{
		RecallCode: "RCL-PREVIEW-" + run, TriggerLot: "ET-2026-001", Reason: "preview test", DryRun: true,
	})
	var res struct {
		Status  string         `json:"status"`
		Summary RecallEventDto `json:"recall_summary"`
	}
	_ = json.Unmarshal(body, &res)
	if code != http.StatusOK || res.Status != "DRY_RUN" {
		t.Fatalf("preview: %d %s", code, body)
	}
	s := res.Summary
	has := func(lot string) bool {
		for _, l := range s.AffectedLots {
			if l == lot {
				return true
			}
		}
		return false
	}
	if !has("ET-2026-001") || !has("SUC-2026-045") {
		t.Fatalf("affected lots: %v", s.AffectedLots)
	}
	shipped := map[string]float64{}
	for _, m := range s.AffectedMarkets {
		if m.Tier == 1 {
			shipped[m.CustomerCode] += m.QtyShipped
		}
	}
	if shipped["MKT-A"] != 30 || shipped["MKT-B"] != 25 || shipped["MKT-C"] != 30 {
		t.Fatalf("markets A/B/C should be 30/25/30, got %v", shipped)
	}
	if s.BlockedStock == nil {
		t.Fatal("blocked_stock must be a list (possibly empty), not null")
	}

	// Nothing was written: no recall, no recall lots, no lot changed status.
	if r1, rl1, rc1, st1 := snapshot(); r1 != r0 || rl1 != rl0 || rc1 != rc0 || st1 != st0 {
		t.Fatalf("preview wrote data: recalls %d->%d, recall lots %d->%d, recalled lots %d->%d, SUC-2026-045 %s->%s",
			r0, r1, rl0, rl1, rc0, rc1, st0, st1)
	}

	// The same checks as saving: unknown lot is 404, an existing recall code 409.
	if code, body := call(t, c.TriggerRecall, "POST", "/recall", RecallTriggerRequest{TriggerLot: "NO-LOT-" + run, DryRun: true}); code != http.StatusNotFound {
		t.Fatalf("preview of unknown lot: want 404, got %d %s", code, body)
	}
	var existing string
	if err := db.QueryRow(`SELECT recall_no FROM recall LIMIT 1`).Scan(&existing); err == nil {
		if code, body := call(t, c.TriggerRecall, "POST", "/recall", RecallTriggerRequest{RecallCode: existing, TriggerLot: "ET-2026-001", DryRun: true}); code != http.StatusConflict {
			t.Fatalf("preview with an existing code: want 409, got %d %s", code, body)
		}
	}
}
