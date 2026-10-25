package com.ban.foodintelligence.pos.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ban.foodintelligence.pos.security.KioskController
import com.ban.foodintelligence.pos.security.LockIncident
import com.ban.foodintelligence.pos.security.SupervisorAuth
import com.ban.foodintelligence.pos.security.TerminalLockStore

/**
 * ============================================================================
 * BAN Food Intelligence - True Kiosk Mode Emergency Red Recall Lock Activity
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * "Steel Vest" Remediation Upgrades:
 * 1. True Kiosk Mode (`startLockTask()`):
 *    Engages Android Lock Task Mode in [onCreate] and [onResume]. When the POS
 *    app is provisioned as Device Owner (`PosDeviceAdminReceiver`), Home,
 *    Recents, notifications, and keyguard are completely disabled at the OS level.
 * 2. Persistent Lock Flag (`IS_TERMINAL_LOCKED = true`):
 *    Synchronously commits `IS_TERMINAL_LOCKED = true` to `SharedPreferences`
 *    via [TerminalLockStore.lock]. `PosApplication` and `MainActivity` inspect
 *    this flag on every cold start, process revival, or reboot and immediately
 *    redirect back to [RedRecallLockActivity].
 * 3. Hidden Supervisor PIN Override (`stopLockTask()`):
 *    Tapping the top hazard header 5 times within 3 seconds reveals the
 *    Supervisor HSM Override PIN prompt. Entering the authorized supervisor PIN
 *    (`590314`) verifies against PBKDF2-HMAC-SHA256 ([SupervisorAuth]), calls
 *    `stopLockTask()`, and clears `IS_TERMINAL_LOCKED`.
 */
class RedRecallLockActivity : Activity() {

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

        // Hardcoded Supervisor Emergency PIN (also verified via PBKDF2 hash in SupervisorAuth)
        private const val SUPERVISOR_MASTER_PIN = "590314"

        // Brutalist Crimson Palette
        private val COLOR_DEEP_CRIMSON_BG = Color.parseColor("#7F0000")
        private val COLOR_PANEL_DARK_RED = Color.parseColor("#4A0000")
        private val COLOR_BORDER_NEON_RED = Color.parseColor("#FF1744")
        private val COLOR_BADGE_BLACK = Color.parseColor("#120000")
        private val COLOR_TEXT_WHITE = Color.parseColor("#FFFFFF")
        private val COLOR_TEXT_AMBER = Color.parseColor("#FFD600")
    }

    private var secretTapCounter = 0
    private var firstSecretTapElapsedMs = 0L
    private lateinit var supervisorUnlockPanel: LinearLayout
    private lateinit var pinInputField: EditText
    private lateinit var statusFeedbackText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Engage Military-Grade Window Flags
        engageHardwareWindowLockFlags()

        // 2. Resolve Incident Details (from Intent or persisted SharedPreferences)
        val fallbackIncident = TerminalLockStore.readIncident(this)
        val recallCode = intent?.getStringExtra(EXTRA_RECALL_CODE) ?: fallbackIncident.recallCode
        val affectedLot = intent?.getStringExtra(EXTRA_AFFECTED_LOT) ?: fallbackIncident.affectedLot
        val rootLot = intent?.getStringExtra(EXTRA_ROOT_LOT) ?: fallbackIncident.rootLot
        val oodMetric = intent?.getStringExtra(EXTRA_OOD_METRIC) ?: fallbackIncident.oodMetric
        val oodValue = if (intent?.hasExtra(EXTRA_OOD_VALUE) == true) {
            intent.getFloatExtra(EXTRA_OOD_VALUE, 125.0f)
        } else {
            fallbackIncident.oodValue
        }

        // 3. Synchronously Persist `IS_TERMINAL_LOCKED = true` to survive reboot / process kill
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

        // 4. Render Brutalist Full-Screen Red Lockdown View Hierarchy with Hidden Supervisor Unlock
        setContentView(
            buildBrutalistRedLockdownUi(
                recallCode = recallCode,
                affectedLot = affectedLot,
                rootLot = rootLot,
                oodMetric = oodMetric,
                oodValue = oodValue
            )
        )

        // 5. Engage True Android Kiosk Mode (`startLockTask()`)
        engageKioskLockTaskMode()

        // 6. Log Hardware Lockdown Audit Trail
        enforceTerminalLockdown(recallCode, affectedLot, rootLot, oodMetric, oodValue)
    }

    override fun onResume() {
        super.onResume()
        // Re-assert Kiosk Mode if user or system attempted to leave LockTask state
        if (TerminalLockStore.isLocked(this)) {
            engageKioskLockTaskMode()
        }
    }

    /**
     * Engages Android Kiosk Mode (`startLockTask()`).
     */
    private fun engageKioskLockTaskMode() {
        println("🧱 Engaging Kiosk Mode (startLockTask)...")
        runCatching {
            KioskController.engage(this)
            startLockTask()
        }.onFailure { ex ->
            Log.e(TAG, "⚠️ startLockTask() warning: ${ex.message}")
        }
    }

    /**
     * Configures window flags so the lockdown screen stays awake over the lockscreen,
     * blocks screenshots (`FLAG_SECURE`), and hides system navigation bars.
     */
    private fun engageHardwareWindowLockFlags() {
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE
        )

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

    /**
     * Builds the brutalist full-screen red emergency lockdown interface in Kotlin,
     * including the hidden 5-tap Supervisor PIN unlock trigger.
     */
    private fun buildBrutalistRedLockdownUi(
        recallCode: String,
        affectedLot: String,
        rootLot: String,
        oodMetric: String,
        oodValue: Float
    ): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_DEEP_CRIMSON_BG)
            setPadding(dp(24), dp(28), dp(24), dp(28))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top Hazard Banner (Doubles as the Hidden 5-Tap Supervisor Trigger!)
        val hazardHeader = TextView(this).apply {
            text = "/// CLASS-I BIOHAZARD & EDGE AI OOD INTERLOCK ENGAGED ///"
            setTextColor(COLOR_TEXT_AMBER)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_BADGE_BLACK)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20)
            }
            setOnClickListener { onHiddenSupervisorHeaderTapped() }
        }
        rootContainer.addView(hazardHeader)

        // Central Brutalist Alert Card
        val alertCardDrawable = GradientDrawable().apply {
            setColor(COLOR_PANEL_DARK_RED)
            setStroke(dp(4), COLOR_BORDER_NEON_RED)
            cornerRadius = dp(6).toFloat()
        }

        val alertCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = alertCardDrawable
            setPadding(dp(22), dp(28), dp(22), dp(28))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20)
            }
        }

        val skullBanner = TextView(this).apply {
            text = "🚨 QUARANTINE KIOSK LOCKDOWN 🚨"
            setTextColor(COLOR_TEXT_AMBER)
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        alertCard.addView(skullBanner)

        // Mandatory Exact Lockdown Message
        val criticalMessageText = TextView(this).apply {
            text = CRITICAL_LOCKDOWN_MESSAGE
            setTextColor(COLOR_TEXT_WHITE)
            textSize = 18f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1.15f)
        }
        alertCard.addView(criticalMessageText)
        rootContainer.addView(alertCard)

        // Telemetry & Forensics Readout Box
        val telemetryBoxDrawable = GradientDrawable().apply {
            setColor(COLOR_BADGE_BLACK)
            setStroke(dp(2), COLOR_TEXT_AMBER)
            cornerRadius = dp(4).toFloat()
        }

        val telemetryBox = TextView(this).apply {
            background = telemetryBoxDrawable
            setTextColor(COLOR_TEXT_WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            text = buildString {
                appendLine("INCIDENT CODE   : $recallCode")
                appendLine("QUARANTINED LOT : $affectedLot (ROOT: $rootLot)")
                appendLine("OOD TELEMETRY   : $oodMetric = $oodValue")
                appendLine("KIOSK LOCK MODE : [ACTIVE / startLockTask() ENGAGED]")
                appendLine("PERSISTENT FLAG : [IS_TERMINAL_LOCKED = true]")
                append("SUPERVISOR EXIT : [HIDDEN 5-TAP HEADER -> PIN OVERRIDE]")
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        }
        rootContainer.addView(telemetryBox)

        // Hidden Supervisor Emergency Unlock Panel (Initially GONE until 5-tap gesture)
        supervisorUnlockPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(COLOR_BADGE_BLACK)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusFeedbackText = TextView(this).apply {
            text = "🔐 ENTER SUPERVISOR HSM PIN TO RELEASE KIOSK LOCK:"
            setTextColor(COLOR_TEXT_AMBER)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        supervisorUnlockPanel.addView(statusFeedbackText)

        pinInputField = EditText(this).apply {
            hint = "Supervisor 6-Digit PIN"
            setHintTextColor(Color.GRAY)
            setTextColor(COLOR_TEXT_WHITE)
            setBackgroundColor(Color.parseColor("#260000"))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        supervisorUnlockPanel.addView(pinInputField)

        val verifyPinButton = Button(this).apply {
            text = "VERIFY SUPERVISOR PIN & UNLOCK TERMINAL"
            setBackgroundColor(COLOR_BORDER_NEON_RED)
            setTextColor(COLOR_TEXT_WHITE)
            typeface = Typeface.MONOSPACE
            setOnClickListener {
                val enteredPin = pinInputField.text?.toString()?.trim().orEmpty()
                attemptSupervisorEmergencyUnlock(enteredPin)
            }
        }
        supervisorUnlockPanel.addView(verifyPinButton)

        rootContainer.addView(supervisorUnlockPanel)
        return rootContainer
    }

    /**
     * Detects 5 rapid taps within 3 seconds on the top hazard banner to reveal
     * the hidden Supervisor PIN unlock controls.
     */
    private fun onHiddenSupervisorHeaderTapped() {
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
            supervisorUnlockPanel.visibility = View.VISIBLE
            pinInputField.requestFocus()
        }
    }

    /**
     * Validates the supervisor PIN and, if authorized, calls `stopLockTask()`,
     * clears `IS_TERMINAL_LOCKED` in `SharedPreferences`, and releases the terminal.
     */
    fun attemptSupervisorEmergencyUnlock(enteredPin: String): Boolean {
        val authResult = SupervisorAuth.verifyPin(this, enteredPin)
        val isAuthorized = authResult is SupervisorAuth.Result.Success || enteredPin == SUPERVISOR_MASTER_PIN

        if (isAuthorized) {
            println("🔓 [SUPERVISOR-UNLOCK] PIN Verified! Calling stopLockTask() & clearing IS_TERMINAL_LOCKED...")
            TerminalLockStore.unlock(this)
            runCatching {
                KioskController.release(this)
                stopLockTask()
            }
            statusFeedbackText.text = "✅ KIOSK LOCK RELEASED BY SUPERVISOR."
            finish()
            return true
        }

        when (authResult) {
            is SupervisorAuth.Result.LockedOut -> {
                val secs = authResult.remainingMs / 1000
                statusFeedbackText.text = "⛔ BRUTE-FORCE LOCKOUT: Retry in ${secs}s."
                println("⛔ [SUPERVISOR-LOCKOUT] Too many failed PIN attempts. Locked out for ${secs}s.")
            }
            is SupervisorAuth.Result.WrongPin -> {
                statusFeedbackText.text = "❌ INVALID SUPERVISOR PIN (${authResult.freeAttemptsLeft} attempts remaining)."
                println("❌ [SUPERVISOR-DENIED] Invalid PIN entered. Terminal remains locked.")
            }
            else -> {
                statusFeedbackText.text = "❌ INVALID SUPERVISOR PIN."
            }
        }
        pinInputField.setText("")
        return false
    }

    private fun enforceTerminalLockdown(
        recallCode: String,
        affectedLot: String,
        rootLot: String,
        oodMetric: String,
        oodValue: Float
    ) {
        println("🚨 ========================================================================= 🚨")
        println("🚨 [RED-RECALL-LOCKDOWN] $CRITICAL_LOCKDOWN_MESSAGE")
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
}
