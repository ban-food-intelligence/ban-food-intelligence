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
 * BAN Food Intelligence - Soft, Minimalist, Premium Material 3 Theme (`AppTheme`)
 * Author: Nuri (Lead UI/UX Engineer)
 * ============================================================================
 *
 * Inspired by Stripe Terminal & Apple POS interfaces:
 * - Airy Off-White background (`#F8F9FA`) and pure elevated white cards (`#FFFFFF`).
 * - Soft Pastel Recall palette (`#FCE8E8` error container, `#D32F2F` refined crimson accent).
 * - Friendly, generous rounded geometry (`16.dp` to `24.dp`, pill buttons at `50%`).
 * - Clean, human-friendly `FontFamily.SansSerif` typography; `FontFamily.Monospace`
 *   is reserved strictly for compact technical lot/UUID chips.
 */
object SoftPosColors {
    val BackgroundOffWhite = Color(0xFFF8F9FA)
    val RecallCanvasTint = Color(0xFFFDF6F6)
    val SurfacePureWhite = Color(0xFFFFFFFF)
    val SurfaceSubtleGray = Color(0xFFF1F5F9)

    val PastelRedContainer = Color(0xFFFCE8E8)
    val PastelRedBorder = Color(0xFFF8D0D0)
    val RecallCrimsonAccent = Color(0xFFD32F2F)
    val RecallCrimsonDark = Color(0xFFB71C1C)

    val TextPrimarySlate = Color(0xFF0F172A)
    val TextSecondaryMuted = Color(0xFF64748B)
    val TextTertiarySubtle = Color(0xFF94A3B8)

    val EmeraldVerifiedBg = Color(0xFFECFDF5)
    val EmeraldVerifiedText = Color(0xFF047857)

    val PrimaryIndigo = Color(0xFF2563EB)
    val OutlineSubtle = Color(0xFFE2E8F0)
    val TextSecondarySlate = TextSecondaryMuted
    val RecallPastelBackground = PastelRedContainer
    val RecallSoftBorder = PastelRedBorder
}

/**
 * Standard Material 3 spacing and elevation tokens (16dp-24dp card padding, 8dp elevation).
 */
object SoftPosDimens {
    val ScreenPadding = 20.dp
    val CardPaddingMin = 16.dp
    val CardPaddingStandard = 20.dp
    val CardPaddingMax = 24.dp
    val CardElevationStandard = 8.dp
}

val SoftPosShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

val SoftPosTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
        color = SoftPosColors.TextPrimarySlate
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
        color = SoftPosColors.TextPrimarySlate
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        color = SoftPosColors.TextPrimarySlate
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
        color = SoftPosColors.TextPrimarySlate
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.1.sp,
        color = SoftPosColors.TextSecondaryMuted
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.15.sp,
        color = SoftPosColors.TextSecondaryMuted
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
        color = SoftPosColors.SurfacePureWhite
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        color = SoftPosColors.TextPrimarySlate
    )
)

private val SoftPosLightColorScheme = lightColorScheme(
    primary = SoftPosColors.RecallCrimsonAccent,
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
    error = SoftPosColors.RecallCrimsonAccent,
    onError = SoftPosColors.SurfacePureWhite,
    errorContainer = SoftPosColors.PastelRedContainer,
    onErrorContainer = SoftPosColors.RecallCrimsonDark
)

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    println("[INFO] Applying Soft Minimalist Material 3 theme (cardPadding=16dp-24dp, elevation=8dp)")
    MaterialTheme(
        colorScheme = SoftPosLightColorScheme,
        typography = SoftPosTypography,
        shapes = SoftPosShapes,
        content = content
    )
}
