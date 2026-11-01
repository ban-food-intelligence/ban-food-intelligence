package com.ban.foodintelligence.pos.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.catalog.PosCatalogRepository
import com.ban.foodintelligence.pos.count.CountProblem
import com.ban.foodintelligence.pos.count.CountRow
import com.ban.foodintelligence.pos.count.ScanEffect
import com.ban.foodintelligence.pos.count.StockCountRules
import com.ban.foodintelligence.pos.net.ApiError
import com.ban.foodintelligence.pos.net.ServerApi
import com.ban.foodintelligence.pos.scan.CameraLotScanner
import com.ban.foodintelligence.pos.scan.LotScan
import com.ban.foodintelligence.pos.ui.design.Fmt
import com.ban.foodintelligence.pos.ui.design.InsetGroup
import com.ban.foodintelligence.pos.ui.design.PosType
import com.ban.foodintelligence.pos.ui.design.PrimaryButton
import com.ban.foodintelligence.pos.ui.design.Shapes
import com.ban.foodintelligence.pos.ui.design.Space
import com.ban.foodintelligence.pos.ui.design.StateMessage
import com.ban.foodintelligence.pos.ui.design.TextAction
import com.ban.foodintelligence.pos.ui.design.palette
import com.google.gson.JsonParser
import kotlinx.coroutines.launch

/** The store lots as the server last reported them (not minus local sales): what the ledger says. */
private fun countRows(state: PosAppState): List<CountRow> =
    state.catalog?.lots.orEmpty().map { CountRow(it.lotNo, it.productName, it.uom, it.availableQty) }

/**
 * Stock count of this shop on the phone (POST /stock-counts with the supervisor PIN), the same rules
 * as the dashboard: empty rows are not counted, packs are whole, a difference needs a reason and is
 * booked as a count adjustment. Only online and with nothing unsent: an unsynced sale would make
 * the shelf look short and then reduce the stock a second time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockCountSheet(state: PosAppState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(countRows(state)) }
    var pin by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    val (lines, problems) = StockCountRules.lines(rows)
    val scanUnavailable = stringResource(R.string.scan_unavailable)
    val changedText = stringResource(R.string.count_changed)

    fun onScanned(raw: String) {
        val code = LotScan.parse(raw) ?: return
        val lotNo = code.keys.firstOrNull { k -> rows.any { it.lotNo.equals(k, ignoreCase = true) } } ?: code.keys.firstOrNull().orEmpty()
        val (next, effect) = StockCountRules.addScanned(rows, lotNo)
        rows = next
        val text = when (effect) {
            ScanEffect.ADDED_ONE -> context.getString(R.string.count_scan_added, lotNo, next.first { it.lotNo.equals(lotNo, true) }.counted)
            ScanEffect.TYPE_THE_WEIGHT -> context.getString(R.string.count_scan_weight, lotNo)
            ScanEffect.NOT_LISTED -> context.getString(R.string.count_scan_not_listed, lotNo)
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun save() {
        attempted = true
        message = null
        if (problems.isNotEmpty() || lines.isEmpty()) return
        saving = true
        scope.launch {
            try {
                val note = "Android POS " + com.ban.foodintelligence.pos.security.DeviceIdentity.id(context)
                val answer = JsonParser.parseString(
                    ServerApi.post(context, "/stock-counts", StockCountRules.requestJson(PosCatalogRepository.STORE_WAREHOUSE, note, lines), pin)
                ).asJsonObject
                saved = context.getString(
                    R.string.count_saved, answer.get("count_no").asString, answer.getAsJsonArray("lines").size(),
                    Fmt.money(answer.get("shortage_eur").asDouble), Fmt.money(answer.get("surplus_eur").asDouble)
                )
                state.refreshAll()
            } catch (e: ApiError) {
                if (e.status == 409 && e.code == "CONFLICT") {
                    state.refreshAll() // a sale synced meanwhile: count again against the new balance
                    rows = countRows(state)
                    message = changedText
                } else {
                    message = e.message
                }
            } catch (e: RuntimeException) {
                message = e.message
            } finally {
                saving = false
            }
        }
    }

    ModalBottomSheet(
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.background,
        shape = Shapes.sheet
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(stringResource(R.string.count_title), style = PosType.title, color = palette.label, modifier = Modifier.padding(horizontal = Space.screen))
            val blocked = when {
                !state.isLive -> stringResource(R.string.count_offline_block)
                state.pendingCount > 0 -> stringResource(R.string.count_pending_block, state.pendingCount)
                else -> null
            }
            when {
                saved != null -> {
                    Text(saved.orEmpty(), style = PosType.body, color = palette.okText, modifier = Modifier.padding(horizontal = Space.screen))
                    TextAction(stringResource(R.string.ocr_back), onClick = onDismiss, modifier = Modifier.padding(horizontal = Space.screen))
                }
                blocked != null -> {
                    Text(blocked, style = PosType.body, color = palette.warningText, modifier = Modifier.padding(horizontal = Space.screen))
                    if (state.pendingCount > 0) TextAction(stringResource(R.string.count_sync_now), onClick = { state.requestSync() }, modifier = Modifier.padding(horizontal = Space.screen))
                }
                rows.isEmpty() -> StateMessage(Icons.Rounded.QrCodeScanner, stringResource(R.string.count_empty))
                else -> {
                    TextAction(
                        stringResource(R.string.count_scan),
                        onClick = { CameraLotScanner.scan(context, onCode = { onScanned(it) }, onUnavailable = { Toast.makeText(context, scanUnavailable, Toast.LENGTH_LONG).show() }) },
                        modifier = Modifier.padding(horizontal = Space.screen)
                    )
                    InsetGroup(footer = stringResource(R.string.count_open_sub)) {
                        rows.forEachIndexed { i, r ->
                            row {
                                CountRowEditor(
                                    row = r,
                                    problem = if (attempted) problems[r.lotNo] else null,
                                    onChange = { changed -> rows = rows.mapIndexed { j, old -> if (j == i) changed else old } }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                        label = { Text(stringResource(R.string.ocr_pin_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen)
                    )
                    if (attempted && lines.isEmpty()) {
                        Text(stringResource(R.string.count_nothing), style = PosType.footnote, color = palette.dangerText, modifier = Modifier.padding(horizontal = Space.screen))
                    }
                    message?.let { Text(it, style = PosType.footnote, color = palette.dangerText, modifier = Modifier.padding(horizontal = Space.screen)) }
                    Spacer(Modifier.height(Space.s))
                    PrimaryButton(
                        text = stringResource(R.string.count_save),
                        onClick = { save() },
                        enabled = !saving && pin.length >= 4,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen)
                    )
                }
            }
        }
    }
}

@Composable
private fun CountRowEditor(row: CountRow, problem: CountProblem?, onChange: (CountRow) -> Unit) {
    val counted = StockCountRules.parseCount(row.counted)
    val differs = counted != null && !counted.isNaN() && kotlin.math.abs(counted - row.expected) > 0.0005
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.productName, style = PosType.body, color = palette.label)
        Text("${row.lotNo} · " + stringResource(R.string.count_expected, Fmt.qty(row.expected, row.uom)), style = PosType.subhead, color = palette.secondaryLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedTextField(
                value = row.counted,
                onValueChange = { onChange(row.copy(counted = it)) },
                label = { Text(stringResource(R.string.count_counted)) },
                singleLine = true,
                isError = problem == CountProblem.NOT_A_NUMBER || problem == CountProblem.WHOLE_UNITS,
                suffix = { Text(Fmt.unit(row.uom), style = PosType.body, color = palette.secondaryLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = if (row.countsWholeUnits) KeyboardType.Number else KeyboardType.Decimal),
                shape = Shapes.button,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.accent, unfocusedBorderColor = palette.separator),
                modifier = Modifier.weight(1f)
            )
            if (differs) {
                OutlinedTextField(
                    value = row.reason,
                    onValueChange = { onChange(row.copy(reason = it.take(255))) },
                    label = { Text(stringResource(R.string.count_reason)) },
                    singleLine = true,
                    isError = problem == CountProblem.REASON_NEEDED,
                    shape = Shapes.button,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.accent, unfocusedBorderColor = palette.separator),
                    modifier = Modifier.weight(1.4f)
                )
            }
        }
        problem?.let {
            Text(
                stringResource(
                    when (it) {
                        CountProblem.NOT_A_NUMBER -> R.string.count_problem_number
                        CountProblem.WHOLE_UNITS -> R.string.count_problem_whole
                        CountProblem.REASON_NEEDED -> R.string.count_problem_reason
                    }
                ),
                style = PosType.footnote, color = palette.dangerText
            )
        }
    }
}
