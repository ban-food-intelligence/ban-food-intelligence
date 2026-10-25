package com.ban.foodintelligence.pos.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ban.foodintelligence.pos.security.KeystoreManager
import com.ban.foodintelligence.pos.security.TerminalLockStore
import com.ban.foodintelligence.pos.worker.SyncWorker

/**
 * ============================================================================
 * BAN Food Intelligence - Primary POS Checkout Activity (`MainActivity`)
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * Checks the persistent `IS_TERMINAL_LOCKED` flag in [onCreate] and [onResume].
 * If the terminal was locked prior to a reboot or process kill, it immediately
 * redirects to [RedRecallLockActivity] before rendering checkout controls.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (redirectIfTerminalLocked()) return

        println("🔐 Provisioning ECDSA P-256 Keypair...")
        KeystoreManager.ensureKeyPairProvisioned()
        val pubKeyB64 = KeystoreManager.exportPublicKeyBase64()
        println("📤 [POS-ENROLLMENT] Exported ECDSA P-256 Public Key for Go Backend: ${pubKeyB64.take(28)}...")

        SyncWorker.enqueueWhenNetworkConnected(this)
    }

    override fun onResume() {
        super.onResume()
        redirectIfTerminalLocked()
    }

    private fun redirectIfTerminalLocked(): Boolean {
        if (TerminalLockStore.isLocked(this)) {
            println("🧱 [MAIN-LOCK-GUARD] IS_TERMINAL_LOCKED=true detected! Redirecting immediately to RedRecallLockActivity...")
            val lockIntent = Intent(this, RedRecallLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(lockIntent)
            finish()
            return true
        }
        return false
    }
}
