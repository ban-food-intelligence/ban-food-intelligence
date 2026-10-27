package com.ban.foodintelligence.pos.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Tokens. Every screen takes colours, type, spacing and shapes from here.
// ---------------------------------------------------------------------------

/** iOS-style semantic palette. One accent; green/orange/red only carry meaning. */
@Immutable
data class Palette(
    val background: Color,      // grouped background
    val group: Color,           // inset group / sheet surface
    val label: Color,           // primary text
    val secondaryLabel: Color,  // secondary text (AA on group)
    val tertiaryLabel: Color,   // placeholders, chevrons
    val separator: Color,
    val fill: Color,            // pills, skeletons, input backgrounds
    val accent: Color,
    val onAccent: Color,
    val ok: Color,              // fill / icon
    val okText: Color,          // text on group (AA)
    val warning: Color,
    val warningText: Color,
    val danger: Color,
    val dangerText: Color,
    val isDark: Boolean
)

val LightPalette = Palette(
    background = Color(0xFFF2F2F7),
    group = Color(0xFFFFFFFF),
    label = Color(0xFF1C1C1E),
    secondaryLabel = Color(0xFF6C6C70),
    tertiaryLabel = Color(0xFFAEAEB2),
    separator = Color(0xFFC6C6C8),
    fill = Color(0xFFE9E9EE),
    accent = Color(0xFF007AFF),
    onAccent = Color.White,
    ok = Color(0xFF34C759),
    okText = Color(0xFF248A3D),
    warning = Color(0xFFFF9500),
    warningText = Color(0xFFC93400),
    danger = Color(0xFFFF3B30),
    dangerText = Color(0xFFD70015),
    isDark = false
)

val DarkPalette = Palette(
    background = Color(0xFF000000),
    group = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0xFF98989F),
    tertiaryLabel = Color(0xFF5A5A5F),
    separator = Color(0xFF38383A),
    fill = Color(0xFF2C2C2E),
    accent = Color(0xFF0A84FF),
    onAccent = Color.White,
    ok = Color(0xFF30D158),
    okText = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    warningText = Color(0xFFFF9F0A),
    danger = Color(0xFFFF453A),
    dangerText = Color(0xFFFF6961),
    isDark = true
)

/** iOS text styles (system font, sentence case). Screens use at most three of these. */
object PosType {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    /** Large figures (totals, stock) with tabular digits. */
    val figure = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val mono = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
}

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val screen = 16.dp        // side margin
    val rowMinHeight = 48.dp  // touch target
}

object Shapes {
    val group = RoundedCornerShape(12.dp)
    val button = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(50)
    val sheet = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** App theme: palette follows the system light/dark setting. */
@Composable
fun PosTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.background, onBackground = p.label,
            surface = p.group, onSurface = p.label, surfaceVariant = p.fill, onSurfaceVariant = p.secondaryLabel,
            outline = p.separator, error = p.danger, surfaceContainerLow = p.group, surfaceContainer = p.group
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.background, onBackground = p.label,
            surface = p.group, onSurface = p.label, surfaceVariant = p.fill, onSurfaceVariant = p.secondaryLabel,
            outline = p.separator, error = p.danger, surfaceContainerLow = p.group, surfaceContainer = p.group
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val palette: Palette
    @Composable get() = LocalPalette.current

// ---------------------------------------------------------------------------
// Formatting (locale-aware; TR/DE "18,16 €", EN "€18.16")
// ---------------------------------------------------------------------------

object Fmt {
    private val EUR: Currency = Currency.getInstance("EUR")

    /** TR/DE: "18,16 €" (symbol after the number); EN: "€18.16". */
    fun money(value: Double, locale: Locale = Locale.getDefault()): String =
        if (locale.language == "tr" || locale.language == "de") "${number(value, 2, locale)} €"
        else NumberFormat.getCurrencyInstance(locale).apply { currency = EUR }.format(value)

    fun number(value: Double, decimals: Int, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }.format(value)

    /** "25,00 kg" / "25.00 kg"; whole units for PAKET/ADET. */
    fun qty(value: Double, uom: String, locale: Locale = Locale.getDefault()): String =
        if (uom.equals("KG", ignoreCase = true)) "${number(value, 2, locale)} kg"
        else "${number(value, 0, locale)} ${uom.lowercase(Locale.ROOT)}"

    fun date(epochMs: Long, locale: Locale = Locale.getDefault()): String {
        val pattern = if (locale.language == "en") "d MMM yyyy" else "dd.MM.yyyy"
        return SimpleDateFormat(pattern, locale).format(Date(epochMs))
    }

    fun dateTime(epochMs: Long, locale: Locale = Locale.getDefault()): String {
        val pattern = if (locale.language == "en") "d MMM yyyy, HH:mm" else "dd.MM.yyyy HH:mm"
        return SimpleDateFormat(pattern, locale).format(Date(epochMs))
    }
}

// ---------------------------------------------------------------------------
// Components
// ---------------------------------------------------------------------------

/** Screen header: large left-aligned title with an optional trailing slot. */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = PosType.largeTitle,
            color = palette.label,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        trailing()
    }
}

/** Grey section label above an inset group (sentence case, not all caps). */
@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = PosType.footnote,
        color = palette.secondaryLabel,
        modifier = Modifier.padding(start = Space.screen + Space.m, end = Space.screen, top = Space.l, bottom = Space.xs + 2.dp)
    )
}

@Composable
fun SectionFooter(text: String) {
    Text(
        text = text,
        style = PosType.footnote,
        color = palette.secondaryLabel,
        modifier = Modifier.padding(start = Space.screen + Space.m, end = Space.screen + Space.m, top = Space.xs + 2.dp)
    )
}

/** Collects rows for an [InsetGroup] so dividers can be drawn between them. */
class InsetGroupScope {
    internal val rows = mutableListOf<Pair<Dp, @Composable () -> Unit>>()

    /** [dividerInset] = start inset of the divider below this row (16dp, or 56dp after an icon). */
    fun row(dividerInset: Dp = Space.m, content: @Composable () -> Unit) {
        rows += dividerInset to content
    }
}

/** iOS inset grouped list: white rounded group, hairline left-inset dividers. */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: InsetGroupScope.() -> Unit
) {
    val scope = InsetGroupScope().apply(content)
    Column(modifier = modifier.fillMaxWidth()) {
        if (header != null) SectionHeader(header)
        // Plain background, not Surface: inside sheets Material would tint it (tonal elevation).
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen).clip(Shapes.group).background(palette.group)
        ) {
            Column {
                scope.rows.forEachIndexed { index, (inset, row) ->
                    row()
                    if (index < scope.rows.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = inset),
                            thickness = 0.5.dp,
                            color = palette.separator
                        )
                    }
                }
            }
        }
        if (footer != null) SectionFooter(footer)
    }
}

/**
 * Standard list row: optional leading icon, title + subtitle, trailing value/content,
 * optional chevron. [accentBar] draws a thin coloured bar on the left (e.g. recalled lot).
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    value: String? = null,
    chevron: Boolean = false,
    accentBar: Color? = null,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Space.rowMinHeight)
            .then(clickModifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (accentBar != null) {
            Box(Modifier.width(3.dp).height(44.dp).background(accentBar))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (accentBar != null) Space.m - 3.dp else Space.m, end = Space.m, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint ?: palette.accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Space.m))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = PosType.body, color = titleColor ?: palette.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = PosType.subhead, color = palette.secondaryLabel, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (value != null) {
                Spacer(Modifier.width(Space.s))
                Text(value, style = PosType.body, color = palette.secondaryLabel, maxLines = 1)
            }
            if (trailing != null) {
                Spacer(Modifier.width(Space.s))
                trailing()
            }
            if (chevron) {
                Spacer(Modifier.width(Space.xs))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = palette.tertiaryLabel,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/** Row with a trailing checkmark for single-choice lists (iOS style radio). */
@Composable
fun CheckRow(title: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    ListRow(title = title, subtitle = subtitle, onClick = onClick) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
    }
}

/** The one primary action of a screen: full width, 50dp, accent fill. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null
) {
    val container = if (destructive) palette.danger else palette.accent
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Shapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = Color.White,
            disabledContainerColor = palette.fill,
            disabledContentColor = palette.secondaryLabel
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = Space.m),
        modifier = modifier.fillMaxWidth().height(50.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = PosType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Secondary actions are plain accent-coloured text buttons. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Space.rowMinHeight)) {
        Text(text, style = PosType.body, color = if (enabled) (color ?: palette.accent) else palette.tertiaryLabel)
    }
}

enum class PillKind { Ok, Warning, Danger, Neutral }

/** Small status pill: "Synced", "Pending 3", "Offline", "Recalled". */
@Composable
fun StatusPill(text: String, kind: PillKind, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val p = palette
    val (bg, fg) = when (kind) {
        PillKind.Ok -> p.ok.copy(alpha = 0.16f) to p.okText
        PillKind.Warning -> p.warning.copy(alpha = 0.18f) to p.warningText
        PillKind.Danger -> p.danger.copy(alpha = 0.14f) to p.dangerText
        PillKind.Neutral -> p.fill to p.secondaryLabel
    }
    Row(
        modifier = modifier.clip(Shapes.pill).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = PosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** Big figure with a smaller, lighter currency/unit part, e.g. "18,16" + " €". */
@Composable
fun Figure(formatted: String, modifier: Modifier = Modifier, color: Color? = null) {
    val p = palette
    val text = buildAnnotatedString {
        formatted.forEach { ch ->
            if (ch.isDigit() || ch == ',' || ch == '.' || ch == '-') {
                withStyle(SpanStyle(color = color ?: p.label)) { append(ch) }
            } else {
                withStyle(SpanStyle(color = p.secondaryLabel, fontSize = 22.sp, fontWeight = FontWeight.Medium)) { append(ch) }
            }
        }
    }
    Text(text, style = PosType.figure, modifier = modifier, maxLines = 1)
}

/** Empty / error state: small grey icon, one sentence, one action, optional details. */
@Composable
fun StateMessage(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    detailsLabel: String? = null,
    details: String? = null
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s)
    ) {
        Icon(icon, contentDescription = null, tint = palette.tertiaryLabel, modifier = Modifier.size(40.dp))
        Text(message, style = PosType.subhead, color = palette.secondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null && onAction != null) TextAction(actionLabel, onAction)
        if (details != null && detailsLabel != null) {
            Row(
                modifier = Modifier.clip(Shapes.pill).clickable { showDetails = !showDetails }.padding(horizontal = Space.s, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(detailsLabel, style = PosType.footnote, color = palette.secondaryLabel)
                Icon(
                    if (showDetails) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null, tint = palette.secondaryLabel, modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = showDetails, enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
                Text(details, style = PosType.caption.copy(fontFamily = FontFamily.Monospace), color = palette.secondaryLabel)
            }
        }
    }
}

/** Grey placeholder block for loading skeletons. */
@Composable
fun SkeletonGroup(rows: Int = 3) {
    InsetGroup {
        repeat(rows) {
            row {
                Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(0.55f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(palette.fill))
                    Box(Modifier.fillMaxWidth(0.35f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(palette.fill))
                }
            }
        }
    }
}

/** Single-line label that shrinks (down to 70 %) instead of being cut off at large font scales. */
@Composable
fun FitText(text: String, fontSize: androidx.compose.ui.unit.TextUnit, color: Color, modifier: Modifier = Modifier) {
    var scale by remember(text) { mutableStateOf(1f) }
    Text(
        text = text,
        fontSize = fontSize * scale,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { r -> if (r.hasVisualOverflow && scale > 0.7f) scale -= 0.08f },
        modifier = modifier
    )
}

/** Vertical spacing helper used between groups. */
@Composable
fun ColumnScope.GroupGap() = Spacer(Modifier.height(Space.s))

/**
 * Screen scaffold: large left-aligned title that collapses into a compact centred title in
 * the top bar once the content scrolls; [trailing] (e.g. the sync pill) stays in the top bar.
 */
@Composable
fun PosScreen(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val collapsed by remember {
        androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 80 }
    }
    Column(modifier = modifier.fillMaxWidth().background(palette.background)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (collapsed) palette.group.copy(alpha = 0.96f) else palette.background)
                .statusBarsPadding()
                .height(44.dp)
                .padding(horizontal = Space.screen)
        ) {
            val titleAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (collapsed) 1f else 0f,
                animationSpec = tween(200),
                label = "compact-title"
            )
            Text(
                title,
                style = PosType.headline,
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp)
                    .then(Modifier.graphicsLayerAlpha(titleAlpha))
            )
            Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        if (collapsed) HorizontalDivider(thickness = 0.5.dp, color = palette.separator)
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = Space.xl),
            modifier = Modifier.fillMaxWidth()
        ) {
            item(key = "large-title") { LargeTitle(title) }
            content()
        }
    }
}

private fun Modifier.graphicsLayerAlpha(a: Float): Modifier = this.then(Modifier.alpha(a))

/** iOS segmented control (two or three equal options). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(palette.fill).padding(2.dp)
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSel) palette.group else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = PosType.footnote.copy(fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal),
                    color = palette.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Space.s)
                )
            }
        }
    }
}
