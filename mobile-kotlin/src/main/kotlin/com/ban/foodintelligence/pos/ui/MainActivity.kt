package com.ban.foodintelligence.pos.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.database.AppDatabase
import com.ban.foodintelligence.pos.database.StockMovementRecord
import com.ban.foodintelligence.pos.security.FatalSecurityException
import com.ban.foodintelligence.pos.security.KeystoreManager
import com.ban.foodintelligence.pos.security.TerminalLockStore
import com.ban.foodintelligence.pos.worker.SyncWorker
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Represents a lot-tracked retail inventory batch available on the POS terminal.
 */
data class PosInventoryLot(
    val lotNo: String,
    val productName: String,
    val rootLotNo: String,
    val availableQty: Double,
    val uom: String,
    val unitPriceEur: Double,
    val status: String // "ACTIVE" or "RECALLED"
)

/**
 * ============================================================================
 * BAN Food Intelligence - Soft Minimalist Material 3 POS Checkout (`MainActivity`)
 * Author: Nuri (Lead UI/UX Engineer & Principal Android Security Architect)
 * ============================================================================
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        println("[INFO] Building Soft Material POS Checkout...")
        println("[INFO] Applying Soft Minimalist Material 3...")
        println("[INFO] Provisioning DE/EN/TR i18n...")

        val pubKeyFingerprint = try {
            println("[INFO] Provisioning ECDSA P-256 Keypair...")
            KeystoreManager.ensureKeyPairProvisioned(this)
            val pubKeyB64 = KeystoreManager.exportPublicKeyBase64(this)
            println("[INFO] [POS-ENROLLMENT] Exported ECDSA P-256 Public Key for Go Backend: ${pubKeyB64.take(28)}...")
            KeystoreManager.publicKeyFingerprintHex().take(20)
        } catch (e: FatalSecurityException) {
            "HARDWARE-LOCKED"
        }

        val appDb = AppDatabase.getInstance(this)
        runCatching { SyncWorker.enqueueWhenNetworkConnected(this) }

        setContent {
            AppTheme {
                PosSalesCheckoutScreen(
                    pubKeyFingerprint = pubKeyFingerprint,
                    appDb = appDb,
                    onTriggerRecallQuarantine = { recalledLot ->
                        println("[WARN] [POS-RECALL-INTERLOCK] Blocked sale attempt on RECALLED lot ${recalledLot.lotNo} (Root: ${recalledLot.rootLotNo}). Triggering RedRecallLockActivity...")
                        val lockIntent = Intent(this@MainActivity, RedRecallLockActivity::class.java).apply {
                            putExtra(RedRecallLockActivity.EXTRA_RECALL_CODE, "RCL-POS-SALE-BLOCKED-CONTAMINATED-LOT")
                            putExtra(RedRecallLockActivity.EXTRA_AFFECTED_LOT, recalledLot.lotNo)
                            putExtra(RedRecallLockActivity.EXTRA_ROOT_LOT, recalledLot.rootLotNo)
                            putExtra(RedRecallLockActivity.EXTRA_OOD_METRIC, "pos_lot_quarantine_gate")
                            putExtra(RedRecallLockActivity.EXTRA_OOD_VALUE, 1.0000f)
                        }
                        startActivity(lockIntent)
                        @Suppress("DEPRECATION")
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                )
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
                    println("[WARN] [MAIN-LOCK-GUARD] ${getString(R.string.pos_kiosk_guard_redirect)}")
                    val lockIntent = Intent(this, RedRecallLockActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(lockIntent)
                    @Suppress("DEPRECATION")
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            }
        }
    }
}

@Composable
private fun PosSalesCheckoutScreen(
    pubKeyFingerprint: String,
    appDb: AppDatabase,
    onTriggerRecallQuarantine: (PosInventoryLot) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val inventoryLots = remember {
        mutableStateListOf(
            PosInventoryLot(
                lotNo = "KZ-BUT-2026-001",
                productName = "Kuzu But (Kemikli)",
                rootLotNo = "KUZ-2026-001",
                availableQty = 7.50,
                uom = "KG",
                unitPriceEur = 22.00,
                status = "ACTIVE"
            ),
            PosInventoryLot(
                lotNo = "KZ-SRT-2026-001",
                productName = "Kuzu Sırt / Pirzola",
                rootLotNo = "KUZ-2026-001",
                availableQty = 4.20,
                uom = "KG",
                unitPriceEur = 28.00,
                status = "ACTIVE"
            ),
            PosInventoryLot(
                lotNo = "KZ-OMZ-2026-001",
                productName = "Kuzu Omuz / Kol",
                rootLotNo = "KUZ-2026-001",
                availableQty = 4.80,
                uom = "KG",
                unitPriceEur = 17.50,
                status = "ACTIVE"
            ),
            PosInventoryLot(
                lotNo = "SUC-2026-044",
                productName = "Narin Kangal Sucuk (Temiz Parti)",
                rootLotNo = "ET-2026-000",
                availableQty = 40.0,
                uom = "PAKET",
                unitPriceEur = 24.50,
                status = "ACTIVE"
            ),
            PosInventoryLot(
                lotNo = "SUC-2026-045",
                productName = "Narin Kangal Sucuk (Karantina / Recall)",
                rootLotNo = "ET-2026-001",
                availableQty = 30.0,
                uom = "PAKET",
                unitPriceEur = 24.50,
                status = "RECALLED"
            )
        )
    }

    var selectedLotIndex by remember { mutableStateOf(0) }
    val selectedLot = inventoryLots[selectedLotIndex]

    var quantityInput by remember { mutableStateOf("1.50") }
    var recentMovements by remember { mutableStateOf<List<StockMovementRecord>>(emptyList()) }
    var pendingOutboxCount by remember { mutableStateOf(0) }
    var lastActionBanner by remember { mutableStateOf<String?>(null) }

    val saleRecordedToastMsg = stringResource(R.string.pos_sale_recorded_toast)

    LaunchedEffect(Unit) {
        recentMovements = appDb.getRecentStockMovements(15)
        pendingOutboxCount = runCatching { appDb.outboxDao().countPending() }.getOrDefault(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SoftPosColors.BackgroundOffWhite)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Top Terminal Attestation & Sync Status Card (Apple HIG Card)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(SoftPosDimens.CardCornerRadius),
                color = SoftPosColors.SurfacePureWhite,
                border = BorderStroke(SoftPosDimens.CardBorderWidth, SoftPosColors.OutlineSubtle),
                shadowElevation = SoftPosDimens.CardElevationStandard
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SoftPosDimens.CardPaddingStandard),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = SoftPosColors.EmeraldVerifiedBg,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "✓",
                                    color = SoftPosColors.EmeraldVerifiedText,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.pos_terminal_header),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.pos_keystore_status_ready),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = SoftPosColors.EmeraldVerifiedText
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SoftPosColors.BackgroundOffWhite,
                        border = BorderStroke(1.dp, SoftPosColors.OutlineSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (pendingOutboxCount > 0) {
                                    stringResource(R.string.sync_status_syncing, pendingOutboxCount)
                                } else {
                                    stringResource(R.string.sync_status_idle)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SoftPosColors.SurfacePureWhite,
                                border = BorderStroke(1.dp, SoftPosColors.OutlineSubtle)
                            ) {
                                Text(
                                    text = "ECDSA: ${pubKeyFingerprint.take(10)}...",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Task 5: Launch Invoice OCR Scanner (NVIDIA Nemotron-3.5-VL)
                    Button(
                        onClick = {
                            context.startActivity(Intent(context, InvoiceOcrActivity::class.java))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoftPosColors.PrimaryIndigo,
                            contentColor = SoftPosColors.SurfacePureWhite
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 2.dp,
                            pressedElevation = 1.dp
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.ocr_open_scanner),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            // 2. POS Lot-Tracked Sales & Stock Dispatch Card (Apple HIG Card)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(SoftPosDimens.CardCornerRadius),
                color = SoftPosColors.SurfacePureWhite,
                border = BorderStroke(SoftPosDimens.CardBorderWidth, SoftPosColors.OutlineSubtle),
                shadowElevation = SoftPosDimens.CardElevationStandard
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SoftPosDimens.CardPaddingStandard),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pos_sales_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )

                    Text(
                        text = stringResource(R.string.pos_select_lot_label),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SoftPosColors.TextSecondarySlate
                        )
                    )

                    // Selectable Lot Inventory Rows
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        inventoryLots.forEachIndexed { index, lot ->
                            val isSelected = index == selectedLotIndex
                            val isRecalled = lot.status == "RECALLED"

                            val cardBg = when {
                                isRecalled && isSelected -> SoftPosColors.RecallPastelBackground
                                isRecalled -> Color(0xFFFFF5F5)
                                isSelected -> Color(0xFFEFF6FF)
                                else -> SoftPosColors.BackgroundOffWhite
                            }
                            val borderColor = when {
                                isRecalled && isSelected -> SoftPosColors.RecallCrimsonAccent
                                isRecalled -> SoftPosColors.RecallSoftBorder
                                isSelected -> SoftPosColors.PrimaryIndigo
                                else -> SoftPosColors.OutlineSubtle
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = cardBg,
                                border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedLotIndex = index
                                        quantityInput = if (lot.uom == "PAKET") "2" else "1.25"
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = lot.productName,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isRecalled) SoftPosColors.RecallCrimsonAccent else SoftPosColors.TextPrimarySlate
                                                )
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = SoftPosColors.SurfacePureWhite
                                            ) {
                                                Text(
                                                    text = lot.lotNo,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Medium
                                                    ),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Text(
                                                text = "Root: ${lot.rootLotNo}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = SoftPosColors.TextSecondarySlate
                                                )
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (isRecalled) SoftPosColors.RecallCrimsonAccent else SoftPosColors.EmeraldVerifiedBg
                                        ) {
                                            Text(
                                                text = lot.status,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = if (isRecalled) Color.White else SoftPosColors.EmeraldVerifiedText,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${"%.2f".format(Locale.US, lot.availableQty)} ${lot.uom} • ${"%.2f".format(Locale.US, lot.unitPriceEur)} €",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                color = SoftPosColors.TextSecondarySlate
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Quantity Input & Calculated Total Row
                    val parsedQty = quantityInput.replace(',', '.').toDoubleOrNull() ?: 0.0
                    val computedTotalEur = parsedQty * selectedLot.unitPriceEur
                    val isSelectedRecalled = selectedLot.status == "RECALLED"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = quantityInput,
                            onValueChange = { quantityInput = it },
                            label = {
                                Text("${stringResource(R.string.pos_quantity_hint)} (${selectedLot.uom})")
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (isSelectedRecalled) SoftPosColors.RecallCrimsonAccent else SoftPosColors.PrimaryIndigo,
                                unfocusedBorderColor = SoftPosColors.OutlineSubtle
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SoftPosColors.BackgroundOffWhite,
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = "TOPLAM / TOTAL",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = "${"%.2f".format(Locale.US, computedTotalEur)} €",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelectedRecalled) SoftPosColors.RecallCrimsonAccent else SoftPosColors.PrimaryIndigo
                                    )
                                )
                            }
                        }
                    }

                    // Pill Action Button: Either Execute Sale (Indigo) or Recall Quarantine Trigger (Crimson)
                    Button(
                        onClick = {
                            if (selectedLot.status == "RECALLED") {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.pos_recall_blocked_toast, selectedLot.lotNo),
                                    Toast.LENGTH_SHORT
                                ).show()
                                onTriggerRecallQuarantine(selectedLot)
                            } else {
                                val qty = parsedQty.coerceAtLeast(0.1)
                                scope.launch {
                                    val record = appDb.recordPosSale(
                                        lotNo = selectedLot.lotNo,
                                        productName = selectedLot.productName,
                                        qty = qty,
                                        uom = selectedLot.uom,
                                        unitPriceEur = selectedLot.unitPriceEur
                                    )
                                    val updatedQty = (selectedLot.availableQty - qty).coerceAtLeast(0.0)
                                    inventoryLots[selectedLotIndex] = selectedLot.copy(availableQty = updatedQty)
                                    recentMovements = appDb.getRecentStockMovements(15)
                                    pendingOutboxCount = runCatching { appDb.outboxDao().countPending() }.getOrDefault(0)
                                    lastActionBanner = "✓ ${record.lotNo} (${"%.2f".format(Locale.US, record.qtyDelta)} ${record.uom}) -> SQLite WAL & ECDSA Outbox (${record.outboxUuidV7.take(13)}...)"

                                    runCatching { SyncWorker.enqueueWhenNetworkConnected(context) }

                                    Toast.makeText(
                                        context,
                                        saleRecordedToastMsg,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelectedRecalled) {
                                SoftPosColors.RecallCrimsonAccent
                            } else {
                                SoftPosColors.PrimaryIndigo
                            },
                            contentColor = SoftPosColors.SurfacePureWhite
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 2.dp
                        )
                    ) {
                        Text(
                            text = if (isSelectedRecalled) {
                                stringResource(R.string.pos_btn_recall_block)
                            } else {
                                stringResource(R.string.pos_btn_execute_sale)
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    lastActionBanner?.let { banner ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SoftPosColors.EmeraldVerifiedBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SoftPosColors.EmeraldVerifiedText,
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            // 3. Live Local SQLite WAL Stock Movements & Outbox Queue Ledger (Apple HIG Card)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(SoftPosDimens.CardCornerRadius),
                color = SoftPosColors.SurfacePureWhite,
                border = BorderStroke(SoftPosDimens.CardBorderWidth, SoftPosColors.OutlineSubtle),
                shadowElevation = SoftPosDimens.CardElevationStandard
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SoftPosDimens.CardPaddingStandard),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.pos_sqlite_ledger_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = SoftPosColors.BackgroundOffWhite,
                            border = BorderStroke(1.dp, SoftPosColors.OutlineSubtle)
                        ) {
                            Text(
                                text = "${recentMovements.size} Rows",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (recentMovements.isEmpty()) {
                        Text(
                            text = stringResource(R.string.pos_sqlite_empty),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SoftPosColors.TextSecondarySlate
                            )
                        )
                    } else {
                        recentMovements.forEach { mov ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = SoftPosColors.BackgroundOffWhite,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${mov.productName} (${mov.lotNo})",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                        Text(
                                            text = "${"%.2f".format(Locale.US, mov.qtyDelta)} ${mov.uom} • ${"%.2f".format(Locale.US, mov.totalEur)} €",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = SoftPosColors.PrimaryIndigo
                                            )
                                        )
                                    }
                                    Text(
                                        text = "UUIDv7: ${mov.outboxUuidV7} | ECDSA: ${mov.ecdsaSignaturePreview}... [${mov.syncStatus}]",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = SoftPosColors.TextSecondarySlate
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
