package com.ban.foodintelligence.pos.ui

import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.ui.design.Fmt

/** Result of checking the sale quantity field against the selected lot. */
sealed interface QtyCheck {
    data class Valid(val qty: Double) : QtyCheck
    data class Invalid(val messageRes: Int, val arg: String? = null) : QtyCheck
}

/**
 * Sale quantity rules:
 * - must be a plain number (comma or dot as decimal separator),
 * - must be > 0 and <= available stock,
 * - KG allows up to 3 decimals (MySQL DECIMAL(14,3)); every other unit (PAKET, ADET)
 *   must be a whole number.
 */
object SaleQuantityValidator {

    private val NUMBER = Regex("""^-?\d+(\.\d+)?$""")
    private const val EPSILON = 1e-9

    fun check(input: String, uom: String, availableQty: Double): QtyCheck {
        val text = input.trim().replace(',', '.')
        if (text.isEmpty()) return QtyCheck.Invalid(R.string.pos_qty_error_empty)
        if (!NUMBER.matches(text)) return QtyCheck.Invalid(R.string.pos_qty_error_not_number)
        val qty = text.toDouble()
        if (qty <= 0.0) return QtyCheck.Invalid(R.string.pos_qty_error_not_positive)

        val decimals = text.substringAfter('.', "").length
        if (uom == "KG") {
            // KG: decimals allowed.
            if (decimals > 3) return QtyCheck.Invalid(R.string.pos_qty_error_kg_precision)
        } else if (text.contains('.')) {
            return QtyCheck.Invalid(R.string.pos_qty_error_whole_number)
        }

        if (qty > availableQty + EPSILON) {
            return QtyCheck.Invalid(R.string.pos_qty_error_exceeds_stock, Fmt.qty(availableQty, uom))
        }
        return QtyCheck.Valid(qty)
    }
}
