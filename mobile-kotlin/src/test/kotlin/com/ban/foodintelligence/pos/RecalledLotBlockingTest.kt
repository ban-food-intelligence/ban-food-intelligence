package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.catalog.PosInventoryLot
import com.ban.foodintelligence.pos.catalog.sellableLots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 3: "geri çağrılan lotların satışı engellenir" on the terminal side. */
class RecalledLotBlockingTest {

    private fun lot(no: String, status: String = "ACTIVE", recall: String? = null, qty: Double = 3.0) =
        PosInventoryLot(no, "Narin Sucuk", qty, "PAKET", 22.0, status, recall, recall?.let { "ET-2026-002" })

    @Test fun activeLotIsSellable() = assertFalse(lot("SUC-2026-046").isBlocked)

    @Test fun recalledStatusBlocks() = assertTrue(lot("SUC-2026-046", status = "RECALLED").isBlocked)

    @Test fun blockedStatusBlocks() = assertTrue(lot("SUC-2026-046", status = "BLOCKED").isBlocked)

    // The cached recall list blocks the lot even before the lot list shows RECALLED (offline).
    @Test fun activeRecallBlocksEvenIfStatusIsStale() =
        assertTrue(lot("SUC-2026-046", recall = "RCL-2026-102").isBlocked)

    @Test fun sellScreenOffersOnlyUnblockedLotsWithStock() {
        val lots = listOf(
            lot("KZB-2026-001"),
            lot("SUC-2026-046", recall = "RCL-2026-102"),
            lot("SUC-2026-045", status = "RECALLED"),
            lot("KZY-2026-001", qty = 0.0)
        )
        assertEquals(listOf("KZB-2026-001"), sellableLots(lots).map { it.lotNo })
    }
}
