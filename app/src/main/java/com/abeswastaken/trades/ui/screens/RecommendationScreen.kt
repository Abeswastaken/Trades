package com.abeswastaken.trades.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.core.engine.Recommendation
import com.abeswastaken.trades.core.engine.Recommender
import com.abeswastaken.trades.data.RecEntity
import com.abeswastaken.trades.data.RecStatus
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.theme.pnlColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationScreen(vm: AppViewModel, id: Long, back: () -> Unit, openLog: () -> Unit) {
    val recs by vm.recs.collectAsState()
    val realizedMap by vm.realizedByRec.collectAsState()
    val fills by vm.fills.collectAsState()
    var loaded by remember { mutableStateOf<RecEntity?>(null) }
    LaunchedEffect(id) { if (recs.none { it.id == id }) loaded = vm.repo.rec(id) }
    val rec = recs.firstOrNull { it.id == id } ?: loaded
    val parsed: Recommendation? = remember(rec?.json) { rec?.let { runCatching { Recommender.parse(it.json) }.getOrNull() } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Recommendation") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        if (rec == null) { Muted("Loading…", Modifier.padding(16.dp)); return@Column }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(rec.summary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                AssistChip(onClick = {}, label = { Text(rec.action.replace('_', ' ')) })
                parsed?.let { AssistChip(onClick = {}, label = { Text("Confidence: ${it.confidence}") }) }
            }
            Muted(
                Instant.ofEpochMilli(rec.createdAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")) +
                    " · ${rec.servedModel} · " + String.format(Locale.US, "$%.4f", rec.costUsd)
            )

            if (parsed == null) {
                SectionTitle("Raw reply")
                Text(rec.json)
            } else {
                SectionTitle("The order")
                InfoCard {
                    parsed.ticker?.let { KeyValue("Ticker", it) }
                    parsed.quantity?.let { KeyValue(if (parsed.isOption) "Contracts" else "Shares", qty(it)) }
                    parsed.limitPrice?.let { KeyValue("Limit price", px(it)) }
                    parsed.strike?.let { KeyValue("Strike", px(it)) }
                    parsed.expiry?.let { KeyValue("Expiry", it) }
                }
                SectionTitle("The math")
                InfoCard {
                    val m = parsed.math
                    m.cashImpact?.let { KeyValue(if (it >= 0) "Cash out" else "Cash in", money(kotlin.math.abs(it))) }
                    m.maxGain?.let { KeyValue("Max gain", money(it)) }
                    m.maxLoss?.let { KeyValue("Max loss", money(it)) }
                    m.breakeven?.let { KeyValue("Breakeven", px(it)) }
                    m.annualizedYieldPercent?.let { KeyValue("Annualized yield", String.format(Locale.US, "%.1f%%", it)) }
                    if (listOf(m.cashImpact, m.maxGain, m.maxLoss, m.breakeven, m.annualizedYieldPercent).all { it == null }) {
                        Muted("No numbers for this one.")
                    }
                }
                SectionTitle("Why")
                Text(parsed.rationale)
                SectionTitle("Risks")
                Text(parsed.risks)
                parsed.exitPlan?.let { SectionTitle("Exit plan"); Text(it) }
            }
            rec.focus?.let { SectionTitle("Your note"); Text(it) }
            rec.digest?.let { SectionTitle("News digest used"); Muted(it) }

            SectionTitle("Outcome")
            val linked = fills.filter { it.recommendationId == rec.id }
            when (rec.status) {
                RecStatus.TAKEN -> {
                    linked.forEach { Muted("${it.date}: ${it.side} ${qty(it.quantity)} ${it.instrument.label} @ ${px(it.price)}") }
                    val r = realizedMap[rec.id]
                    if (r != null) KeyValue("Realized so far", signedMoney(r), pnlColor(r), bold = true)
                    else Muted("Still open.")
                    OutlinedButton(
                        onClick = { if (vm.startDraftFromRec(rec)) openLog() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Log another fill for this") }
                }
                RecStatus.SKIPPED -> Muted("Skipped.")
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { if (vm.startDraftFromRec(rec)) openLog() },
                        enabled = parsed != null && parsed.actionEnum.name != "HOLD",
                    ) { Text("Mark as taken") }
                    OutlinedButton(onClick = { vm.skip(rec.id) }) { Text(if (parsed?.actionEnum?.name == "HOLD") "Done" else "Skip") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
