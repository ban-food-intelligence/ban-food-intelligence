package com.ban.foodintelligence.pos.ocr

import android.util.Base64
import android.util.Log
import com.ban.foodintelligence.pos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A single invoice line proposed by the vision-language model. */
data class InvoiceLineItem(
    val productName: String,
    val quantity: Double,
    val unit: String,
    val lotNumber: String,
    val unitCost: Double,
    val currency: String
) {
    val lineTotal: Double get() = quantity * unitCost
}

/** Structured result of one OCR pass over an invoice image. */
data class InvoiceExtraction(
    val supplier: String?,
    val invoiceNumber: String?,
    val items: List<InvoiceLineItem>,
    val rawJson: String,
    val modelId: String,
    val latencyMs: Long,
    val isDemoResponse: Boolean = false
)

class InvoiceOcrException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * ============================================================================
 * BAN Food Intelligence - Task 5: Invoice OCR via NVIDIA NIM Vision-Language API
 * ============================================================================
 *
 * Sends an invoice image to `nvidia/nemotron-3.5-super-vl-120b-a12b` on the
 * OpenAI-compatible NVIDIA NIM endpoint and asks for Product Name, Quantity,
 * Lot Number and Unit Cost as strict JSON.
 *
 * The model output is treated as a SUGGESTION only. Nothing is written to stock
 * until the operator explicitly confirms it in [com.ban.foodintelligence.pos.ui.InvoiceOcrActivity].
 *
 * SECURITY NOTE: [NV_API_KEY] comes from `NVIDIA_API_KEY` in the gitignored
 * `local.properties` via BuildConfig, so it is never in source control. It is still
 * compiled into the APK and can be extracted by anyone holding the device. For
 * production the call should be proxied through the Go backend (which holds the key
 * server-side and can apply per-terminal rate limits), with the terminal
 * authenticating via its ECDSA key.
 */
object NvidiaVisionOcrClient {

    private const val TAG = "NvidiaVisionOcr"

    /** `NVIDIA_API_KEY` from local.properties; empty when not configured. */
    private val NV_API_KEY: String = BuildConfig.NVIDIA_API_KEY

    const val MODEL_ID = "nvidia/nemotron-3.5-super-vl-120b-a12b"
    private const val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // A 120B VL model can take a while on a full-page invoice.
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val EXTRACTION_PROMPT = """
        You are an invoice data-extraction engine for a food-business ERP.
        Read the attached supplier invoice image and extract every product line.
        For each line return: Product Name, Quantity, Lot Number, and Unit Cost.

        Respond with ONLY a single JSON object, no markdown, no commentary, in exactly this shape:
        {
          "supplier": string or null,
          "invoice_number": string or null,
          "items": [
            {
              "product_name": string,
              "quantity": number,
              "unit": string,
              "lot_number": string,
              "unit_cost": number,
              "currency": string
            }
          ]
        }

        Rules:
        - Use a dot as decimal separator in numbers (e.g. 12.5, not 12,5).
        - "unit" is the unit of measure as printed (e.g. KG, ADET, PAKET).
        - If a lot number is not printed for a line, use an empty string. Never invent one.
        - Do not include totals, VAT lines or shipping lines as items.
    """.trimIndent()

    val isApiKeyConfigured: Boolean
        get() = NV_API_KEY.startsWith("nvapi-")

    /**
     * Runs OCR on a JPEG-encoded invoice image. Executes on [Dispatchers.IO].
     * @throws InvoiceOcrException on missing key, HTTP errors, network failure or unparseable output.
     */
    suspend fun extractInvoice(imageJpeg: ByteArray): InvoiceExtraction = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured) {
            throw InvoiceOcrException("NVIDIA API key is not configured (set NVIDIA_API_KEY in local.properties).")
        }

        val dataUri = "data:image/jpeg;base64," + Base64.encodeToString(imageJpeg, Base64.NO_WRAP)

        val requestJson = JSONObject().apply {
            put("model", MODEL_ID)
            put("temperature", 0.0)
            put("top_p", 1.0)
            put("max_tokens", 2048)
            put("stream", false)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", EXTRACTION_PROMPT))
                                .put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", dataUri))
                                )
                        )
                    }
                )
            )
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $NV_API_KEY")
            .header("Accept", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        Log.i(TAG, "Sending invoice (${imageJpeg.size} bytes) to $MODEL_ID")
        val startedAt = System.currentTimeMillis()

        val responseBody = try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw InvoiceOcrException(
                        "NVIDIA NIM returned HTTP ${response.code}: ${body.take(300)}",
                        httpCode = response.code
                    )
                }
                body
            }
        } catch (e: IOException) {
            throw InvoiceOcrException("Network error while contacting NVIDIA NIM: ${e.message}", cause = e)
        }

        val latencyMs = System.currentTimeMillis() - startedAt

        val content = try {
            JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: JSONException) {
            throw InvoiceOcrException("Unexpected NVIDIA NIM response format.", cause = e)
        }

        Log.i(TAG, "OCR completed in ${latencyMs} ms")
        parseModelContent(content, latencyMs = latencyMs, isDemo = false)
    }

    /**
     * Parses the model's text output into an [InvoiceExtraction]. Tolerates markdown
     * code fences and reasoning text around the JSON object.
     */
    fun parseModelContent(content: String, latencyMs: Long, isDemo: Boolean): InvoiceExtraction {
        val jsonText = extractJsonObject(content)
            ?: throw InvoiceOcrException("Model output did not contain a JSON object.")

        val root = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            throw InvoiceOcrException("Model output was not valid JSON.", cause = e)
        }

        val itemsArray = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsArray.length()) {
                val o = itemsArray.optJSONObject(i) ?: continue
                val name = o.optString("product_name").trim()
                if (name.isEmpty()) continue
                add(
                    InvoiceLineItem(
                        productName = name,
                        quantity = parseNumber(o.opt("quantity")),
                        unit = o.optString("unit", "KG").ifBlank { "KG" }.uppercase(),
                        lotNumber = o.optString("lot_number").trim(),
                        unitCost = parseNumber(o.opt("unit_cost")),
                        currency = o.optString("currency", "EUR").ifBlank { "EUR" }
                    )
                )
            }
        }

        if (items.isEmpty()) {
            throw InvoiceOcrException("No product lines could be extracted from the invoice.")
        }

        return InvoiceExtraction(
            supplier = root.optString("supplier").takeIf { it.isNotBlank() && it != "null" },
            invoiceNumber = root.optString("invoice_number").takeIf { it.isNotBlank() && it != "null" },
            items = items,
            rawJson = root.toString(2),
            modelId = MODEL_ID,
            latencyMs = latencyMs,
            isDemoResponse = isDemo
        )
    }

    private fun extractJsonObject(text: String): String? {
        // Drop any reasoning block some Nemotron variants emit before the answer.
        val cleaned = text.substringAfterLast("</think>")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }

    private fun parseNumber(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value
            .replace(Regex("[^0-9,.-]"), "")
            .let { s -> if (s.contains(',') && !s.contains('.')) s.replace(',', '.') else s.replace(",", "") }
            .toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    /**
     * Canned model output matching the simulated invoice. Used ONLY when the operator
     * explicitly chooses the demo path because no API key is configured; the UI labels
     * the result as a demo response.
     */
    const val DEMO_MODEL_RESPONSE = """
        {
          "supplier": "Anadolu Et Kombinasi A.S.",
          "invoice_number": "FTR-2026-10871",
          "items": [
            {"product_name": "Dana Kiyma Eti (%20 yag)", "quantity": 50, "unit": "KG", "lot_number": "ET-2026-014", "unit_cost": 9.80, "currency": "EUR"},
            {"product_name": "Kuzu Karkas", "quantity": 25, "unit": "KG", "lot_number": "KUZ-2026-002", "unit_cost": 11.50, "currency": "EUR"},
            {"product_name": "Dogal Sucuk Kilifi", "quantity": 200, "unit": "ADET", "lot_number": "KLF-2026-031", "unit_cost": 0.35, "currency": "EUR"}
          ]
        }
    """
}
