package com.ban.foodintelligence.pos.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * App-wide UI language (TR / EN / DE), stored in SharedPreferences and applied to
 * every activity through [wrap] in `attachBaseContext`.
 */
object AppLocale {
    private const val PREFS = "pos_locale"
    private const val KEY_LANG = "lang"
    val SUPPORTED = listOf("tr" to "TR", "en" to "EN", "de" to "DE")
    /** Language names shown in their own language (iOS convention). */
    val SUPPORTED_NAMES = listOf("tr" to "Türkçe", "en" to "English", "de" to "Deutsch")

    fun current(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null)
        if (saved != null) return saved
        val device = Locale.getDefault().language
        return if (SUPPORTED.any { it.first == device }) device else "en"
    }

    fun set(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, lang).commit()
    }

    fun wrap(base: Context): Context {
        val locale = Locale(current(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

private val DisabledContainer = Color(0xFFE5E5EA)
private val DisabledContent = SoftPosColors.TextSecondaryMuted

/** Solid Apple-blue pill button: white label and icon on `#007AFF` (or [containerColor]). */
@Composable
fun PosPrimaryButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = SoftPosColors.PrimaryIndigo,
    height: Dp = 52.dp
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
            disabledContainerColor = DisabledContainer,
            disabledContentColor = DisabledContent
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 1.dp),
        modifier = modifier.fillMaxWidth().height(height)
    ) {
        ButtonContent(text, icon)
    }
}

/** Outlined pill button: blue label and icon on white. */
@Composable
fun PosSecondaryButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = SoftPosColors.PrimaryIndigo
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        border = BorderStroke(1.dp, if (enabled) contentColor else DisabledContainer),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = SoftPosColors.SurfacePureWhite,
            contentColor = contentColor,
            disabledContentColor = DisabledContent
        ),
        modifier = modifier
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
