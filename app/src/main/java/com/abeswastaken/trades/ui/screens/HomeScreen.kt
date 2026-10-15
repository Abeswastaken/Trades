package com.abeswastaken.trades.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.data.RecEntity
import com.abeswastaken.trades.data.RecStatus
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.RecRun
import com.abeswastaken.trades.ui.theme.pnlColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val recDate = DateTimeFormatter.ofPattern("MMM d, h:mm a")

@Composable
fun HomeScreen(vm: AppViewModel, openRec: (Long) -> Unit) {
    val ledger by vm.ledger.collectAsState()
    val settings by vm.settings.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val recs by vm.recs.collectAsState()
    val realized by vm.realizedByRec.collectAsState()
    val spend by vm.spendThisMonth.collectAsState()
    val run by vm.recRun.collectAsState()

    LaunchedEffect(Unit) { vm.refreshQuotes() }

    var model by remember(settings.defaultModel) { mutableStateOf(settings.defaultModel) }
    var focus by rememberSaveable { mutableStateOf("") }

    // Stock marked at last quote; options carried at their open price (premium) since chains aren't fetched here.
    val stockValue = ledger.positions.filter { it.instrument.type == AssetType.STOCK }.sumOf { p ->
        (quotes[p.instrument.ticker]?.price ?: p.avgCost) * p.quantity
    }
    val optionValue = ledger.positions.filter { it.instrument.isOption }.sumOf { it.basis }
    val equity = ledger.cash + stockValue + optionValue
    val now = LocalDate.now()
    val monthRealized = ledger.realizedBetween(now.withDayOfMonth(1), now)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                "Colvin Trades", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
            )
            InfoCard {
                Text("Account value (est.)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(equity), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                if (settings.startingCash > 0) {
                    val change = equity - settings.startingCash
                    Text(
                        "${signedMoney(change)} (${pct(change / settings.startingCash * 100)}) since start",
                        color = pnlColor(change),
                    )
                }
                Spacer(Modifier.height(8.dp))
                KeyValue("Cash", money(ledger.cash))
                KeyValue("Realized this month", signedMoney(monthRealized), pnlColor(monthRealized))
                KeyValue("Realized all time", signedMoney(ledger.realizedTotal), pnlColor(ledger.realizedTotal))
                KeyValue("Claude spend this month", money(spend))
                if (settings.startingCash == 0.0) {
                    Muted("Set your starting cash in Settings so cash and buying power are right.", Modifier.padding(top = 6.dp))
                }
            }
        }

        item {
            SectionTitle("Get a recommendation")
            InfoCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClaudeModel.entries.forEach { m ->
                        FilterChip(selected = model == m, onClick = { model = m }, label = { Text(m.label) })
                    }
                }
                OutlinedTextField(
                    value = focus, onValueChange = { focus = it },
                    label = { Text("Anything to focus on? (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), maxLines = 3,
                )
                Spacer(Modifier.height(10.dp))
                when (val r = run) {
                    is RecRun.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(r.step + "…")
                    }
                    else -> {
                        if (r is RecRun.Failed) {
                            Text(r.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Button(
                            onClick = { vm.recommend(model, focus) { id -> focus = ""; openRec(id) } },
                            enabled = settings.hasApiKey,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Ask ${model.label}") }
                        if (!settings.hasApiKey) Muted("Add your Anthropic API key in Settings first.", Modifier.padding(top = 6.dp))
                    }
                }
            }
        }

        item { SectionTitle("Recent recommendations") }
        if (recs.isEmpty()) item { Muted("None yet.") }
        items(recs.take(30), key = { it.id }) { r ->
            RecRow(r, realized[r.id]) { openRec(r.id) }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecRow(r: RecEntity, realized: Double?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(r.summary, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Muted(Instant.ofEpochMilli(r.createdAt).atZone(ZoneId.systemDefault()).format(recDate))
            Muted(
                when (r.status) {
                    RecStatus.TAKEN -> "Taken"
                    RecStatus.SKIPPED -> "Skipped"
                    else -> "Open"
                }
            )
            if (realized != null) Text(signedMoney(realized), color = pnlColor(realized), style = MaterialTheme.typography.bodySmall)
        }
    }
}
