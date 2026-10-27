package com.ban.foodintelligence.pos.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.security.KioskController
import com.ban.foodintelligence.pos.security.LockIncident
import com.ban.foodintelligence.pos.security.SupervisorAuth
import com.ban.foodintelligence.pos.security.TerminalLockStore
import com.ban.foodintelligence.pos.ui.design.Fmt
import com.ban.foodintelligence.pos.ui.design.InsetGroup
import com.ban.foodintelligence.pos.ui.design.ListRow
import com.ban.foodintelligence.pos.ui.design.PosTheme
import com.ban.foodintelligence.pos.ui.design.PosType
import com.ban.foodintelligence.pos.ui.design.PrimaryButton
import com.ban.foodintelligence.pos.ui.design.Shapes
import com.ban.foodintelligence.pos.ui.design.Space
import com.ban.foodintelligence.pos.ui.design.TextAction
import com.ban.foodintelligence.pos.ui.design.palette
import kotlinx.coroutines.launch

/**
 * Full-screen stop shown when an active recall affects lots in this shop (or the device
 * key store is compromised). The state is persisted, so a restart lands here again, and
 * the activity runs in lock-task (kiosk) mode. Only a supervisor PIN, checked against
 * the server (bcrypt + audit) or the offline PBKDF2 hash, releases it.
 */
class RedRecallLockActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RECALL_CODE = "EXTRA_RECALL_CODE"
        const val EXTRA_AFFECTED_LOT = "EXTRA_AFFECTED_LOT"
        const val EXTRA_ROOT_LOT = "EXTRA_ROOT_LOT"
        // Kept for older callers; not shown any more.
        const val EXTRA_OOD_METRIC = "EXTRA_OOD_METRIC"
        const val EXTRA_OOD_VALUE = "EXTRA_OOD_VALUE"

        private const val PREFS_ACK = "pos_recall_ack"
        private const val KEY_ACK = "acknowledged_recalls"

        /** Recalls a supervisor already confirmed on this device (they do not lock again). */
        fun acknowledgedRecalls(context: Context): Set<String> =
            context.getSharedPreferences(PREFS_ACK, Context.MODE_PRIVATE).getStringSet(KEY_ACK, emptySet()).orEmpty()

        private fun acknowledge(context: Context, recallCode: String) {
            val prefs = context.getSharedPreferences(PREFS_ACK, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_ACK, acknowledgedRecalls(context) + recallCode).commit()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stored = TerminalLockStore.readIncident(this)
        val incident = LockIncident(
            recallCode = intent?.getStringExtra(EXTRA_RECALL_CODE) ?: stored.recallCode,
            affectedLot = intent?.getStringExtra(EXTRA_AFFECTED_LOT) ?: stored.affectedLot,
            rootLot = intent?.getStringExtra(EXTRA_ROOT_LOT) ?: stored.rootLot,
            oodMetric = "",
            oodValue = 0f,
            lockedAtEpochMs = if (stored.lockedAtEpochMs > 0 && TerminalLockStore.isLocked(this)) stored.lockedAtEpochMs else System.currentTimeMillis()
        )
        TerminalLockStore.lock(this, incident)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            KioskController.engage(this)
            startLockTask()
        }

        setContent {
            PosTheme {
                LockScreen(incident = incident, onUnlocked = ::releaseLock)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (TerminalLockStore.isLocked(this)) runCatching { startLockTask() }
    }

    private fun releaseLock(incident: LockIncident, supervisor: String, offline: Boolean) {
        if (incident.recallCode.isNotBlank()) acknowledge(this, incident.recallCode)
        TerminalLockStore.unlock(this)
        runCatching {
            KioskController.release(this)
            stopLockTask()
        }
        val msg = if (offline || supervisor.isBlank()) getString(R.string.lock_unlocked_offline)
        else getString(R.string.lock_unlocked_toast, supervisor)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Blocked while the terminal is locked.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)
}

private sealed interface UnlockState {
    data object Idle : UnlockState
    data object Checking : UnlockState
    data class Failed(val result: SupervisorAuth.Result) : UnlockState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockScreen(incident: LockIncident, onUnlocked: (LockIncident, String, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<UnlockState>(UnlockState.Idle) }
    val deviceId = remember {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "POS"
    }

    fun submit() {
        if (pin.length < 4 || state == UnlockState.Checking) return
        state = UnlockState.Checking
        scope.launch {
            val result = SupervisorAuth.verify(context, pin, deviceId, incident.affectedLot, incident.recallCode)
            pin = ""
            if (result is SupervisorAuth.Result.Success) {
                showPin = false
                onUnlocked(incident, result.supervisor, result.offline)
            } else {
                state = UnlockState.Failed(result)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = Space.l),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape).background(palette.danger.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.danger, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(Space.m))
            Text(stringResource(R.string.lockdown_subtitle), style = PosType.subhead, color = palette.dangerText)
            Text(
                stringResource(R.string.lockdown_title),
                style = PosType.largeTitle,
                color = palette.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Space.screen)
            )
            Spacer(Modifier.height(Space.s))
            Text(
                stringResource(R.string.lockdown_body),
                style = PosType.body,
                color = palette.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Space.l)
            )

            InsetGroup(modifier = Modifier.padding(top = Space.l)) {
                if (incident.recallCode.isNotBlank()) {
                    row { ListRow(title = stringResource(R.string.lock_recall), value = incident.recallCode) }
                }
                if (incident.affectedLot.isNotBlank()) {
                    row { ListRow(title = stringResource(R.string.lock_lots), subtitle = incident.affectedLot.replace(",", ", ")) }
                }
                if (incident.rootLot.isNotBlank()) {
                    row { ListRow(title = stringResource(R.string.lock_root), value = incident.rootLot) }
                }
                row { ListRow(title = stringResource(R.string.lock_since), value = Fmt.dateTime(incident.lockedAtEpochMs)) }
            }

            Spacer(Modifier.height(Space.xl))
            PrimaryButton(
                text = stringResource(R.string.lock_unlock_button),
                icon = Icons.Rounded.LockOpen,
                onClick = { state = UnlockState.Idle; showPin = true },
                modifier = Modifier.padding(horizontal = Space.screen)
            )
        }
    }

    if (showPin) {
        ModalBottomSheet(
            onDismissRequest = { if (state != UnlockState.Checking) showPin = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.group,
            shape = Shapes.sheet
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.m)
            ) {
                Text(stringResource(R.string.lock_pin_title), style = PosType.title, color = palette.label)
                Text(stringResource(R.string.lock_pin_message), style = PosType.subhead, color = palette.secondaryLabel)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> if (v.length <= 12 && v.all { it.isDigit() }) pin = v },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.lock_pin_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isError = state is UnlockState.Failed,
                    shape = Shapes.button,
                    textStyle = PosType.title,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.accent, unfocusedBorderColor = palette.separator),
                    modifier = Modifier.fillMaxWidth()
                )
                val failed = state as? UnlockState.Failed
                if (failed != null) {
                    Text(
                        text = when (val r = failed.result) {
                            is SupervisorAuth.Result.WrongPin -> pluralStringResource(R.plurals.lock_pin_wrong, r.attemptsLeft, r.attemptsLeft)
                            is SupervisorAuth.Result.LockedOut -> stringResource(R.string.lock_pin_locked, ((r.remainingMs + 59_999) / 60_000).toInt())
                            SupervisorAuth.Result.OfflineUnavailable -> stringResource(R.string.lock_offline_unavailable)
                            else -> ""
                        },
                        style = PosType.footnote,
                        color = palette.dangerText
                    )
                }
                PrimaryButton(
                    text = stringResource(if (state == UnlockState.Checking) R.string.lock_checking else R.string.lock_pin_confirm),
                    onClick = ::submit,
                    enabled = pin.length >= 4 && state != UnlockState.Checking
                )
                TextAction(
                    text = stringResource(R.string.lock_pin_cancel),
                    onClick = { showPin = false },
                    enabled = state != UnlockState.Checking,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
