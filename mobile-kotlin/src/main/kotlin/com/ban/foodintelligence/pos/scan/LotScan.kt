package com.ban.foodintelligence.pos.scan

import com.ban.foodintelligence.pos.catalog.PosInventoryLot
import java.time.LocalDate
import java.time.YearMonth

/**
 * What a scanned barcode says about a lot. Our lot labels carry the lot number itself (QR from
 * the dashboard's lot card, also typed by a keyboard-wedge hand scanner); a GS1 pack label carries
 * the batch in AI 10 and the expiry date in AI 17. On a pack we produced, that batch is our lot
 * number, so it is looked up like one.
 */
data class LotScanCode(val lotNo: String?, val batch: String?, val expiry: LocalDate?) {
    /** The values a lot number may be found under, upper-cased, without duplicates. */
    val keys: List<String> get() = listOfNotNull(lotNo, batch).map { it.uppercase() }.distinct()
}

/** Where a scanned code leads on this terminal. */
sealed class LotScanResult {
    /** A lot of this shop's catalog: sell it, or show why it is blocked. */
    data class Found(val lot: PosInventoryLot) : LotScanResult()

    /** On an active recall list but not stocked here: the pack in hand must not be sold. */
    data class RecalledElsewhere(val lotNo: String) : LotScanResult()

    /** Nothing known under this code. */
    data class Unknown(val code: String) : LotScanResult()
}

object LotScan {
    private const val GS = '\u001D' // FNC1 as a hand scanner or ML Kit reports it

    // GS1 application identifiers with a fixed data length; any other known one runs to GS or the end.
    private val fixedLength = mapOf(
        "00" to 18, "01" to 14, "02" to 14, "11" to 6, "12" to 6, "13" to 6, "15" to 6, "16" to 6, "17" to 6, "20" to 2
    )
    private val variableAis = setOf("10", "21", "22", "30", "37", "240", "241", "250", "400", "90")
    private val parenthesised = Regex("""^\((\d{2,4})\)""")

    /** Parses a raw scan; null for an empty one. */
    fun parse(raw: String): LotScanCode? {
        val s = raw.trim().removePrefix("]C1").removePrefix("]Q3").removePrefix("]d2").removePrefix("]e0").trim()
        if (s.isEmpty()) return null
        val gs1 = when {
            s.startsWith("(") -> parseParenthesised(s)
            s.contains(GS) || looksLikeRawGs1(s) -> parseRaw(s.trimStart(GS))
            else -> null
        }
        if (gs1 != null && (gs1.containsKey("10") || gs1.containsKey("17"))) {
            return LotScanCode(lotNo = null, batch = gs1["10"]?.trim()?.ifEmpty { null }, expiry = gs1["17"]?.let(::gs1Date))
        }
        return LotScanCode(lotNo = s.uppercase(), batch = null, expiry = null)
    }

    /** A GTIN-led element string: "01" and 14 digits, then more data. Our lot numbers have letters. */
    private fun looksLikeRawGs1(s: String): Boolean =
        s.length > 16 && s.startsWith("01") && s.substring(2, 16).all { it.isDigit() }

    private fun parseParenthesised(s: String): Map<String, String>? {
        val out = linkedMapOf<String, String>()
        var rest = s
        while (rest.isNotEmpty()) {
            val m = parenthesised.find(rest) ?: return if (out.isEmpty()) null else out
            val ai = m.groupValues[1]
            rest = rest.substring(m.value.length)
            val end = rest.indexOf('(').let { if (it < 0) rest.length else it }
            out[ai] = rest.substring(0, end)
            rest = rest.substring(end)
        }
        return out
    }

    private fun parseRaw(s: String): Map<String, String>? {
        val out = linkedMapOf<String, String>()
        var i = 0
        while (i < s.length) {
            if (s[i] == GS) { i++; continue }
            val ai = (2..4).map { n -> if (i + n <= s.length) s.substring(i, i + n) else "" }
                .firstOrNull { it in fixedLength || it in variableAis || isMeasure(it) }
                ?: return if (out.isEmpty()) null else out // an AI we do not know: stop, do not guess
            i += ai.length
            val len = fixedLength[ai] ?: if (isMeasure(ai)) 6 else null
            val end = if (len != null) minOf(i + len, s.length) else s.indexOf(GS, i).let { if (it < 0) s.length else it }
            out[ai] = s.substring(i, end)
            i = end
        }
        return out
    }

    /** 310n..369n: four-digit measure AIs (net weight etc.) with six digits of data. */
    private fun isMeasure(ai: String): Boolean = ai.length == 4 && ai.all { it.isDigit() } && ai.substring(0, 2).toInt() in 31..36

    /** GS1 YYMMDD; day 00 means the last day of the month. */
    internal fun gs1Date(v: String): LocalDate? {
        if (v.length != 6 || !v.all { it.isDigit() }) return null
        val year = 2000 + v.substring(0, 2).toInt()
        val month = v.substring(2, 4).toInt()
        val day = v.substring(4, 6).toInt()
        return runCatching {
            if (day == 0) YearMonth.of(year, month).atEndOfMonth() else LocalDate.of(year, month, day)
        }.getOrNull()
    }

    /** Finds the lot: by our lot number first, then by the supplier batch; recall lists count too. */
    fun resolve(code: LotScanCode, lots: List<PosInventoryLot>, recalledLotNos: Collection<String>): LotScanResult {
        val keys = code.keys
        lots.firstOrNull { it.lotNo.uppercase() in keys }?.let { return LotScanResult.Found(it) }
        val recalled = recalledLotNos.associateBy { it.uppercase() }
        keys.firstNotNullOfOrNull { recalled[it] }?.let { return LotScanResult.RecalledElsewhere(it) }
        return LotScanResult.Unknown(code.lotNo ?: code.batch ?: "")
    }
}
