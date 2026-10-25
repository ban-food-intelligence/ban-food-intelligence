package com.ban.foodintelligence.pos.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device-admin component. It makes the app eligible to be provisioned as
 * DEVICE OWNER, which is what turns `startLockTask()` from user-escapable screen
 * pinning into a real kiosk:
 *
 *     adb shell dpm set-device-owner com.ban.foodintelligence.pos/.admin.PosDeviceAdminReceiver
 *
 * (Only works on a freshly reset device with no accounts, or via MDM/QR provisioning.)
 */
class PosDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("PosDeviceAdmin", "🧱 Device admin enabled.")
    }
}
