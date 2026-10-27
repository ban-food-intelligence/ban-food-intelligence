package com.ban.foodintelligence.pos.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.catalog.CatalogSource
import com.ban.foodintelligence.pos.security.SupervisorAuth
import com.ban.foodintelligence.pos.ui.design.Fmt
import com.ban.foodintelligence.pos.ui.design.InsetGroup
import com.ban.foodintelligence.pos.ui.design.ListRow
import com.ban.foodintelligence.pos.ui.design.PosScreen
import com.ban.foodintelligence.pos.ui.design.SkeletonGroup
import com.ban.foodintelligence.pos.ui.design.StateMessage
import com.ban.foodintelligence.pos.ui.design.palette
import kotlinx.coroutines.launch

/**
 * Active recalls from GET /recall (cached for offline use) and the lots in this shop they
 * block. Everything here comes from recorded lot relations in MySQL, never from AI.
 */
@Composable
fun RecallScreen(state: PosAppState, trailing: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = state.catalog
    val affected = state.lots.filter { it.isBlocked }
    val unlocks = remember(state.catalog) { SupervisorAuth.auditLog(context) }

    PosScreen(title = stringResource(R.string.recall_title), trailing = { trailing() }) {
        catalogBanner(state)
        when {
            catalog == null -> item { SkeletonGroup(2) }
            catalog.source == CatalogSource.NONE -> item { ServerErrorState(catalog.error) { scope.launch { state.refreshAll() } } }
            catalog.recalls.isEmpty() -> item {
                StateMessage(
                    icon = Icons.Rounded.VerifiedUser,
                    message = stringResource(R.string.recall_none) + "\n" + stringResource(R.string.recall_none_sub)
                )
            }
            else -> {
                if (affected.isNotEmpty()) item(key = "banner") {
                    InsetGroup {
                        row {
                            ListRow(
                                title = pluralStringResource(R.plurals.recall_banner, affected.size, affected.size),
                                icon = Icons.Rounded.ReportProblem,
                                iconTint = palette.danger,
                                accentBar = palette.danger
                            )
                        }
                    }
                }
                item(key = "active") {
                    InsetGroup(header = stringResource(R.string.recall_active_header)) {
                        catalog.recalls.forEach { rc ->
                            row {
                                ListRow(
                                    title = rc.recallCode,
                                    subtitle = listOf(stringResource(R.string.recall_source_lot, rc.triggerLot), rc.reason)
                                        .filter { it.isNotBlank() }.joinToString("\n"),
                                    value = expiryLabel(rc.initiatedAt.take(10))
                                )
                            }
                        }
                    }
                }
                if (affected.isNotEmpty()) item(key = "local") {
                    InsetGroup(header = stringResource(R.string.recall_local_header)) {
                        affected.forEach { lot ->
                            row {
                                ListRow(
                                    title = lot.productName,
                                    subtitle = lot.lotNo,
                                    value = Fmt.qty(lot.availableQty, lot.uom),
                                    accentBar = palette.danger
                                )
                            }
                        }
                    }
                }
            }
        }
        if (unlocks.isNotEmpty()) item(key = "unlocks") {
            InsetGroup(header = stringResource(R.string.recall_unlock_history)) {
                unlocks.take(10).forEach { e ->
                    row(dividerInset = 56.dp) {
                        ListRow(
                            title = e.supervisor.ifBlank { stringResource(R.string.recall_unlock_offline) },
                            subtitle = listOf(e.recallCode, e.lotNo).filter { it.isNotBlank() }.joinToString(" · "),
                            icon = Icons.Rounded.LockOpen,
                            iconTint = palette.secondaryLabel,
                            value = Fmt.dateTime(e.atMs)
                        )
                    }
                }
            }
        }
    }
}
