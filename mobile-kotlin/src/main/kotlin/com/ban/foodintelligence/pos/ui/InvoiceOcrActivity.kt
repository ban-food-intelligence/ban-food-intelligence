package com.ban.foodintelligence.pos.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.net.ApiError
import com.ban.foodintelligence.pos.ocr.DraftLine
import com.ban.foodintelligence.pos.ocr.DraftProblem
import com.ban.foodintelligence.pos.ocr.InvoiceSuggestion
import com.ban.foodintelligence.pos.ocr.ReceiptDraft
import com.ban.foodintelligence.pos.ocr.ServerInvoiceClient
import com.ban.foodintelligence.pos.security.TerminalLockStore
import com.ban.foodintelligence.pos.ui.design.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** UI state for the invoice flow. */
private sealed interface OcrUiState {
    data object Idle : OcrUiState
    data object Loading : OcrUiState
    data class Review(val draft: ReceiptDraft, val suggestion: InvoiceSuggestion) : OcrUiState
    data class Error(val message: String) : OcrUiState
    data class Saved(val receipt: ServerInvoiceClient.SavedReceipt) : OcrUiState
}

/**
 * ============================================================================
 * BAN Food Intelligence - Receive goods from an invoice (`InvoiceOcrActivity`)
 * ============================================================================
 *
 * Photo -> POST /purchases/invoice (the API holds the vision key; the answer is a suggestion
 * matched to our suppliers and products, nothing is written) -> the supervisor checks every line
 * and gives our own lot numbers -> POST /purchases with the supervisor PIN, exactly as the
 * dashboard's receipt form. The receipt is a purchase on the server (lots, supplier, expiry,
 * PURCHASE_IN movements); the phone never books stock on its own.
 */
class InvoiceOcrActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                InvoiceOcrScreen(
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

private fun newReceiptNo(): String = "ALS-POS-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

@Composable
private fun InvoiceOcrScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var capturedInvoice by remember { mutableStateOf<Bitmap?>(null) }
    var state by remember { mutableStateOf<OcrUiState>(OcrUiState.Idle) }
    var pin by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveAttempted by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<ApiError?>(null) }
    var status by remember { mutableStateOf<ServerInvoiceClient.Status?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            status = ServerInvoiceClient.status(context)
        } catch (e: ApiError) {
            statusError = e.message
        } catch (e: RuntimeException) {
            statusError = e.message
        }
    }
    val canRead = status?.configured == true
    val unavailableMsg = stringResource(R.string.ocr_unavailable)

    fun runExtraction(bitmap: Bitmap) {
        if (!canRead || pin.length < 4) return
        state = OcrUiState.Loading
        saveAttempted = false
        saveError = null
        scope.launch {
            state = try {
                val s = ServerInvoiceClient.read(context, pin, bitmap)
                OcrUiState.Review(ReceiptDraft.from(s, LocalDate.now(), newReceiptNo()), s)
            } catch (e: ApiError) {
                OcrUiState.Error(e.message)
            } catch (e: RuntimeException) {
                OcrUiState.Error(e.message ?: unavailableMsg)
            }
        }
    }

    fun save(draft: ReceiptDraft) {
        saveAttempted = true
        saveError = null
        if (draft.problems().isNotEmpty()) return
        isSaving = true
        scope.launch {
            try {
                state = OcrUiState.Saved(ServerInvoiceClient.save(context, pin, draft))
            } catch (e: ApiError) {
                saveError = e
            } catch (e: RuntimeException) {
                saveError = ApiError(0, "UNEXPECTED", e.message ?: unavailableMsg)
            } finally {
                isSaving = false
            }
        }
    }

    val cameraUnavailableMsg = stringResource(R.string.ocr_camera_unavailable)
    val captureFailedMsg = stringResource(R.string.ocr_capture_failed)

    // Real camera capture: the system camera app writes a full-size JPEG into our cache via FileProvider.
    val photoFile = remember { File(File(context.cacheDir, "invoices").apply { mkdirs() }, "invoice.jpg") }
    val photoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (!saved) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeInvoicePhoto(photoFile) }
            if (bitmap == null) {
                snackbarHostState.showSnackbar(captureFailedMsg)
            } else {
                capturedInvoice = bitmap
                runExtraction(bitmap)
            }
        }
    }

    fun useSampleInvoice() {
        val sample = renderSimulatedInvoice()
        capturedInvoice = sample
        runExtraction(sample)
    }

    fun openCamera() {
        try {
            cameraLauncher.launch(photoUri)
        } catch (e: ActivityNotFoundException) {
            // No camera app on this device/emulator image: fall back to the labelled sample invoice.
            scope.launch { snackbarHostState.showSnackbar(cameraUnavailableMsg) }
            useSampleInvoice()
        }
    }

    Scaffold(
        containerColor = SoftPosColors.BackgroundOffWhite,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
      Box(modifier = Modifier.fillMaxSize()) {
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
                TextButton(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(contentColor = SoftPosColors.PrimaryIndigo)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ocr_back))
                }
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

            // The server decides whether invoices can be read (it holds the key).
            when {
                statusError != null -> SoftCard(containerColor = SoftPosColors.PastelRedContainer) {
                    Text(stringResource(R.string.error_server_unreachable), style = MaterialTheme.typography.titleMedium)
                    Text(statusError.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
                status?.configured == false -> SoftCard(containerColor = SoftPosColors.PastelRedContainer) {
                    Text(stringResource(R.string.ocr_not_configured_server), style = MaterialTheme.typography.bodyMedium)
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

                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text(stringResource(R.string.ocr_pin_label)) },
                    supportingText = { Text(stringResource(R.string.ocr_pin_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                PosPrimaryButton(
                    text = stringResource(
                        if (capturedInvoice == null) R.string.ocr_btn_take_photo else R.string.ocr_btn_recapture
                    ),
                    icon = Icons.Rounded.CameraAlt,
                    enabled = canRead && state !is OcrUiState.Loading,
                    onClick = { openCamera() }
                )
                PosSecondaryButton(
                    text = stringResource(R.string.ocr_btn_sample),
                    icon = Icons.Rounded.Description,
                    enabled = canRead && state !is OcrUiState.Loading,
                    onClick = { useSampleInvoice() },
                    modifier = Modifier.fillMaxWidth()
                )
                if (capturedInvoice != null) {
                    PosSecondaryButton(
                        text = stringResource(R.string.ocr_btn_extract),
                        icon = Icons.Rounded.DocumentScanner,
                        enabled = canRead && pin.length >= 4 && state !is OcrUiState.Loading,
                        onClick = { capturedInvoice?.let(::runExtraction) },
                        modifier = Modifier.fillMaxWidth()
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

                    OcrUiState.Loading -> OcrShimmerLoadingCard(modelId = status?.model.orEmpty())

                    is OcrUiState.Error -> SoftCard(containerColor = SoftPosColors.PastelRedContainer) {
                        Text(
                            text = stringResource(R.string.ocr_error_title),
                            style = MaterialTheme.typography.titleLarge.copy(color = SoftPosColors.RecallCrimsonDark)
                        )
                        Text(text = s.message, style = MaterialTheme.typography.bodyMedium)
                    }

                    is OcrUiState.Review -> ReviewCard(
                        draft = s.draft,
                        suggestion = s.suggestion,
                        localProblems = if (saveAttempted) s.draft.problems() else emptyMap(),
                        serverError = saveError,
                        isSaving = isSaving,
                        canSave = pin.length >= 4,
                        onChange = { state = OcrUiState.Review(it, s.suggestion) },
                        onSave = { save(s.draft) },
                        onDiscard = { state = OcrUiState.Idle }
                    )

                    is OcrUiState.Saved -> SoftCard(containerColor = SoftPosColors.EmeraldVerifiedBg) {
                        Text(
                            text = stringResource(R.string.ocr_saved_receipt, s.receipt.receiptNo, s.receipt.lines, Fmt.money(s.receipt.total)),
                            style = MaterialTheme.typography.titleMedium.copy(color = SoftPosColors.EmeraldVerifiedText)
                        )
                        PosPrimaryButton(
                            text = stringResource(R.string.ocr_back),
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            onClick = onBack
                        )
                    }
                }
            }
        }

        if (state is OcrUiState.Loading) {
            OcrProcessingOverlay(modelId = status?.model.orEmpty())
        }
      }
    }
}

/** Full-screen scrim with a spinner while the API reads the invoice; blocks touches. */
@Composable
private fun OcrProcessingOverlay(modelId: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(SoftPosDimens.CardCornerRadius),
            color = SoftPosColors.SurfacePureWhite,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(32.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(color = SoftPosColors.PrimaryIndigo, strokeWidth = 4.dp)
                Text(
                    text = stringResource(R.string.ocr_processing_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.ocr_processing_body, modelId),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Decodes the camera JPEG at most ~1600 px on the long side, upright per its EXIF orientation. */
private fun decodeInvoicePhoto(file: File): Bitmap? {
    if (!file.exists() || file.length() == 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Standard Material 3 Shimmer skeleton loading effect displayed while waiting
 * for the invoice reading answer.
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
private fun matchLabel(match: String): String = when (match) {
    "EXACT" -> stringResource(R.string.ocr_match_exact)
    "PARTIAL" -> stringResource(R.string.ocr_match_partial)
    else -> stringResource(R.string.ocr_match_none)
}

@Composable
private fun warningLabel(code: String): String = when (code) {
    "NO_PRODUCT_MATCH" -> stringResource(R.string.ocr_warn_no_product)
    "UNIT_DIFFERS" -> stringResource(R.string.ocr_warn_unit)
    "QTY_MISSING" -> stringResource(R.string.ocr_warn_qty)
    "PRICE_MISSING" -> stringResource(R.string.ocr_warn_price)
    "NOT_EUR" -> stringResource(R.string.ocr_warn_currency)
    "EXPIRY_MISSING" -> stringResource(R.string.ocr_warn_expiry)
    else -> code
}

@Composable
private fun problemLabel(p: DraftProblem): String = when (p) {
    DraftProblem.REQUIRED -> stringResource(R.string.ocr_field_required)
    DraftProblem.NOT_A_NUMBER -> stringResource(R.string.ocr_field_number)
    DraftProblem.NOT_A_DATE -> stringResource(R.string.ocr_field_date_format)
}

@Composable
private fun DraftField(
    label: String,
    value: String,
    error: String?,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * The read invoice as an editable receipt. Nothing is saved yet: the supervisor compares every
 * field with the paper, gives our own lot numbers and saves with the PIN. Problems come from the
 * same rules as the API (shown after the first save attempt) and from the API's 422 field details.
 */
@Composable
private fun ReviewCard(
    draft: ReceiptDraft,
    suggestion: InvoiceSuggestion,
    localProblems: Map<String, DraftProblem>,
    serverError: ApiError?,
    isSaving: Boolean,
    canSave: Boolean,
    onChange: (ReceiptDraft) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    val serverFields = serverError?.fieldErrors.orEmpty()
    @Composable
    fun err(key: String): String? = serverFields[key] ?: localProblems[key]?.let { problemLabel(it) }
    fun line(i: Int, change: (DraftLine) -> DraftLine) =
        onChange(draft.copy(lines = draft.lines.mapIndexed { j, l -> if (j == i) change(l) else l }))

    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.ocr_suggestions_title), style = MaterialTheme.typography.titleLarge)
            Chip(text = "${suggestion.latencyMs ?: 0} ms", container = SoftPosColors.SurfaceSubtleGray)
        }
        Text(
            text = stringResource(R.string.ocr_meta, suggestion.supplierName ?: "—", suggestion.invoiceNo ?: "—"),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.ocr_suggestions_hint),
            style = MaterialTheme.typography.bodyMedium.copy(color = SoftPosColors.TextTertiarySubtle)
        )

        DraftField(stringResource(R.string.ocr_field_receipt_no), draft.receiptNo, err("receipt_no"), { onChange(draft.copy(receiptNo = it)) })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DraftField(
                stringResource(R.string.ocr_field_supplier), draft.supplierCode, err("supplier_code"),
                { onChange(draft.copy(supplierCode = it)) }, Modifier.weight(1f)
            )
            Chip(text = matchLabel(draft.supplierMatch), container = SoftPosColors.SurfaceSubtleGray)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DraftField(stringResource(R.string.ocr_field_invoice_no), draft.invoiceNo, err("invoice_no"), { onChange(draft.copy(invoiceNo = it)) }, Modifier.weight(1f))
            DraftField(stringResource(R.string.ocr_field_date), draft.receiptDate, err("receipt_date"), { onChange(draft.copy(receiptDate = it)) }, Modifier.weight(1f))
        }

        draft.lines.forEachIndexed { i, l ->
            val k = "lines[$i]."
            val doubtful = l.match != "EXACT" || l.warnings.isNotEmpty()
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (doubtful) SoftPosColors.PastelRedContainer else SoftPosColors.BackgroundOffWhite,
                border = BorderStroke(1.dp, if (doubtful) SoftPosColors.PastelRedBorder else SoftPosColors.OutlineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = l.printedName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f)
                        )
                        Chip(text = matchLabel(l.match), container = SoftPosColors.SurfacePureWhite)
                    }
                    l.warnings.forEach { w ->
                        Text(warningLabel(w), style = MaterialTheme.typography.bodyMedium.copy(color = SoftPosColors.RecallCrimsonAccent))
                    }
                    DraftField(stringResource(R.string.ocr_field_sku), l.sku, err(k + "sku"), { v -> line(i) { it.copy(sku = v) } })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DraftField(stringResource(R.string.ocr_field_lot), l.lotNo, err(k + "lot_no"), { v -> line(i) { it.copy(lotNo = v) } }, Modifier.weight(1f))
                        DraftField(stringResource(R.string.ocr_field_supplier_lot), l.supplierLotNo, err(k + "supplier_lot_no"), { v -> line(i) { it.copy(supplierLotNo = v) } }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DraftField(
                            stringResource(R.string.ocr_field_qty, l.unit.ifBlank { "—" }), l.qty, err(k + "qty"),
                            { v -> line(i) { it.copy(qty = v) } }, Modifier.weight(1f), KeyboardType.Decimal
                        )
                        DraftField(
                            stringResource(R.string.ocr_field_price), l.unitPrice, err(k + "unit_price"),
                            { v -> line(i) { it.copy(unitPrice = v) } }, Modifier.weight(1f), KeyboardType.Decimal
                        )
                    }
                    DraftField(stringResource(R.string.ocr_field_expiry), l.expiryDate, err(k + "expiry_date"), { v -> line(i) { it.copy(expiryDate = v) } })
                    TextButton(onClick = { onChange(draft.copy(lines = draft.lines.filterIndexed { j, _ -> j != i })) }) {
                        Text(stringResource(R.string.ocr_remove_line), color = SoftPosColors.RecallCrimsonAccent)
                    }
                }
            }
        }

        (serverFields["lines"] ?: localProblems["lines"]?.let { problemLabel(it) })?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = SoftPosColors.RecallCrimsonAccent))
        }
        if (serverError != null && serverError.fieldErrors.isEmpty()) {
            Text(serverError.message, style = MaterialTheme.typography.bodyMedium.copy(color = SoftPosColors.RecallCrimsonAccent))
        }
        PosPrimaryButton(
            text = stringResource(R.string.ocr_btn_confirm_save),
            icon = Icons.Rounded.Inventory,
            enabled = !isSaving && canSave && draft.lines.isNotEmpty(),
            onClick = onSave
        )
        PosSecondaryButton(
            text = stringResource(R.string.ocr_btn_discard),
            icon = Icons.Rounded.DeleteOutline,
            enabled = !isSaving,
            onClick = onDiscard,
            contentColor = SoftPosColors.RecallCrimsonAccent,
            modifier = Modifier.fillMaxWidth()
        )
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
private fun Chip(text: String, container: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
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
        color = android.graphics.Color.rgb(30, 30, 30); textSize = 24f
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

    val cols = floatArrayOf(60f, 380f, 600f, 700f, 800f)
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
