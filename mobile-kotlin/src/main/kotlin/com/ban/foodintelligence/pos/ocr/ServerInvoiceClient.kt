package com.ban.foodintelligence.pos.ocr

import android.content.Context
import android.graphics.Bitmap
import com.ban.foodintelligence.pos.net.ServerApi
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Invoice reading through the Go API: the photo goes to POST /purchases/invoice, which holds the
 * vision key and answers with suggestions; the checked receipt is saved with POST /purchases.
 * No key is ever compiled into this app.
 */
object ServerInvoiceClient {
    /** The API's image limit is 768 KB; the photo is re-encoded smaller until it fits. */
    private const val MAX_IMAGE_BYTES = 700 * 1024

    data class Status(val configured: Boolean, val model: String)
    data class SavedReceipt(val receiptNo: String, val total: Double, val lines: Int)

    suspend fun status(context: Context): Status {
        val o = JsonParser.parseString(ServerApi.get(context, "/purchases/invoice")).asJsonObject
        return Status(o.get("configured")?.asBoolean == true, o.get("model")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
    }

    suspend fun read(context: Context, pin: String, photo: Bitmap): InvoiceSuggestion {
        val body = Gson().toJson(mapOf("image_base64" to Base64.getEncoder().encodeToString(jpegUnderLimit(photo))))
        return InvoiceSuggestion.parse(ServerApi.post(context, "/purchases/invoice", body, pin))
    }

    suspend fun save(context: Context, pin: String, draft: ReceiptDraft): SavedReceipt {
        val o = JsonParser.parseString(ServerApi.post(context, "/purchases", draft.toRequestJson(), pin)).asJsonObject
        return SavedReceipt(o.get("receipt_no").asString, o.get("total").asDouble, o.getAsJsonArray("lines")?.size() ?: 0)
    }

    private fun jpegUnderLimit(photo: Bitmap): ByteArray {
        var last = ByteArray(0)
        for (quality in intArrayOf(85, 70, 55, 40)) {
            last = ByteArrayOutputStream().use { out -> photo.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
            if (last.size <= MAX_IMAGE_BYTES) return last
        }
        return last // the API answers 422 with a clear message if it is still too large
    }
}
