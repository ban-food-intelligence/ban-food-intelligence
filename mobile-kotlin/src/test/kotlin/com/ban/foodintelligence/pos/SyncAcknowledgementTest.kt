package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.worker.parseSyncAcknowledgement
import org.junit.Assert.*
import org.junit.Test

class SyncAcknowledgementTest {
    private val sent = setOf("sale-1", "sale-2")

    @Test fun missingOrMalformedAckNeverDeletesOfflineSales() {
        for (body in listOf("", "<html>OK</html>", "{}", "null", "{\"acknowledged_ids\":null}", "{\"acknowledged_ids\":42}")) {
            assertNull(body, parseSyncAcknowledgement(body, sent))
        }
    }

    @Test fun explicitEmptyAckIsValidAndAcceptsNothing() {
        val ack = parseSyncAcknowledgement("""{"acknowledged_ids":[],"rejected":[]} """, sent)!!
        assertTrue(ack.accepted.isEmpty())
        assertTrue(ack.rejected.isEmpty())
    }

    @Test fun partialAcceptancePreservesTheRejectedSale() {
        val ack = parseSyncAcknowledgement("""{"acknowledged_ids":["sale-1"],"rejected":[{"id":"sale-2","code":"LOT_BLOCKED","message":"Recalled"}]}""", sent)!!
        assertEquals(setOf("sale-1"), ack.accepted)
        assertEquals("LOT_BLOCKED" to "Recalled", ack.rejected["sale-2"])
    }

    @Test fun allRejectedCannotBecomeAllAccepted() {
        val ack = parseSyncAcknowledgement("""{"acknowledged_ids":[],"rejected":[{"id":"sale-1","code":"LOT_EXPIRED"},{"id":"sale-2","code":"LOT_BLOCKED"}]}""", sent)!!
        assertTrue(ack.accepted.isEmpty())
        assertEquals(sent, ack.rejected.keys)
    }

    @Test fun foreignOrContradictoryIdsInvalidateTheWholeResponse() {
        for (body in listOf(
            """{"acknowledged_ids":["another-device-sale"]}""",
            """{"acknowledged_ids":["sale-1"],"rejected":[{"id":"sale-1","code":"LOT_BLOCKED"}]}""",
            """{"acknowledged_ids":[],"rejected":[{"id":"another-device-sale","code":"LOT_BLOCKED"}]}""",
            """{"acknowledged_ids":[42]}"""
        )) assertNull(body, parseSyncAcknowledgement(body, sent))
    }
}
