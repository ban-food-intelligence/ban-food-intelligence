package controllers

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"testing"

	"ban-food-intelligence/api-go/models"
)

// Reliable stock under load: ten tills sell the same lot at the same moment, but the shop
// holds only three packs. trg_sm_before_insert locks the lot's balance row (SELECT ... FOR
// UPDATE), so the sales queue up: exactly three are booked, the other seven are refused with
// INSUFFICIENT_STOCK, and the shop balance ends at 0, never below.
func TestIntegrationConcurrentSalesNeverOversell(t *testing.T) {
	db := testDB(t)
	defer db.Close()
	run := runSuffix()
	lot := "CNC-" + run
	shipmentLot(t, db, lot, 5, -1, 30)
	code, body := call(t, NewMovementsController(db).Transfer, "POST", "/movements/transfer",
		TransferRequest{LotNo: lot, FromWarehouse: "ANA-DEPO", ToWarehouse: "MAGAZA-01", Qty: 3})
	if code != http.StatusCreated {
		t.Fatalf("transfer to the shop: %d %s", code, body)
	}

	const tills, inShop = 10, 3
	ctrl := NewSyncController(db)
	reqs := make([]*http.Request, tills)
	for i := range reqs {
		pos := newTestPOS(t, fmt.Sprintf("POS-CNC-%s-%02d", run, i))
		reqs[i] = signedSale(t, pos, lot, 1, testUUIDv7())
	}

	statuses := make([]int, tills)
	answers := make([]models.SyncBatchResponse, tills)
	start := make(chan struct{})
	var wg sync.WaitGroup
	for i := range reqs {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			rec := httptestRecorder(ctrl, reqs[i])
			statuses[i] = rec.Code
			_ = json.Unmarshal(rec.Body.Bytes(), &answers[i])
		}(i)
	}
	close(start) // all tills press "sell" together
	wg.Wait()

	sold, refused := 0, 0
	for i, a := range answers {
		switch {
		case statuses[i] == http.StatusOK && len(a.AcknowledgedIDs) == 1:
			sold++
		case statuses[i] == http.StatusOK && len(a.Rejected) == 1 && a.Rejected[0].Code == "INSUFFICIENT_STOCK":
			refused++
		default:
			t.Errorf("till %d: unexpected answer %d %+v", i, statuses[i], a)
		}
	}
	if sold != inShop || refused != tills-inShop {
		t.Fatalf("%d packs in the shop, %d tills: want %d sales and %d refusals, got %d and %d",
			inShop, tills, inShop, tills-inShop, sold, refused)
	}

	var shop float64
	var movements int
	if err := db.QueryRow(`SELECT sb.qty FROM stock_balance sb JOIN lot l ON l.id = sb.lot_id
		JOIN warehouse w ON w.id = sb.warehouse_id WHERE l.lot_no = ? AND w.code = 'MAGAZA-01'`, lot).Scan(&shop); err != nil {
		t.Fatal(err)
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM stock_movement sm JOIN lot l ON l.id = sm.lot_id
		WHERE l.lot_no = ? AND sm.movement_type = 'SALE'`, lot).Scan(&movements); err != nil {
		t.Fatal(err)
	}
	if shop != 0 || movements != inShop {
		t.Fatalf("after the rush: shop balance %.3f (want 0), SALE movements %d (want %d)", shop, movements, inShop)
	}
}
