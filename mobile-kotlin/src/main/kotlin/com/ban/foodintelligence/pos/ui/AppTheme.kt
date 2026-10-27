package com.ban.foodintelligence.pos.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ============================================================================
 * BAN Food Intelligence - Apple HIG / Stripe Terminal Enterprise Theme (`AppTheme`)
 * Author: Nuri (Lead UI/UX Engineer)
 * ============================================================================
 *
 * Follows strict Apple Human Interface Guidelines (Clean iOS Enterprise aesthetic):
 * - System light grouped background (`#F2F2F7`) with pure white card surfaces (`#FFFFFF`).
 * - Cards use `RoundedCornerShape(20.dp)`, subtle `1.dp` borders (`#E5E5EA`), and
 *   refined low-elevation shadows.
 * - Clean Sans-Serif typography hierarchy; `FontFamily.Monospace` is reserved
 *   exclusively for compact technical lot/UUID pills (`SUC-2026-045`).
 */
object SoftPosColors {
    // Apple iOS System Grouped Background & Surfaces
    val BackgroundOffWhite = Color(0xFFF2F2F7)
    val RecallCanvasTint = Color(0xFFF2F2F7)
    val SurfacePureWhite = Color(0xFFFFFFFF)
    val SurfaceSubtleGray = Color(0xFFF2F2F7)

    // Apple iOS Separators & Borders
    val OutlineSubtle = Color(0xFFE5E5EA)
    val DividerHairline = Color(0xFFE5E5EA)

    // Semantic Recall / Alert Palette (Refined iOS System Red Tint)
    val PastelRedContainer = Color(0xFFFFF1F0)
    val PastelRedBorder = Color(0xFFFFCCC7)
    val RecallCrimsonAccent = Color(0xFFD92D20)
    val RecallCrimsonDark = Color(0xFFB42318)

    // Typography Hierarchy Colors (iOS Label / Secondary Label / Tertiary Label)
    val TextPrimarySlate = Color(0xFF1C1C1E)
    val TextSecondaryMuted = Color(0xFF636366)
    val TextTertiarySubtle = Color(0xFF8E8E93)

    // Semantic Verified / Active Palette (iOS System Green Tint)
    val EmeraldVerifiedBg = Color(0xFFECFDF3)
    val EmeraldVerifiedText = Color(0xFF027A48)

    // Primary Action Accent (iOS System Blue / Stripe Indigo)
    val PrimaryIndigo = Color(0xFF007AFF)
    val PrimaryAccentBlue = PrimaryIndigo
    val TextPrimaryCharcoal = TextPrimarySlate
    val TextSecondarySlate = TextSecondaryMuted
    val RecallPastelBackground = PastelRedContainer
    val RecallSoftBorder = PastelRedBorder
}

/**
 * Apple HIG & Material 3 spacing, corner radius, and elevation tokens.
 */
object SoftPosDimens {
    val ScreenPadding = 20.dp
    val CardPaddingMin = 16.dp
    val CardPaddingStandard = 20.dp
    val CardPaddingMax = 24.dp
    val CardCornerRadius = 20.dp
    val CardBorderWidth = 1.dp
    val CardElevationStandard = 2.dp
    val StackSpacing = 16.dp
}

val SoftPosShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

val SoftPosTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
        color = SoftPosColors.TextPrimarySlate
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        color = SoftPosColors.TextPrimarySlate
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
        color = SoftPosColors.TextPrimarySlate
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = SoftPosColors.TextPrimarySlate
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        color = SoftPosColors.TextSecondaryMuted
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        color = SoftPosColors.TextSecondaryMuted
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        color = SoftPosColors.SurfacePureWhite
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
        color = SoftPosColors.TextPrimarySlate
    )
)

private val SoftPosLightColorScheme = lightColorScheme(
    primary = SoftPosColors.PrimaryIndigo,
    onPrimary = SoftPosColors.SurfacePureWhite,
    primaryContainer = SoftPosColors.PastelRedContainer,
    onPrimaryContainer = SoftPosColors.RecallCrimsonDark,
    secondary = SoftPosColors.TextPrimarySlate,
    onSecondary = SoftPosColors.SurfacePureWhite,
    background = SoftPosColors.BackgroundOffWhite,
    onBackground = SoftPosColors.TextPrimarySlate,
    surface = SoftPosColors.SurfacePureWhite,
    onSurface = SoftPosColors.TextPrimarySlate,
    surfaceVariant = SoftPosColors.SurfaceSubtleGray,
    onSurfaceVariant = SoftPosColors.TextSecondaryMuted,
    outline = SoftPosColors.OutlineSubtle,
    error = SoftPosColors.RecallCrimsonAccent,
    onError = SoftPosColors.SurfacePureWhite,
    errorContainer = SoftPosColors.PastelRedContainer,
    onErrorContainer = SoftPosColors.RecallCrimsonDark
)

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    println("[INFO] Applying Apple HIG / Stripe Terminal Enterprise Material 3 Theme (#F2F2F7 / #FFFFFF / 20.dp)")
    MaterialTheme(
        colorScheme = SoftPosLightColorScheme,
        typography = SoftPosTypography,
        shapes = SoftPosShapes,
        content = content
    )
}
