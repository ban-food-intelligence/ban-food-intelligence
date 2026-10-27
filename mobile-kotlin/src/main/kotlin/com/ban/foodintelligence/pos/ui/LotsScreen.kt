package com.ban.foodintelligence.pos.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.catalog.AiSummary
import com.ban.foodintelligence.pos.catalog.CatalogSource
import com.ban.foodintelligence.pos.catalog.InsightsRepository
import com.ban.foodintelligence.pos.catalog.PosInventoryLot
import com.ban.foodintelligence.pos.catalog.TraceLink
import com.ban.foodintelligence.pos.catalog.TraceResult
import com.ban.foodintelligence.pos.ui.design.Fmt
import com.ban.foodintelligence.pos.ui.design.InsetGroup
import com.ban.foodintelligence.pos.ui.design.ListRow
import com.ban.foodintelligence.pos.ui.design.PosScreen
import com.ban.foodintelligence.pos.ui.design.PosType
import com.ban.foodintelligence.pos.ui.design.Segmented
import com.ban.foodintelligence.pos.ui.design.Shapes
import com.ban.foodintelligence.pos.ui.design.SkeletonGroup
import com.ban.foodintelligence.pos.ui.design.Space
import com.ban.foodintelligence.pos.ui.design.StateMessage
import com.ban.foodintelligence.pos.ui.design.palette
import kotlinx.coroutines.launch

@Composable
fun LotsScreen(state: PosAppState, trailing: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracing by remember { mutableStateOf<PosInventoryLot?>(null) }
    var showForecasts by remember { mutableStateOf(false) }
    val catalog = state.catalog

    PosScreen(title = stringResource(R.string.lots_title), trailing = { trailing() }) {
        catalogBanner(state)
        when {
            catalog == null -> item { SkeletonGroup(4) }
            catalog.source == CatalogSource.NONE -> item { ServerErrorState(catalog.error) { scope.launch { state.refreshAll() } } }
            state.lots.isEmpty() -> item { StateMessage(Icons.Rounded.Inventory2, stringResource(R.string.sell_empty)) }
            else -> item(key = "lots") {
                InsetGroup(header = stringResource(R.string.lots_section_shop)) {
                    state.lots.forEach { lot -> row { LotRow(lot) { tracing = lot } } }
                }
            }
        }
        item(key = "more") {
            InsetGroup(header = stringResource(R.string.lots_section_tools)) {
                row(dividerInset = 56.dp) {
                    ListRow(
                        title = stringResource(R.string.lots_receive_invoice),
                        icon = Icons.Rounded.DocumentScanner,
                        chevron = true,
                        onClick = { context.startActivity(Intent(context, InvoiceOcrActivity::class.java)) }
                    )
                }
                row(dividerInset = 56.dp) {
                    ListRow(
                        title = stringResource(R.string.lots_forecasts),
                        subtitle = stringResource(R.string.lots_forecasts_sub),
                        icon = Icons.Rounded.Insights,
                        chevron = true,
                        onClick = { showForecasts = true }
                    )
                }
            }
        }
    }

    tracing?.let { lot -> TraceSheet(lot) { tracing = null } }
    if (showForecasts) ForecastSheet { showForecasts = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraceSheet(lot: PosInventoryLot, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var direction by remember { mutableStateOf(0) } // 0 backward, 1 forward
    var result by remember(direction) { mutableStateOf<Result<TraceResult>?>(null) }
    LaunchedEffect(direction) {
        result = InsightsRepository.trace(context, lot.lotNo, if (direction == 0) "backward" else "forward")
    }

    ModalBottomSheet(
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.background,
        shape = Shapes.sheet
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.xl)) {
            Column(Modifier.padding(horizontal = Space.screen)) {
                Text(lot.productName, style = PosType.title, color = palette.label)
                Text(lot.lotNo, style = PosType.subhead, color = palette.secondaryLabel)
                Spacer(Modifier.height(Space.m))
                Segmented(
                    options = listOf(stringResource(R.string.trace_backward), stringResource(R.string.trace_forward)),
                    selected = direction,
                    onSelect = { direction = it }
                )
            }
            Spacer(Modifier.height(Space.s))
            val r = result
            when {
                r == null -> SkeletonGroup(3)
                r.isFailure -> ServerErrorState(r.exceptionOrNull()?.message) { direction = direction }
                else -> TraceTimeline(lot.lotNo, r.getOrThrow(), backward = direction == 0)
            }
        }
    }
}

@Composable
private fun TraceTimeline(rootLot: String, trace: TraceResult, backward: Boolean) {
    // Steps away from the root lot, nearest first.
    val depthOf = trace.lots.associate { it.lotNo to it.depth }
    val steps = trace.links.sortedBy { depthOf[if (backward) it.inputLotNo else it.outputLotNo] ?: 0 }
    if (steps.isEmpty() && trace.shipments.isEmpty()) {
        StateMessage(Icons.Rounded.Inventory2, stringResource(R.string.trace_empty))
        return
    }
    val lotsByNo = trace.lots.associateBy { it.lotNo }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.s).clip(Shapes.group).background(palette.group)) {
        Column(Modifier.padding(vertical = Space.s)) {
            TimelineNode(
                title = lotsByNo[rootLot]?.productName ?: rootLot,
                subtitle = rootLot,
                emphasised = true,
                last = steps.isEmpty()
            )
            steps.forEachIndexed { i, link ->
                val other = lotsByNo[if (backward) link.inputLotNo else link.outputLotNo]
                val supplier = other?.supplierName?.takeIf { it.isNotBlank() }
                TimelineNode(
                    title = other?.productName ?: (if (backward) link.inputLotNo else link.outputLotNo),
                    subtitle = listOfNotNull(
                        if (backward) link.inputLotNo else link.outputLotNo,
                        docLabel(link),
                        stringResource(R.string.trace_used, Fmt.qty(link.qtyConsumed, link.inputUom), Fmt.qty(link.qtyProduced, link.outputUom)),
                        supplier?.let { stringResource(R.string.trace_supplier, it) }
                    ).joinToString("\n"),
                    emphasised = false,
                    last = i == steps.lastIndex
                )
            }
        }
    }
    if (!backward && trace.shipments.isNotEmpty()) {
        InsetGroup(header = stringResource(R.string.trace_shipments)) {
            trace.shipments.forEach { s ->
                row(dividerInset = 56.dp) {
                    ListRow(
                        title = s.customerName,
                        subtitle = listOfNotNull(s.lotNo, expiryLabel(s.shipDate), if (s.tier > 1) stringResource(R.string.trace_tier2) else null).joinToString(" · "),
                        icon = Icons.Rounded.LocalShipping,
                        value = Fmt.qty(s.qty, s.uom)
                    )
                }
            }
        }
    }
}

@Composable
private fun docLabel(link: TraceLink): String = when (link.linkType) {
    "PRODUCTION" -> stringResource(R.string.trace_doc_production, link.documentNo)
    "BUTCHERY" -> stringResource(R.string.trace_doc_butchery, link.documentNo)
    else -> stringResource(R.string.trace_doc_rework, link.documentNo)
}

/** One step of the vertical timeline: dot + connecting line, then text. */
@Composable
private fun TimelineNode(title: String, subtitle: String, emphasised: Boolean, last: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = Space.m)) {
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (emphasised) palette.accent else palette.tertiaryLabel))
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(palette.separator))
        }
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = Space.s)) {
            Text(title, style = PosType.body, color = palette.label)
            Text(subtitle, style = PosType.subhead, color = palette.secondaryLabel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForecastSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<Result<AiSummary>?>(null) }
    LaunchedEffect(Unit) { result = InsightsRepository.aiSummary(context) }

    ModalBottomSheet(
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.background,
        shape = Shapes.sheet
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(stringResource(R.string.ai_title), style = PosType.title, color = palette.label, modifier = Modifier.padding(horizontal = Space.screen))
            val r = result
            when {
                r == null -> SkeletonGroup(4)
                r.isFailure -> ServerErrorState(r.exceptionOrNull()?.message) {}
                else -> ForecastContent(r.getOrThrow())
            }
        }
    }
}

@Composable
private fun ForecastContent(summary: AiSummary) {
    summary.comparisons.groupBy { it.refNo }.forEach { (ref, rows) ->
        val header = if (rows.first().task == "PRODUCTION") stringResource(R.string.ai_section_production, ref)
        else stringResource(R.string.ai_section_butchery, ref)
        InsetGroup(header = header) {
            rows.forEach { c ->
                row {
                    val unitIsEur = c.target == "ekp"
                    fun fmt(v: Double?): String = when {
                        v == null -> "-"
                        unitIsEur -> Fmt.money(v)
                        c.target == "actual_qty" -> Fmt.number(v, 1)
                        else -> "${Fmt.number(v, 2)} kg"
                    }
                    fun pct(v: Double?): String = if (v == null) "" else " (${if (v > 0) "+" else ""}${Fmt.number(v, 1)} %)"
                    ListRow(
                        title = targetLabel(c.target),
                        subtitle = "${stringResource(R.string.ai_model)} ${fmt(c.model)}${pct(c.modelDevPct)}\n" +
                            "${stringResource(R.string.ai_baseline)} ${fmt(c.baseline)}${pct(c.baselineDevPct)}",
                        value = "${stringResource(R.string.ai_actual)} ${fmt(c.actual)}"
                    )
                }
            }
        }
    }
    val models = summary.metrics.filter { it.role == "MODEL" }
    if (models.isNotEmpty()) {
        InsetGroup(header = stringResource(R.string.ai_error_header), footer = stringResource(R.string.ai_note)) {
            models.forEach { m ->
                val base = summary.metrics.firstOrNull { it.task == m.task && it.target == m.target && it.model == "baseline_hist_mean" }
                row {
                    ListRow(
                        title = targetLabel(m.target),
                        value = stringResource(
                            R.string.ai_metric_value,
                            "${Fmt.number(m.mapePct, 2)} %",
                            base?.let { "${Fmt.number(it.mapePct, 2)} %" } ?: "-"
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun targetLabel(target: String): String = when (target) {
    "actual_qty" -> stringResource(R.string.ai_target_actual_qty)
    "ekp" -> stringResource(R.string.ai_target_ekp)
    "but_kg" -> stringResource(R.string.ai_target_but_kg)
    "sirt_kg" -> stringResource(R.string.ai_target_sirt_kg)
    "omuz_kg" -> stringResource(R.string.ai_target_omuz_kg)
    "kaburga_kg" -> stringResource(R.string.ai_target_kaburga_kg)
    "kiyma_kg" -> stringResource(R.string.ai_target_kiyma_kg)
    "fire_kg" -> stringResource(R.string.ai_target_fire_kg)
    "part_kg" -> stringResource(R.string.ai_target_part_kg)
    else -> target
}
