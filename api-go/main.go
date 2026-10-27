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

// apiVersion is reported by GET /health.
const apiVersion = "1.2.0"

const defaultMySQLDSN = "root:ban@tcp(127.0.0.1:3306)/food_intel?parseTime=true&multiStatements=true&charset=utf8mb4"

func logInfo(component, msg string, args ...interface{}) {
	log.Printf("[INFO] [%s] "+msg, append([]interface{}{component}, args...)...)
}

func logWarn(component, msg string, args ...interface{}) {
	log.Printf("[WARN] [%s] "+msg, append([]interface{}{component}, args...)...)
}

func logError(component, msg string, args ...interface{}) {
	log.Printf("[ERROR] [%s] "+msg, append([]interface{}{component}, args...)...)
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.LUTC)
	logInfo("MAIN", "Starting BAN Food Intelligence AVATAR ERP Go Backend...")
	logInfo("MYSQL", "Initializing MySQL 8.0 connection pool with startup retry policy...")

	dsn := os.Getenv("MYSQL_DSN")
	if dsn == "" {
		dsn = defaultMySQLDSN
	}

	db, err := initMySQLPool(dsn)
	if err != nil {
		logWarn("MYSQL", "MySQL initial ping exhausted retries (%v) — pool remains active for on-demand reconnect.", err)
	} else {
		logInfo("MYSQL", "MySQL 8.0 connection pool verified against schema 'food_intel'.")
		ensureStoredProcedureAliases(db)
	}
	defer func() {
		if db != nil {
			_ = db.Close()
		}
	}()

	logInfo("SECURITY", "Enforcing ECDSA P-256 (SHA256withECDSA) synchronization contract...")
	productionCtrl := controllers.NewProductionController(db)
	butcheryCtrl := controllers.NewButcheryController(db)
	recallCtrl := controllers.NewRecallController(db)
	syncCtrl := controllers.NewSyncController(db)
	traceCtrl := controllers.NewTraceController(db)
	lotCtrl := controllers.NewLotController(db)
	yieldCtrl := controllers.NewYieldController(db)
	reportsCtrl := controllers.NewReportsController(db)

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
			"version":        apiVersion,
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

	// Reports: purchases, lot movement ledger, POS sales
	mux.HandleFunc("/purchases", reportsCtrl.Purchases)
	mux.HandleFunc("/movements", reportsCtrl.Movements)
	mux.HandleFunc("/sales", reportsCtrl.Sales)

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

	logInfo("HTTP", "Go HTTP Server listening on http://localhost:%s", port)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		logError("HTTP", "Server fatal error: %v", err)
		os.Exit(1)
	}
}

func initMySQLPool(dsn string) (*sql.DB, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		logError("MYSQL", "Failed to initialize driver handle: %v", err)
		return nil, err
	}
	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(10)
	db.SetConnMaxLifetime(5 * time.Minute)
	db.SetConnMaxIdleTime(2 * time.Minute)

	const maxRetries = 5
	const retryDelay = 2 * time.Second

	var lastErr error
	for attempt := 1; attempt <= maxRetries; attempt++ {
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		lastErr = db.PingContext(ctx)
		cancel()
		if lastErr == nil {
			logInfo("MYSQL", "Database connection established on attempt %d/%d", attempt, maxRetries)
			return db, nil
		}
		logWarn("MYSQL", "Database ping attempt %d/%d failed: %v (retrying in %s)", attempt, maxRetries, lastErr, retryDelay)
		if attempt < maxRetries {
			time.Sleep(retryDelay)
		}
	}

	logError("MYSQL", "Database connection failed after %d retries: %v", maxRetries, lastErr)
	return db, fmt.Errorf("mysql unreachable after %d retries: %w", maxRetries, lastErr)
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
			logWarn("MYSQL-SP", "Stored procedure alias setup note: %v", err)
		}
	}
	logInfo("MYSQL-SP", "Verified MySQL Stored Procedures: sp_calculate_production_cost, sp_execute_butchery, sp_recall_by_lot.")
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
		logInfo("HTTP", "%s %s (%s)", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
	})
}
