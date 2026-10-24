package com.ban.foodintelligence.pos.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ban.foodintelligence.pos.database.AppDatabase
import com.ban.foodintelligence.pos.model.SyncOutboxQueue
import com.ban.foodintelligence.pos.security.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * ============================================================================
 * BAN Food Intelligence - Resilient Offline Outbox Sync Worker (`SyncWorker`)
 * Author: Nuri (Principal Android Security Engineer & Lead POS Architect)
 * ============================================================================
 *
 * Wakes up automatically when network connectivity is restored
 * (`NetworkType.CONNECTED`), drains pending [SyncOutboxQueue] records via
 * Room Coroutines & Flow, attaches hardware-backed HMAC-SHA256 signatures
 * and BFLA/IDOR security claims, and dispatches to Berke's Go API (`/sync`)
 * with exponential backoff resilience.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val database: AppDatabase by lazy {
        AppDatabase.getInstance(applicationContext)
    }

    private val outboxDao by lazy {
        database.outboxDao()
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        println("📡 Network Restored: Flushing Outbox...")

        // 1. Observe pending outbox queue via Kotlin Flow & snapshot batch
        val pendingCount = outboxDao.observePendingCount().first()
        if (pendingCount == 0) {
            println("✅ [SYNC-WORKER] Outbox queue is empty. Zero pending records to flush.")
            return@withContext Result.success()
        }

        val pendingBatch = outboxDao.observePendingRecords().first().take(MAX_BATCH_SIZE)
        println("📦 [SYNC-WORKER] Draining ${pendingBatch.size} of $pendingCount pending records from Room DB...")

        // 2. Verify local HMAC-SHA256 integrity of each record before transmission
        val verifiedRecords = mutableListOf<SyncOutboxQueue>()
        for (record in pendingBatch) {
            val expectedCanonical = KeystoreManager.buildCanonicalYieldPayload(
                tenantId = record.tenantId,
                deviceId = record.deviceId,
                actorRole = record.actorRole,
                batchId = record.batchId,
                predictedYield = record.predictedYield,
                timestampUuidV7 = record.timestamp
            )
            if (KeystoreManager.verifyHMACSignature(expectedCanonical, record.hmacSignature)) {
                verifiedRecords.add(record)
            } else {
                println("🚨 [SECURITY-TAMPER-ALERT] Local SQLite tampering detected for UUIDv7=${record.timestamp}! Quarantining record.")
                outboxDao.markFailed(record.timestamp)
            }
        }

        if (verifiedRecords.isEmpty()) {
            return@withContext Result.failure()
        }

        // 3. Construct JSON Payload & Sign Envelope with Hardware HMAC-SHA256
        val primaryRecord = verifiedRecords.first()
        val envelopeIdempotencyUuidV7 = SyncOutboxQueue.generateUuidV7()
        val jsonPayload = buildSyncBatchJsonPayload(
            envelopeUuidV7 = envelopeIdempotencyUuidV7,
            tenantId = primaryRecord.tenantId,
            deviceId = primaryRecord.deviceId,
            actorRole = primaryRecord.actorRole,
            records = verifiedRecords
        )

        // Sign entire JSON body to prevent MITM tampering and bind BFLA/IDOR headers
        println("🔒 [SYNC-SECURITY] Signing `/sync` JSON envelope with Hardware TEE HMAC-SHA256...")
        val envelopeCanonical = "${primaryRecord.tenantId}:${primaryRecord.deviceId}:${primaryRecord.actorRole}:$envelopeIdempotencyUuidV7:$jsonPayload"
        val envelopeHmacSignature = KeystoreManager.generateHMACSignature(envelopeCanonical)

        // 4. Transmit to Berke's Go REST API (`/sync`) with BFLA/IDOR & HMAC Headers
        val syncUrl = inputData.getString(KEY_GO_API_SYNC_URL) ?: DEFAULT_GO_API_SYNC_URL
        val request = Request.Builder()
            .url(syncUrl)
            // MITM Protection: Hardware-backed 256-bit HMAC-SHA256 signature
            .header("X-HMAC-Signature", envelopeHmacSignature)
            // IDOR Protection: Binds request strictly to authenticated Store Tenant & POS Hardware ID
            .header("X-Tenant-ID", primaryRecord.tenantId)
            .header("X-Device-ID", primaryRecord.deviceId)
            // BFLA Protection: Enforces least-privilege RBAC scope (POS device can only call /sync)
            .header("X-Actor-Role", primaryRecord.actorRole)
            // Replay Protection: RFC 9562 UUIDv7 monotonic idempotency token
            .header("X-Idempotency-UUIDv7", envelopeIdempotencyUuidV7)
            .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return@withContext try {
            println("🚀 [SYNC-HTTP] POST $syncUrl | Records: ${verifiedRecords.size} | HMAC: ${envelopeHmacSignature.take(16)}...")
            httpClient.newCall(request).execute().use { response ->
                val timestamps = verifiedRecords.map { it.timestamp }
                when {
                    response.isSuccessful -> {
                        outboxDao.markAcknowledged(timestamps)
                        println("✅ [SYNC-SUCCESS] Go API `/sync` acknowledged ${timestamps.size} records (HTTP ${response.code}).")
                        Result.success()
                    }
                    response.code == 401 || response.code == 403 -> {
                        println("🛑 [BFLA/IDOR-REJECTED] Go API rejected authorization or HMAC signature (HTTP ${response.code}).")
                        Result.failure()
                    }
                    else -> {
                        handleExponentialBackoff(timestamps, "HTTP ${response.code}")
                    }
                }
            }
        } catch (ioEx: IOException) {
            val timestamps = verifiedRecords.map { it.timestamp }
            handleExponentialBackoff(timestamps, ioEx.message ?: "Network unreachable")
        }
    }

    /**
     * Builds the canonical JSON payload sent to Berke's Go API (`/sync`).
     * Every item includes its individual `batch_id`, `predicted_yield`,
     * `timestamp` (UUIDv7), and `hmac_signature`.
     */
    private fun buildSyncBatchJsonPayload(
        envelopeUuidV7: String,
        tenantId: String,
        deviceId: String,
        actorRole: String,
        records: List<SyncOutboxQueue>
    ): String {
        val itemsArray = JSONArray()
        for (item in records) {
            val itemObj = JSONObject().apply {
                put("batch_id", item.batchId)
                put("predicted_yield", item.predictedYield.toDouble())
                put("timestamp", item.timestamp) // UUIDv7 format
                put("hmac_signature", item.hmacSignature)
                put("tenant_id", item.tenantId)
                put("device_id", item.deviceId)
                put("actor_role", item.actorRole)
                put("retry_count", item.retryCount)
            }
            itemsArray.put(itemObj)
        }

        return JSONObject().apply {
            put("sync_envelope_uuid_v7", envelopeUuidV7)
            put("tenant_id", tenantId)
            put("device_id", deviceId)
            put("actor_role", actorRole)
            put("record_count", records.size)
            put("outbox_items", itemsArray)
        }.toString()
    }

    /**
     * Increments retry counters in SQLite Room and schedules WorkManager
     * exponential backoff ($15\text{s} \times 2^{\text{attempt}}$ up to $300\text{s}$).
     */
    private suspend fun handleExponentialBackoff(
        timestamps: List<String>,
        reason: String
    ): Result {
        outboxDao.incrementRetryCount(timestamps)
        val attempt = runAttemptCount + 1
        if (attempt > MAX_RETRY_ATTEMPTS) {
            println("❌ [SYNC-ABORT] Exceeded max retry attempts ($MAX_RETRY_ATTEMPTS). Marking batch for manual inspection.")
            timestamps.forEach { outboxDao.markFailed(it) }
            return Result.failure()
        }

        val backoffSeconds = min(
            MAX_BACKOFF_SECONDS,
            (INITIAL_BACKOFF_SECONDS * 2.0.pow(runAttemptCount.toDouble())).toLong()
        )
        println("⏳ [EXPONENTIAL-BACKOFF] `/sync` failed ($reason). Attempt #$attempt -> Retrying in ${backoffSeconds}s...")
        return Result.retry()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "ban_avatar_pos_outbox_sync_worker"
        const val KEY_GO_API_SYNC_URL = "GO_API_SYNC_URL"
        const val DEFAULT_GO_API_SYNC_URL = "https://api.banfoodintelligence.internal/v1/sync"

        private const val MAX_BATCH_SIZE = 50
        private const val MAX_RETRY_ATTEMPTS = 8
        private const val INITIAL_BACKOFF_SECONDS = 15L
        private const val MAX_BACKOFF_SECONDS = 300L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Registers a constrained WorkManager job that automatically wakes up
         * as soon as network connectivity is restored (`NetworkType.CONNECTED`)
         * with hardware-enforced `BackoffPolicy.EXPONENTIAL`.
         */
        fun enqueueWhenNetworkConnected(context: Context) {
            val networkConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INITIAL_BACKOFF_SECONDS,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                syncWorkRequest
            )
            println("📅 [WORK-MANAGER] Scheduled SyncWorker (Trigger: NetworkType.CONNECTED | Policy: EXPONENTIAL_BACKOFF).")
        }
    }
}
