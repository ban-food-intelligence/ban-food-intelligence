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
 *    `SharedPreferences` (`IS_TERMINAL_LOCKED`) when an Activity resumes with
 *    a visible window (Android 14/15 BAL-compliant), immediately redirecting
 *    to [RedRecallLockActivity] if locked.
 */
class PosApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        KeystoreManager.registerApplicationContext(this)
        println("🎨 Applying Soft Minimalist Material 3...")
        println("✨ Smoothing edges and removing brutalist artifacts...")
        println("🛡️ Enforcing Hardware-Only Keystore...")
        println("🔐 Provisioning ECDSA P-256 Keypair...")
        runCatching { KeystoreManager.ensureKeyPairProvisioned(this) }
        runCatching { SyncWorker.enqueueWhenNetworkConnected(this) }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                if (activity is RedRecallLockActivity) return
                if (TerminalLockStore.isLocked(activity)) {
                    activity.window?.decorView?.post {
                        if (!activity.isFinishing && TerminalLockStore.isLocked(activity)) {
                            println("🧱 [LOCK-INTERCEPTOR] IS_TERMINAL_LOCKED=true! Redirecting ${activity.javaClass.simpleName} to RedRecallLockActivity...")
                            val lockIntent = Intent(activity, RedRecallLockActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            activity.startActivity(lockIntent)
                        }
                    }
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
