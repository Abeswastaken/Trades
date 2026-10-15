package com.abeswastaken.trades.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var cash by remember(s.startingCash) { mutableStateOf(if (s.startingCash == 0.0) "" else s.startingCash.toString()) }
    var finnhub by remember(s.finnhubKey) { mutableStateOf(s.finnhubKey) }
    var showLog by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = vm.repo.exportJson()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
            }.onSuccess { vm.toast.value = "Backup saved" }
                .onFailure { AppLog.e("Backup failed", it); vm.toast.value = "Backup failed: ${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }
            }.onSuccess { confirmImport = it }.onFailure { vm.toast.value = "Couldn't read that file" }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Claude")
            InfoCard {
                Text(if (s.hasApiKey) "API key saved (encrypted on this phone)." else "No API key yet.")
                OutlinedTextField(
                    key, { key = it.trim() }, label = { Text(if (s.hasApiKey) "Replace key" else "Anthropic API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { vm.setApiKey(key); key = ""; vm.toast.value = "Key saved" }, enabled = key.startsWith("sk-")) { Text("Save key") }
                    if (s.hasApiKey) OutlinedButton(onClick = { vm.setApiKey(null) }) { Text("Remove") }
                }
                Spacer(Modifier.height(12.dp))
                Text("Default model", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClaudeModel.entries.forEach { m ->
                        FilterChip(selected = s.defaultModel == m, onClick = { vm.updateSettings { copy(defaultModel = m) } }, label = { Text(m.label) })
                    }
                }
                Muted("Sonnet for everyday calls, Opus for the big ones. Haiku is cheapest but weakest.")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Haiku news digest")
                        Muted("Summarizes headlines first so the main call gets signal, not noise. About a tenth of a cent.")
                    }
                    Switch(checked = s.useNewsDigest, onCheckedChange = { v -> vm.updateSettings { copy(useNewsDigest = v) } })
                }
            }

            SectionTitle("Account")
            InfoCard {
                OutlinedTextField(
                    cash, { cash = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Starting cash (USD)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Muted("The seed money in the Claude account. Cash is this plus or minus every fill.")
                Button(
                    onClick = { vm.updateSettings { copy(startingCash = cash.toDoubleOrNull() ?: 0.0) }; vm.toast.value = "Saved" },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Save") }
            }

            SectionTitle("Market data")
            InfoCard {
                Muted("Quotes, option chains and headlines come from Yahoo Finance (no key). Add a free Finnhub key as a backup for quotes and news when Yahoo is down.")
                OutlinedTextField(
                    finnhub, { finnhub = it.trim() }, label = { Text("Finnhub API key (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(onClick = { vm.updateSettings { copy(finnhubKey = finnhub) }; vm.toast.value = "Saved" }, modifier = Modifier.padding(top = 8.dp)) { Text("Save") }
            }

            SectionTitle("Covered-call scanner")
            ScannerRules(vm)

            SectionTitle("Backup")
            InfoCard {
                Muted("Saves fills, recommendations, watchlist and spend to a file. The API key is not included.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { exportLauncher.launch("colvin-trades-${LocalDate.now()}.json") }) { Text("Back up") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Restore") }
                }
            }

            SectionTitle("Diagnostics")
            InfoCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showLog = true }) { Text("View log") }
                    OutlinedButton(onClick = {
                        val all = AppLog.files().joinToString("\n") { it.readText() }
                        shareText(context, "colvin-trades-log.txt", "text/plain", all)
                    }) { Text("Share log") }
                }
                val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                Muted("Version $version", Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Log") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(AppLog.tail(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } },
        )
    }

    confirmImport?.let { text ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Replace everything?") },
            text = { Text("Restoring replaces all fills, recommendations, watchlist and spend on this phone with the backup.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = null
                    scope.launch {
                        runCatching { vm.repo.importJson(text) }
                            .onSuccess { vm.toast.value = "Backup restored" }
                            .onFailure { AppLog.e("Restore failed", it); vm.toast.value = "Restore failed: ${it.message}" }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScannerRules(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    val r = s.rules
    var minPrice by remember(r) { mutableStateOf(r.minPrice.toString()) }
    var maxPrice by remember(r) { mutableStateOf(r.maxPrice.toString()) }
    var minVol by remember(r) { mutableStateOf(r.minAvgVolume.toString()) }
    var minDte by remember(r) { mutableStateOf(r.minDte.toString()) }
    var maxDte by remember(r) { mutableStateOf(r.maxDte.toString()) }
    var otm by remember(r) { mutableStateOf(r.minOtmPercent.toString()) }
    var oi by remember(r) { mutableStateOf(r.minOpenInterest.toString()) }
    var bid by remember(r) { mutableStateOf(r.minBid.toString()) }
    var prem by remember(r) { mutableStateOf(r.minPremiumPercent.toString()) }

    InfoCard {
        Muted("These run in plain code before Claude sees anything, so they cost nothing.")
        RulePair("Min price", minPrice, { minPrice = it }, "Max price", maxPrice, { maxPrice = it })
        RulePair("Min avg volume", minVol, { minVol = it }, "Min open interest", oi, { oi = it })
        RulePair("Min DTE", minDte, { minDte = it }, "Max DTE", maxDte, { maxDte = it })
        RulePair("Min % OTM", otm, { otm = it }, "Min bid", bid, { bid = it })
        OutlinedTextField(
            prem, { prem = it }, label = { Text("Min premium % of stock price") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = {
                vm.updateSettings {
                    copy(
                        rules = rules.copy(
                            minPrice = minPrice.toDoubleOrNull() ?: rules.minPrice,
                            maxPrice = maxPrice.toDoubleOrNull() ?: rules.maxPrice,
                            minAvgVolume = minVol.toLongOrNull() ?: rules.minAvgVolume,
                            minDte = minDte.toIntOrNull() ?: rules.minDte,
                            maxDte = maxDte.toIntOrNull() ?: rules.maxDte,
                            minOtmPercent = otm.toDoubleOrNull() ?: rules.minOtmPercent,
                            minOpenInterest = oi.toLongOrNull() ?: rules.minOpenInterest,
                            minBid = bid.toDoubleOrNull() ?: rules.minBid,
                            minPremiumPercent = prem.toDoubleOrNull() ?: rules.minPremiumPercent,
                        )
                    )
                }
                vm.toast.value = "Scanner rules saved"
            }) { Text("Save rules") }
            OutlinedButton(onClick = { vm.updateSettings { copy(rules = com.abeswastaken.trades.core.scanner.ScanRules()) } }) { Text("Defaults") }
        }
    }
}

@Composable
private fun RulePair(a: String, av: String, onA: (String) -> Unit, b: String, bv: String, onB: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedTextField(
            av, onA, label = { Text(a) }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedTextField(
            bv, onB, label = { Text(b) }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}
