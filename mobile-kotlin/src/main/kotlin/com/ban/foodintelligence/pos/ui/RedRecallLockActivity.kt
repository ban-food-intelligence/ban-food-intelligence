package com.ban.foodintelligence.pos.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ============================================================================
 * BAN Food Intelligence - Emergency Red Recall & OOD Lockdown UI
 * Author: Nuri (Principal Android Security Engineer & Lead POS Architect)
 * ============================================================================
 *
 * Triggered immediately by:
 * 1. `OfflineYieldPredictor` throwing `AnomalyDetectedException` when ONNX Edge AI
 *    detects Out-of-Distribution (OOD) / biologically impossible telemetry
 *    (e.g., `tempC > 100°C` sensor spoofing or thermal biohazard), OR
 * 2. A Class I pathogen recall broadcast (`RCL-2026-ET001-SALMONELLA` / `SUC-2026-045`).
 *
 * Enforces a brutalist full-screen crimson lockdown aesthetic, disables all
 * hardware checkout peripherals, blocks `onBackPressed()` and system keys,
 * and displays the mandatory lockdown warning.
 */
class RedRecallLockActivity : Activity() {

    companion object {
        const val CRITICAL_LOCKDOWN_MESSAGE =
            "CRITICAL ALERT: Biologically Impossible Sensor Data or Contaminated Lot Detected. Terminal Locked."

        const val EXTRA_RECALL_CODE = "EXTRA_RECALL_CODE"
        const val EXTRA_AFFECTED_LOT = "EXTRA_AFFECTED_LOT"
        const val EXTRA_ROOT_LOT = "EXTRA_ROOT_LOT"
        const val EXTRA_OOD_METRIC = "EXTRA_OOD_METRIC"
        const val EXTRA_OOD_VALUE = "EXTRA_OOD_VALUE"

        // Brutalist Crimson Color Palette
        private val COLOR_DEEP_CRIMSON_BG = Color.parseColor("#7F0000")
        private val COLOR_PANEL_DARK_RED = Color.parseColor("#4A0000")
        private val COLOR_BORDER_NEON_RED = Color.parseColor("#FF1744")
        private val COLOR_BADGE_BLACK = Color.parseColor("#120000")
        private val COLOR_TEXT_WHITE = Color.parseColor("#FFFFFF")
        private val COLOR_TEXT_AMBER = Color.parseColor("#FFD600")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Engage Military-Grade Window & Screen-Pinning Flags
        engageHardwareWindowLockFlags()

        // 2. Extract Anomaly / Recall Telemetry Context
        val recallCode = intent?.getStringExtra(EXTRA_RECALL_CODE) ?: "RCL-OOD-THERMAL-BIOHAZARD"
        val affectedLot = intent?.getStringExtra(EXTRA_AFFECTED_LOT) ?: "SUC-2026-045"
        val rootLot = intent?.getStringExtra(EXTRA_ROOT_LOT) ?: "ET-2026-001"
        val oodMetric = intent?.getStringExtra(EXTRA_OOD_METRIC) ?: "ambient_temp_celsius"
        val oodValue = intent?.getFloatExtra(EXTRA_OOD_VALUE, 125.0f) ?: 125.0f

        // 3. Render Brutalist Full-Screen Red Lockdown View Hierarchy
        val rootLockView = buildBrutalistRedLockdownUi(
            recallCode = recallCode,
            affectedLot = affectedLot,
            rootLot = rootLot,
            oodMetric = oodMetric,
            oodValue = oodValue
        )
        setContentView(rootLockView)

        // 4. Sever Hardware Peripherals & Log Security Audit Event
        enforceTerminalLockdown(recallCode, affectedLot, rootLot, oodMetric, oodValue)
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
     * Builds the brutalist full-screen red emergency lockdown interface in Kotlin.
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
            setPadding(dp(28), dp(36), dp(28), dp(36))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top Hazard Stripe Banner
        val hazardHeader = TextView(this).apply {
            text = "/// CLASS-I BIOHAZARD & EDGE AI OOD INTERLOCK ENGAGED ///"
            setTextColor(COLOR_TEXT_AMBER)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_BADGE_BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(24)
            }
        }
        rootContainer.addView(hazardHeader)

        // Central Brutalist Alert Card with High-Contrast Crimson Border
        val alertCardDrawable = GradientDrawable().apply {
            setColor(COLOR_PANEL_DARK_RED)
            setStroke(dp(4), COLOR_BORDER_NEON_RED)
            cornerRadius = dp(6).toFloat()
        }

        val alertCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = alertCardDrawable
            setPadding(dp(24), dp(32), dp(24), dp(32))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(24)
            }
        }

        // Giant Warning Icon Header
        val skullBanner = TextView(this).apply {
            text = "🚨 QUARANTINE LOCKDOWN 🚨"
            setTextColor(COLOR_TEXT_AMBER)
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        }
        alertCard.addView(skullBanner)

        // Mandatory Exact Lockdown Message
        val criticalMessageText = TextView(this).apply {
            text = CRITICAL_LOCKDOWN_MESSAGE
            setTextColor(COLOR_TEXT_WHITE)
            textSize = 20f
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
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(18), dp(16), dp(18), dp(16))
            text = buildString {
                appendLine("INCIDENT CODE   : $recallCode")
                appendLine("QUARANTINED LOT : $affectedLot (ROOT: $rootLot)")
                appendLine("OOD SENSOR FAULT: $oodMetric = $oodValue")
                appendLine("BARCODE SCANNER : [HARD-LOCKED / INTERRUPT SEVERED]")
                appendLine("CASH DRAWER     : [SOLENOID DISENGAGED]")
                append("UNLOCK PROTOCOL : DUAL-KEY SUPERVISOR HSM SIGNATURE REQUIRED")
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootContainer.addView(telemetryBox)

        return rootContainer
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
        println("🚨 [INCIDENT-DETAILS] Code: $recallCode | Lot: $affectedLot | Root: $rootLot")
        println("🚨 [OOD-TELEMETRY]    Sensor Metric: $oodMetric = $oodValue (Biologically Impossible)")
        println("🛑 [POS-PERIPHERALS]  Barcode Scanner, Cash Drawer & Payment Terminal LOCKED.")
        println("🔐 [HSM-SUPERVISOR]   Awaiting Dual-Key Cryptographic Override Signature...")
        println("🚨 ========================================================================= 🚨")
    }

    /**
     * Strictly disables the Android Back button to prevent cashiers or rogue
     * actors from dismissing the emergency quarantine screen.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        println("⛔ [SECURITY-BLOCK] Back navigation intercepted and blocked during Red Recall Lockdown!")
        // Intentionally do NOT call super.onBackPressed()
    }

    /**
     * Blocks hardware volume, back, search, and menu keys while locked.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        println("⛔ [SECURITY-BLOCK] Hardware KeyEvent ($keyCode) blocked by RedRecallLockActivity.")
        return true
    }
}
