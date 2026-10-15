package com.abeswastaken.trades.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.abeswastaken.trades.core.ledger.Position
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Side
import com.abeswastaken.trades.data.FillDraft
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.theme.pnlColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionsScreen(vm: AppViewModel, openLog: () -> Unit) {
    val ledger by vm.ledger.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val loading by vm.quotesLoading.collectAsState()
    val error by vm.quotesError.collectAsState()
    var confirm by remember { mutableStateOf<Pair<String, Position>?>(null) }

    // Mark to market every time the screen opens.
    LaunchedEffect(Unit) { vm.refreshQuotes() }

    val stocks = ledger.positions.filter { it.instrument.type == AssetType.STOCK }
    val options = ledger.positions.filter { it.instrument.isOption }.sortedBy { it.instrument.expiry }
    val unrealized = stocks.sumOf { p -> quotes[p.instrument.ticker]?.let { (it.price - p.avgCost) * p.quantity } ?: 0.0 }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Positions") },
            actions = {
                IconButton(onClick = { vm.refreshQuotes() }, enabled = !loading) { Icon(Icons.Filled.Refresh, "Refresh quotes") }
            },
        )
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item {
                InfoCard {
                    KeyValue("Cash", money(ledger.cash))
                    KeyValue("Unrealized on stock", signedMoney(unrealized), pnlColor(unrealized))
                    KeyValue("Realized all time", signedMoney(ledger.realizedTotal), pnlColor(ledger.realizedTotal))
                    if (loading) Muted("Updating quotes…")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { SectionTitle("Stock") }
            if (stocks.isEmpty()) item { Muted("No shares held.") }
            items(stocks, key = { it.instrument.label }) { p -> StockRow(p, quotes[p.instrument.ticker], ledger.coverableContracts(p.instrument.ticker)) }

            item { SectionTitle("Options") }
            if (options.isEmpty()) item { Muted("No open options.") }
            items(options, key = { it.instrument.label }) { p ->
                OptionRow(
                    p, quotes[p.instrument.ticker],
                    onClose = {
                        vm.startDraft(
                            FillDraft(
                                ticker = p.instrument.ticker, type = p.instrument.type,
                                side = if (p.quantity < 0) Side.BUY else Side.SELL,
                                quantity = qty(abs(p.quantity)), strike = p.instrument.strike?.toString().orEmpty(),
                                expiry = p.instrument.expiry?.toString().orEmpty(), note = "Close",
                            )
                        )
                        openLog()
                    },
                    onExpire = { confirm = "expire" to p },
                    onAssign = { confirm = "assign" to p },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    confirm?.let { (what, p) ->
        val shares = abs(p.quantity) * 100
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "expire") "Mark as expired?" else "Mark as assigned?") },
            text = {
                Text(
                    if (what == "expire") "${p.instrument.label} closes at 0.00 and the premium becomes realized."
                    else "${p.instrument.label} closes at 0.00 and ${qty(shares)} ${p.instrument.ticker} shares are sold at ${px(p.instrument.strike ?: 0.0)}."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "expire") vm.expire(p) else vm.assign(p)
                    confirm = null
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StockRow(p: Position, q: Quote?, coverable: Int) {
    InfoCard(Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(p.instrument.ticker, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(q?.let { px(it.price) } ?: "no quote")
        }
        KeyValue("Shares", qty(p.quantity))
        KeyValue("Avg cost", px(p.avgCost))
        q?.let {
            val u = (it.price - p.avgCost) * p.quantity
            KeyValue("Market value", money(it.price * p.quantity))
            KeyValue("Unrealized", signedMoney(u), pnlColor(u), bold = true)
            it.nextEarnings?.let { e -> KeyValue("Next earnings", e.toString()) }
        }
        if (coverable > 0) Muted("$coverable call contract(s) can still be written against these shares.")
    }
}

@Composable
private fun OptionRow(p: Position, q: Quote?, onClose: () -> Unit, onExpire: () -> Unit, onAssign: () -> Unit) {
    val inst = p.instrument
    val dte = inst.expiry?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
    val short = p.quantity < 0
    InfoCard(Modifier.padding(bottom = 8.dp)) {
        Text(inst.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        KeyValue(if (short) "Short contracts" else "Long contracts", qty(abs(p.quantity)))
        KeyValue(if (short) "Premium received" else "Premium paid", "${px(p.avgCost)} (${money(abs(p.basis))})")
        dte?.let { KeyValue("Days to expiry", if (it < 0) "expired ${-it}d ago" else it.toString()) }
        q?.let { s ->
            KeyValue("Stock", px(s.price))
            if (inst.type == AssetType.CALL && short && inst.strike != null && s.price >= inst.strike!!) {
                Text("In the money: assignment is likely", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onClose) { Text(if (short) "Buy to close" else "Sell to close") }
            if (short) TextButton(onClick = onExpire) { Text("Expired") }
            if (short && inst.type == AssetType.CALL) TextButton(onClick = onAssign) { Text("Assigned") }
        }
    }
}
