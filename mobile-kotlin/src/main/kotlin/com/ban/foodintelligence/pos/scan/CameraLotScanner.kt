package com.ban.foodintelligence.pos.scan

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Camera scan through Google's code scanner (Play services). It brings its own camera screen, so
 * this app needs no camera permission and never sees the images. Not every POS device has Play
 * services: there the search field still takes a typed lot number or a keyboard-wedge hand scanner.
 */
object CameraLotScanner {
    private const val TAG = "CameraLotScanner"

    fun scan(context: Context, onCode: (String) -> Unit, onUnavailable: () -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX)
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw.isNullOrBlank()) onUnavailable() else onCode(raw)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Code scanner unavailable: ${e.message}")
                onUnavailable()
            }
        // A cancelled scan (back button) needs no message.
    }
}
