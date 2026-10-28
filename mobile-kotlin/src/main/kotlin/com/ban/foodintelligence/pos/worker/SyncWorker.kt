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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ban.foodintelligence.pos.net.ApiConfig
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.database.AppDatabase
import com.ban.foodintelligence.pos.model.SyncOutboxQueue
import com.ban.foodintelligence.pos.security.FatalSecurityException
import com.ban.foodintelligence.pos.security.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
 * BAN Food Intelligence - "Quantum Shield" + "Brutalist L10n" Outbox Sync Worker
 * Author: Nuri (Principal Android Security Architect & Localization Expert)
 * ============================================================================
 *
 * QUANTUM SHIELD & BRUTALIST L10N REMEDIATION:
 * 1. Coroutine Dispatcher Isolation (`Dispatchers.IO.limitedParallelism(1)`):
 *    Caps background sync execution to a single thread slice so a 20,000-record
 *    backlog drain can NEVER starve `Dispatchers.IO` threads needed by the
 *    foreground Kiosk POS UI.
 * 2. Cooperative `yield()` & `delay(500)` Batch Throttling / Battery Saver:
 *    After each 50-record batch, explicitly calls `yield()` and `delay(500L)`
 *    to release the SQLite WAL writer lock, allow SQLite WAL autocheckpoints
 *    to complete without contention, prevent SoC thermal throttling, and keep
 *    the Kiosk Mode UI at a locked 60/120 FPS.
 * 3. Trilingual Localization (`R.string.sync_status_*` for EN / TR / DE).
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val throttledSyncDispatcher = Dispatchers.IO.limitedParallelism(1)

    override suspend fun doWork(): Result = withContext(throttledSyncDispatcher) {
        KeystoreManager.registerApplicationContext(applicationContext)
        Log.i(TAG, "Sending outbox")
        var batchesSent = 0
        var recordsSent = 0

        while (true) {
            ensureActive()

            if (batchesSent >= MAX_BATCHES_PER_RUN) {
                val remaining = outboxDao.countPending()
                if (remaining > 0) {
                    val localizedSyncMsg = applicationContext.getString(R.string.sync_status_syncing, remaining)
                    Log.i(TAG, "$localizedSyncMsg")
                    scheduleContinuation(applicationContext)
                }
                return@withContext Result.success()
            }

            val pending = outboxDao.getPendingItems(limit = BATCH_SIZE)
            if (pending.isEmpty()) break

            val syncingStatus = applicationContext.getString(R.string.sync_status_syncing, pending.size)

            when (val outcome = flushBatch(pending)) {
                is BatchOutcome.Sent -> {
                    batchesSent++
                    recordsSent += outcome.count
                    val ackMsg = applicationContext.getString(R.string.sync_status_batch_ack, outcome.count)
                    Log.i(TAG, "Batch #$batchesSent: $ackMsg")

                                yield()
                    delay(BATCH_THROTTLE_DELAY_MS)
                }
                BatchOutcome.AllQuarantined -> {
                    Log.w(TAG, "Whole batch failed local integrity checks and was quarantined; yielding & continuing.")
                    yield()
                    delay(BATCH_THROTTLE_DELAY_MS)
                }
                is BatchOutcome.Retry -> return@withContext handleBackoff(outcome.timestamps, outcome.reason)
                is BatchOutcome.Fatal -> {
                    Log.e(TAG, "Fatal sync failure: ${outcome.reason}")
                    return@withContext Result.failure()
                }
            }
        }

        val idleMsg = applicationContext.getString(R.string.sync_status_idle)
        Log.i(TAG, "$idleMsg ($recordsSent records in $batchesSent batches).")
        Result.success()
    }

    private suspend fun flushBatch(pending: List<SyncOutboxQueue>): BatchOutcome {
        val verified = ArrayList<SyncOutboxQueue>(pending.size)
        val corruptedTimestamps = ArrayList<String>()

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
            } catch (e: FatalSecurityException) {
                return BatchOutcome.Fatal("Fatal Hardware Keystore compromise: ${e.message}")
            } catch (e: SecurityException) {
                return BatchOutcome.Fatal("Keystore policy violation: ${e.message}")
            }
            if (valid) {
                verified.add(record)
            } else {
                Log.e(TAG, "Signature check failed for UUIDv7=${record.timestamp}; queuing for quarantine.")
                corruptedTimestamps.add(record.timestamp)
            }
        }

        if (corruptedTimestamps.isNotEmpty()) {
            outboxDao.markBatchFailed(corruptedTimestamps)
        }
        if (verified.isEmpty()) return BatchOutcome.AllQuarantined

        val idempotencyKey = deterministicBatchUuidV7(verified)
        val head = verified.first()
        val jsonPayload = buildSyncBatchJsonPayload(idempotencyKey, head, verified)

        val envelopeCanonical =
            "${head.tenantId}:${head.deviceId}:${head.actorRole}:$idempotencyKey:$jsonPayload"
        val envelopeSignature = try {
            KeystoreManager.signPayload(envelopeCanonical)
        } catch (e: FatalSecurityException) {
            return BatchOutcome.Fatal("Hardware TEE/StrongBox missing or compromised: ${e.message}")
        } catch (e: SecurityException) {
            return BatchOutcome.Fatal("Cannot sign envelope: ${e.message}")
        }

        val syncUrl = inputData.getString(KEY_GO_API_SYNC_URL) ?: (ApiConfig.baseUrl(applicationContext) + "/sync")
        val request = Request.Builder()
            .url(syncUrl)
            .header("X-Signature", envelopeSignature)
            .header("X-Signature-Alg", "ECDSA-P256-SHA256")
            .header("X-Key-Fingerprint", KeystoreManager.publicKeyFingerprintHex())
            .header("X-Idempotency-UUIDv7", idempotencyKey)
            .header("X-Tenant-ID", head.tenantId)
            .header("X-Device-ID", head.deviceId)
            .header("X-Actor-Role", head.actorRole)
            .header("Accept-Language", Locale.getDefault().language)
            .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val timestamps = verified.map { it.timestamp }
        return try {
            Log.i(TAG, "POST $syncUrl | records=${verified.size} | idem=$idempotencyKey")
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> applyAcknowledgement(verified, body)
                    response.code == 401 || response.code == 403 -> {
                        val rejectedMsg = applicationContext.getString(R.string.sync_status_signature_rejected)
                        BatchOutcome.Fatal("$rejectedMsg (HTTP ${response.code})")
                    }
                    else -> BatchOutcome.Retry(timestamps, "HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            BatchOutcome.Retry(timestamps, e.message ?: "network unreachable")
        }
    }

    private suspend fun applyAcknowledgement(
        verified: List<SyncOutboxQueue>,
        responseBody: String
    ): BatchOutcome {
        SyncState.markContacted(applicationContext)
        val ackedIds = parseAcknowledgedIds(responseBody)
        // Permanent rejections (recalled lot, not enough stock, invalid data) stop retrying and
        // show their reason on the Sync tab; anything else stays queued for a retry.
        val permanent = parseRejected(responseBody).filterValues { it.first in PERMANENT_REJECTIONS }
        if (permanent.isNotEmpty()) {
            AppDatabase.getInstance(applicationContext).markRejected(permanent.mapValues { it.value.second })
        }
        if (ackedIds == null) {
            outboxDao.markAcknowledged(verified.map { it.timestamp })
            outboxDao.purgeSyncedRecords()
            return BatchOutcome.Sent(verified.size)
        }
        val acked = verified.filter { it.timestamp in ackedIds }
        val rejected = verified.filter { it.timestamp !in ackedIds && it.timestamp !in permanent }
        if (acked.isNotEmpty()) {
            outboxDao.markAcknowledged(acked.map { it.timestamp })
            outboxDao.purgeSyncedRecords()
        }
        return if (rejected.isEmpty()) {
            BatchOutcome.Sent(acked.size)
        } else {
            BatchOutcome.Retry(rejected.map { it.timestamp }, "partial ack: ${rejected.size} rejected")
        }
    }

    /** id -> (code, localised message) from the server's "rejected" list. */
    private fun parseRejected(body: String): Map<String, Pair<String, String>> = try {
        val array = JSONObject(body).optJSONArray("rejected") ?: JSONArray()
        buildMap {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                put(o.getString("id"), o.optString("code") to o.optString("message"))
            }
        }
    } catch (_: org.json.JSONException) {
        emptyMap()
    }

    private fun parseAcknowledgedIds(body: String): Set<String>? {
        if (body.isBlank()) return null
        return try {
            val array = JSONObject(body).optJSONArray("acknowledged_ids") ?: return null
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        } catch (_: org.json.JSONException) {
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
            // Server unreachable for a long time: stop this chain but keep the sales PENDING.
            // The app calls syncNow() as soon as the API answers again; nothing is lost.
            Log.w(TAG, "Giving up after $MAX_RETRY_ATTEMPTS attempts ($reason); ${timestamps.size} sales stay pending")
            return Result.failure()
        }
        val retryMsg = applicationContext.getString(R.string.sync_status_network_retry)
        Log.w(TAG, "$retryMsg ($reason). Attempt #$attempt.")
        return Result.retry()
    }

    companion object {
        private const val TAG = "SyncWorker"

        const val UNIQUE_WORK_NAME = "ban_avatar_pos_outbox_sync_worker"
        private val PERMANENT_REJECTIONS = setOf("INSUFFICIENT_STOCK", "LOT_BLOCKED", "INVALID_INPUT", "CONFLICT", "NOT_FOUND", "LOT_NOT_FOUND")
        const val KEY_GO_API_SYNC_URL = "GO_API_SYNC_URL"

        const val BATCH_SIZE = 50
        const val BATCH_THROTTLE_DELAY_MS = 500L
        private const val MAX_BATCHES_PER_RUN = 100
        private const val MAX_RETRY_ATTEMPTS = 8
        private const val INITIAL_BACKOFF_SECONDS = 15L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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

        /**
         * Send now: replaces a job that is waiting in exponential backoff (KEEP would leave it
         * waiting for minutes after the server is back). A running job is left alone. Safe
         * because every record carries its UUIDv7 and the server ignores replays.
         * Call off the main thread.
         */
        fun syncNow(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            val running = runCatching {
                wm.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get().any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            if (running) return
            wm.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, buildRequest())
            Log.i(TAG, "Sync requested")
        }

        fun enqueueWhenNetworkConnected(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                buildRequest()
            )
            Log.i(TAG, "Sync scheduled (waits for network)")
        }

        private fun scheduleContinuation(context: Context) {
            val continuationRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(2, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                continuationRequest
            )
        }
    }
}
