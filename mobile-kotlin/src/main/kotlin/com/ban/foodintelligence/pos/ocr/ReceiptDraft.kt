package com.ban.foodintelligence.pos.ocr

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * POST /purchases/invoice answer. The API reads the photo with the vision model (its key never
 * reaches this app), matches supplier and products by word rules and writes nothing: these are
 * suggestions. Every field is nullable because Gson ignores Kotlin defaults.
 */
data class InvoiceSuggestion(
    @SerializedName("supplier_name") val supplierName: String? = null,
    @SerializedName("supplier_code") val supplierCode: String? = null,
    @SerializedName("supplier_match") val supplierMatch: String? = null,
    @SerializedName("invoice_no") val invoiceNo: String? = null,
    @SerializedName("invoice_date") val invoiceDate: String? = null,
    val lines: List<SuggestionLine>? = null,
    val model: String? = null,
    @SerializedName("latency_ms") val latencyMs: Long? = null
) {
    companion object {
        fun parse(json: String): InvoiceSuggestion = Gson().fromJson(json, InvoiceSuggestion::class.java)
    }
}

data class SuggestionLine(
    @SerializedName("product_name") val productName: String? = null,
    val sku: String? = null,
    @SerializedName("sku_name") val skuName: String? = null,
    val match: String? = null,
    val qty: Double? = null,
    val unit: String? = null,
    @SerializedName("unit_price") val unitPrice: Double? = null,
    val currency: String? = null,
    @SerializedName("supplier_lot_no") val supplierLotNo: String? = null,
    @SerializedName("expiry_date") val expiryDate: String? = null,
    val warnings: List<String>? = null
)

/** One receipt line as the supervisor edits it (text fields, as typed). */
data class DraftLine(
    val printedName: String,
    val match: String,
    val warnings: List<String>,
    val unit: String,
    val sku: String,
    val lotNo: String,
    val supplierLotNo: String,
    val qty: String,
    val unitPrice: String,
    val expiryDate: String
)

/** What blocks saving a field; the screen shows it next to the field, as the API's 422 keys. */
enum class DraftProblem { REQUIRED, NOT_A_NUMBER, NOT_A_DATE }

/**
 * The receipt the supervisor checks before saving with POST /purchases. Our own lot number is
 * never suggested; a printed lot number becomes the supplier's batch.
 */
data class ReceiptDraft(
    val receiptNo: String,
    val supplierCode: String,
    val supplierPrinted: String,
    val supplierMatch: String,
    val invoiceNo: String,
    val receiptDate: String,
    val lines: List<DraftLine>
) {
    /** Problems per API field key ("supplier_code", "lines[0].lot_no", ...); empty when it can be sent. */
    fun problems(): Map<String, DraftProblem> {
        val out = linkedMapOf<String, DraftProblem>()
        if (receiptNo.isBlank()) out["receipt_no"] = DraftProblem.REQUIRED
        if (supplierCode.isBlank()) out["supplier_code"] = DraftProblem.REQUIRED
        if (!isDate(receiptDate)) out["receipt_date"] = DraftProblem.NOT_A_DATE
        if (lines.isEmpty()) out["lines"] = DraftProblem.REQUIRED
        lines.forEachIndexed { i, l ->
            val k = "lines[$i]."
            if (l.sku.isBlank()) out[k + "sku"] = DraftProblem.REQUIRED
            if (l.lotNo.isBlank()) out[k + "lot_no"] = DraftProblem.REQUIRED
            val qty = number(l.qty)
            if (qty == null || qty <= 0.0) out[k + "qty"] = if (l.qty.isBlank()) DraftProblem.REQUIRED else DraftProblem.NOT_A_NUMBER
            val price = number(l.unitPrice)
            if (price == null || price < 0.0) out[k + "unit_price"] = if (l.unitPrice.isBlank()) DraftProblem.REQUIRED else DraftProblem.NOT_A_NUMBER
            if (l.expiryDate.isNotBlank() && !isDate(l.expiryDate)) out[k + "expiry_date"] = DraftProblem.NOT_A_DATE
        }
        return out
    }

    /** The POST /purchases body; call only when [problems] is empty. Warehouse: the API default. */
    fun toRequestJson(): String = Gson().toJson(
        mapOf(
            "receipt_no" to receiptNo.trim().uppercase(),
            "supplier_code" to supplierCode.trim().uppercase(),
            "receipt_date" to receiptDate.trim(),
            "invoice_no" to invoiceNo.trim(),
            "lines" to lines.map { l ->
                mapOf(
                    "sku" to l.sku.trim().uppercase(),
                    "lot_no" to l.lotNo.trim().uppercase(),
                    "supplier_lot_no" to l.supplierLotNo.trim(),
                    "qty" to number(l.qty),
                    "unit_price" to number(l.unitPrice),
                    "expiry_date" to l.expiryDate.trim()
                )
            }
        )
    )

    companion object {
        fun from(s: InvoiceSuggestion, today: LocalDate, receiptNo: String): ReceiptDraft = ReceiptDraft(
            receiptNo = receiptNo,
            supplierCode = s.supplierCode.orEmpty(),
            supplierPrinted = s.supplierName.orEmpty(),
            supplierMatch = s.supplierMatch ?: "NONE",
            invoiceNo = s.invoiceNo.orEmpty(),
            receiptDate = today.toString(), // the goods arrive today; the invoice date is only shown
            lines = s.lines.orEmpty().map { l ->
                DraftLine(
                    printedName = l.productName.orEmpty(),
                    match = l.match ?: "NONE",
                    warnings = l.warnings.orEmpty(),
                    unit = l.unit.orEmpty(),
                    sku = l.sku.orEmpty(),
                    lotNo = "",
                    supplierLotNo = l.supplierLotNo.orEmpty(),
                    qty = plain(l.qty),
                    unitPrice = plain(l.unitPrice),
                    expiryDate = l.expiryDate.orEmpty()
                )
            }
        )

        /** "12,5" or "12.5" -> 12.5; null for empty or not a number. */
        fun number(text: String): Double? = text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            ?.takeIf { !it.isNaN() && !it.isInfinite() }

        /** 120.5 -> "120.5", 30.0 -> "30", null -> "". */
        fun plain(v: Double?): String = v?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

        private fun isDate(v: String): Boolean = try {
            LocalDate.parse(v.trim()); true
        } catch (e: DateTimeParseException) {
            false
        }
    }
}
