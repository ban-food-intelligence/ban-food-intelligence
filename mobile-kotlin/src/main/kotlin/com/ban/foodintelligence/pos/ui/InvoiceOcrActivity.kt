package com.ban.foodintelligence.pos.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.database.AppDatabase
import com.ban.foodintelligence.pos.ocr.InvoiceExtraction
import com.ban.foodintelligence.pos.ocr.NvidiaVisionOcrClient
import com.ban.foodintelligence.pos.security.TerminalLockStore
import com.ban.foodintelligence.pos.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

/** UI state for the invoice OCR flow. */
private sealed interface OcrUiState {
    data object Idle : OcrUiState
    data object Loading : OcrUiState
    data class Suggestions(val extraction: InvoiceExtraction) : OcrUiState
    data class Error(val message: String, val offerDemo: Boolean) : OcrUiState
    data class Saved(val count: Int) : OcrUiState
}

/**
 * ============================================================================
 * BAN Food Intelligence - Task 5: Invoice OCR Screen (`InvoiceOcrActivity`)
 * ============================================================================
 *
 * Flow: capture (simulated) -> NVIDIA Nemotron VL extraction (with Shimmer
 * loading skeleton and graceful Snackbar error handling) -> operator review
 * -> "Confirm & Save to Stock" writes signed RECEIPT movements to SQLite WAL and
 * queues them for sync. Lines without a lot number are never saved, because a
 * lot-less receipt would break traceability.
 */
class InvoiceOcrActivity : ComponentActivity() {

    companion object {
        const val OCR_UNAVAILABLE_SNACKBAR_MESSAGE =
            "OCR service unavailable. Please enter details manually."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        println("[INFO] Initializing NVIDIA Nemotron-3.5-VL Invoice OCR screen...")

        val appDb = AppDatabase.getInstance(this)
        setContent {
            AppTheme {
                InvoiceOcrScreen(
                    appDb = appDb,
                    onBack = {
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A recalled terminal must not accept new stock either.
        if (TerminalLockStore.isLocked(this)) {
            window?.decorView?.post {
                startActivity(
                    Intent(this, RedRecallLockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }
    }
}

@Composable
private fun InvoiceOcrScreen(appDb: AppDatabase, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var capturedInvoice by remember { mutableStateOf<Bitmap?>(null) }
    var state by remember { mutableStateOf<OcrUiState>(OcrUiState.Idle) }
    var isSaving by remember { mutableStateOf(false) }

    val noKeyMessage = stringResource(R.string.ocr_error_no_key)

    fun runExtraction(bitmap: Bitmap) {
        state = OcrUiState.Loading
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { bitmap.toJpeg(quality = 85) }
                val extraction = NvidiaVisionOcrClient.extractInvoice(jpeg)
                state = OcrUiState.Suggestions(extraction)
            } catch (e: Throwable) {
                println("[WARN] NVIDIA NIM OCR call failed: ${e.message}")
                val keyMissing = !NvidiaVisionOcrClient.isApiKeyConfigured
                state = OcrUiState.Error(
                    message = if (keyMissing) {
                        noKeyMessage
                    } else {
                        InvoiceOcrActivity.OCR_UNAVAILABLE_SNACKBAR_MESSAGE
                    },
                    offerDemo = true
                )
                snackbarHostState.showSnackbar(
                    message = InvoiceOcrActivity.OCR_UNAVAILABLE_SNACKBAR_MESSAGE,
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    Scaffold(
        containerColor = SoftPosColors.BackgroundOffWhite,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SoftPosColors.BackgroundOffWhite)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(SoftPosDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.ocr_back)) }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = stringResource(R.string.ocr_screen_title),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = stringResource(R.string.ocr_screen_subtitle),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Capture card
            SoftCard {
                val bitmap = capturedInvoice
                if (bitmap == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(SoftPosColors.SurfaceSubtleGray, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.ocr_screen_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                            .background(SoftPosColors.SurfaceSubtleGray, RoundedCornerShape(16.dp))
                            .padding(8.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            capturedInvoice = renderSimulatedInvoice()
                            state = OcrUiState.Idle
                        },
                        enabled = state !is OcrUiState.Loading,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text(
                            stringResource(
                                if (capturedInvoice == null) R.string.ocr_btn_capture else R.string.ocr_btn_recapture
                            )
                        )
                    }
                    PillButton(
                        text = stringResource(R.string.ocr_btn_extract),
                        enabled = capturedInvoice != null && state !is OcrUiState.Loading,
                        modifier = Modifier.weight(1.4f),
                        onClick = { capturedInvoice?.let(::runExtraction) }
                    )
                }
            }

            // Result area with smooth Crossfade transition
            Crossfade(
                targetState = state,
                animationSpec = tween(durationMillis = 300),
                label = "ocr_state_crossfade"
            ) { s ->
                when (s) {
                    OcrUiState.Idle -> Unit

                    OcrUiState.Loading -> OcrShimmerLoadingCard(
                        modelId = NvidiaVisionOcrClient.MODEL_ID
                    )

                    is OcrUiState.Error -> SoftCard(containerColor = SoftPosColors.PastelRedContainer) {
                        Text(
                            text = stringResource(R.string.ocr_error_title),
                            style = MaterialTheme.typography.titleLarge.copy(color = SoftPosColors.RecallCrimsonDark)
                        )
                        Text(
                            text = InvoiceOcrActivity.OCR_UNAVAILABLE_SNACKBAR_MESSAGE,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        if (s.message != InvoiceOcrActivity.OCR_UNAVAILABLE_SNACKBAR_MESSAGE) {
                            Text(text = s.message, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (s.offerDemo) {
                            OutlinedButton(
                                onClick = {
                                    state = OcrUiState.Suggestions(
                                        NvidiaVisionOcrClient.parseModelContent(
                                            NvidiaVisionOcrClient.DEMO_MODEL_RESPONSE,
                                            latencyMs = 0,
                                            isDemo = true
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text(stringResource(R.string.ocr_btn_use_demo))
                            }
                        }
                    }

                    is OcrUiState.Suggestions -> SuggestionsCard(
                        extraction = s.extraction,
                        isSaving = isSaving,
                        onDiscard = { state = OcrUiState.Idle },
                        onConfirm = {
                            isSaving = true
                            scope.launch {
                                val saved = s.extraction.items
                                    .filter { it.lotNumber.isNotBlank() && it.quantity > 0 }
                                    .map { item ->
                                        appDb.recordStockReceipt(
                                            lotNo = item.lotNumber,
                                            productName = item.productName,
                                            qty = item.quantity,
                                            uom = item.unit,
                                            unitCostEur = item.unitCost
                                        )
                                    }
                                runCatching { SyncWorker.enqueueWhenNetworkConnected(context) }
                                isSaving = false
                                state = OcrUiState.Saved(saved.size)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.ocr_saved_success, saved.size),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )

                    is OcrUiState.Saved -> SoftCard(containerColor = SoftPosColors.EmeraldVerifiedBg) {
                        Text(
                            text = stringResource(R.string.ocr_saved_success, s.count),
                            style = MaterialTheme.typography.titleMedium.copy(color = SoftPosColors.EmeraldVerifiedText)
                        )
                        PillButton(text = stringResource(R.string.ocr_back), onClick = onBack)
                    }
                }
            }
        }
    }
}

/**
 * Standard Material 3 Shimmer skeleton loading effect displayed while waiting
 * for the NVIDIA NIM Vision-Language API response.
 */
@Composable
private fun OcrShimmerLoadingCard(modelId: String) {
    val transition = rememberInfiniteTransition(label = "ocr_shimmer_transition")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ocr_shimmer_offset"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFE2E8F0),
            Color(0xFFF8FAFC),
            Color(0xFFE2E8F0)
        ),
        start = Offset(translateAnim - 300f, translateAnim - 300f),
        end = Offset(translateAnim, translateAnim)
    )

    SoftCard {
        Text(
            text = stringResource(R.string.ocr_loading, modelId),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        repeat(3) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SoftPosColors.BackgroundOffWhite)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(shimmerBrush)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(shimmerBrush)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(shimmerBrush)
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionsCard(
    extraction: InvoiceExtraction,
    isSaving: Boolean,
    onDiscard: () -> Unit,
    onConfirm: () -> Unit
) {
    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.ocr_suggestions_title),
                style = MaterialTheme.typography.titleLarge
            )
            Chip(
                text = if (extraction.isDemoResponse) "DEMO" else "${extraction.latencyMs} ms",
                container = if (extraction.isDemoResponse) SoftPosColors.PastelRedContainer else SoftPosColors.SurfaceSubtleGray
            )
        }

        if (extraction.isDemoResponse) {
            Text(
                text = stringResource(R.string.ocr_demo_badge),
                style = MaterialTheme.typography.labelSmall.copy(color = SoftPosColors.RecallCrimsonAccent)
            )
        }

        Text(
            text = stringResource(
                R.string.ocr_meta,
                extraction.supplier ?: "—",
                extraction.invoiceNumber ?: "—"
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.ocr_suggestions_hint),
            style = MaterialTheme.typography.bodyMedium.copy(color = SoftPosColors.TextTertiarySubtle)
        )

        extraction.items.forEach { item ->
            val missingLot = item.lotNumber.isBlank()
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (missingLot) SoftPosColors.PastelRedContainer else SoftPosColors.BackgroundOffWhite,
                border = BorderStroke(1.dp, if (missingLot) SoftPosColors.PastelRedBorder else SoftPosColors.OutlineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.productName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Chip(
                            text = item.lotNumber.ifBlank { "NO LOT" },
                            container = SoftPosColors.SurfacePureWhite
                        )
                        Text(
                            text = String.format(
                                Locale.US, "%.2f %s × %.2f %s = %.2f %s",
                                item.quantity, item.unit, item.unitCost, item.currency,
                                item.lineTotal, item.currency
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (missingLot) {
                        Text(
                            text = stringResource(R.string.ocr_missing_lot),
                            style = MaterialTheme.typography.bodyMedium.copy(color = SoftPosColors.RecallCrimsonAccent)
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.ocr_raw_json),
            style = MaterialTheme.typography.titleMedium
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SoftPosColors.SurfaceSubtleGray,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = extraction.rawJson,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(12.dp)
            )
        }

        PillButton(
            text = stringResource(R.string.ocr_btn_confirm_save),
            enabled = !isSaving && extraction.items.any { it.lotNumber.isNotBlank() },
            onClick = onConfirm
        )
        TextButton(
            onClick = onDiscard,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ocr_btn_discard), color = SoftPosColors.TextSecondaryMuted)
        }
    }
}

@Composable
private fun SoftCard(
    containerColor: Color = SoftPosColors.SurfacePureWhite,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SoftPosDimens.CardCornerRadius),
        color = containerColor,
        border = BorderStroke(SoftPosDimens.CardBorderWidth, SoftPosColors.OutlineSubtle),
        shadowElevation = SoftPosDimens.CardElevationStandard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SoftPosDimens.CardPaddingStandard),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) { content() }
    }
}

@Composable
private fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SoftPosColors.PrimaryIndigo,
            contentColor = SoftPosColors.SurfacePureWhite
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
        modifier = modifier.fillMaxWidth().height(50.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Chip(text: String, container: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun Bitmap.toJpeg(quality: Int): ByteArray =
    ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }

/**
 * Simulated camera capture: renders a realistic supplier invoice so the full OCR
 * pipeline can be demonstrated on an emulator without a physical document.
 */
private fun renderSimulatedInvoice(): Bitmap {
    val width = 1000
    val height = 1240
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)

    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK; textSize = 44f; typeface = Typeface.DEFAULT_BOLD
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(30, 30, 30); textSize = 26f
    }
    val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    val line = Paint().apply { color = android.graphics.Color.rgb(180, 180, 180); strokeWidth = 2f }

    c.drawText("ANADOLU ET KOMBINASI A.S.", 60f, 90f, title)
    c.drawText("Organize Sanayi Bolgesi 4. Cad. No:12, Konya", 60f, 135f, text)
    c.drawText("Vergi No: 0680 412 337", 60f, 170f, text)
    c.drawText("FATURA / INVOICE", 640f, 90f, bold)
    c.drawText("No: FTR-2026-10871", 640f, 135f, text)
    c.drawText("Tarih: 24.10.2026", 640f, 170f, text)
    c.drawText("Alici: BAN Gida Ltd. - Merkez Depo", 60f, 230f, text)
    c.drawLine(60f, 260f, 940f, 260f, line)

    val cols = floatArrayOf(60f, 400f, 560f, 650f, 800f)
    listOf("Urun", "Lot No", "Miktar", "Birim", "Birim Fiyat").forEachIndexed { i, h ->
        c.drawText(h, cols[i], 300f, bold)
    }
    c.drawLine(60f, 320f, 940f, 320f, line)

    val rows = listOf(
        arrayOf("Dana Kiyma Eti (%20 yag)", "ET-2026-014", "50", "KG", "9,80 EUR"),
        arrayOf("Kuzu Karkas", "KUZ-2026-002", "25", "KG", "11,50 EUR"),
        arrayOf("Dogal Sucuk Kilifi", "KLF-2026-031", "200", "ADET", "0,35 EUR")
    )
    var y = 370f
    rows.forEach { r ->
        r.forEachIndexed { i, v -> c.drawText(v, cols[i], y, text) }
        y += 55f
    }
    c.drawLine(60f, y, 940f, y, line)

    c.drawText("Ara Toplam:", 600f, y + 50f, text);  c.drawText("847,50 EUR", 800f, y + 50f, text)
    c.drawText("KDV %10:", 600f, y + 90f, text);     c.drawText("84,75 EUR", 800f, y + 90f, text)
    c.drawText("GENEL TOPLAM:", 600f, y + 140f, bold); c.drawText("932,25 EUR", 800f, y + 140f, bold)

    c.drawText("Soguk zincir: +2 / +4 C. Veteriner saglik sertifikasi ektedir.", 60f, height - 80f, text)
    return bmp
}
