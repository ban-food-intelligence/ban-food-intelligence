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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.ban.foodintelligence.pos.catalog.CatalogSource
import com.ban.foodintelligence.pos.catalog.PosCatalog
import com.ban.foodintelligence.pos.catalog.PosCatalogRepository
import com.ban.foodintelligence.pos.catalog.PosInventoryLot
import com.ban.foodintelligence.pos.database.AppDatabase
import com.ban.foodintelligence.pos.database.StockMovementRecord
import com.ban.foodintelligence.pos.security.FatalSecurityException
import com.ban.foodintelligence.pos.security.KeystoreManager
import com.ban.foodintelligence.pos.security.TerminalLockStore
import com.ban.foodintelligence.pos.worker.SyncWorker
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ============================================================================
 * BAN Food Intelligence - Soft Minimalist Material 3 POS Checkout (`MainActivity`)
 * Author: Nuri (Lead UI/UX Engineer & Principal Android Security Architect)
 * ============================================================================
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

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
                        println("[WARN] [POS-RECALL-INTERLOCK] Blocked sale attempt on ${recalledLot.status} lot ${recalledLot.lotNo} (recall: ${recalledLot.recallCode}). Triggering RedRecallLockActivity...")
                        val lockIntent = Intent(this@MainActivity, RedRecallLockActivity::class.java).apply {
                            putExtra(RedRecallLockActivity.EXTRA_RECALL_CODE, recalledLot.recallCode ?: recalledLot.status)
                            putExtra(RedRecallLockActivity.EXTRA_AFFECTED_LOT, recalledLot.lotNo)
                            putExtra(RedRecallLockActivity.EXTRA_ROOT_LOT, recalledLot.recallRootLot ?: recalledLot.lotNo)
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PosSalesCheckoutScreen(
    pubKeyFingerprint: String,
    appDb: AppDatabase,
    onTriggerRecallQuarantine: (PosInventoryLot) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Lots and recall state come from the Go API (GET /lots + GET /recall), cached for offline use.
    var catalog by remember { mutableStateOf<PosCatalog?>(null) }
    var pendingSales by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var isCatalogLoading by remember { mutableStateOf(false) }

    // Available = server stock minus local sales the server has not acknowledged yet.
    val inventoryLots = catalog?.lots.orEmpty().map { lot ->
        lot.copy(availableQty = (lot.availableQty - (pendingSales[lot.lotNo] ?: 0.0)).coerceAtLeast(0.0))
    }

    var selectedLotNo by remember { mutableStateOf<String?>(null) }
    val selectedLot = inventoryLots.firstOrNull { it.lotNo == selectedLotNo } ?: inventoryLots.firstOrNull()

    var quantityInput by remember { mutableStateOf("") }
    var recentMovements by remember { mutableStateOf<List<StockMovementRecord>>(emptyList()) }
    var pendingOutboxCount by remember { mutableStateOf(0) }
    var lastActionBanner by remember { mutableStateOf<String?>(null) }
    var isQuickGuideOpen by remember { mutableStateOf(false) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val saleRecordedToastMsg = stringResource(R.string.pos_sale_recorded_toast)

    suspend fun refreshLocal() {
        recentMovements = appDb.getRecentStockMovements(15)
        pendingOutboxCount = runCatching { appDb.outboxDao().countPending() }.getOrDefault(0)
        pendingSales = runCatching { appDb.pendingSaleQtyByLot() }.getOrDefault(emptyMap())
    }

    suspend fun refreshCatalog() {
        isCatalogLoading = true
        catalog = PosCatalogRepository.load(context)
        isCatalogLoading = false
    }

    LaunchedEffect(Unit) {
        refreshLocal()
        refreshCatalog()
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
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = null,
                                    tint = SoftPosColors.EmeraldVerifiedText,
                                    modifier = Modifier.size(22.dp)
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

                    // Language (TR | EN | DE) and Quick Guide, always visible in the header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LanguageToggle()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            QuickGuideHelpButton(onClick = { isQuickGuideOpen = true })
                            androidx.compose.material3.IconButton(onClick = { isSettingsOpen = true }) {
                                Icon(
                                    Icons.Rounded.Settings,
                                    contentDescription = stringResource(R.string.settings_title),
                                    tint = SoftPosColors.PrimaryIndigo
                                )
                            }
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (pendingOutboxCount > 0) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDone,
                                contentDescription = null,
                                tint = if (pendingOutboxCount > 0) SoftPosColors.PrimaryIndigo else SoftPosColors.EmeraldVerifiedText,
                                modifier = Modifier.size(20.dp)
                            )
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
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SoftPosColors.SurfacePureWhite,
                                border = BorderStroke(1.dp, SoftPosColors.OutlineSubtle)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Key,
                                        contentDescription = null,
                                        tint = SoftPosColors.TextSecondarySlate,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = pubKeyFingerprint.take(10),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Task 5: Invoice OCR (camera capture -> NVIDIA Nemotron VL)
                    PosPrimaryButton(
                        text = stringResource(R.string.ocr_open_scanner),
                        icon = Icons.Rounded.CameraAlt,
                        onClick = {
                            context.startActivity(Intent(context, InvoiceOcrActivity::class.java))
                        }
                    )
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
                    PosSectionHeader(
                        title = stringResource(R.string.pos_sales_section_title),
                        icon = Icons.Rounded.PointOfSale
                    )

                    Text(
                        text = stringResource(R.string.pos_select_lot_label),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SoftPosColors.TextSecondarySlate
                        )
                    )

                    // Catalog source: live API, offline cache, or nothing yet
                    val currentCatalog = catalog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                currentCatalog == null || (isCatalogLoading && currentCatalog.lots.isEmpty()) ->
                                    stringResource(R.string.pos_catalog_loading)
                                currentCatalog.source == CatalogSource.LIVE ->
                                    stringResource(R.string.pos_catalog_live)
                                currentCatalog.source == CatalogSource.CACHED ->
                                    stringResource(
                                        R.string.pos_catalog_cached,
                                        java.text.DateFormat.getDateTimeInstance(
                                            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                                        ).format(java.util.Date(currentCatalog.fetchedAtMs))
                                    )
                                else -> stringResource(R.string.error_server_unreachable) + "\n" + currentCatalog.error.orEmpty()
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (currentCatalog?.source == CatalogSource.LIVE) {
                                    SoftPosColors.EmeraldVerifiedText
                                } else {
                                    SoftPosColors.TextSecondarySlate
                                }
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        PosSecondaryButton(
                            text = stringResource(R.string.pos_btn_refresh),
                            icon = Icons.Rounded.Refresh,
                            onClick = { scope.launch { refreshLocal(); refreshCatalog() } },
                            enabled = !isCatalogLoading
                        )
                    }

                    if (currentCatalog != null && inventoryLots.isEmpty() && !isCatalogLoading) {
                        Text(
                            text = stringResource(R.string.pos_catalog_empty, PosCatalogRepository.STORE_WAREHOUSE),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // Selectable Lot Inventory Rows
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        inventoryLots.forEach { lot ->
                            val isSelected = lot.lotNo == selectedLot?.lotNo
                            val isRecalled = lot.isBlocked

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
                                        selectedLotNo = lot.lotNo
                                        quantityInput = ""
                                    }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = lot.productName,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isRecalled) SoftPosColors.RecallCrimsonAccent else SoftPosColors.TextPrimarySlate
                                            ),
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (isRecalled) SoftPosColors.RecallCrimsonAccent else SoftPosColors.EmeraldVerifiedBg
                                        ) {
                                            Text(
                                                text = if (lot.recallCode != null) "RECALLED" else lot.status,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = if (isRecalled) Color.White else SoftPosColors.EmeraldVerifiedText,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                maxLines = 1,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
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
                                            text = "${SaleQuantityValidator.formatQty(lot.availableQty, lot.uom)} | ${"%.2f".format(Locale.US, lot.unitPriceEur)} EUR/${lot.uom}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                color = SoftPosColors.TextSecondarySlate
                                            )
                                        )
                                    }
                                    if (lot.recallCode != null) {
                                        Text(
                                            text = stringResource(R.string.pos_recall_tag, lot.recallCode, lot.recallRootLot.orEmpty()),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = SoftPosColors.RecallCrimsonAccent
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (selectedLot != null) {
                        // Quantity is validated on every keystroke; the total is shown only for a valid quantity.
                        val qtyCheck = SaleQuantityValidator.check(quantityInput, selectedLot.uom, selectedLot.availableQty)
                        val validQty = (qtyCheck as? QtyCheck.Valid)?.qty
                        val isSelectedRecalled = selectedLot.isBlocked

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = quantityInput,
                                onValueChange = { quantityInput = it },
                                label = {
                                    Text("${stringResource(R.string.pos_quantity_hint)} (${selectedLot.uom})")
                                },
                                supportingText = {
                                    Text(
                                        text = when (qtyCheck) {
                                            is QtyCheck.Invalid -> if (qtyCheck.arg != null) {
                                                stringResource(qtyCheck.messageRes, qtyCheck.arg)
                                            } else {
                                                stringResource(qtyCheck.messageRes)
                                            }
                                            is QtyCheck.Valid -> stringResource(
                                                R.string.pos_available_label,
                                                SaleQuantityValidator.formatQty(selectedLot.availableQty, selectedLot.uom)
                                            )
                                        }
                                    )
                                },
                                isError = qtyCheck is QtyCheck.Invalid && quantityInput.isNotBlank(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (selectedLot.uom == "KG") KeyboardType.Decimal else KeyboardType.Number
                                ),
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (isSelectedRecalled) SoftPosColors.RecallCrimsonAccent else SoftPosColors.PrimaryIndigo,
                                    unfocusedBorderColor = SoftPosColors.OutlineSubtle
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = SoftPosColors.BackgroundOffWhite,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.pos_total_label),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(
                                        text = if (validQty != null) {
                                            "${"%.2f".format(Locale.US, validQty * selectedLot.unitPriceEur)} EUR"
                                        } else {
                                            "-"
                                        },
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelectedRecalled) SoftPosColors.RecallCrimsonAccent else SoftPosColors.PrimaryIndigo
                                        )
                                    )
                                }
                            }
                        }

                        // Pill Action Button: Either Execute Sale (Indigo) or Recall Quarantine Trigger (Crimson)
                        PosPrimaryButton(
                            text = if (isSelectedRecalled) {
                                stringResource(R.string.pos_btn_recall_block)
                            } else {
                                stringResource(R.string.pos_btn_execute_sale)
                            },
                            icon = if (isSelectedRecalled) Icons.Rounded.Lock else Icons.Rounded.ShoppingCart,
                            containerColor = if (isSelectedRecalled) SoftPosColors.RecallCrimsonAccent else SoftPosColors.PrimaryIndigo,
                            height = 54.dp,
                            onClick = {
                                if (isSelectedRecalled) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.pos_recall_blocked_toast, selectedLot.lotNo),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onTriggerRecallQuarantine(selectedLot)
                                } else if (validQty != null) {
                                    isSaving = true
                                    scope.launch {
                                        val record = appDb.recordPosSale(
                                            lotNo = selectedLot.lotNo,
                                            productName = selectedLot.productName,
                                            qty = validQty,
                                            uom = selectedLot.uom,
                                            unitPriceEur = selectedLot.unitPriceEur
                                        )
                                        quantityInput = ""
                                        refreshLocal()
                                        lastActionBanner = "${record.lotNo} (${"%.3f".format(Locale.US, record.qtyDelta)} ${record.uom}) -> SQLite WAL & ECDSA Outbox (${record.outboxUuidV7.take(13)}...)"

                                        runCatching { SyncWorker.enqueueWhenNetworkConnected(context) }
                                        isSaving = false

                                        Toast.makeText(
                                            context,
                                            saleRecordedToastMsg,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            enabled = !isSaving && (isSelectedRecalled || validQty != null)
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
                        PosSectionHeader(
                            title = stringResource(R.string.pos_sqlite_ledger_title),
                            icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = SoftPosColors.BackgroundOffWhite,
                            border = BorderStroke(1.dp, SoftPosColors.OutlineSubtle)
                        ) {
                            Text(
                                text = stringResource(R.string.pos_rows_count, recentMovements.size),
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

        if (isSettingsOpen) {
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { isSettingsOpen = false }) {
                com.ban.foodintelligence.pos.ui.design.PosTheme {
                    SettingsScreen(
                        keyFingerprint = pubKeyFingerprint,
                        onServerChanged = { scope.launch { refreshLocal(); refreshCatalog() } }
                    )
                }
            }
        }

        if (isQuickGuideOpen) {
            QuickGuideBottomSheet(onDismiss = { isQuickGuideOpen = false })
        }
    }
}
