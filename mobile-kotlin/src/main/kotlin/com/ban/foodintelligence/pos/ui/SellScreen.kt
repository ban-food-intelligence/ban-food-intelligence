package com.ban.foodintelligence.pos.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.catalog.CatalogSource
import com.ban.foodintelligence.pos.catalog.PosInventoryLot
import com.ban.foodintelligence.pos.catalog.sellableLots
import com.ban.foodintelligence.pos.ui.design.Figure
import com.ban.foodintelligence.pos.ui.design.Fmt
import com.ban.foodintelligence.pos.ui.design.InsetGroup
import com.ban.foodintelligence.pos.ui.design.ListRow
import com.ban.foodintelligence.pos.ui.design.PillKind
import com.ban.foodintelligence.pos.ui.design.PosScreen
import com.ban.foodintelligence.pos.ui.design.PosType
import com.ban.foodintelligence.pos.ui.design.PrimaryButton
import com.ban.foodintelligence.pos.ui.design.Shapes
import com.ban.foodintelligence.pos.ui.design.SkeletonGroup
import com.ban.foodintelligence.pos.ui.design.Space
import com.ban.foodintelligence.pos.ui.design.StateMessage
import com.ban.foodintelligence.pos.ui.design.StatusPill
import com.ban.foodintelligence.pos.ui.design.TextAction
import com.ban.foodintelligence.pos.ui.design.palette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SellScreen(state: PosAppState, trailing: @Composable () -> Unit, onOpenRecalls: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selling by remember { mutableStateOf<PosInventoryLot?>(null) }
    var blocked by remember { mutableStateOf<PosInventoryLot?>(null) }
    val scope = rememberCoroutineScope()
    val catalog = state.catalog
    val lots = state.lots.filter {
        query.isBlank() || it.productName.contains(query, ignoreCase = true) || it.lotNo.contains(query, ignoreCase = true)
    }
    val available = sellableLots(lots)
    val recalled = lots.filter { it.isBlocked }
    // Recalled lots that are not stocked in this shop (e.g. SUC-2026-045, all shipped to
    // markets) still show up when searched for, so a cashier can check a pack in hand.
    val recalledElsewhere = if (query.isBlank()) emptyList() else catalog?.recalls.orEmpty()
        .flatMap { rc -> rc.affectedLots.map { it to rc } }
        .filter { (lotNo, _) -> lotNo.contains(query.trim(), ignoreCase = true) && state.lots.none { it.lotNo == lotNo } }
        .distinctBy { it.first }

    PosScreen(title = stringResource(R.string.sell_title), trailing = { trailing() }) {
        item(key = "search") { SearchField(query) { query = it } }
        catalogBanner(state)
        val recalls = catalog?.recalls.orEmpty()
        if (recalls.isNotEmpty() && query.isBlank()) item(key = "recall-banner") {
            InsetGroup {
                row {
                    ListRow(
                        title = pluralStringResource(R.plurals.sell_recall_banner, recalls.size, recalls.size),
                        subtitle = recalls.joinToString(" · ") { it.recallCode },
                        icon = Icons.Rounded.ReportProblem,
                        iconTint = palette.dangerText,
                        chevron = true,
                        onClick = onOpenRecalls
                    )
                }
            }
        }

        when {
            catalog == null -> item(key = "skeleton") { SkeletonGroup(4) }
            catalog.source == CatalogSource.NONE -> item(key = "error") { ServerErrorState(catalog.error) { scope.launch { state.refreshAll() } } }
            state.lots.isEmpty() -> item(key = "empty") {
                StateMessage(Icons.Rounded.Inventory2, stringResource(R.string.sell_empty), actionLabel = stringResource(R.string.action_retry), onAction = { scope.launch { state.refreshAll() } })
            }
            available.isEmpty() && recalled.isEmpty() && recalledElsewhere.isEmpty() -> item(key = "nomatch") {
                StateMessage(Icons.Rounded.Search, stringResource(R.string.sell_no_match, query))
            }
            else -> {
                if (available.isNotEmpty()) item(key = "available") {
                    InsetGroup(header = stringResource(R.string.sell_section_available)) {
                        available.forEach { lot ->
                            row { LotRow(lot) { selling = lot } }
                        }
                    }
                }
                if (recalled.isNotEmpty() || recalledElsewhere.isNotEmpty()) item(key = "recalled") {
                    InsetGroup(header = stringResource(R.string.sell_section_blocked)) {
                        recalled.forEach { lot -> row { LotRow(lot) { blocked = lot } } }
                        recalledElsewhere.forEach { (lotNo, rc) ->
                            row {
                                ListRow(
                                    title = lotNo,
                                    subtitle = stringResource(R.string.lot_recalled_reason, rc.recallCode, rc.triggerLot),
                                    icon = Icons.Rounded.Lock,
                                    iconTint = palette.dangerText,
                                    accentBar = palette.danger
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selling?.let { lot ->
        SellSheet(lot = lot, state = state, onDismiss = { selling = null })
    }
    blocked?.let { lot -> BlockedSheet(lot) { blocked = null } }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = palette.secondaryLabel) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                androidx.compose.material3.IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel), tint = palette.secondaryLabel)
                }
            }
        } else null,
        placeholder = { Text(stringResource(R.string.sell_search_hint), style = PosType.body, color = palette.tertiaryLabel) },
        shape = Shapes.button,
        textStyle = PosType.body,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = palette.fill, unfocusedContainerColor = palette.fill,
            focusedBorderColor = palette.fill, unfocusedBorderColor = palette.fill,
            focusedTextColor = palette.label, unfocusedTextColor = palette.label
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.xs)
    )
}

/** Offline banner (cached list) as a slim row, not a warning box. */
fun androidx.compose.foundation.lazy.LazyListScope.catalogBanner(state: PosAppState) {
    val catalog = state.catalog ?: return
    if (catalog.source == CatalogSource.CACHED) {
        item(key = "offline") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen + Space.xs, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = palette.warningText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.catalog_offline_banner, Fmt.dateTime(catalog.fetchedAtMs)),
                    style = PosType.footnote, color = palette.secondaryLabel
                )
            }
        }
    }
}

@Composable
fun ServerErrorState(details: String?, onRetry: () -> Unit) {
    StateMessage(
        icon = Icons.Rounded.CloudOff,
        message = stringResource(R.string.error_server_unreachable),
        actionLabel = stringResource(R.string.action_retry),
        onAction = onRetry,
        detailsLabel = stringResource(R.string.error_details),
        details = details
    )
}

/** "2026-10-19" -> locale date; empty stays empty. */
fun expiryLabel(iso: String): String = runCatching {
    Fmt.date(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso)!!.time)
}.getOrDefault(iso)

@Composable
fun LotRow(lot: PosInventoryLot, onClick: () -> Unit) {
    val subtitle = buildString {
        append(lot.lotNo)
        // Expiry on its own line; the date itself never splits ("18 Oct" / "2026").
        if (lot.expiryDate.isNotBlank()) append('\n').append(stringResource(R.string.lot_expiry, expiryLabel(lot.expiryDate).replace(' ', '\u00A0')))
    }
    ListRow(
        title = lot.productName,
        subtitle = subtitle,
        accentBar = if (lot.isBlocked) palette.danger else null,
        chevron = !lot.isBlocked,
        onClick = onClick,
        modifier = Modifier.semantics { if (lot.isBlocked) contentDescription = "${lot.productName}, ${lot.lotNo}" }
    ) {
        if (lot.isBlocked) {
            StatusPill(stringResource(R.string.lot_recalled_pill), PillKind.Danger, Icons.Rounded.Lock)
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${Fmt.money(lot.unitPriceEur)} / ${Fmt.unit(lot.uom)}",
                    style = PosType.subhead.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = palette.label
                )
                Text(Fmt.qty(lot.availableQty, lot.uom), style = PosType.footnote.copy(fontFeatureSettings = "tnum"), color = palette.secondaryLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SellSheet(lot: PosInventoryLot, state: PosAppState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var input by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var soldQty by remember { mutableStateOf<Double?>(null) }
    val check = SaleQuantityValidator.check(input, lot.uom, lot.availableQty)
    val qty = (check as? QtyCheck.Valid)?.qty
    val isKg = lot.uom == "KG"

    fun step(delta: Double) {
        val current = input.replace(',', '.').toDoubleOrNull() ?: 0.0
        val next = (current + delta).coerceIn(0.0, lot.availableQty)
        input = if (isKg) String.format(Locale.getDefault(), "%.2f", next) else next.toLong().toString()
    }

    ModalBottomSheet(
        tonalElevation = 0.dp,
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.background,
        shape = Shapes.sheet
    ) {
        val sold = soldQty
        if (sold != null) {
            SaleDone(qty = Fmt.qty(sold, lot.uom), product = lot.productName, onNext = onDismiss)
            return@ModalBottomSheet
        }
        Column(Modifier.padding(bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Column(Modifier.padding(horizontal = Space.screen)) {
                Text(lot.productName, style = PosType.title, color = palette.label)
                Text(lot.lotNo, style = PosType.subhead, color = palette.secondaryLabel)
            }
            InsetGroup {
                row { ListRow(stringResource(R.string.sell_sheet_price), value = "${Fmt.money(lot.unitPriceEur)} / ${Fmt.unit(lot.uom)}") }
                row { ListRow(stringResource(R.string.sell_sheet_in_stock), value = Fmt.qty(lot.availableQty, lot.uom)) }
                if (lot.expiryDate.isNotBlank()) row { ListRow(stringResource(R.string.lot_expiry, "").trim(), value = expiryLabel(lot.expiryDate)) }
            }
            InsetGroup(header = stringResource(R.string.sell_sheet_quantity)) {
                row {
                    Row(
                        modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s)
                    ) {
                        StepButton(Icons.Rounded.Remove, stringResource(R.string.sell_decrease)) { step(if (isKg) -0.1 else -1.0) }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            isError = check is QtyCheck.Invalid && input.isNotBlank(),
                            keyboardOptions = KeyboardOptions(keyboardType = if (isKg) KeyboardType.Decimal else KeyboardType.Number),
                            textStyle = PosType.title.copy(textAlign = TextAlign.Center, fontFeatureSettings = "tnum"),
                            suffix = { Text(Fmt.unit(lot.uom), style = PosType.body, color = palette.secondaryLabel) },
                            shape = Shapes.button,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.accent, unfocusedBorderColor = palette.separator),
                            modifier = Modifier.weight(1f)
                        )
                        StepButton(Icons.Rounded.Add, stringResource(R.string.sell_increase)) { step(if (isKg) 0.1 else 1.0) }
                    }
                }
            }
            val hint = when (check) {
                is QtyCheck.Invalid -> if (input.isBlank()) null else if (check.arg != null) stringResource(check.messageRes, check.arg) else stringResource(check.messageRes)
                is QtyCheck.Valid -> null
            }
            if (hint != null) {
                Text(hint, style = PosType.footnote, color = palette.dangerText, modifier = Modifier.padding(horizontal = Space.screen + Space.m))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen + Space.xs, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.sell_sheet_total), style = PosType.headline, color = palette.secondaryLabel, modifier = Modifier.weight(1f))
                Figure(if (qty != null) Fmt.money(qty * lot.unitPriceEur) else Fmt.money(0.0), color = if (qty != null) palette.label else palette.tertiaryLabel)
            }

            PrimaryButton(
                text = stringResource(R.string.sell_confirm),
                icon = Icons.Rounded.ShoppingCart,
                enabled = qty != null && !saving,
                onClick = {
                    val q = qty ?: return@PrimaryButton
                    saving = true
                    scope.launch {
                        state.appDb.recordPosSale(
                            lotNo = lot.lotNo, productName = lot.productName, qty = q, uom = lot.uom, unitPriceEur = lot.unitPriceEur
                        )
                        view.performHapticFeedback(
                            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
                        )
                        state.refreshLocal()
                        state.requestSync()
                        saving = false
                        soldQty = q
                    }
                },
                modifier = Modifier.padding(horizontal = Space.screen)
            )
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = palette.fill, contentColor = palette.accent),
        modifier = Modifier.size(48.dp)
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun SaleDone(qty: String, product: String, onNext: () -> Unit) {
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(top = Space.m, bottom = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s)
    ) {
        Box(
            modifier = Modifier.size(72.dp).scale(scale.value).clip(CircleShape).background(palette.ok),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(40.dp))
        }
        Text(stringResource(R.string.sell_done_title), style = PosType.title, color = palette.label)
        Text(
            stringResource(R.string.sell_done_body, qty, product),
            style = PosType.subhead, color = palette.secondaryLabel, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.s))
        PrimaryButton(text = stringResource(R.string.sell_next), onClick = onNext)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockedSheet(lot: PosInventoryLot, onDismiss: () -> Unit) {
    ModalBottomSheet(
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.background,
        shape = Shapes.sheet
    ) {
        Column(Modifier.padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(Modifier.padding(horizontal = Space.screen), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(palette.danger.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.danger)
                }
                Spacer(Modifier.width(Space.m))
                Column {
                    Text(lot.productName, style = PosType.headline, color = palette.label)
                    Text(stringResource(R.string.sell_section_blocked), style = PosType.subhead, color = palette.dangerText)
                }
            }
            InsetGroup(
                footer = if (lot.recallCode != null) stringResource(R.string.lot_recalled_reason, lot.recallCode, lot.recallRootLot.orEmpty())
                else stringResource(R.string.lot_blocked_reason)
            ) {
                row { ListRow(stringResource(R.string.lock_lots), value = lot.lotNo) }
                if (lot.recallCode != null) row { ListRow(stringResource(R.string.lock_recall), value = lot.recallCode) }
                if (!lot.recallRootLot.isNullOrBlank()) row { ListRow(stringResource(R.string.lock_root), value = lot.recallRootLot) }
                if (!lot.recallReason.isNullOrBlank()) row { ListRow(lot.recallReason) }
            }
            TextAction(stringResource(R.string.action_close), onClick = onDismiss, modifier = Modifier.padding(horizontal = Space.screen))
        }
    }
}

