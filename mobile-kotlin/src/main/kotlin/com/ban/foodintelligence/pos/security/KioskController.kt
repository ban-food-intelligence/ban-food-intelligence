package com.ban.foodintelligence.pos.security

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.ban.foodintelligence.pos.admin.PosDeviceAdminReceiver

/**
 * ============================================================================
 * Kiosk / lock-task controller
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * Two very different strengths, depending on provisioning:
 *
 * 1. DEVICE OWNER (real kiosk): the package is allow-listed with
 *    `setLockTaskPackages`, and on API 28+ every lock-task feature (Home,
 *    Overview, notifications, keyguard) is disabled. The user cannot leave.
 *
 * 2. NOT device owner (screen pinning only): `startLockTask()` falls back to
 *    Android screen pinning. The user is prompted to confirm, and can exit with
 *    the system gesture (Back + Overview). This slows a cashier down but is NOT a
 *    security boundary.
 */
object KioskController {

    private const val TAG = "KioskController"

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /** Enters lock-task mode if not already in it. Safe to call repeatedly. */
    fun engage(activity: Activity) {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (dpm.isDeviceOwnerApp(activity.packageName)) {
            val admin = ComponentName(activity, PosDeviceAdminReceiver::class.java)
            dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            Log.w(TAG, "🧱 Device owner detected: full kiosk lock-task allow-list applied.")
        } else {
            Log.w(TAG, "⚠️ Not device owner: startLockTask() will only pin the screen (user-escapable).")
        }

        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            Log.w(TAG, "🧱 Engaging Kiosk Mode (startLockTask)...")
            activity.startLockTask()
        }
    }

    /** Leaves lock-task mode if active. */
    fun release(activity: Activity) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            Log.w(TAG, "🔓 Releasing Kiosk Mode (stopLockTask)...")
            activity.stopLockTask()
        }
    }
}
