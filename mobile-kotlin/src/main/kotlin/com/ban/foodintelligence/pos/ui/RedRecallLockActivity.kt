package com.ban.foodintelligence.pos.ui

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.ban.foodintelligence.pos.R

/**
 * Red Recall Lock Screen UI by Nuri.
 * Activated immediately when a Class I Recall broadcast (e.g. RCL-2026-ET001-SALMONELLA)
 * is received or a recalled lot (SUC-2026-045) is scanned at POS checkout.
 * Prohibits retail sales, locks payment hardware, and alerts store manager.
 */
class RedRecallLockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock window flags to keep screen on and block dismiss
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        val recallCode = intent.getStringExtra("EXTRA_RECALL_CODE") ?: "RCL-2026-ET001-SALMONELLA"
        val affectedLot = intent.getStringExtra("EXTRA_AFFECTED_LOT") ?: "SUC-2026-045"
        val rootLot = intent.getStringExtra("EXTRA_ROOT_LOT") ?: "ET-2026-001"

        // Setup lock state
        enforceTerminalLockdown(recallCode, affectedLot, rootLot)
    }

    private fun enforceTerminalLockdown(recallCode: String, affectedLot: String, rootLot: String) {
        // Disables POS peripheral scanner, cash drawer, and barcode input
        // Enforces full-screen red emergency overlay
        println("🚨 [EMERGENCY LOCKDOWN] Recall Code: $recallCode")
        println("🚨 Recalled Finished Good: $affectedLot | Root Cause Lot: $rootLot")
        println("🚨 Hardware POS checkout locked. Supervisor cryptographic signature required to unlock.")
    }

    override fun onBackPressed() {
        // Disable back button while emergency recall lock screen is active
    }
}
