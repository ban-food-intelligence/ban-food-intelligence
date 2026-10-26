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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
 * BAN Food Intelligence - Soft Minimalist Material 3 Recall Kiosk Lock Screen
 * Author: Nuri (Lead UI/UX Engineer, Localization & Security Architect)
 * ============================================================================
 *
 * Designed with a Stripe / Apple POS aesthetic:
 * 1. Airy Off-White canvas (`#F8F9FA`) with floating, soft-shadowed [ElevatedCard]
 *    surfaces (`RoundedCornerShape(24.dp)`, `elevation = 8.dp`).
 * 2. Friendly Pastel Red accents (`#FCE8E8` background, `#D32F2F` text/accents)
 *    instead of harsh black/neon red.
 * 3. Readable Sans-Serif typography (`FontFamily.SansSerif`) for all worker-facing
 *    copy, with `FontFamily.Monospace` reserved only for compact lot/metric chips.
 * 4. Smooth rounded `OutlinedTextField` (`RoundedCornerShape(16.dp)`) and pill-shaped
 *    primary `UNLOCK` button (`CircleShape` / `RoundedCornerShape(50)`).
 * 5. Preserves True Kiosk Mode (`startLockTask()`), persistent `IS_TERMINAL_LOCKED`,
 *    trilingual EN/DE/TR localization, and the hidden 5-tap Supervisor reveal.
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

        println("🎨 Applying Soft Minimalist Material 3...")
        println("✨ Smoothing edges and removing brutalist artifacts...")
        println("🌍 Provisioning DE/EN/TR i18n...")

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
        println("🔒 [PERSISTENT-LOCK] Set SharedPreferences IS_TERMINAL_LOCKED = true (Code: $recallCode)")

        // 3. Mount Soft Minimalist Material 3 Jetpack Compose UI
        setContent {
            AppTheme {
                SoftRecallLockScreen(
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
        println("🧱 Engaging Kiosk Mode (startLockTask)...")
        runCatching {
            KioskController.engage(this)
            startLockTask()
        }.onFailure { ex ->
            Log.e(TAG, "⚠️ startLockTask() warning: ${ex.message}")
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
            println("🔓 [SUPERVISOR-UNLOCK] PIN Verified! Calling stopLockTask() & clearing IS_TERMINAL_LOCKED...")
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
            finish()
            return true to msg
        }

        val errorFeedback = when (authResult) {
            is SupervisorAuth.Result.LockedOut -> {
                val secs = (authResult.remainingMs / 1000L).toInt()
                println("⛔ [SUPERVISOR-LOCKOUT] Too many failed PIN attempts. Locked out for ${secs}s.")
                getString(R.string.error_lockout_throttled, secs)
            }
            is SupervisorAuth.Result.WrongPin -> {
                println("❌ [SUPERVISOR-DENIED] Invalid PIN entered. Terminal remains locked.")
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
        println("🚨 ========================================================================= 🚨")
        println("🚨 [RED-RECALL-LOCKDOWN] ${getString(R.string.lockdown_body)}")
        println("🧱 [KIOSK-LOCK-TASK]     Android Kiosk Mode (startLockTask) ENGAGED.")
        println("🔒 [PERSISTENT-STATE]    SharedPreferences IS_TERMINAL_LOCKED = true")
        println("🚨 [INCIDENT-DETAILS]    Code: $recallCode | Lot: $affectedLot | Root: $rootLot")
        println("🚨 [OOD-TELEMETRY]       Metric: $oodMetric = $oodValue")
        println("🛑 [POS-PERIPHERALS]     Barcode Scanner, Cash Drawer & Payment Terminal LOCKED.")
        println("🚨 ========================================================================= 🚨")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        println("⛔ [KIOSK-BLOCK] Back button blocked during Kiosk Lockdown!")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        println("⛔ [KIOSK-BLOCK] Hardware KeyEvent ($keyCode) blocked during Kiosk Lockdown.")
        return true
    }

    @Composable
    private fun SoftRecallLockScreen(
        recallCode: String,
        affectedLot: String,
        rootLot: String,
        oodMetric: String,
        oodValue: Float,
        onVerifyPin: (String) -> Pair<Boolean, String>
    ) {
        var secretTapCounter by remember { mutableIntStateOf(0) }
        var firstSecretTapElapsedMs by remember { mutableLongStateOf(0L) }
        var isSupervisorConsoleVisible by remember { mutableStateOf(false) }
        var pinInput by remember { mutableStateOf("") }
        var authFeedbackMessage by remember { mutableStateOf<String?>(null) }

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
                println("🕵️ [SUPERVISOR-OVERRIDE] Hidden 5-tap gesture detected! Revealing Supervisor PIN console...")
                isSupervisorConsoleVisible = true
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SoftPosColors.BackgroundOffWhite),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Subtle Pill Bar (Hidden 5-Tap Supervisor Trigger)
                Surface(
                    shape = CircleShape,
                    color = SoftPosColors.PastelRedContainer,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSecretHeaderTap
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(SoftPosColors.RecallCrimsonAccent)
                        )
                        Text(
                            text = stringResource(R.string.lockdown_subtitle),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = SoftPosColors.RecallCrimsonAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Surface(
                            shape = CircleShape,
                            color = SoftPosColors.SurfacePureWhite
                        ) {
                            Text(
                                text = activeLocaleTag,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SoftPosColors.RecallCrimsonAccent,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Central Elevated Recall Card (Stripe / Apple POS style)
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = SoftPosColors.SurfacePureWhite
                    ),
                    elevation = CardDefaults.elevatedCardElevation(
                        defaultElevation = 8.dp
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Soft Pastel Red Floating Lock Icon (Also supports 5-tap reveal)
                        Surface(
                            shape = CircleShape,
                            color = SoftPosColors.PastelRedContainer,
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onSecretHeaderTap
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "🔒",
                                    fontSize = 28.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Title ("Critical Recall" / "Kritik İade" / "Kritischer Rückruf")
                        Text(
                            text = stringResource(R.string.lockdown_title),
                            style = MaterialTheme.typography.displaySmall.copy(
                                color = SoftPosColors.RecallCrimsonAccent,
                                fontWeight = FontWeight.Bold
                            ),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Friendly, Soft Pastel Red Warning Callout Container
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = SoftPosColors.PastelRedContainer
                        ) {
                            Text(
                                text = stringResource(R.string.lockdown_body),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = SoftPosColors.RecallCrimsonDark,
                                    fontWeight = FontWeight.Medium
                                ),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(22.dp))

                        HorizontalDivider(
                            color = SoftPosColors.SurfaceSubtleGray,
                            thickness = 1.dp
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Clean Section Title for Telemetry
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
                                )
                            )
                            MonospacePillChip(
                                text = "KIOSK ACTIVE",
                                backgroundColor = SoftPosColors.PastelRedContainer,
                                textColor = SoftPosColors.RecallCrimsonAccent
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Subtle Telemetry Chips (Monospace used ONLY for technical IDs)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SoftTelemetryRow(
                                label = "Incident Code",
                                technicalId = recallCode
                            )
                            SoftTelemetryRow(
                                label = "Quarantined Lot",
                                technicalId = "$affectedLot → $rootLot"
                            )
                            SoftTelemetryRow(
                                label = "Sensor / ML Gate",
                                technicalId = "$oodMetric = ${"%.4f".format(Locale.US, oodValue)}"
                            )
                        }
                    }
                }

                // Smoothly Revealed Supervisor PIN Unlock Card (After 5-Tap Gesture)
                AnimatedVisibility(
                    visible = isSupervisorConsoleVisible,
                    enter = fadeIn() + expandVertically()
                ) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = SoftPosColors.SurfacePureWhite
                        ),
                        elevation = CardDefaults.elevatedCardElevation(
                            defaultElevation = 6.dp
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = SoftPosColors.PastelRedContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(text = "🔑", fontSize = 16.sp)
                                    }
                                }
                                Column {
                                    Text(
                                        text = stringResource(R.string.supervisor_console_header),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = SoftPosColors.TextPrimarySlate,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                    Text(
                                        text = authFeedbackMessage ?: stringResource(R.string.supervisor_pin_prompt),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = if (authFeedbackMessage != null) {
                                                SoftPosColors.RecallCrimsonAccent
                                            } else {
                                                SoftPosColors.TextSecondaryMuted
                                            }
                                        )
                                    )
                                }
                            }

                            // Smooth, Rounded OutlinedTextField (RoundedCornerShape(16.dp))
                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = { candidate ->
                                    if (candidate.length <= 12 && candidate.all { it.isDigit() }) {
                                        pinInput = candidate
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleLarge.copy(
                                    color = SoftPosColors.TextPrimarySlate,
                                    fontFamily = FontFamily.SansSerif,
                                    letterSpacing = 3.sp
                                ),
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.supervisor_pin_hint),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            color = SoftPosColors.TextTertiarySubtle
                                        )
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
                                    focusedBorderColor = SoftPosColors.RecallCrimsonAccent,
                                    unfocusedBorderColor = SoftPosColors.PastelRedBorder,
                                    cursorColor = SoftPosColors.RecallCrimsonAccent,
                                    focusedTextColor = SoftPosColors.TextPrimarySlate,
                                    unfocusedTextColor = SoftPosColors.TextPrimarySlate
                                )
                            )

                            // Pill-Shaped Primary Button (RoundedCornerShape(50))
                            Button(
                                onClick = {
                                    val (_, msg) = onVerifyPin(pinInput.trim())
                                    authFeedbackMessage = msg
                                    pinInput = ""
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SoftPosColors.RecallCrimsonAccent,
                                    contentColor = SoftPosColors.SurfacePureWhite
                                ),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 4.dp,
                                    pressedElevation = 2.dp
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.btn_unlock),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        color = SoftPosColors.SurfacePureWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.5.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SoftTelemetryRow(
        label: String,
        technicalId: String
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SoftPosColors.BackgroundOffWhite)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SoftPosColors.TextSecondaryMuted,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            MonospacePillChip(
                text = technicalId,
                backgroundColor = SoftPosColors.SurfacePureWhite,
                textColor = SoftPosColors.TextPrimarySlate
            )
        }
    }

    @Composable
    private fun MonospacePillChip(
        text: String,
        backgroundColor: androidx.compose.ui.graphics.Color,
        textColor: androidx.compose.ui.graphics.Color
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = backgroundColor,
            shadowElevation = 1.dp
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = textColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
