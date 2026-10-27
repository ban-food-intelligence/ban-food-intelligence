package com.ban.foodintelligence.pos.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.database.STATUS_REJECTED
import com.ban.foodintelligence.pos.model.SyncOutboxQueue
import com.ban.foodintelligence.pos.ui.design.Fmt
import com.ban.foodintelligence.pos.ui.design.InsetGroup
import com.ban.foodintelligence.pos.ui.design.ListRow
import com.ban.foodintelligence.pos.ui.design.PillKind
import com.ban.foodintelligence.pos.ui.design.PosScreen
import com.ban.foodintelligence.pos.ui.design.StateMessage
import com.ban.foodintelligence.pos.ui.design.StatusPill
import com.ban.foodintelligence.pos.ui.design.palette
import com.ban.foodintelligence.pos.worker.SyncState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Outbox: every local sale with its sync state (pending / sent / rejected + reason). */
@Composable
fun SyncScreen(state: PosAppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lastContact by remember { mutableLongStateOf(SyncState.lastContactMs(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            state.refreshLocal()
            lastContact = SyncState.lastContactMs(context)
            delay(3_000)
        }
    }
    val rejected = state.movements.count { it.syncStatus == STATUS_REJECTED || it.syncStatus == SyncOutboxQueue.STATUS_FAILED }

    PosScreen(title = stringResource(R.string.sync_title), trailing = { SyncPill(state) }) {
        item(key = "status") {
            InsetGroup(footer = stringResource(R.string.sync_footer)) {
                row {
                    ListRow(
                        title = stringResource(R.string.sync_last),
                        value = if (lastContact > 0) Fmt.dateTime(lastContact) else stringResource(R.string.sync_never)
                    )
                }
                row {
                    ListRow(
                        title = stringResource(R.string.sync_send_now),
                        titleColor = palette.accent,
                        onClick = { state.requestSync() }
                    )
                }
                if (rejected > 0) row {
                    ListRow(
                        title = stringResource(R.string.sync_retry_rejected),
                        titleColor = palette.accent,
                        onClick = {
                            scope.launch {
                                state.appDb.retryRejected()
                                state.refreshLocal()
                                state.requestSync()
                            }
                        }
                    )
                }
            }
        }
        if (state.movements.isEmpty()) {
            item(key = "empty") { StateMessage(Icons.Rounded.Receipt, stringResource(R.string.sync_empty)) }
        } else item(key = "queue") {
            InsetGroup(header = stringResource(R.string.sync_queue_header)) {
                state.movements.forEach { m ->
                    row {
                        val (label, kind) = when (m.syncStatus) {
                            SyncOutboxQueue.STATUS_SYNCED -> stringResource(R.string.sync_state_synced) to PillKind.Ok
                            STATUS_REJECTED -> stringResource(R.string.sync_state_rejected) to PillKind.Danger
                            SyncOutboxQueue.STATUS_FAILED -> stringResource(R.string.sync_state_failed) to PillKind.Danger
                            else -> stringResource(R.string.sync_state_pending) to PillKind.Neutral
                        }
                        ListRow(
                            title = "${m.productName} · ${Fmt.qty(abs(m.qtyDelta), m.uom)}",
                            subtitle = listOfNotNull(
                                "${m.lotNo} · ${Fmt.money(m.totalEur)} · ${Fmt.dateTime(m.createdAtMs)}",
                                m.syncReason
                            ).joinToString("\n"),
                            accentBar = if (kind == PillKind.Danger) palette.danger else null
                        ) {
                            StatusPill(label, kind)
                        }
                    }
                }
            }
        }
    }
}
