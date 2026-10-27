package com.ban.foodintelligence.pos.catalog

import android.content.Context
import com.ban.foodintelligence.pos.net.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class TraceLot(
    val depth: Int,
    val lotNo: String,
    val productName: String,
    val originType: String,
    val qty: Double,
    val uom: String,
    val status: String,
    val supplierName: String
)

data class TraceLink(
    val linkType: String,
    val documentNo: String,
    val inputLotNo: String,
    val qtyConsumed: Double,
    val inputUom: String,
    val outputLotNo: String,
    val qtyProduced: Double,
    val outputUom: String
)

data class TraceShipment(val tier: Int, val customerName: String, val lotNo: String, val qty: Double, val uom: String, val shipDate: String)

data class TraceResult(
    val lots: List<TraceLot>,
    val links: List<TraceLink>,
    val shipments: List<TraceShipment>
)

/** One prediction vs actual row of GET /ai/summary. */
data class AiComparison(
    val task: String,
    val refNo: String,
    val target: String,
    val actual: Double?,
    val model: Double?,
    val modelDevPct: Double?,
    val baseline: Double?,
    val baselineDevPct: Double?
)

data class AiMetric(val task: String, val target: String, val unit: String, val model: String, val role: String, val mae: Double, val mapePct: Double)

data class AiSummary(val comparisons: List<AiComparison>, val metrics: List<AiMetric>)

/** Read-only API calls for the Lots tab: full trace of a lot and the AI forecast comparison. */
object InsightsRepository {

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    private fun get(context: Context, path: String): String {
        val req = Request.Builder()
            .url(ApiConfig.baseUrl(context) + path)
            .header("Accept-Language", Locale.getDefault().language)
            .get().build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(200)}")
            return body
        }
    }

    suspend fun trace(context: Context, lotNo: String, direction: String): Result<TraceResult> = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(get(context, "/trace/${URLEncoder.encode(lotNo, "UTF-8")}?direction=$direction"))
            TraceResult(
                lots = o.optJSONArray("lots").objects().map {
                    TraceLot(
                        it.optInt("depth"), it.optString("lot_no"), it.optString("product_name"), it.optString("origin_type"),
                        it.optDouble("qty"), it.optString("uom_code"), it.optString("status"), it.optString("supplier_name")
                    )
                },
                links = o.optJSONArray("links").objects().map {
                    TraceLink(
                        it.optString("link_type"), it.optString("document_no"), it.optString("input_lot_no"),
                        it.optDouble("qty_consumed"), it.optString("input_uom"), it.optString("output_lot_no"),
                        it.optDouble("qty_produced"), it.optString("output_uom")
                    )
                },
                shipments = o.optJSONArray("shipments").objects().map {
                    TraceShipment(it.optInt("tier"), it.optString("customer_name"), it.optString("lot_no"), it.optDouble("qty"), it.optString("uom_code"), it.optString("ship_date"))
                }
            )
        }
    }

    suspend fun aiSummary(context: Context): Result<AiSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(get(context, "/ai/summary"))
            AiSummary(
                comparisons = o.optJSONArray("comparisons").objects().map {
                    AiComparison(
                        it.optString("task"), it.optString("ref_no"), it.optString("target"),
                        it.optNullableDouble("actual"), it.optNullableDouble("model_pred"), it.optNullableDouble("model_dev_pct"),
                        it.optNullableDouble("baseline_pred"), it.optNullableDouble("baseline_dev_pct")
                    )
                },
                metrics = o.optJSONArray("metrics").objects().map {
                    AiMetric(it.optString("task"), it.optString("target"), it.optString("unit"), it.optString("model"), it.optString("role"), it.optDouble("mae"), it.optDouble("mape_pct"))
                }
            )
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (isNull(key) || !has(key)) null else optDouble(key)
}
