package com.ban.foodintelligence.pos.catalog

import android.content.Context
import android.util.Log
import com.ban.foodintelligence.pos.net.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A lot on sale at this terminal's store, as reported by the Go API. */
data class PosInventoryLot(
    val lotNo: String,
    val productName: String,
    val availableQty: Double,
    val uom: String,
    val unitPriceEur: Double,
    val status: String,
    /** Recall that blocks this lot, if any (from GET /recall, lock_enforced = true). */
    val recallCode: String?,
    val recallRootLot: String?,
    /** "2026-10-19"; empty when the lot has no expiry date. */
    val expiryDate: String = "",
    /** Recall reason text, when recalled. */
    val recallReason: String? = null
) {
    val isBlocked: Boolean get() = status == "RECALLED" || status == "BLOCKED" || recallCode != null
}

/** Where the lot list on screen came from. */
enum class CatalogSource { LIVE, CACHED, NONE }

/** One active recall (GET /recall, lock_enforced = true). */
data class RecallInfo(
    val recallCode: String,
    val triggerLot: String,
    val reason: String,
    val affectedLots: List<String>,
    val initiatedAt: String
)

data class PosCatalog(
    val lots: List<PosInventoryLot>,
    val source: CatalogSource,
    val fetchedAtMs: Long,
    val error: String?,
    val recalls: List<RecallInfo> = emptyList()
)

/**
 * Loads the store's lots (GET /lots) and the active recalls (GET /recall) from the Go API.
 * Both raw responses are cached in SharedPreferences, so recall blocking keeps working
 * offline with the last list the terminal saw.
 */
object PosCatalogRepository {

    private const val TAG = "PosCatalog"
    const val STORE_WAREHOUSE = "MAGAZA-01"

    private const val PREFS = "pos_catalog_cache"
    private const val KEY_LOTS = "lots_json"
    private const val KEY_RECALLS = "recalls_json"
    private const val KEY_FETCHED_AT = "fetched_at_ms"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun load(context: Context): PosCatalog = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val baseUrl = ApiConfig.baseUrl(context)
            val lotsJson = get(baseUrl, "/lots")
            val recallsJson = get(baseUrl, "/recall")
            val lots = parse(lotsJson, recallsJson)
            val recalls = parseRecalls(recallsJson)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString(KEY_LOTS, lotsJson)
                .putString(KEY_RECALLS, recallsJson)
                .putLong(KEY_FETCHED_AT, now)
                .apply()
            PosCatalog(lots, CatalogSource.LIVE, now, null, recalls)
        } catch (e: Exception) {
            if (e !is IOException && e !is JSONException) throw e
            Log.w(TAG, "Catalog fetch failed, using cache: ${e.message}")
            val cachedLots = prefs.getString(KEY_LOTS, null)
            val cachedRecalls = prefs.getString(KEY_RECALLS, null)
            if (cachedLots == null) {
                PosCatalog(emptyList(), CatalogSource.NONE, 0L, e.message)
            } else {
                PosCatalog(
                    lots = parse(cachedLots, cachedRecalls ?: "[]"),
                    source = CatalogSource.CACHED,
                    fetchedAtMs = prefs.getLong(KEY_FETCHED_AT, 0L),
                    error = e.message,
                    recalls = parseRecalls(cachedRecalls ?: "[]")
                )
            }
        }
    }

    private fun get(baseUrl: String, path: String): String {
        val request = Request.Builder().url(baseUrl + path).get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("GET $path returned HTTP ${response.code}")
            return body
        }
    }

    /** Active recalls only (lock_enforced = true: the recall is OPEN and its lots are RECALLED). */
    private fun parseRecalls(recallsJson: String): List<RecallInfo> {
        val arr = if (recallsJson.isBlank() || recallsJson == "null") JSONArray() else JSONArray(recallsJson)
        return buildList {
            for (i in 0 until arr.length()) {
                val ev = arr.getJSONObject(i)
                if (!ev.optBoolean("lock_enforced", false)) continue
                val lots = ev.optJSONArray("affected_lots") ?: JSONArray()
                add(
                    RecallInfo(
                        recallCode = ev.optString("recall_code"),
                        triggerLot = ev.optString("trigger_lot"),
                        reason = ev.optString("reason"),
                        affectedLots = (0 until lots.length()).map { lots.getString(it) },
                        initiatedAt = ev.optString("timestamp")
                    )
                )
            }
        }
    }

    /** Lots in [STORE_WAREHOUSE] with stock and a sale price, joined with the active recalls. */
    private fun parse(lotsJson: String, recallsJson: String): List<PosInventoryLot> {
        val recalledBy = mutableMapOf<String, RecallInfo>()
        parseRecalls(recallsJson).forEach { rc -> rc.affectedLots.forEach { recalledBy[it] = rc } }

        val rows = if (lotsJson.isBlank() || lotsJson == "null") JSONArray() else JSONArray(lotsJson)
        return buildList {
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                if (o.optString("warehouse_code") != STORE_WAREHOUSE) continue
                if (o.isNull("sale_price")) continue
                val qty = o.optDouble("qty", 0.0)
                if (qty <= 0.0) continue
                val lotNo = o.getString("lot_no")
                val recall = recalledBy[lotNo]
                add(
                    PosInventoryLot(
                        lotNo = lotNo,
                        productName = o.optString("product_name"),
                        availableQty = qty,
                        uom = o.optString("uom_code"),
                        unitPriceEur = o.getDouble("sale_price"),
                        status = o.optString("status"),
                        recallCode = recall?.recallCode,
                        recallRootLot = recall?.triggerLot,
                        expiryDate = o.optString("expiry_date"),
                        recallReason = recall?.reason
                    )
                )
            }
        }
    }
}
