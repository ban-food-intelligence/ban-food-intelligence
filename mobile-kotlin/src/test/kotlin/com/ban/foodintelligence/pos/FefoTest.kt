package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.catalog.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FefoTest {
    private val today = LocalDate.of(2026, 10, 29)
    private fun lot(no: String, expiry: String, sku: String = "KZ-BUT") =
        PosInventoryLot(no, "Same display name", 2.0, "KG", 22.0, "ACTIVE", null, null, expiryDate = expiry, sku = sku)

    @Test fun earliestExpiryWinsEvenWhenReceivedOrNamedLater() {
        val lots = listOf(lot("A", "2026-11-04"), lot("Z", "2026-10-29"), lot("B", "2026-11-01"))
        assertEquals(listOf("Z", "B", "A"), fefoSaleLots(lots, today).map { it.lotNo })
        assertEquals(setOf("Z"), fefoRecommendedLotNos(lots, today))
    }
    @Test fun recalledExpiredAndLocallyExhaustedLotsCannotBeRecommended() {
        val lots = listOf(lot("old", "2026-10-28"), lot("recalled", "2026-10-29").copy(recallCode = "RCL-1"),
            lot("empty", "2026-10-29").copy(availableQty = 0.0), lot("ok", "2026-11-01"))
        assertEquals(listOf("ok"), fefoSaleLots(lots, today).map { it.lotNo })
        assertEquals(setOf("ok"), fefoRecommendedLotNos(lots, today))
    }
    @Test fun differentProductsAreNeverCompetedAgainstEachOther() {
        val lots = listOf(lot("leg", "2026-11-01"), lot("back", "2026-10-30", "KZ-SIRT"))
        assertEquals(setOf("leg", "back"), fefoRecommendedLotNos(lots, today))
    }
    @Test fun unknownDatesComeLastAndDoNotPretendToBeFefo() {
        val lots = listOf(lot("unknown", ""), lot("invalid", "bad"), lot("dated", "2026-10-30"))
        assertEquals("dated", fefoSaleLots(lots, today).first().lotNo)
        assertEquals(setOf("dated"), fefoRecommendedLotNos(lots, today))
        assertTrue(fefoRecommendedLotNos(lots.take(2), today).isEmpty())
    }
    @Test fun tiesAreStableAndUnknownProductIdentityIsNotGuessed() {
        val lots = listOf(lot("B", "2026-10-30"), lot("A", "2026-10-30"), lot("no-sku", "2026-10-29", ""))
        assertEquals(setOf("A"), fefoRecommendedLotNos(lots, today))
    }
}
