package com.abeswastaken.trades.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.abeswastaken.trades.alerts.AlertScheduler
import com.abeswastaken.trades.alerts.Notifier
import com.abeswastaken.trades.core.engine.AlertLevel
import com.abeswastaken.trades.core.engine.AlertRules
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.data.InboxItem
import com.abeswastaken.trades.data.LessonEntry
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.Work
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private val clock = DateTimeFormatter.ofPattern("EEE h:mm a")

private fun fmt(ms: Long, f: DateTimeFormatter = stamp) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(f)

@Composable
private fun levelColor(l: AlertLevel) = when (l) {
    AlertLevel.CRITICAL -> MaterialTheme.colorScheme.error
    AlertLevel.NOTICE -> Color(0xFFF9A825)
    AlertLevel.OPPORTUNITY -> Color(0xFF2E7D32)
}

// ---- Inbox ------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(vm: AppViewModel, back: () -> Unit, openNotebook: () -> Unit) {
    val items by vm.inbox.collectAsState()
    var why by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    // Everything on screen counts as read once he leaves.
    DisposableEffect(Unit) { onDispose { vm.markInboxRead() } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Alerts") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = openNotebook, modifier = Modifier.testTag("open_notebook")) { Icon(Icons.AutoMirrored.Filled.MenuBook, "Notebook") }
                IconButton(onClick = { vm.markInboxRead() }) { Icon(Icons.Filled.DoneAll, "Mark all read") }
            },
        )
        LazyColumn(Modifier.padding(horizontal = 16.dp).testTag("inbox_list")) {
            if (items.isEmpty()) item {
                Muted("No alerts yet. Turn on alerts in Settings › Alerts; they also land here when the phone is quiet.", Modifier.padding(vertical = 16.dp))
            }
            items(items, key = { it.id }) { i -> InboxRow(i, onWhy = { why = it }); HorizontalDivider() }
            if (items.isNotEmpty()) item {
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Clear the inbox") }
            }
        }
    }
    why?.let { WhyDialog(vm, it) { why = null } }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all alerts?") },
        text = { Text("The notebook is kept.") },
        confirmButton = { TextButton(onClick = { vm.clearInbox(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun InboxRow(i: InboxItem, onWhy: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = levelColor(i.level), modifier = Modifier.size(8.dp)) {}
            Spacer(Modifier.width(8.dp))
            Text(i.title, fontWeight = if (i.read) FontWeight.Normal else FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        Text(i.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("${fmt(i.at)} · ${i.check} · ${i.level.label}", Modifier.weight(1f))
            i.ticker?.let { t -> TextButton(onClick = { onWhy(t) }) { Text("Why is $t moving?") } }
        }
    }
}

// ---- Why is it moving? -------------------------------------------------------------------------

@Composable
fun WhyDialog(vm: AppViewModel, ticker: String, onDismiss: () -> Unit) {
    val all by vm.explanations.collectAsState()
    val settings by vm.settings.collectAsState()
    val w = all[ticker.uppercase()]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why is $ticker moving?") },
        text = {
            Column {
                when (w) {
                    null, is Work.Idle -> Muted("${settings.defaultModel.label} reads today's news, SEC filings and your position, explains the move in two lines and adds a lesson to your notebook. About a cent.")
                    is Work.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(w.step + "…")
                    }
                    is Work.Failed -> Text(w.message, color = MaterialTheme.colorScheme.error)
                    is Work.Done -> LessonBody(w.value, showTicker = false)
                }
            }
        },
        confirmButton = {
            if (w !is Work.Running) TextButton(onClick = { vm.explain(ticker) }, enabled = settings.hasApiKey, modifier = Modifier.testTag("why_ask")) {
                Text(if (w is Work.Done) "Ask again" else "Explain")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LessonBody(l: LessonEntry, showTicker: Boolean) {
    Column {
        if (showTicker) Muted("${fmt(l.at)} · ${l.ticker}")
        Text(l.why, style = MaterialTheme.typography.bodyMedium)
        Muted("Driver: ${l.driver.lowercase()} · confidence ${l.confidence}", Modifier.padding(top = 2.dp))
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("Lesson: ${l.title}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(l.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (l.watchFor.isNotBlank()) Text("Watch for: ${l.watchFor}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        if (!showTicker) Muted("Saved to your notebook · ${money(l.costUsd)}", Modifier.padding(top = 6.dp))
    }
}

// ---- Notebook ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(vm: AppViewModel, back: () -> Unit) {
    val lessons by vm.lessons.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Notebook") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item { Muted("Every \"why is it moving?\" answer, with its lesson. Newest first.", Modifier.padding(bottom = 8.dp)) }
            if (lessons.isEmpty()) item { Muted("Nothing yet. Tap \"Why is it moving?\" on an alert or a holding.") }
            items(lessons, key = { it.at.toString() + it.ticker }) { l ->
                InfoCard(Modifier.padding(bottom = 8.dp)) { LessonBody(l, showTicker = true) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---- Settings › Alerts --------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertsSettings(vm: AppViewModel, openInbox: () -> Unit) {
    val s by vm.settings.collectAsState()
    val a = s.alerts
    val status by vm.alertStatus.collectAsState()
    val checking by vm.checking.collectAsState()
    val context = LocalContext.current
    // Re-read permissions when he comes back from the system screens.
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notificationsOn = remember(resumed, a.enabled) { Notifier.enabled(context) }
    val unrestricted = remember(resumed, a.enabled) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val exact = remember(resumed, a.enabled) { AlertScheduler.canExact(context) }

    fun askBattery() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        resumed++
        if (!granted) vm.toast.value = "Notifications are off: alerts will only land in the inbox"
        if (!unrestricted) askBattery()
    }

    fun turnOn(on: Boolean) {
        vm.updateAlerts { copy(enabled = on) }
        if (!on) return
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else if (!unrestricted) askBattery()
    }

    InfoCard(Modifier.testTag("alerts_card")) {
        SwitchRow("Background alerts", "Checks at 9:31 ET, through the session and before the close, Monday to Friday, skipping holidays. Nothing fires outside market hours.", a.enabled, "alerts_switch") { turnOn(it) }
        Muted(statusLine(a, status), Modifier.padding(top = 4.dp).testTag("alerts_status"))
        if (a.enabled && !notificationsOn) Warning("Notifications are off for this app, so alerts only land in the inbox.", "Turn on") {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        if (a.enabled && !unrestricted) Warning("Battery is optimized for this app, so Android may delay or skip checks. Set it to Unrestricted.", "Fix") { askBattery() }
        if (a.enabled && !exact) Warning("Exact alarms aren't allowed, so checks may run a few minutes late.", "Allow") {
            if (Build.VERSION.SDK_INT >= 31) runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
        }

        Spacer(Modifier.height(8.dp))
        Text("When", style = MaterialTheme.typography.labelLarge)
        SwitchRow("Open check, 9:31 ET", "A full read a minute after the open: the plain checks, then Claude's brief on what matters today.", a.openCheck, "alerts_open") { v -> vm.updateAlerts { copy(openCheck = v) } }
        if (a.openCheck) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClaudeModel.entries.forEach { m ->
                FilterChip(selected = a.openModel == m, onClick = { vm.updateAlerts { copy(openModel = m) } }, label = { Text(m.label) })
            }
        }
        if (a.openCheck) Muted(when (a.openModel) {
            ClaudeModel.HAIKU -> "Haiku: well under a cent a day, thinner read."
            ClaudeModel.SONNET -> "Sonnet: a few cents a day."
            ClaudeModel.OPUS -> "Opus: roughly twice Sonnet."
        })
        SwitchRow("Intraday watch", "Plain checks only, no Claude: free.", a.intraday, "alerts_intraday") { v -> vm.updateAlerts { copy(intraday = v) } }
        if (a.intraday) ChipRow(AlertRules.INTERVALS.map { "Every $it min" to it.toDouble() }, a.intervalMin.toDouble()) { v -> vm.updateAlerts { copy(intervalMin = v.toInt()) } }
        SwitchRow("Before-close check", "30 minutes before the close (12:30 on early-close days): options expiring today in or near the money.", a.closeCheck, "alerts_close") { v -> vm.updateAlerts { copy(closeCheck = v) } }
        SwitchRow("Quiet mode", "Only money-at-risk alerts buzz. Everything else goes to the inbox silently.", a.quiet, "alerts_quiet") { v -> vm.updateAlerts { copy(quiet = v) } }

        Spacer(Modifier.height(8.dp))
        Text("What to watch", style = MaterialTheme.typography.labelLarge)
        SwitchRow("Big moves", "Holdings and watchlist names moving this much, 52-week highs and lows.", a.moves, "alerts_moves") { v -> vm.updateAlerts { copy(moves = v) } }
        if (a.moves) ChipRow(listOf(3.0, 5.0, 8.0, 10.0).map { "±${it.toInt()}%" to it }, a.movePercent) { v -> vm.updateAlerts { copy(movePercent = v) } }
        SwitchRow("Short options at risk", "In the money, or within this far of the strike.", a.shortCalls, "alerts_short") { v -> vm.updateAlerts { copy(shortCalls = v) } }
        if (a.shortCalls) ChipRow(listOf(1.0, 2.0, 3.0, 5.0).map { "${it.toInt()}%" to it }, a.nearPercent) { v -> vm.updateAlerts { copy(nearPercent = v) } }
        SwitchRow("Ex-dividend assignment risk", "The day before ex-dividend, when a short call's time value is below the dividend.", a.exDividend, "alerts_exdiv") { v -> vm.updateAlerts { copy(exDividend = v) } }
        SwitchRow("Earnings today", "On anything held or watched.", a.earnings, "alerts_earnings") { v -> vm.updateAlerts { copy(earnings = v) } }
        SwitchRow("Reddit long shots", "When the crowd piles into a stock already making a huge move.", a.longShots, "alerts_longshots") { v -> vm.updateAlerts { copy(longShots = v) } }
        SwitchRow("Unusual activity", "Volume on pace for this many times normal, and options volume far above open interest.", a.unusual, "alerts_unusual") { v -> vm.updateAlerts { copy(unusual = v) } }
        if (a.unusual) ChipRow(listOf(2.0, 3.0, 5.0).map { "${it.toInt()}×" to it }, a.volumeMultiple) { v -> vm.updateAlerts { copy(volumeMultiple = v) } }
        SwitchRow("Insider buys and offerings", "From SEC filings, at the open and before the close.", a.insider, "alerts_insider") { v -> vm.updateAlerts { copy(insider = v) } }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { vm.sendTestAlert() }, modifier = Modifier.testTag("alert_test")) { Text("Send a test alert") }
            OutlinedButton(onClick = { vm.checkNow() }, enabled = !checking, modifier = Modifier.testTag("alert_check_now")) { Text(if (checking) "Checking…" else "Check now") }
            TextButton(onClick = openInbox) { Text("Open inbox") }
        }
    }
}

private fun statusLine(a: AlertRules, s: com.abeswastaken.trades.data.AlertStatus): String {
    if (!a.enabled) return "Off." + (s.lastAt?.let { " Last check ${fmt(it, clock)}." } ?: "")
    val last = s.lastAt?.let { "Last check ${fmt(it, clock)} (${s.lastCheck}): ${s.lastResult}" } ?: "No check yet."
    val next = s.nextAt?.let { "Next: ${s.nextCheck} ${fmt(it, clock)}." } ?: ""
    return "$last $next".trim()
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Muted(sub)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(options: List<Pair<String, Double>>, selected: Double, onPick: (Double) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, v) ->
            FilterChip(selected = kotlin.math.abs(selected - v) < 1e-6, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Warning(text: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(action) }
    }
}

/** The bell on Home, with the unread count. */
@Composable
fun InboxBell(vm: AppViewModel, onClick: () -> Unit) {
    val unread by vm.unread.collectAsState()
    IconButton(onClick = onClick, modifier = Modifier.testTag("inbox_button")) {
        androidx.compose.material3.BadgedBox(badge = { if (unread > 0) androidx.compose.material3.Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
            Icon(Icons.Filled.Notifications, contentDescription = "Alerts")
        }
    }
}
