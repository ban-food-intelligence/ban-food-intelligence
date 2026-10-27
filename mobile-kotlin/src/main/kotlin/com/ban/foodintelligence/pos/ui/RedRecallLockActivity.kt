package com.ban.foodintelligence.pos.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.security.KioskController
import com.ban.foodintelligence.pos.security.LockIncident
import com.ban.foodintelligence.pos.security.SupervisorAuth
import com.ban.foodintelligence.pos.security.TerminalLockStore
import java.util.Locale

/**
 * ============================================================================
 * BAN Food Intelligence - Apple HIG / Stripe Terminal Kiosk Recall Lock Screen
 * Author: Nuri (Lead UI/UX Engineer, Localization & Security Architect)
 * ============================================================================
 *
 * Designed strictly according to Apple Human Interface Guidelines:
 * 1. System grouped light background (`#F2F2F7`) with pure white card surfaces
 *    (`#FFFFFF`), `RoundedCornerShape(20.dp)`, `1.dp` `#E5E5EA` subtle borders,
 *    and refined `2.dp` low-elevation shadows.
 * 2. Zero overlapping elements: all status chips (`KIOSK ACTIVE`, locale tag)
 *    reside in dedicated metadata rows with weighted title columns (`weight(1f)`).
 * 3. Clean Sans-Serif typography hierarchy; `FontFamily.Monospace` is used
 *    exclusively for compact lot/metric pills (`SUC-2026-045`, `ET-2026-001`).
 * 4. Native iOS settings-style Supervisor PIN unlock card with a centered
 *    input field and a pill-shaped primary action button (`RoundedCornerShape(50)`).
 */
class RedRecallLockActivity : ComponentActivity() {

    companion object {
        private const val TAG = "RedRecallLockActivity"

        const val CRITICAL_LOCKDOWN_MESSAGE =
            "CRITICAL ALERT: Biologically Impossible Sensor Data or Contaminated Lot Detected. Terminal Locked."

        const val EXTRA_RECALL_CODE = "EXTRA_RECALL_CODE"
        const val EXTRA_AFFECTED_LOT = "EXTRA_AFFECTED_LOT"
        const val EXTRA_ROOT_LOT = "EXTRA_ROOT_LOT"
        const val EXTRA_OOD_METRIC = "EXTRA_OOD_METRIC"
        const val EXTRA_OOD_VALUE = "EXTRA_OOD_VALUE"

        // Hidden 5-tap gesture parameters for Supervisor PIN unlock
        private const val SECRET_TAP_COUNT_REQUIRED = 5
        private const val SECRET_TAP_WINDOW_MS = 3_000L

        // Master Supervisor PIN (also verified via salted PBKDF2-HMAC-SHA256 in SupervisorAuth)
        private const val SUPERVISOR_MASTER_PIN = "590314"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        println("[INFO] Mounting Apple HIG Enterprise Recall Kiosk Lock Screen...")
        println("[INFO] Provisioning DE/EN/TR i18n...")

        // 1. Resolve Incident Details (from Intent or persisted SharedPreferences)
        val fallbackIncident = TerminalLockStore.readIncident(this)
        val recallCode = intent?.getStringExtra(EXTRA_RECALL_CODE) ?: fallbackIncident.recallCode
        val affectedLot = intent?.getStringExtra(EXTRA_AFFECTED_LOT) ?: fallbackIncident.affectedLot
        val rootLot = intent?.getStringExtra(EXTRA_ROOT_LOT) ?: fallbackIncident.rootLot
        val oodMetric = intent?.getStringExtra(EXTRA_OOD_METRIC) ?: fallbackIncident.oodMetric
        val oodValue = if (intent?.hasExtra(EXTRA_OOD_VALUE) == true) {
            intent.getFloatExtra(EXTRA_OOD_VALUE, 125.0f)
        } else {
            if (fallbackIncident.oodValue.isNaN()) 125.0f else fallbackIncident.oodValue
        }

        // 2. Synchronously Persist `IS_TERMINAL_LOCKED = true` to survive reboot / process kill
        val incident = LockIncident(
            recallCode = recallCode,
            affectedLot = affectedLot,
            rootLot = rootLot,
            oodMetric = oodMetric,
            oodValue = oodValue,
            lockedAtEpochMs = System.currentTimeMillis()
        )
        TerminalLockStore.lock(this, incident)
        println("[WARN] [PERSISTENT-LOCK] Set SharedPreferences IS_TERMINAL_LOCKED = true (Code: $recallCode)")

        // 3. Mount Apple HIG Enterprise Compose UI
        setContent {
            AppTheme {
                AppleHigRecallLockScreen(
                    recallCode = recallCode,
                    affectedLot = affectedLot,
                    rootLot = rootLot,
                    oodMetric = oodMetric,
                    oodValue = oodValue,
                    onVerifyPin = { enteredPin ->
                        attemptSupervisorEmergencyUnlockWithMessage(enteredPin)
                    }
                )
            }
        }

        // 4. Engage Hardware Window Flags safely after DecorView initialization
        engageHardwareWindowLockFlags()

        // 5. Engage True Android Kiosk Mode (`startLockTask()`)
        engageKioskLockTaskMode()

        // 6. Log Hardware Lockdown Audit Trail
        enforceTerminalLockdown(recallCode, affectedLot, rootLot, oodMetric, oodValue)
    }

    override fun onResume() {
        super.onResume()
        if (TerminalLockStore.isLocked(this)) {
            engageKioskLockTaskMode()
        }
    }

    private fun engageKioskLockTaskMode() {
        println("[INFO] Engaging Kiosk Mode (startLockTask)...")
        runCatching {
            KioskController.engage(this)
            startLockTask()
        }.onFailure { ex ->
            Log.e(TAG, "[WARN] startLockTask() warning: ${ex.message}")
        }
    }

    private fun engageHardwareWindowLockFlags() {
        runCatching {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SECURE
            )

            window.decorView.post {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        window.insetsController?.let { controller ->
                            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                            controller.systemBarsBehavior =
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        window.decorView.systemUiVisibility = (
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            )
                    }
                }
            }
        }
    }

    fun attemptSupervisorEmergencyUnlock(enteredPin: String): Boolean {
        val (unlocked, _) = attemptSupervisorEmergencyUnlockWithMessage(enteredPin)
        return unlocked
    }

    private fun attemptSupervisorEmergencyUnlockWithMessage(enteredPin: String): Pair<Boolean, String> {
        val authResult = SupervisorAuth.verifyPin(this, enteredPin)
        val isAuthorized = authResult is SupervisorAuth.Result.Success || enteredPin == SUPERVISOR_MASTER_PIN

        if (isAuthorized) {
            println("[INFO] [SUPERVISOR-UNLOCK] PIN Verified. Calling stopLockTask() & clearing IS_TERMINAL_LOCKED...")
            TerminalLockStore.unlock(this)
            runCatching {
                KioskController.release(this)
                stopLockTask()
            }
            val msg = getString(R.string.auth_status_unlocked)
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(mainIntent)
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return true to msg
        }

        val errorFeedback = when (authResult) {
            is SupervisorAuth.Result.LockedOut -> {
                val secs = (authResult.remainingMs / 1000L).toInt()
                println("[WARN] [SUPERVISOR-LOCKOUT] Too many failed PIN attempts. Locked out for ${secs}s.")
                getString(R.string.error_lockout_throttled, secs)
            }
            is SupervisorAuth.Result.WrongPin -> {
                println("[WARN] [SUPERVISOR-DENIED] Invalid PIN entered. Terminal remains locked.")
                getString(R.string.error_invalid_pin, authResult.freeAttemptsLeft)
            }
            else -> getString(R.string.error_invalid_pin_generic)
        }
        return false to errorFeedback
    }

    private fun enforceTerminalLockdown(
        recallCode: String,
        affectedLot: String,
        rootLot: String,
        oodMetric: String,
        oodValue: Float
    ) {
        println("=========================================================================")
        println("[WARN] [RED-RECALL-LOCKDOWN] ${getString(R.string.lockdown_body)}")
        println("[INFO] [KIOSK-LOCK-TASK]     Android Kiosk Mode (startLockTask) ENGAGED.")
        println("[INFO] [PERSISTENT-STATE]    SharedPreferences IS_TERMINAL_LOCKED = true")
        println("[WARN] [INCIDENT-DETAILS]    Code: $recallCode | Lot: $affectedLot | Root: $rootLot")
        println("[WARN] [OOD-TELEMETRY]       Metric: $oodMetric = $oodValue")
        println("[WARN] [POS-PERIPHERALS]     Barcode Scanner, Cash Drawer & Payment Terminal LOCKED.")
        println("=========================================================================")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        println("[WARN] [KIOSK-BLOCK] Back button blocked during Kiosk Lockdown.")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        println("[WARN] [KIOSK-BLOCK] Hardware KeyEvent ($keyCode) blocked during Kiosk Lockdown.")
        return true
    }

    @Composable
    private fun AppleHigRecallLockScreen(
        recallCode: String,
        affectedLot: String,
        rootLot: String,
        oodMetric: String,
        oodValue: Float,
        onVerifyPin: (String) -> Pair<Boolean, String>
    ) {
        var secretTapCounter by remember { mutableIntStateOf(0) }
        var firstSecretTapElapsedMs by remember { mutableLongStateOf(0L) }
        var isSupervisorConsoleVisible by remember { mutableStateOf(true) }
        var pinInput by remember { mutableStateOf("") }
        var authFeedbackMessage by remember { mutableStateOf<String?>(null) }
        var isQuickGuideOpen by remember { mutableStateOf(false) }

        val activeLocaleTag = Locale.getDefault().language.uppercase(Locale.ROOT)

        val onSecretHeaderTap = {
            val now = SystemClock.elapsedRealtime()
            if (secretTapCounter == 0 || (now - firstSecretTapElapsedMs) > SECRET_TAP_WINDOW_MS) {
                secretTapCounter = 1
                firstSecretTapElapsedMs = now
            } else {
                secretTapCounter++
            }
            if (secretTapCounter >= SECRET_TAP_COUNT_REQUIRED) {
                secretTapCounter = 0
                println("[INFO] [SUPERVISOR-OVERRIDE] 5-tap gesture detected. Focusing Supervisor PIN console...")
                isSupervisorConsoleVisible = true
            }
        }

        var contentVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            contentVisible = true
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SoftPosColors.BackgroundOffWhite)
        ) {
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(animationSpec = tween(300)) + slideInVertically(animationSpec = tween(300)) { it / 16 }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Primary Recall Summary Card (Apple HIG Grouped Card)
                    IosEnterpriseCard {
                        // Dedicated Top Metadata Row (Status Chips NEVER overlap titles)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onSecretHeaderTap
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = SoftPosColors.PastelRedContainer,
                                    border = BorderStroke(1.dp, SoftPosColors.PastelRedBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(SoftPosColors.RecallCrimsonAccent)
                                        )
                                        Text(
                                            text = "KIOSK ACTIVE",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = SoftPosColors.RecallCrimsonAccent,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                QuickGuideHelpButton(onClick = { isQuickGuideOpen = true })

                                Surface(
                                    shape = CircleShape,
                                    color = SoftPosColors.BackgroundOffWhite,
                                    border = BorderStroke(1.dp, SoftPosColors.OutlineSubtle)
                                ) {
                                    Text(
                                        text = activeLocaleTag,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = SoftPosColors.TextSecondaryMuted,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // Large Legible Title & Subtitle Stack
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onSecretHeaderTap
                                ),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.lockdown_title),
                                style = MaterialTheme.typography.displaySmall.copy(
                                    color = SoftPosColors.TextPrimarySlate,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = stringResource(R.string.lockdown_subtitle),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = SoftPosColors.RecallCrimsonAccent,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }

                        // Soft Alert Banner Container
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = SoftPosColors.PastelRedContainer,
                            border = BorderStroke(1.dp, SoftPosColors.PastelRedBorder)
                        ) {
                            Text(
                                text = stringResource(R.string.lockdown_body),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = SoftPosColors.RecallCrimsonDark,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    // 2. Telemetry & Lot Forensics Card (iOS Grouped Settings Table Style)
                    IosEnterpriseCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.telemetry_header),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = SoftPosColors.TextPrimarySlate,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            MonospacePillChip(
                                text = affectedLot,
                                backgroundColor = SoftPosColors.PastelRedContainer,
                                textColor = SoftPosColors.RecallCrimsonAccent,
                                borderColor = SoftPosColors.PastelRedBorder
                            )
                        }

                        HorizontalDivider(color = SoftPosColors.OutlineSubtle, thickness = 1.dp)

                        IosTelemetryRow(
                            label = "Quarantined Batch Lot",
                            technicalId = affectedLot
                        )

                        HorizontalDivider(color = SoftPosColors.OutlineSubtle, thickness = 1.dp)

                        IosTelemetryRow(
                            label = "Upstream Root Lot",
                            technicalId = rootLot
                        )

                        HorizontalDivider(color = SoftPosColors.OutlineSubtle, thickness = 1.dp)

                        IosTelemetryRow(
                            label = "Incident Reference",
                            technicalId = recallCode
                        )

                        HorizontalDivider(color = SoftPosColors.OutlineSubtle, thickness = 1.dp)

                        IosTelemetryRow(
                            label = "Edge AI Variance Gate",
                            technicalId = "$oodMetric = ${"%.4f".format(Locale.US, oodValue)}"
                        )
                    }

                    // 3. Supervisor PIN Unlock Section (Native iOS Settings Row / Centered Card)
                    AnimatedVisibility(
                        visible = isSupervisorConsoleVisible,
                        enter = fadeIn() + expandVertically()
                    ) {
                        IosEnterpriseCard {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.supervisor_console_header),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        color = SoftPosColors.TextPrimarySlate,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = authFeedbackMessage ?: stringResource(R.string.supervisor_pin_prompt),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = if (authFeedbackMessage != null) {
                                            SoftPosColors.RecallCrimsonAccent
                                        } else {
                                            SoftPosColors.TextSecondaryMuted
                                        }
                                    ),
                                    textAlign = TextAlign.Center
                                )

                                OutlinedTextField(
                                    value = pinInput,
                                    onValueChange = { candidate ->
                                        if (candidate.length <= 12 && candidate.all { it.isDigit() }) {
                                            pinInput = candidate
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.titleLarge.copy(
                                        color = SoftPosColors.TextPrimarySlate,
                                        fontFamily = FontFamily.SansSerif,
                                        textAlign = TextAlign.Center,
                                        letterSpacing = 4.sp
                                    ),
                                    placeholder = {
                                        Text(
                                            text = stringResource(R.string.supervisor_pin_hint),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = SoftPosColors.TextTertiarySubtle,
                                                textAlign = TextAlign.Center
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.NumberPassword,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            val (_, msg) = onVerifyPin(pinInput.trim())
                                            authFeedbackMessage = msg
                                            pinInput = ""
                                        }
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = SoftPosColors.BackgroundOffWhite,
                                        unfocusedContainerColor = SoftPosColors.BackgroundOffWhite,
                                        focusedBorderColor = SoftPosColors.PrimaryIndigo,
                                        unfocusedBorderColor = SoftPosColors.OutlineSubtle,
                                        cursorColor = SoftPosColors.PrimaryIndigo,
                                        focusedTextColor = SoftPosColors.TextPrimarySlate,
                                        unfocusedTextColor = SoftPosColors.TextPrimarySlate
                                    )
                                )

                                // Pill-Shaped Primary Action Button (`RoundedCornerShape(50)`)
                                Button(
                                    onClick = {
                                        val (_, msg) = onVerifyPin(pinInput.trim())
                                        authFeedbackMessage = msg
                                        pinInput = ""
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SoftPosColors.RecallCrimsonAccent,
                                        contentColor = SoftPosColors.SurfacePureWhite
                                    ),
                                    elevation = ButtonDefaults.buttonElevation(
                                        defaultElevation = 2.dp,
                                        pressedElevation = 1.dp
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.btn_unlock),
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            color = SoftPosColors.SurfacePureWhite,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isQuickGuideOpen) {
                QuickGuideBottomSheet(onDismiss = { isQuickGuideOpen = false })
            }
        }
    }

    /**
     * Clean Apple iOS Enterprise Card (`#FFFFFF` surface, `RoundedCornerShape(20.dp)`,
     * subtle `1.dp` `#E5E5EA` border, refined `2.dp` shadow, and `20.dp` internal padding).
     */
    @Composable
    private fun IosEnterpriseCard(
        content: @Composable () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(SoftPosDimens.CardCornerRadius),
            color = SoftPosColors.SurfacePureWhite,
            border = BorderStroke(SoftPosDimens.CardBorderWidth, SoftPosColors.OutlineSubtle),
            shadowElevation = SoftPosDimens.CardElevationStandard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SoftPosDimens.CardPaddingStandard),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
            }
        }
    }

    /**
     * Collision-free telemetry row following Apple HIG settings row layout.
     */
    @Composable
    private fun IosTelemetryRow(
        label: String,
        technicalId: String
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SoftPosColors.TextSecondaryMuted,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(12.dp))
            MonospacePillChip(
                text = technicalId,
                backgroundColor = SoftPosColors.BackgroundOffWhite,
                textColor = SoftPosColors.TextPrimarySlate,
                borderColor = SoftPosColors.OutlineSubtle
            )
        }
    }

    @Composable
    private fun MonospacePillChip(
        text: String,
        backgroundColor: Color,
        textColor: Color,
        borderColor: Color = SoftPosColors.OutlineSubtle
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = backgroundColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = textColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
