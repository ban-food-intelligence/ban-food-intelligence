package com.ban.foodintelligence.pos.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.ban.foodintelligence.pos.ui.design.PillKind
import com.ban.foodintelligence.pos.ui.design.StatusPill
import com.ban.foodintelligence.pos.ui.design.palette
import com.ban.foodintelligence.pos.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything the tabs share: lots + recalls from the API, local sales and the outbox. */
@Stable
class PosAppState(private val context: Context, val appDb: AppDatabase) {
    var catalog by mutableStateOf<PosCatalog?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var pendingSales by mutableStateOf<Map<String, Double>>(emptyMap())
        private set
    var movements by mutableStateOf<List<StockMovementRecord>>(emptyList())
        private set
    var pendingCount by mutableStateOf(0)
        private set

    /** Store lots with local unsynced sales already subtracted from the server stock. */
    val lots: List<PosInventoryLot>
        get() = catalog?.lots.orEmpty().map { lot ->
            lot.copy(availableQty = (lot.availableQty - (pendingSales[lot.lotNo] ?: 0.0)).coerceAtLeast(0.0))
        }

    suspend fun refreshLocal() {
        movements = appDb.getRecentStockMovements(50)
        pendingCount = runCatching { appDb.outboxDao().countPending() }.getOrDefault(0)
        pendingSales = runCatching { appDb.pendingSaleQtyByLot() }.getOrDefault(emptyMap())
    }

    suspend fun refreshCatalog() {
        isLoading = true
        catalog = PosCatalogRepository.load(context)
        isLoading = false
    }

    suspend fun refreshAll() {
        refreshLocal()
        refreshCatalog()
    }

    val isLive: Boolean get() = catalog?.source == CatalogSource.LIVE

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Send the outbox now (also replaces a job waiting in backoff). */
    fun requestSync() {
        io.launch { runCatching { SyncWorker.syncNow(context) } }
    }
}

private enum class Tab(val labelRes: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    Sell(R.string.tab_sell, Icons.Rounded.ShoppingCart, Icons.Outlined.ShoppingCart),
    Lots(R.string.tab_lots, Icons.Rounded.Inventory2, Icons.Outlined.Inventory2),
    Recall(R.string.tab_recall, Icons.Rounded.ReportProblem, Icons.Outlined.ReportProblem),
    Sync(R.string.tab_sync, Icons.Rounded.Sync, Icons.Outlined.Sync),
    Settings(R.string.tab_settings, Icons.Rounded.Settings, Icons.Outlined.Settings)
}

@Composable
fun PosApp(appDb: AppDatabase, keyFingerprint: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { PosAppState(context.applicationContext, appDb) }
    var tab by rememberSaveable { mutableStateOf(Tab.Sell) }

    // Load at start, then every 15 s while the app is open (new recalls, stock, sync status).
    // While offline or with unsent sales it checks every 5 s, and sends the outbox as soon as
    // the API answers again, so the pill returns to "Synced" without restarting the app.
    LaunchedEffect(Unit) {
        while (true) {
            state.refreshAll()
            if (state.isLive && state.pendingCount > 0) state.requestSync()
            delay(if (!state.isLive || state.pendingCount > 0) 5_000 else 15_000)
        }
    }

    // A new active recall that reaches lots in this shop stops sales once, until a supervisor
    // confirms the shelf was cleared (RedRecallLockActivity). Works from the offline cache too.
    LaunchedEffect(state.catalog) {
        val catalog = state.catalog ?: return@LaunchedEffect
        val acknowledged = RedRecallLockActivity.acknowledgedRecalls(context)
        catalog.recalls.firstOrNull { rc ->
            rc.recallCode !in acknowledged && catalog.lots.any { it.recallCode == rc.recallCode }
        }?.let { rc ->
            val localLots = catalog.lots.filter { it.recallCode == rc.recallCode }.joinToString(",") { it.lotNo }
            context.startActivity(
                Intent(context, RedRecallLockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(RedRecallLockActivity.EXTRA_RECALL_CODE, rc.recallCode)
                    .putExtra(RedRecallLockActivity.EXTRA_AFFECTED_LOT, localLots)
                    .putExtra(RedRecallLockActivity.EXTRA_ROOT_LOT, rc.triggerLot)
            )
        }
    }

    val syncPill: @Composable () -> Unit = { SyncPill(state) }

    Scaffold(
        containerColor = palette.background,
        bottomBar = {
            androidx.compose.foundation.layout.Column {
                HorizontalDivider(thickness = 0.5.dp, color = palette.separator)
                NavigationBar(containerColor = palette.group.copy(alpha = 0.97f), tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        val selected = t == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { tab = t },
                            icon = { Icon(if (selected) t.selectedIcon else t.icon, contentDescription = null) },
                            label = {
                                com.ban.foodintelligence.pos.ui.design.FitText(
                                    stringResource(t.labelRes), fontSize = 11.sp,
                                    color = if (selected) palette.accent else palette.secondaryLabel
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = palette.accent,
                                selectedTextColor = palette.accent,
                                indicatorColor = palette.group.copy(alpha = 0f),
                                unselectedIconColor = palette.secondaryLabel,
                                unselectedTextColor = palette.secondaryLabel
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            when (tab) {
                Tab.Sell -> SellScreen(state = state, trailing = syncPill, onOpenRecalls = { tab = Tab.Recall })
                Tab.Lots -> LotsScreen(state = state, trailing = syncPill)
                Tab.Recall -> RecallScreen(state = state, trailing = syncPill)
                Tab.Sync -> SyncScreen(state = state)
                Tab.Settings -> SettingsScreen(
                    keyFingerprint = keyFingerprint,
                    onServerChanged = { scope.launch { state.refreshAll() } }
                )
            }
        }
    }
}

/** Header pill: Synced / N pending / Offline. */
@Composable
fun SyncPill(state: PosAppState) {
    val catalog = state.catalog
    when {
        catalog == null -> Unit
        catalog.source != CatalogSource.LIVE ->
            StatusPill(stringResource(R.string.status_offline), PillKind.Warning, Icons.Rounded.CloudOff)
        state.pendingCount > 0 ->
            StatusPill(stringResource(R.string.status_pending, state.pendingCount), PillKind.Neutral, Icons.Rounded.CloudUpload)
        else ->
            StatusPill(stringResource(R.string.status_synced), PillKind.Ok, Icons.Rounded.CloudDone)
    }
}
