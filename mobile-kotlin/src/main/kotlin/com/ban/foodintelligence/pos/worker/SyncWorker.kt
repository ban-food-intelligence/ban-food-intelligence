package com.ban.foodintelligence.pos.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ban.foodintelligence.pos.database.AppDatabase
import com.ban.foodintelligence.pos.model.SyncOutboxQueue
import com.ban.foodintelligence.pos.security.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 * BAN Food Intelligence - Offline Outbox Sync Worker (`SyncWorker`)
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * Fixes vs. the previous version:
 * 1. DRAIN LOOP: sends batches of [BATCH_SIZE] until the backlog is empty (the
 *    old worker sent one batch of 50 and exited with `success`, stranding the
 *    rest). A per-run cap [MAX_BATCHES_PER_RUN] keeps one run inside
 *    WorkManager's execution window; if rows remain, the worker chains a
 *    continuation run.
 * 2. BOUNDED READS: each iteration reads `LIMIT [BATCH_SIZE]` rows from SQL.
 * 3. DETERMINISTIC IDEMPOTENCY KEY: `X-Idempotency-UUIDv7` is derived from the
 *    batch content, so a retry of the same batch carries the SAME key and the
 *    server can dedupe it (the old key was random per attempt).
 * 4. ASYMMETRIC SIGNATURES: the envelope is signed with the Keystore ECDSA
 *    P-256 key. The server verifies with the enrolled public key.
 *
 * Loop termination: every iteration either removes rows from PENDING (acked, or
 * quarantined as FAILED) or returns from the worker, so it cannot spin.
 *
 * SERVER CONTRACT (the Go `/sync` handler must match; it currently does not):
 * - Body: `{ sync_envelope_uuid_v7, tenant_id, device_id, actor_role,
 *   record_count, outbox_items:[{batch_id, predicted_yield, timestamp,
 *   signature, signature_alg, ...}] }`
 * - Optional response `{"acknowledged_ids":["<record timestamp>", ...]}`. Only
 *   those rows are marked synced; the rest are retried. If the field is absent,
 *   an HTTP 2xx acknowledges the whole batch.
 * - `X-Tenant-ID`, `X-Device-ID`, `X-Actor-Role` are CLAIMS used to look up the
 *   enrolled key. Authorization must come from the verified signature and an
 *   authenticated identity, never from these headers alone.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val outboxDao by lazy { AppDatabase.getInstance(applicationContext).outboxDao() }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private sealed class BatchOutcome {
        data class Sent(val count: Int) : BatchOutcome()
        object AllQuarantined : BatchOutcome()
        data class Retry(val timestamps: List<String>, val reason: String) : BatchOutcome()
        data class Fatal(val reason: String) : BatchOutcome()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        println("📡 Network Restored: Flushing Outbox...")
        Log.i(TAG, "📡 Network Restored: Flushing Outbox...")
        var batchesSent = 0
        var recordsSent = 0

        while (true) {
            ensureActive()

            if (batchesSent >= MAX_BATCHES_PER_RUN) {
                val remaining = outboxDao.countPending()
                if (remaining > 0) {
                    println("🔁 [SYNC-CHAIN] Per-run cap reached with $remaining pending. Re-enqueuing continuation worker...")
                    Log.i(TAG, "🔁 Per-run cap reached with $remaining pending. Chaining continuation run...")
                    scheduleContinuation(applicationContext)
                }
                return@withContext Result.success()
            }

            val pending = outboxDao.getPendingItems(limit = BATCH_SIZE)
            if (pending.isEmpty()) break

            when (val outcome = flushBatch(pending)) {
                is BatchOutcome.Sent -> {
                    batchesSent++
                    recordsSent += outcome.count
                    println("📦 [SYNC-LOOP] Batch #$batchesSent acknowledged (${outcome.count} records, $recordsSent total). Checking for remaining backlog...")
                    Log.i(TAG, "📦 Batch #$batchesSent acknowledged (${outcome.count} records, $recordsSent total).")
                }
                BatchOutcome.AllQuarantined -> {
                    Log.w(TAG, "🚨 Whole batch failed local integrity checks and was quarantined; continuing.")
                }
                is BatchOutcome.Retry -> return@withContext handleBackoff(outcome.timestamps, outcome.reason)
                is BatchOutcome.Fatal -> {
                    Log.e(TAG, "🛑 Fatal sync failure: ${outcome.reason}")
                    return@withContext Result.failure()
                }
            }
        }

        println("✅ [SYNC-COMPLETE] Outbox backlog completely drained ($recordsSent records in $batchesSent batches).")
        Log.i(TAG, "✅ Outbox fully drained ($recordsSent records in $batchesSent batches).")
        Result.success()
    }

    private suspend fun flushBatch(pending: List<SyncOutboxQueue>): BatchOutcome {
        // 1. Local integrity: re-verify each record's ECDSA signature before sending.
        val verified = ArrayList<SyncOutboxQueue>(pending.size)
        for (record in pending) {
            val canonical = KeystoreManager.buildCanonicalYieldPayload(
                tenantId = record.tenantId,
                deviceId = record.deviceId,
                actorRole = record.actorRole,
                batchId = record.batchId,
                predictedYield = record.predictedYield,
                timestampUuidV7 = record.timestamp
            )
            val valid = try {
                KeystoreManager.verifySignature(canonical, record.hmacSignature)
            } catch (e: SecurityException) {
                return BatchOutcome.Fatal("Keystore unavailable or policy violation: ${e.message}")
            }
            if (valid) {
                verified.add(record)
            } else {
                Log.e(TAG, "🚨 Signature check failed for UUIDv7=${record.timestamp}; quarantining.")
                outboxDao.markFailed(record.timestamp)
            }
        }
        if (verified.isEmpty()) return BatchOutcome.AllQuarantined

        // 2. Deterministic, content-derived idempotency key.
        val idempotencyKey = deterministicBatchUuidV7(verified)
        val head = verified.first()
        val jsonPayload = buildSyncBatchJsonPayload(idempotencyKey, head, verified)

        // 3. Sign the envelope (key id + identity claims + idempotency key + body).
        val envelopeCanonical =
            "${head.tenantId}:${head.deviceId}:${head.actorRole}:$idempotencyKey:$jsonPayload"
        val envelopeSignature = try {
            KeystoreManager.signPayload(envelopeCanonical)
        } catch (e: SecurityException) {
            return BatchOutcome.Fatal("Cannot sign envelope: ${e.message}")
        }

        val syncUrl = inputData.getString(KEY_GO_API_SYNC_URL) ?: DEFAULT_GO_API_SYNC_URL
        val request = Request.Builder()
            .url(syncUrl)
            .header("X-Signature", envelopeSignature)
            .header("X-Signature-Alg", "ECDSA-P256-SHA256")
            .header("X-Key-Fingerprint", KeystoreManager.publicKeyFingerprintHex())
            .header("X-Idempotency-UUIDv7", idempotencyKey)
            .header("X-Tenant-ID", head.tenantId)
            .header("X-Device-ID", head.deviceId)
            .header("X-Actor-Role", head.actorRole)
            .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val timestamps = verified.map { it.timestamp }
        return try {
            Log.i(TAG, "🚀 POST $syncUrl | records=${verified.size} | idem=$idempotencyKey")
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> applyAcknowledgement(verified, body)
                    response.code == 401 || response.code == 403 ->
                        BatchOutcome.Fatal("Server rejected authorization/signature (HTTP ${response.code})")
                    else -> BatchOutcome.Retry(timestamps, "HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            BatchOutcome.Retry(timestamps, e.message ?: "network unreachable")
        }
    }

    /**
     * Marks acknowledged rows as synced. Un-acknowledged rows stay PENDING and are
     * reported as a retry so the loop cannot resend the same rows indefinitely.
     */
    private suspend fun applyAcknowledgement(
        verified: List<SyncOutboxQueue>,
        responseBody: String
    ): BatchOutcome {
        val ackedIds = parseAcknowledgedIds(responseBody)
        if (ackedIds == null) {
            outboxDao.markAcknowledged(verified.map { it.timestamp })
            return BatchOutcome.Sent(verified.size)
        }
        val acked = verified.filter { it.timestamp in ackedIds }
        val rejected = verified.filter { it.timestamp !in ackedIds }
        if (acked.isNotEmpty()) outboxDao.markAcknowledged(acked.map { it.timestamp })
        return if (rejected.isEmpty()) {
            BatchOutcome.Sent(acked.size)
        } else {
            BatchOutcome.Retry(rejected.map { it.timestamp }, "partial ack: ${rejected.size} rejected")
        }
    }

    private fun parseAcknowledgedIds(body: String): Set<String>? {
        if (body.isBlank()) return null
        return try {
            val array = JSONObject(body).optJSONArray("acknowledged_ids") ?: return null
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        } catch (e: org.json.JSONException) {
            null
        }
    }

    private fun buildSyncBatchJsonPayload(
        envelopeUuidV7: String,
        head: SyncOutboxQueue,
        records: List<SyncOutboxQueue>
    ): String {
        val items = JSONArray()
        for (item in records) {
            items.put(
                JSONObject().apply {
                    put("batch_id", item.batchId)
                    put("predicted_yield", item.predictedYield.toDouble())
                    put("timestamp", item.timestamp)
                    put("signature", item.hmacSignature)
                    put("signature_alg", "ECDSA-P256-SHA256")
                    put("tenant_id", item.tenantId)
                    put("device_id", item.deviceId)
                    put("actor_role", item.actorRole)
                    put("retry_count", item.retryCount)
                }
            )
        }
        return JSONObject().apply {
            put("sync_envelope_uuid_v7", envelopeUuidV7)
            put("tenant_id", head.tenantId)
            put("device_id", head.deviceId)
            put("actor_role", head.actorRole)
            put("record_count", records.size)
            put("outbox_items", items)
        }.toString()
    }

    private suspend fun handleBackoff(timestamps: List<String>, reason: String): Result {
        outboxDao.incrementRetryCount(timestamps)
        val attempt = runAttemptCount + 1
        if (attempt > MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "❌ Exceeded $MAX_RETRY_ATTEMPTS attempts ($reason). Marking ${timestamps.size} rows FAILED.")
            timestamps.forEach { outboxDao.markFailed(it) }
            return Result.failure()
        }
        Log.w(TAG, "⏳ Sync failed ($reason). Attempt #$attempt; WorkManager applies exponential backoff.")
        return Result.retry()
    }

    companion object {
        private const val TAG = "SyncWorker"

        const val UNIQUE_WORK_NAME = "ban_avatar_pos_outbox_sync_worker"
        const val KEY_GO_API_SYNC_URL = "GO_API_SYNC_URL"
        const val DEFAULT_GO_API_SYNC_URL = "https://api.banfoodintelligence.internal/v1/sync"

        const val BATCH_SIZE = 50
        private const val MAX_BATCHES_PER_RUN = 100
        private const val MAX_RETRY_ATTEMPTS = 8
        private const val INITIAL_BACKOFF_SECONDS = 15L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Builds a UUIDv7-shaped key that is a pure function of the batch content:
         * - 48-bit timestamp field = the earliest record's own UUIDv7 millisecond value,
         * - remaining bits come from SHA-256 over the sorted record identities and
         *   signatures,
         * - version = 7, variant = RFC 4122.
         * Same batch in, same key out, on every retry.
         */
        internal fun deterministicBatchUuidV7(records: List<SyncOutboxQueue>): String {
            val sorted = records.sortedBy { it.timestamp }
            val digest = MessageDigest.getInstance("SHA-256")
            for (r in sorted) {
                val line = "${r.timestamp}|${r.batchId}|" +
                    "${"%.4f".format(Locale.US, r.predictedYield)}|${r.hmacSignature}\n"
                digest.update(line.toByteArray(Charsets.UTF_8))
            }
            val hash = digest.digest()

            val epochMs = sorted.minOf { uuidV7EpochMillis(it.timestamp) }
            val bytes = ByteArray(16)
            for (i in 0 until 6) {
                bytes[i] = ((epochMs ushr (8 * (5 - i))) and 0xFF).toByte()
            }
            for (i in 6 until 16) {
                bytes[i] = hash[i - 6]
            }
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

            val hex = bytes.joinToString("") { "%02x".format(it) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }

        private fun uuidV7EpochMillis(uuid: String): Long =
            uuid.replace("-", "").take(12).toLongOrNull(16) ?: 0L

        private fun buildRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        /** Schedules a drain when connectivity is available. No-op if one is already queued. */
        fun enqueueWhenNetworkConnected(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                buildRequest()
            )
            Log.i(TAG, "📅 SyncWorker scheduled (NetworkType.CONNECTED, exponential backoff).")
        }

        /**
         * Chains a follow-up run behind the currently running one. `KEEP` would be
         * ignored here because the current run is still active.
         */
        private fun scheduleContinuation(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest()
            )
        }
    }
}
