package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.ui.QtyCheck
import com.ban.foodintelligence.pos.ui.SaleQuantityValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleQuantityValidatorTest {

    private fun invalid(input: String, uom: String = "KG", stock: Double = 5.25): Int {
        val r = SaleQuantityValidator.check(input, uom, stock)
        assertTrue("expected invalid for '$input'", r is QtyCheck.Invalid)
        return (r as QtyCheck.Invalid).messageRes
    }

    @Test fun rejectsEmpty() = assertEquals(R.string.pos_qty_error_empty, invalid("  "))
    @Test fun rejectsText() = assertEquals(R.string.pos_qty_error_not_number, invalid("abc"))
    @Test fun rejectsScientificNotation() = assertEquals(R.string.pos_qty_error_not_number, invalid("1e3"))
    @Test fun rejectsZero() = assertEquals(R.string.pos_qty_error_not_positive, invalid("0"))
    @Test fun rejectsNegative() = assertEquals(R.string.pos_qty_error_not_positive, invalid("-5"))
    @Test fun rejectsAboveStock() = assertEquals(R.string.pos_qty_error_exceeds_stock, invalid("9"))
    @Test fun rejectsFourKgDecimals() = assertEquals(R.string.pos_qty_error_kg_precision, invalid("1.2345"))
    @Test fun rejectsDecimalPaket() = assertEquals(R.string.pos_qty_error_whole_number, invalid("2.5", "PAKET", 3.0))
    @Test fun rejectsDecimalPointPaket() = assertEquals(R.string.pos_qty_error_whole_number, invalid("2.0", "PAKET", 3.0))

    @Test fun acceptsKgWithCommaDecimal() {
        val r = SaleQuantityValidator.check("0,5", "KG", 5.25)
        assertEquals(QtyCheck.Valid(0.5), r)
    }

    @Test fun acceptsExactStock() {
        assertEquals(QtyCheck.Valid(3.0), SaleQuantityValidator.check("3", "PAKET", 3.0))
    }
}
