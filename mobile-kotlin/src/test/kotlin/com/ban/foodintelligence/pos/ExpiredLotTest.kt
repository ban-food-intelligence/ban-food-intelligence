package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.catalog.PosInventoryLot
import com.ban.foodintelligence.pos.catalog.sellableLots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Expired lots are not sold; the expiry day itself still is (same rule as the database trigger). */
class ExpiredLotTest {

    private val today = LocalDate.of(2026, 10, 29)

    private fun lot(no: String, expiry: String, qty: Double = 2.0) =
        PosInventoryLot(no, "Kuzu kıyma", qty, "KG", 16.0, "ACTIVE", null, null, expiryDate = expiry)

    @Test fun dayAfterExpiryIsExpired() = assertTrue(lot("KZY-2026-001", "2026-10-28").isExpiredOn(today))

    @Test fun expiryDayIsStillSellable() = assertFalse(lot("KZY-2026-001", "2026-10-29").isExpiredOn(today))

    @Test fun lotWithoutExpiryNeverExpires() = assertFalse(lot("BAH-2026-001", "").isExpiredOn(today))

    // A date the app cannot read does not block on the phone; the API still refuses the sale.
    @Test fun unreadableDateIsLeftToTheServer() = assertFalse(lot("KZY-2026-001", "29.10.2026").isExpiredOn(today))

    @Test fun sellScreenSkipsExpiredLots() {
        val lots = listOf(lot("KZB-2026-001", "2026-10-31"), lot("KZY-2026-001", "2026-10-28"))
        assertEquals(listOf("KZB-2026-001"), sellableLots(lots, today).map { it.lotNo })
    }
}
