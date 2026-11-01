package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.count.CountLine
import com.ban.foodintelligence.pos.count.CountProblem
import com.ban.foodintelligence.pos.count.CountRow
import com.ban.foodintelligence.pos.count.ScanEffect
import com.ban.foodintelligence.pos.count.StockCountRules
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockCountRulesTest {
    private val rows = listOf(
        CountRow("SUC-1", "Narin Sucuk 1 kg", "PAKET", 20.0),
        CountRow("KZB-1", "Kuzu but", "KG", 4.5),
        CountRow("SUC-2", "Narin Sucuk 1 kg", "PAKET", 5.0)
    )

    @Test fun readsCommaOrDotAndAnEmptyFieldIsNotCounted() {
        assertNull(StockCountRules.parseCount(""))
        assertEquals(4.25, StockCountRules.parseCount(" 4,25 ")!!, 0.0)
        assertTrue(StockCountRules.parseCount("-1")!!.isNaN())
        assertTrue(StockCountRules.parseCount("abc")!!.isNaN())
    }

    @Test fun sendsOnlyCountedLotsWithTheBalanceSeenAndAReasonForEachDifference() {
        val typed = listOf(rows[0].copy(counted = "17", reason = " Kırık "), rows[1].copy(counted = "4,5"), rows[2])
        val (lines, problems) = StockCountRules.lines(typed)
        assertTrue(problems.isEmpty())
        assertEquals(listOf(CountLine("SUC-1", 17.0, 20.0, "Kırık"), CountLine("KZB-1", 4.5, 4.5, "")), lines)
        assertEquals(-3.0, lines[0].diff, 0.0)
    }

    @Test fun blocksADifferenceWithoutReasonHalfAPackAndNonsense() {
        val typed = listOf(rows[0].copy(counted = "18"), rows[1].copy(counted = "4,2x"), rows[2].copy(counted = "4,5", reason = "x"))
        assertEquals(
            mapOf("SUC-1" to CountProblem.REASON_NEEDED, "KZB-1" to CountProblem.NOT_A_NUMBER, "SUC-2" to CountProblem.WHOLE_UNITS),
            StockCountRules.lines(typed).second
        )
    }

    @Test fun aScannedPackAddsOneAndAWeighedLotAsksForTheWeight() {
        val (once, e1) = StockCountRules.addScanned(rows, "suc-1")
        assertEquals(ScanEffect.ADDED_ONE, e1)
        assertEquals("1", once[0].counted)
        val (twice, _) = StockCountRules.addScanned(once, "SUC-1")
        assertEquals("2", twice[0].counted)
        assertEquals(ScanEffect.TYPE_THE_WEIGHT, StockCountRules.addScanned(rows, "KZB-1").second)
        assertEquals(ScanEffect.NOT_LISTED, StockCountRules.addScanned(rows, "ET-2026-001").second)
    }

    @Test fun theRequestIsWhatPostStockCountsExpects() {
        val body = JsonParser.parseString(StockCountRules.requestJson("MAGAZA-01", "Android POS", listOf(CountLine("SUC-1", 17.0, 20.0, "Kırık")))).asJsonObject
        assertEquals("MAGAZA-01", body["warehouse"].asString)
        val line = body["lines"].asJsonArray[0].asJsonObject
        assertEquals("SUC-1", line["lot_no"].asString)
        assertEquals(17.0, line["counted_qty"].asDouble, 0.0)
        assertEquals(20.0, line["expected_qty"].asDouble, 0.0)
        assertEquals("Kırık", line["reason"].asString)
    }
}
