package com.ban.foodintelligence.pos.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ban.foodintelligence.pos.database.AppDatabase
import com.ban.foodintelligence.pos.security.FatalSecurityException
import com.ban.foodintelligence.pos.security.KeystoreManager
import com.ban.foodintelligence.pos.security.TerminalLockStore
import com.ban.foodintelligence.pos.ui.design.PosTheme
import com.ban.foodintelligence.pos.ui.design.palette
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ban.foodintelligence.pos.worker.SyncWorker

/**
 * POS host activity: prepares the signing key and the offline database, schedules the
 * outbox sync and shows [PosApp] (tabs Sell, Lots, Recall, Sync, Settings). While the
 * terminal is locked by a recall it always redirects to [RedRecallLockActivity].
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Key provisioning, opening SQLite and scheduling sync are slow on some devices:
        // do them off the main thread and show the app as soon as they are ready.
        setContent {
            PosTheme {
                var ready by remember { mutableStateOf<Pair<AppDatabase, String>?>(null) }
                LaunchedEffect(Unit) {
                    ready = withContext(Dispatchers.IO) {
                        val fingerprint = try {
                            KeystoreManager.ensureKeyPairProvisioned(applicationContext)
                            KeystoreManager.publicKeyFingerprintHex().take(16)
                        } catch (e: FatalSecurityException) {
                            "-"
                        }
                        val db = AppDatabase.getInstance(applicationContext)
                        runCatching { SyncWorker.enqueueWhenNetworkConnected(applicationContext) }
                        db to fingerprint
                    }
                }
                val r = ready
                if (r == null) {
                    Box(Modifier.fillMaxSize().background(palette.background))
                } else {
                    PosApp(appDb = r.first, keyFingerprint = r.second)
                }
            }
        }
        redirectIfTerminalLocked()
    }

    override fun onResume() {
        super.onResume()
        redirectIfTerminalLocked()
    }

    private fun redirectIfTerminalLocked() {
        if (!TerminalLockStore.isLocked(this)) return
        window?.decorView?.post {
            if (!isFinishing && TerminalLockStore.isLocked(this)) {
                startActivity(
                    Intent(this, RedRecallLockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        }
    }
}
