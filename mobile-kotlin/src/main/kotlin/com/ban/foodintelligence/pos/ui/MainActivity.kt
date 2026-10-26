package com.ban.foodintelligence.pos.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.security.FatalSecurityException
import com.ban.foodintelligence.pos.security.KeystoreManager
import com.ban.foodintelligence.pos.security.TerminalLockStore
import com.ban.foodintelligence.pos.worker.SyncWorker

/**
 * ============================================================================
 * BAN Food Intelligence - Soft Minimalist Material 3 POS Checkout (`MainActivity`)
 * Author: Nuri (Lead UI/UX Engineer & Principal Android Security Architect)
 * ============================================================================
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        println("🎨 Applying Soft Minimalist Material 3...")
        println("✨ Smoothing edges and removing brutalist artifacts...")
        println("🌍 Provisioning DE/EN/TR i18n...")

        val pubKeyFingerprint = try {
            println("🔐 Provisioning ECDSA P-256 Keypair...")
            KeystoreManager.ensureKeyPairProvisioned(this)
            val pubKeyB64 = KeystoreManager.exportPublicKeyBase64(this)
            println("📤 [POS-ENROLLMENT] Exported ECDSA P-256 Public Key for Go Backend: ${pubKeyB64.take(28)}...")
            KeystoreManager.publicKeyFingerprintHex().take(20)
        } catch (e: FatalSecurityException) {
            "HARDWARE-LOCKED"
        }

        runCatching { SyncWorker.enqueueWhenNetworkConnected(this) }

        setContent {
            AppTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SoftPosColors.BackgroundOffWhite)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = SoftPosColors.SurfacePureWhite
                        ),
                        elevation = CardDefaults.elevatedCardElevation(
                            defaultElevation = 6.dp
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = SoftPosColors.EmeraldVerifiedBg,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(text = "✓", color = SoftPosColors.EmeraldVerifiedText, fontSize = 20.sp)
                                    }
                                }
                                Column {
                                    Text(
                                        text = stringResource(R.string.pos_terminal_header),
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = stringResource(R.string.pos_keystore_status_ready),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = SoftPosColors.EmeraldVerifiedText
                                        )
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = SoftPosColors.BackgroundOffWhite,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.sync_status_idle),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = SoftPosColors.SurfacePureWhite
                                    ) {
                                        Text(
                                            text = "ECDSA-P256: $pubKeyFingerprint...",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Pill-Shaped Button to Preview the Soft Recall Lock Screen
                            Button(
                                onClick = {
                                    val lockIntent = Intent(this@MainActivity, RedRecallLockActivity::class.java).apply {
                                        putExtra(RedRecallLockActivity.EXTRA_RECALL_CODE, "RCL-OOD-EPISTEMIC-VARIANCE")
                                        putExtra(RedRecallLockActivity.EXTRA_AFFECTED_LOT, "SUC-2026-045")
                                        putExtra(RedRecallLockActivity.EXTRA_ROOT_LOT, "ET-2026-001")
                                        putExtra(RedRecallLockActivity.EXTRA_OOD_METRIC, "gp_uncertainty_variance")
                                        putExtra(RedRecallLockActivity.EXTRA_OOD_VALUE, 0.9645f)
                                    }
                                    startActivity(lockIntent)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SoftPosColors.RecallCrimsonAccent,
                                    contentColor = SoftPosColors.SurfacePureWhite
                                ),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 4.dp,
                                    pressedElevation = 2.dp
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.lockdown_title),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
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
        if (TerminalLockStore.isLocked(this)) {
            window?.decorView?.post {
                if (!isFinishing && TerminalLockStore.isLocked(this)) {
                    println("🧱 [MAIN-LOCK-GUARD] ${getString(R.string.pos_kiosk_guard_redirect)}")
                    val lockIntent = Intent(this, RedRecallLockActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(lockIntent)
                }
            }
        }
    }
}
