package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.catalog.PosInventoryLot
import com.ban.foodintelligence.pos.scan.LotScan
import com.ban.foodintelligence.pos.scan.LotScanCode
import com.ban.foodintelligence.pos.scan.LotScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class LotScanTest {
    private fun lot(no: String, recall: String? = null) =
        PosInventoryLot(no, "Kuzu but", 2.0, "KG", 22.0, if (recall != null) "RECALLED" else "ACTIVE", recall, null, expiryDate = "2026-11-05", sku = "KZ-BUT")

    @Test fun ourLabelCarriesTheLotNumberAsTyped() {
        assertEquals(LotScanCode("KZB-2026-001", null, null), LotScan.parse("  kzb-2026-001\n"))
        assertEquals(LotScanCode("SUC-2026-045", null, null), LotScan.parse("]Q3SUC-2026-045")) // symbology prefix
        assertNull(LotScan.parse("   "))
    }

    @Test fun gs1WithParenthesesGivesBatchAndExpiry() {
        val code = LotScan.parse("(01)08690000000017(17)261108(10)SUC-2026-045")
        assertEquals(LotScanCode(null, "SUC-2026-045", LocalDate.of(2026, 11, 8)), code)
    }

    @Test fun rawGs1WithFnc1SeparatorsAndAWeight() {
        val gs = '\u001D'
        val code = LotScan.parse("]C1010869000000001731030012501726113010KZB-2026-001${gs}21SN77")
        assertEquals(LotScanCode(null, "KZB-2026-001", LocalDate.of(2026, 11, 30)), code)
        // variable batch first, closed by FNC1; day 00 = last day of the month
        assertEquals(LotScanCode(null, "B-7", LocalDate.of(2027, 2, 28)), LotScan.parse("0108690000000017" + "10B-7$gs" + "17270200"))
    }

    @Test fun aGtinWithoutBatchOrAnUnknownAiIsNotGuessed() {
        assertEquals(LotScanCode("08690000000017", null, null), LotScan.parse("08690000000017"))
        assertEquals(LotScanCode("0108690000000017990ABC", null, null), LotScan.parse("0108690000000017990ABC"))
        assertNull(LotScan.gs1Date("261332"))
        assertNull(LotScan.gs1Date("26111"))
    }

    @Test fun resolvesByLotNumberOrBatchThenByRecallList() {
        val lots = listOf(lot("KZB-2026-001"), lot("SUC-2026-045", recall = "RCL-1"))
        assertEquals(LotScanResult.Found(lots[0]), LotScan.resolve(LotScan.parse("kzb-2026-001")!!, lots, emptyList()))
        // a recalled lot is found too: the screen shows why it cannot be sold
        assertEquals(LotScanResult.Found(lots[1]), LotScan.resolve(LotScan.parse("(10)SUC-2026-045")!!, lots, emptyList()))
        assertEquals(LotScanResult.RecalledElsewhere("ET-2026-001"),
            LotScan.resolve(LotScan.parse("et-2026-001")!!, lots, listOf("ET-2026-001", "SUC-2026-045")))
        assertEquals(LotScanResult.Unknown("XX-1"), LotScan.resolve(LotScan.parse("XX-1")!!, lots, listOf("ET-2026-001")))
    }
}
