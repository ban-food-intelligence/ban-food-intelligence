package com.ban.foodintelligence.pos.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ban.foodintelligence.pos.R
import java.util.Locale

/**
 * Trilingual (TR / EN / DE) BottomSheet displaying the 3-step Quick Guide
 * for warehouse and POS personnel. Loads localized strings from Android
 * resources (`values-tr`, `values`, `values-de`) and includes an instant
 * TR | EN | DE language toggle inside the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickGuideBottomSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val defaultLang = remember {
        when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
            "tr" -> "tr"
            "de" -> "de"
            else -> "tr" // Default to TR for warehouse personnel if OS is generic, while allowing 1-tap TR/EN/DE switch
        }
    }
    var selectedLang by remember { mutableStateOf(defaultLang) }

    val localizedContext = remember(selectedLang, context) {
        val locale = Locale(selectedLang)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.createConfigurationContext(config)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SoftPosColors.SurfacePureWhite,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Row: Title + Instant Language Switcher (TR | EN | DE)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localizedContext.getString(R.string.quick_guide_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = SoftPosColors.TextPrimarySlate,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = localizedContext.getString(R.string.quick_guide_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SoftPosColors.TextSecondaryMuted
                        )
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Segmented Language Pill (TR | EN | DE)
                Surface(
                    shape = CircleShape,
                    color = SoftPosColors.BackgroundOffWhite,
                    border = BorderStroke(1.dp, SoftPosColors.OutlineSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("tr" to "TR", "en" to "EN", "de" to "DE").forEach { (code, label) ->
                            val isSelected = selectedLang == code
                            Surface(
                                modifier = Modifier.clickable { selectedLang = code },
                                shape = CircleShape,
                                color = if (isSelected) SoftPosColors.PrimaryIndigo else Color.Transparent
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (isSelected) Color.White else SoftPosColors.TextSecondaryMuted,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3-Step Grouped iOS Instruction Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(SoftPosDimens.CardCornerRadius),
                color = SoftPosColors.BackgroundOffWhite,
                border = BorderStroke(1.dp, SoftPosColors.OutlineSubtle)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Step 1: Making a Sale
                    QuickGuideStepRow(
                        stepNumber = "1",
                        badgeBg = SoftPosColors.EmeraldVerifiedBg,
                        badgeText = SoftPosColors.EmeraldVerifiedText,
                        title = localizedContext.getString(R.string.quick_guide_step1_title),
                        description = localizedContext.getString(R.string.quick_guide_step1_desc)
                    )

                    HorizontalDivider(thickness = 1.dp, color = SoftPosColors.OutlineSubtle)

                    // Step 2: Recall Lock Status
                    QuickGuideStepRow(
                        stepNumber = "2",
                        badgeBg = SoftPosColors.PastelRedContainer,
                        badgeText = SoftPosColors.RecallCrimsonAccent,
                        title = localizedContext.getString(R.string.quick_guide_step2_title),
                        description = localizedContext.getString(R.string.quick_guide_step2_desc)
                    )

                    HorizontalDivider(thickness = 1.dp, color = SoftPosColors.OutlineSubtle)

                    // Step 3: Supervisor Unlock
                    QuickGuideStepRow(
                        stepNumber = "3",
                        badgeBg = Color(0xFFE0E7FF),
                        badgeText = SoftPosColors.PrimaryIndigo,
                        title = localizedContext.getString(R.string.quick_guide_step3_title),
                        description = localizedContext.getString(R.string.quick_guide_step3_desc)
                    )
                }
            }

            // Pill-Shaped Dismiss Button
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoftPosColors.PrimaryIndigo,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = localizedContext.getString(R.string.quick_guide_btn_close),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

@Composable
private fun QuickGuideStepRow(
    stepNumber: String,
    badgeBg: Color,
    badgeText: Color,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = badgeBg,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stepNumber,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = badgeText,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = SoftPosColors.TextPrimarySlate,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SoftPosColors.TextSecondaryMuted,
                    lineHeight = 20.sp
                )
            )
        }
    }
}
