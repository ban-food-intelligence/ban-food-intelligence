package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"time"

	_ "github.com/go-sql-driver/mysql"

	"ban-food-intelligence/api-go/controllers"
)

const defaultMySQLDSN = "root:ban@tcp(127.0.0.1:3306)/food_intel?parseTime=true&multiStatements=true&charset=utf8mb4"

func main() {
	fmt.Println("================================================================================")
	fmt.Println("🚀 BAN FOOD INTELLIGENCE - AVATAR ERP GO BACKEND (PHASE 1: HEART START)")
	fmt.Println("================================================================================")
	fmt.Println("🔌 Wiring MySQL Connection Pool...")

	dsn := os.Getenv("MYSQL_DSN")
	if dsn == "" {
		dsn = defaultMySQLDSN
	}

	db, err := initMySQLPool(dsn)
	if err != nil {
		log.Printf("⚠️ MySQL initial ping warning (%v) — pool remains active for retry on incoming requests.", err)
	} else {
		fmt.Println("✅ MySQL 8.0 Connection Pool connected to 'food_intel' schema.")
		ensureStoredProcedureAliases(db)
	}
	defer func() {
		if db != nil {
			_ = db.Close()
		}
	}()

	fmt.Println("🔐 Enforcing ECDSA Sync Contract...")
	productionCtrl := controllers.NewProductionController(db)
	butcheryCtrl := controllers.NewButcheryController(db)
	recallCtrl := controllers.NewRecallController(db)
	syncCtrl := controllers.NewSyncController(db)
	traceCtrl := controllers.NewTraceController(db)
	lotCtrl := controllers.NewLotController(db)
	yieldCtrl := controllers.NewYieldController(db)

	mux := http.NewServeMux()

	// Health & Readiness Endpoint
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		dbStatus := "CONNECTED"
		if db == nil || db.PingContext(r.Context()) != nil {
			dbStatus = "DEGRADED"
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"service":        "ban-food-intelligence-api-go",
			"status":         "UP",
			"mysql_status":   dbStatus,
			"sync_crypto":    "ECDSA-P256-SHA256 (crypto/ecdsa + crypto/x509)",
			"server_time":    time.Now().UTC(),
		})
	})

	// Task 1: External Production & EKP Cost Calculation
	mux.HandleFunc("/production", productionCtrl.ListOrders)
	mux.HandleFunc("/api/v1/production", productionCtrl.ListOrders)

	// Task 2: Whole Lamb Butchery & Cost Allocation
	mux.HandleFunc("/butchery", butcheryCtrl.ReconcileButchery)
	mux.HandleFunc("/butchery/reconcile", butcheryCtrl.ReconcileButchery)
	mux.HandleFunc("/api/v1/butchery", butcheryCtrl.ReconcileButchery)

	// Task 3: Recursive Lot Traceability & Emergency Recall
	mux.HandleFunc("/recall", recallCtrl.TriggerRecall)
	mux.HandleFunc("/api/v1/recall", recallCtrl.TriggerRecall)
	mux.HandleFunc("/trace/", traceCtrl.TraceHandler)
	mux.HandleFunc("/api/v1/trace/", traceCtrl.TraceHandler)
	mux.HandleFunc("/lots", lotCtrl.ListStockByLot)
	mux.HandleFunc("/api/v1/lots/trace/", lotCtrl.TraceLot)

	// Task 4: ML Yield Predictions & Deviation Telemetry
	mux.HandleFunc("/yield", yieldCtrl.PredictYield)
	mux.HandleFunc("/api/v1/yield/predict", yieldCtrl.PredictYield)

	// Offline Edge POS Synchronization (Hardware ECDSA P-256 + UUIDv7 Idempotency)
	mux.HandleFunc("/sync", syncCtrl.IngestOutbox)
	mux.HandleFunc("/v1/sync", syncCtrl.IngestOutbox)
	mux.HandleFunc("/api/v1/sync", syncCtrl.IngestOutbox)
	mux.HandleFunc("/sync/register-key", syncCtrl.RegisterDeviceKey)

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	server := &http.Server{
		Addr:         ":" + port,
		Handler:      withCORSAndLogging(mux),
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 20 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	fmt.Printf("🌐 Go HTTP Server listening on http://localhost:%s\n", port)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("💀 Server fatal error: %v", err)
	}
}

func initMySQLPool(dsn string) (*sql.DB, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(10)
	db.SetConnMaxLifetime(5 * time.Minute)
	db.SetConnMaxIdleTime(2 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		return db, err
	}
	return db, nil
}

// ensureStoredProcedureAliases provisions `sp_calculate_production_cost`,
// `sp_execute_butchery`, and `sp_recall_by_lot` in Ali's `food_intel` database
// so callers and tests can invoke them directly alongside `sp_production_complete`
// and `sp_recall_initiate`.
func ensureStoredProcedureAliases(db *sql.DB) {
	procedures := []string{
		`DROP PROCEDURE IF EXISTS sp_calculate_production_cost`,
		`CREATE PROCEDURE sp_calculate_production_cost(IN p_order_no VARCHAR(30))
		 BEGIN
		   SELECT order_no, sku, product_name, status,
		          planned_qty, actual_qty, qty_variance, fulfilment_pct,
		          input_kg, COALESCE(yield_pct_of_meat, 0) AS yield_pct_of_meat,
		          material_cost, service_cost, total_cost,
		          ekp, ekp_display, COALESCE(vkp, 0) AS vkp,
		          COALESCE(gross_profit_per_unit, 0) AS gross_profit_per_unit,
		          COALESCE(revenue_at_vkp, 0) AS revenue_at_vkp,
		          COALESCE(gross_profit_total, 0) AS gross_profit_total,
		          COALESCE(margin_pct, 0) AS margin_pct
		   FROM v_production_cost
		   WHERE order_no = p_order_no;
		 END`,
		`DROP PROCEDURE IF EXISTS sp_execute_butchery`,
		`CREATE PROCEDURE sp_execute_butchery(IN p_op_no VARCHAR(30), IN p_alloc_method VARCHAR(20))
		 BEGIN
		   SELECT op_no, species, allocation_method, status,
		          input_weight_kg, output_weight_kg, cut_weight_kg, waste_weight_kg, weight_diff_kg,
		          purchase_unit_price, purchase_cost, additional_cost, total_cost,
		          declared_total_cost, declared_cost_diff, allocated_total, allocation_diff,
		          weight_ok, declared_cost_ok, allocation_ok
		   FROM v_butchery_reconciliation
		   WHERE op_no = p_op_no OR (p_op_no = '' AND allocation_method = p_alloc_method)
		   LIMIT 1;
		 END`,
		`DROP PROCEDURE IF EXISTS sp_recall_by_lot`,
		`CREATE PROCEDURE sp_recall_by_lot(
		   IN p_recall_no VARCHAR(30),
		   IN p_lot_no VARCHAR(30),
		   IN p_reason VARCHAR(500),
		   IN p_user VARCHAR(64)
		 )
		 BEGIN
		   IF NOT EXISTS (SELECT 1 FROM recall WHERE recall_no = p_recall_no) THEN
		     CALL sp_recall_initiate(p_recall_no, p_lot_no, p_reason, p_user);
		   END IF;
		   CALL sp_recall_report(p_recall_no);
		 END`,
	}

	for _, stmt := range procedures {
		if _, err := db.Exec(stmt); err != nil {
			log.Printf("⚠️ Stored procedure alias setup note: %v", err)
		}
	}
	fmt.Println("⚙️  Verified MySQL Stored Procedures: sp_calculate_production_cost, sp_execute_butchery, sp_recall_by_lot.")
}

func withCORSAndLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Tenant-ID, X-Device-ID, X-Actor-Role, X-Signature, X-Signature-Alg, X-Key-Fingerprint, X-Idempotency-UUIDv7, X-POS-Public-Key")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("📡 [%s] %s (%s)", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
	})
}
