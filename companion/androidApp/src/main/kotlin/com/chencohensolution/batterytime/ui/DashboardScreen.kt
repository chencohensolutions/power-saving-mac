package com.chencohensolution.batterytime.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.batterytime.ActiveMode
import com.chencohensolution.batterytime.Command
import com.chencohensolution.batterytime.CompanionClient
import com.chencohensolution.batterytime.Connection
import com.chencohensolution.batterytime.DashboardController
import com.chencohensolution.batterytime.DashboardState
import com.chencohensolution.batterytime.Format
import com.chencohensolution.batterytime.LimitAction
import com.chencohensolution.batterytime.MacState
import com.chencohensolution.batterytime.Pairing
import com.chencohensolution.batterytime.Preset
import kotlinx.coroutines.delay

/**
 * The phone's screen for the Mac: everything in BatteryTime's menu, which can't be reached while the lid is closed.
 * Polls while visible (LifecycleStartEffect) and stops in the background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    controller: DashboardController,
    client: CompanionClient,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    onUnpair: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(controller) {
        controller.start()
        onStopOrDispose { controller.stop() }
    }
    // The clock for "lid closed for…" and "offline for…"
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); controller.clearError() }
    }
    var ruleFor by remember { mutableStateOf<String?>(null) }
    var addingRule by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) { if (refreshing) { delay(800); refreshing = false } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.mac?.mac?.name ?: state.pairing.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ConnectionLine(state, now)
                    }
                },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Keep phone screen on") },
                            leadingIcon = { Icon(Icons.Outlined.LightMode, null) },
                            trailingIcon = { if (keepScreenOn) Icon(Icons.Outlined.Check, null) },
                            onClick = { onKeepScreenOn(!keepScreenOn); menu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Unpair this Mac") },
                            leadingIcon = { Icon(Icons.Outlined.LinkOff, null) },
                            onClick = { confirmUnpair = true; menu = false },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; controller.refreshNow() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val mac = state.mac
            if (mac == null) {
                FirstConnect(state)
            } else {
                Dashboard(
                    mac = mac,
                    state = state,
                    now = now,
                    client = client,
                    send = controller::send,
                    onApp = { ruleFor = it },
                    onAddRule = { addingRule = true },
                )
            }
        }
    }

    val mac = state.mac
    if (ruleFor != null && mac != null) {
        val name = ruleFor!!
        RuleSheet(
            app = name,
            rule = LimitAction.from(mac.rules[name]),
            client = client,
            pairing = state.pairing,
            onPick = { controller.send(Command.SetAppRule(name, it)); ruleFor = null },
            onDismiss = { ruleFor = null },
        )
    }
    if (addingRule && mac != null) {
        AddRuleSheet(
            apps = mac.runningApps.filter { it !in mac.rules },
            client = client,
            pairing = state.pairing,
            onPick = { addingRule = false; ruleFor = it },
            onDismiss = { addingRule = false },
        )
    }
    if (confirmUnpair || state.connection == Connection.WrongCode) {
        val wrong = state.connection == Connection.WrongCode
        AlertDialog(
            onDismissRequest = { if (!wrong) confirmUnpair = false },
            title = { Text(if (wrong) "Pair again" else "Unpair ${state.pairing.name}?") },
            text = {
                Text(
                    if (wrong) "The Mac's code changed (New Code in Phone Companion). Scan its code again to keep using this screen."
                    else "This phone stops seeing and controlling the Mac until you scan its code again."
                )
            },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; onUnpair() }) { Text(if (wrong) "Pair Again" else "Unpair") } },
            dismissButton = { if (!wrong) TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectionLine(state: DashboardState, now: Long) {
    val (color, text) = when (val c = state.connection) {
        Connection.Connecting -> MaterialTheme.colorScheme.outline to "Connecting…"
        Connection.Connected -> BatteryGreen to (state.mac?.let { Format.lid(it.lid, now) } ?: "Connected")
        is Connection.Offline -> BatteryAmber to "Not reachable for ${Format.duration(now - c.sinceMs)}"
        Connection.WrongCode -> BatteryRed to "Pair again"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FirstConnect(state: DashboardState) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.connection is Connection.Offline) {
            Text("Can't reach ${state.pairing.name}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Text(
                "Check the Mac is awake (Stay On With Lid Closed), Phone Companion is on, and your phone is on the same Wi-Fi. Still trying…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun Dashboard(
    mac: MacState,
    state: DashboardState,
    now: Long,
    client: CompanionClient,
    send: (Command) -> Unit,
    onApp: (String) -> Unit,
    onAddRule: () -> Unit,
) {
    val offline = state.connection !is Connection.Connected
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (offline && state.connection is Connection.Offline) {
            item {
                Banner(
                    "Last seen ${Format.duration(now - (state.connection as Connection.Offline).sinceMs)} ago. " +
                        "The Mac may be asleep or on another Wi-Fi. Showing the last state."
                )
            }
        }
        item { BatteryCard(mac) }
        item { LidCard(mac, state.pending, send) }
        item { SaverCard(mac, state.pending, now, send) }
        item {
            SectionTitle("Using Significant Energy")
        }
        if (mac.apps.isEmpty()) {
            item {
                Text(
                    "No apps using significant energy",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        items(mac.apps, key = { it.name }) { app ->
            AppRowItem(app, mac.saver.active, client, state.pairing) { onApp(app.name) }
        }
        if (mac.saver.otherAppsOnECores > 0 && mac.saver.active) {
            item {
                Text(
                    "+ ${mac.saver.otherAppsOnECores} other apps on efficiency cores",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item {
            TextButton(onClick = onAddRule) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("App Rules (${mac.rules.size})")
            }
        }
        mac.lastRecap?.let { recap ->
            item {
                Text(
                    "Last lid close: $recap",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp).navigationBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = BatteryAmber.copy(alpha = 0.18f))) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun BatteryCard(mac: MacState) {
    val b = mac.battery
    val color = batteryColor(b.percent, b.charging)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { b.percent / 100f },
                    modifier = Modifier.size(96.dp),
                    color = color,
                    trackColor = color.copy(alpha = 0.18f),
                    strokeWidth = 9.dp,
                    strokeCap = StrokeCap.Round,
                )
                Text("${b.percent}%", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text(Format.batteryHeadline(b), style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Power source: ${b.powerSource.ifEmpty { "Battery" }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mac.lowPowerMode) {
                    Text("Low Power Mode on", color = BatteryAmber, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LidCard(mac: MacState, pending: Set<String>, send: (Command) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SwitchRow(
                "Stay On With Lid Closed",
                "Keeps the Mac running with the lid shut",
                mac.stayAwake,
                busy = "setStayAwake" in pending,
            ) { send(Command.SetStayAwake(it)) }
            SwitchRow("Low Power Mode", null, mac.lowPowerMode, busy = "setLowPowerMode" in pending) {
                send(Command.SetLowPowerMode(it))
            }
            SwitchRow(
                "Low Power Mode When Lid Closed",
                null,
                mac.lowPowerWhenLidClosed,
                busy = "setLowPowerWhenLidClosed" in pending,
            ) { send(Command.SetLowPowerWhenLidClosed(it)) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, on: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Switch(checked = on, onCheckedChange = onChange)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!on) },
    )
}

@Composable
private fun SaverCard(mac: MacState, pending: Set<String>, now: Long, send: (Command) -> Unit) {
    val s = mac.saver
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Battery Saver", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(mac.preset.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The status box, as in the Mac menu
            val accent = s.active
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    s.status.ifEmpty { "Battery Saver" },
                    fontWeight = FontWeight.SemiBold,
                    color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (s.statusDetail.isNotEmpty()) {
                    Text(s.statusDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text("Level", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Preset.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = mac.preset == p,
                        onClick = { send(Command.SetPreset(p)) },
                        shape = SegmentedButtonDefaults.itemShape(i, Preset.entries.size),
                    ) { Text(p.title, maxLines = 1) }
                }
            }
            Text(mac.preset.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Active", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ActiveMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mac.activeMode == m,
                        onClick = { send(Command.SetActiveMode(m)) },
                        shape = SegmentedButtonDefaults.itemShape(i, ActiveMode.entries.size),
                    ) { Text(if (m == ActiveMode.LidClosed) "Lid closed" else m.title, maxLines = 1) }
                }
            }

            val pausedUntil = s.pausedUntil?.takeIf { it > now }
            val canPause = mac.activeMode != ActiveMode.Never && !(mac.preset == Preset.Off && mac.rules.isEmpty())
            if (pausedUntil != null) {
                Button(onClick = { send(Command.ResumeLimits) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Resume Limits Now (${Format.duration(pausedUntil - now)} left)")
                }
            } else if (canPause) {
                FilledTonalButton(
                    onClick = { send(Command.PauseLimits(60)) },
                    enabled = "pauseLimits" !in pending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Pause, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pause Limits for 1 Hour")
                }
            }
        }
    }
}

@Composable
private fun AppRowItem(app: MacState.AppRow, saverActive: Boolean, client: CompanionClient, pairing: Pairing, onClick: () -> Unit) {
    val tag = Format.appTag(app, saverActive)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            leadingContent = { AppIcon(app.name, client, pairing) },
            headlineContent = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val parts = listOfNotNull(
                    app.power.takeIf { it > 0 }?.let { "Energy ${it.toInt()}" },
                    if (app.rule != null) "your rule" else app.reason?.takeIf { saverActive && app.limitedNow },
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
            },
            trailingContent = {
                if (tag.isNotEmpty()) {
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (app.limitedNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** What to do with one app: follow Battery Saver, or a rule */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleSheet(
    app: String,
    rule: LimitAction?,
    client: CompanionClient,
    pairing: Pairing,
    onPick: (LimitAction?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, client, pairing, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(app, style = MaterialTheme.typography.titleLarge)
            }
            ChoiceRow("Follow Battery Saver", rule == null) { onPick(null) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            for (a in listOf(LimitAction.ECores, LimitAction.P50, LimitAction.P25, LimitAction.Pause, LimitAction.None)) {
                ChoiceRow(a.title, rule == a) { onPick(a) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRuleSheet(
    apps: List<String>,
    client: CompanionClient,
    pairing: Pairing,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Add a Rule For", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (apps.isEmpty()) {
            Text("Every running app has a rule.", Modifier.padding(24.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(apps) { name ->
                ListItem(
                    leadingContent = { AppIcon(name, client, pairing) },
                    headlineContent = { Text(name) },
                    modifier = Modifier.clickable { onPick(name) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
