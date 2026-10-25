package com.ban.foodintelligence.pos

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.ban.foodintelligence.pos.security.KeystoreManager
import com.ban.foodintelligence.pos.security.TerminalLockStore
import com.ban.foodintelligence.pos.ui.RedRecallLockActivity
import com.ban.foodintelligence.pos.worker.SyncWorker

/**
 * ============================================================================
 * BAN Food Intelligence - POS Application Entrypoint (`PosApplication`)
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * Enforces persistent Kiosk Lockdown across process deaths and reboots:
 * 1. Provisions the asymmetric ECDSA P-256 keypair inside Android Keystore.
 * 2. Registers a global [ActivityLifecycleCallbacks] interceptor that checks
 *    `SharedPreferences` (`IS_TERMINAL_LOCKED`) whenever ANY activity starts
 *    or resumes, immediately redirecting to [RedRecallLockActivity] if locked.
 */
class PosApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        println("🔐 Provisioning ECDSA P-256 Keypair...")
        runCatching { KeystoreManager.ensureKeyPairProvisioned() }
        runCatching { SyncWorker.enqueueWhenNetworkConnected(this) }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                enforcePersistentLockIfNecessary(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                enforcePersistentLockIfNecessary(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                enforcePersistentLockIfNecessary(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        if (TerminalLockStore.isLocked(this)) {
            println("🧱 [BOOT-LOCK-GUARD] Persistent flag IS_TERMINAL_LOCKED=true detected on process startup! Redirecting to RedRecallLockActivity...")
            val lockIntent = Intent(this, RedRecallLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(lockIntent)
        }
    }

    private fun enforcePersistentLockIfNecessary(activity: Activity) {
        if (activity is RedRecallLockActivity) return
        if (TerminalLockStore.isLocked(activity)) {
            println("🧱 [LOCK-INTERCEPTOR] IS_TERMINAL_LOCKED=true! Blocking ${activity.javaClass.simpleName} and redirecting to RedRecallLockActivity...")
            val lockIntent = Intent(activity, RedRecallLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            activity.startActivity(lockIntent)
        }
    }
}
