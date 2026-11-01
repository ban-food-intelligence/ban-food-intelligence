package com.ban.foodintelligence.pos.count

import com.google.gson.Gson
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * A stock count of this shop's warehouse, as typed (or scanned) on the phone. The same rules as
 * the dashboard's count form and the API: an empty row is not counted, packs and pieces are whole,
 * and every difference needs a reason. [expected] is the server's ledger balance when the list was
 * loaded; the API refuses the count (409) if it changed meanwhile.
 */
data class CountRow(
    val lotNo: String,
    val productName: String,
    val uom: String,
    val expected: Double,
    val counted: String = "",
    val reason: String = ""
) {
    val countsWholeUnits: Boolean get() = !uom.equals("KG", ignoreCase = true)
}

enum class CountProblem { NOT_A_NUMBER, WHOLE_UNITS, REASON_NEEDED }

data class CountLine(val lotNo: String, val countedQty: Double, val expectedQty: Double, val reason: String) {
    val diff: Double get() = round3(countedQty - expectedQty)
}

/** What a scan did to the list. */
enum class ScanEffect { ADDED_ONE, TYPE_THE_WEIGHT, NOT_LISTED }

object StockCountRules {
    /** null for an empty field; NaN for something that is not a non-negative number. */
    fun parseCount(text: String): Double? {
        val s = text.trim().replace(',', '.')
        if (s.isEmpty()) return null
        return if (Regex("""\d+(\.\d+)?""").matches(s)) s.toDouble() else Double.NaN
    }

    /** The lines to send for the counted rows, and per lot what blocks saving. */
    fun lines(rows: List<CountRow>): Pair<List<CountLine>, Map<String, CountProblem>> {
        val lines = mutableListOf<CountLine>()
        val problems = linkedMapOf<String, CountProblem>()
        for (r in rows) {
            val counted = parseCount(r.counted) ?: continue
            when {
                counted.isNaN() -> problems[r.lotNo] = CountProblem.NOT_A_NUMBER
                r.countsWholeUnits && counted != floor(counted) -> problems[r.lotNo] = CountProblem.WHOLE_UNITS
                else -> {
                    val diff = round3(counted - r.expected)
                    val reason = r.reason.trim()
                    if (diff != 0.0 && reason.isEmpty()) problems[r.lotNo] = CountProblem.REASON_NEEDED
                    lines += CountLine(r.lotNo, counted, r.expected, if (diff != 0.0) reason else "")
                }
            }
        }
        return lines to problems
    }

    /**
     * A scanned pack adds one to its lot (an empty field counts from zero). Weighed lots cannot be
     * counted by scanning: the screen asks for the weight instead.
     */
    fun addScanned(rows: List<CountRow>, lotNo: String): Pair<List<CountRow>, ScanEffect> {
        val i = rows.indexOfFirst { it.lotNo.equals(lotNo, ignoreCase = true) }
        if (i < 0) return rows to ScanEffect.NOT_LISTED
        val row = rows[i]
        if (!row.countsWholeUnits) return rows to ScanEffect.TYPE_THE_WEIGHT
        val now = parseCount(row.counted)?.takeIf { !it.isNaN() } ?: 0.0
        val next = rows.toMutableList().also { it[i] = row.copy(counted = (floor(now).toLong() + 1).toString()) }
        return next to ScanEffect.ADDED_ONE
    }

    /** The POST /stock-counts body. */
    fun requestJson(warehouse: String, note: String, lines: List<CountLine>): String = Gson().toJson(
        mapOf(
            "warehouse" to warehouse,
            "note" to note,
            "lines" to lines.map {
                mapOf("lot_no" to it.lotNo, "counted_qty" to it.countedQty, "expected_qty" to it.expectedQty, "reason" to it.reason)
            }
        )
    )
}

internal fun round3(v: Double): Double = (v * 1000).roundToLong() / 1000.0
