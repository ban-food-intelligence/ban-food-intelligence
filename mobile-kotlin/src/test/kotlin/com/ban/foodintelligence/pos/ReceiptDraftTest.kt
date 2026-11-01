package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.ocr.DraftProblem
import com.ban.foodintelligence.pos.ocr.InvoiceSuggestion
import com.ban.foodintelligence.pos.ocr.ReceiptDraft
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptDraftTest {
    // the shape POST /purchases/invoice answers with (api-go InvoiceSuggestionDto)
    private val answer = """
        {"supplier_name":"ÖRNEK ET TEDARİK A.Ş.","supplier_code":"TED-ET-01","supplier_match":"EXACT",
         "invoice_no":"FTR-2026-0912","invoice_date":"2026-10-30","model":"m","latency_ms":2400,
         "lines":[
          {"product_name":"DANA ET SUCUKLUK","sku":"HM-ET-DANA","sku_name":"Dana et (sucukluk)","match":"EXACT","qty":120.5,"unit":"KG",
           "unit_price":9.8,"currency":"EUR","supplier_lot_no":"TED-B-4411","expiry_date":"2026-11-08","warnings":[]},
          {"product_name":"Zeytinyağı 5 litre","sku":"","match":"NONE","qty":2,"unit":"ADET","unit_price":null,"currency":"EUR",
           "supplier_lot_no":"","expiry_date":"","warnings":["NO_PRODUCT_MATCH","PRICE_MISSING"]}]}
    """.trimIndent()

    private val today = LocalDate.of(2026, 11, 1)

    @Test fun suggestionsFillTheDraftButNeverOurLotNumber() {
        val d = ReceiptDraft.from(InvoiceSuggestion.parse(answer), today, "ALS-POS-1")
        assertEquals("TED-ET-01", d.supplierCode)
        assertEquals("2026-11-01", d.receiptDate) // goods arrive today, not on the invoice date
        assertEquals(listOf("HM-ET-DANA", ""), d.lines.map { it.sku })
        assertEquals(listOf("", ""), d.lines.map { it.lotNo })
        assertEquals(listOf("120.5", "2"), d.lines.map { it.qty })
        assertEquals(listOf("9.8", ""), d.lines.map { it.unitPrice })
        assertEquals("TED-B-4411", d.lines[0].supplierLotNo)
        assertEquals(listOf("NO_PRODUCT_MATCH", "PRICE_MISSING"), d.lines[1].warnings)
    }

    @Test fun nothingCanBeSentUntilEveryRequiredFieldIsThere() {
        val d = ReceiptDraft.from(InvoiceSuggestion.parse(answer), today, "ALS-POS-1")
        assertEquals(
            mapOf(
                "lines[0].lot_no" to DraftProblem.REQUIRED,
                "lines[1].sku" to DraftProblem.REQUIRED, "lines[1].lot_no" to DraftProblem.REQUIRED,
                "lines[1].unit_price" to DraftProblem.REQUIRED
            ),
            d.problems()
        )
        val bad = d.copy(receiptDate = "1.11.2026", lines = listOf(d.lines[0].copy(lotNo = "ET-1", qty = "0", unitPrice = "abc", expiryDate = "8.11.26")))
        assertEquals(
            mapOf(
                "receipt_date" to DraftProblem.NOT_A_DATE, "lines[0].qty" to DraftProblem.NOT_A_NUMBER,
                "lines[0].unit_price" to DraftProblem.NOT_A_NUMBER, "lines[0].expiry_date" to DraftProblem.NOT_A_DATE
            ),
            bad.problems()
        )
        assertEquals(DraftProblem.REQUIRED, d.copy(lines = emptyList()).problems()["lines"])
    }

    @Test fun theRequestIsWhatPostPurchasesExpects() {
        val d = ReceiptDraft.from(InvoiceSuggestion.parse(answer), today, "als-pos-1")
        val ready = d.copy(lines = listOf(d.lines[0].copy(lotNo = "et-2026-099", qty = "120,5")))
        assertTrue(ready.problems().isEmpty())
        val body = JsonParser.parseString(ready.toRequestJson()).asJsonObject
        assertEquals("ALS-POS-1", body["receipt_no"].asString)
        assertEquals("TED-ET-01", body["supplier_code"].asString)
        assertEquals("FTR-2026-0912", body["invoice_no"].asString)
        val line = body["lines"].asJsonArray[0].asJsonObject
        assertEquals("ET-2026-099", line["lot_no"].asString)
        assertEquals(120.5, line["qty"].asDouble, 0.0)
        assertEquals(9.8, line["unit_price"].asDouble, 0.0)
        assertEquals("2026-11-08", line["expiry_date"].asString)
    }

    @Test fun numbersAreReadWithCommaOrDotAndShownPlain() {
        assertEquals(12.5, ReceiptDraft.number(" 12,5 ")!!, 0.0)
        assertEquals(null, ReceiptDraft.number(""))
        assertEquals(null, ReceiptDraft.number("NaN"))
        assertEquals("30", ReceiptDraft.plain(30.0))
        assertEquals("0", ReceiptDraft.plain(0.0))
        assertEquals("", ReceiptDraft.plain(null))
    }
}
