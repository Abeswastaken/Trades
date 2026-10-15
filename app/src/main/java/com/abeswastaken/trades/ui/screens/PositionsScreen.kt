package com.abeswastaken.trades.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.core.ledger.OptionEvents
import com.abeswastaken.trades.core.ledger.Position
import com.abeswastaken.trades.core.market.OptionMark
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.data.FillDraft
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.theme.pnlColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** What the confirm dialog is about to do. */
private enum class Event { EXPIRE, ASSIGN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionsScreen(vm: AppViewModel, openLog: () -> Unit) {
    val ledger by vm.ledger.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val loading by vm.quotesLoading.collectAsState()
    val error by vm.quotesError.collectAsState()
    val marks by vm.optionMarks.collectAsState()
    val suggesting by vm.suggesting.collectAsState()
    var sheet by remember { mutableStateOf<Position?>(null) }
    var confirm by remember { mutableStateOf<Pair<Event, Position>?>(null) }
    var rolling by remember { mutableStateOf<Position?>(null) }

    // Mark to market every time the screen opens.
    LaunchedEffect(Unit) { vm.refreshQuotes() }

    // A Home alert can ask for one option's action sheet.
    val focus by vm.focusPosition.collectAsState()
    LaunchedEffect(focus, ledger) {
        val f = focus ?: return@LaunchedEffect
        ledger.positions.firstOrNull { it.instrument == f }?.let { sheet = it }
        vm.focusPosition.value = null
    }

    val stocks = ledger.positions.filter { it.instrument.type == AssetType.STOCK }
    val options = ledger.positions.filter { it.instrument.isOption }.sortedBy { it.instrument.expiry }
    val unrealized = stocks.sumOf { p -> quotes[p.instrument.ticker]?.let { (it.price - p.avgCost) * p.quantity } ?: 0.0 }
    val optionUnrealized = options.sumOf { marks[it.instrument]?.unrealized ?: 0.0 }

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
                    if (marks.isNotEmpty()) KeyValue("Unrealized on options", signedMoney(optionUnrealized), pnlColor(optionUnrealized))
                    KeyValue("Realized all time", signedMoney(ledger.realizedTotal), pnlColor(ledger.realizedTotal))
                    if (loading) Muted("Updating quotes…")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { SectionTitle("Stock") }
            if (stocks.isEmpty()) item { Muted("No shares held.") }
            items(stocks, key = { it.instrument.label }) { p ->
                val coverable = ledger.coverableContracts(p.instrument.ticker)
                StockRow(
                    p, quotes[p.instrument.ticker], coverable, busy = suggesting == p.instrument.ticker,
                    onCoveredCall = { vm.coveredCallDraft(p, coverable) },
                    onSell = { vm.startDraft(FillDraft.close(p, quotes[p.instrument.ticker]?.price)); openLog() },
                )
            }

            item { SectionTitle("Options") }
            if (options.isEmpty()) item { Muted("No open options.") }
            else item { Muted("Tap an option to log what happened to it.", Modifier.padding(bottom = 6.dp)) }
            items(options, key = { it.instrument.label }) { p ->
                OptionRow(p, quotes[p.instrument.ticker], marks[p.instrument], onClick = { sheet = p })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    sheet?.let { p ->
        val short = p.quantity < 0
        val mark = marks[p.instrument]
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What happened to ${p.instrument.label}?", style = MaterialTheme.typography.titleMedium)
                Muted("Each choice opens with the contract, size and date filled in.")
                SheetButton(if (short) "Bought to close" else "Sold to close", "option_close") {
                    sheet = null
                    vm.startDraft(FillDraft.close(p, mark?.quote?.let { if (short) it.ask.takeIf { a -> a > 0 } ?: it.premium else it.premium }))
                    openLog()
                }
                SheetButton("Rolled to a new strike or date", "option_roll") { sheet = null; rolling = p }
                SheetButton(if (short) "Expired worthless" else "Expired (worthless)", "option_expire") { sheet = null; confirm = Event.EXPIRE to p }
                if (short) SheetButton(
                    if (p.instrument.type == AssetType.CALL) "Assigned (shares called away)" else "Assigned (bought the shares)", "option_assign",
                ) { sheet = null; confirm = Event.ASSIGN to p }
            }
        }
    }

    confirm?.let { (what, p) -> EventDialog(what, p, onDismiss = { confirm = null }) { date ->
        if (what == Event.EXPIRE) vm.expire(p, date) else vm.assign(p, date)
        confirm = null
    } }

    rolling?.let { p ->
        RollDialog(p, marks[p.instrument], suggest = { vm.repo.rollTarget(p.instrument) }, onDismiss = { rolling = null }) { fills ->
            vm.roll(fills)
            rolling = null
        }
    }
}

@Composable
private fun SheetButton(label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
}

/** Expired/assigned confirm, dated on the expiry (or today if it hasn't come yet), editable. */
@Composable
private fun EventDialog(what: Event, p: Position, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val default = p.instrument.expiry?.let { if (it.isBefore(today)) it else today } ?: today
    var date by remember { mutableStateOf(default.toString()) }
    val parsed = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
    val shares = abs(p.quantity) * 100
    val call = p.instrument.type == AssetType.CALL
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (what == Event.EXPIRE) "Mark as expired?" else "Mark as assigned?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        what == Event.EXPIRE && p.quantity < 0 -> "${p.instrument.label} closes at 0.00 and the premium becomes realized."
                        what == Event.EXPIRE -> "${p.instrument.label} closes at 0.00 and what you paid becomes a realized loss."
                        call -> "${p.instrument.label} closes at 0.00 and ${qty(shares)} ${p.instrument.ticker} shares are sold at ${px(p.instrument.strike ?: 0.0)}."
                        else -> "${p.instrument.label} closes at 0.00 and you buy ${qty(shares)} ${p.instrument.ticker} shares at ${px(p.instrument.strike ?: 0.0)}."
                    }
                )
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, isError = parsed == null)
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Close the current option and open the next one, as two fills logged together. */
@Composable
private fun RollDialog(
    p: Position, mark: OptionMark?, suggest: suspend () -> com.abeswastaken.trades.core.market.OptionQuote?,
    onDismiss: () -> Unit, onSave: (List<com.abeswastaken.trades.core.model.Fill>) -> Unit,
) {
    val inst = p.instrument
    val short = p.quantity < 0
    val nextFriday = (inst.expiry ?: LocalDate.now()).plusDays(7)
    var close by remember { mutableStateOf(mark?.quote?.let { px(if (short && it.ask > 0) it.ask else it.premium) }.orEmpty()) }
    var strike by remember { mutableStateOf(inst.strike?.let { qty(it) }.orEmpty()) }
    var expiry by remember { mutableStateOf(nextFriday.toString()) }
    var premium by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    // Short calls: prefill next week's same (or next-higher) strike from today's chain.
    val lookUp = short && inst.type == AssetType.CALL
    var hint by remember { mutableStateOf(if (lookUp) "Looking up next week's call…" else null) }
    LaunchedEffect(Unit) {
        if (!lookUp) return@LaunchedEffect
        val o = runCatching { kotlinx.coroutines.withTimeoutOrNull(8_000) { suggest() } }.getOrNull()
        if (o == null) { hint = null; return@LaunchedEffect }
        strike = qty(o.strike)
        expiry = o.expiry.toString()
        if (premium.isBlank()) premium = px(o.premium)
        hint = "From today's chain: ${o.expiry} ${qty(o.strike)}C, ${if (o.liveBid) "bid" else "last trade"} ${px(o.premium)}. Use what Schwab fills."
    }
    val fills = runCatching {
        OptionEvents.roll(
            p, close.toDouble(), strike.toDouble(), LocalDate.parse(expiry.trim()), premium.toDouble(), LocalDate.parse(date.trim()),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Roll ${inst.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted("${qty(abs(p.quantity))} contract(s). Enter what Schwab filled for each leg.")
                hint?.let { Muted(it) }
                RollField(close, { close = it }, if (short) "Buy back at" else "Sell at", "roll_close")
                RollField(strike, { strike = it }, "New strike", "roll_strike")
                OutlinedTextField(expiry, { expiry = it }, label = { Text("New expiry (YYYY-MM-DD)") }, singleLine = true)
                RollField(premium, { premium = it }, if (short) "New premium received" else "New premium paid", "roll_premium")
                OutlinedTextField(date, { date = it }, label = { Text("Date") }, singleLine = true)
                fills.onSuccess { f ->
                    val net = f.sumOf { it.cashFlow }
                    Text(
                        "${if (net >= 0) "Net credit" else "Net debit"} ${money(abs(net))}",
                        color = pnlColor(net), fontWeight = FontWeight.SemiBold,
                    )
                }.onFailure { if (premium.isNotBlank() && close.isNotBlank()) Muted(it.message ?: "Check the fields") }
            }
        },
        confirmButton = {
            TextButton(onClick = { fills.onSuccess(onSave) }, enabled = fills.isSuccess, modifier = Modifier.testTag("roll_save")) { Text("Save roll") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RollField(value: String, onChange: (String) -> Unit, label: String, tag: String) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag(tag),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockRow(p: Position, q: Quote?, coverable: Int, busy: Boolean, onCoveredCall: () -> Unit, onSell: () -> Unit) {
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
            it.nextEarnings?.takeIf { e -> !e.isBefore(LocalDate.now()) }?.let { e -> KeyValue("Next earnings", e.toString()) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (coverable > 0) FilledTonalButton(onClick = onCoveredCall, enabled = !busy, modifier = Modifier.testTag("cc_${p.instrument.ticker}")) {
                Text(if (busy) "Finding a strike…" else "Sell $coverable covered call${if (coverable > 1) "s" else ""}")
            }
            TextButton(onClick = onSell) { Text("Sell shares") }
        }
    }
}

@Composable
private fun OptionRow(p: Position, q: Quote?, mark: OptionMark?, onClick: () -> Unit) {
    val inst = p.instrument
    val dte = inst.expiry?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
    val short = p.quantity < 0
    InfoCard(Modifier.padding(bottom = 8.dp).clickable(onClick = onClick).testTag("option_${inst.label}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(inst.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text("Update ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        KeyValue(if (short) "Short contracts" else "Long contracts", qty(abs(p.quantity)))
        KeyValue(if (short) "Premium received" else "Premium paid", "${px(p.avgCost)} (${money(abs(p.basis))})")
        dte?.let {
            if (it < 0) Text("Expired ${-it}d ago: tap to mark expired or assigned", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else KeyValue("Days to expiry", it.toString())
        }
        mark?.let { m ->
            KeyValue("Now (bid / ask)", "${px(m.quote.bid)} / ${px(m.quote.ask)}")
            KeyValue("Unrealized", signedMoney(m.unrealized), pnlColor(m.unrealized), bold = true)
            m.premiumCapturedPercent?.let { KeyValue("Premium captured", String.format(java.util.Locale.US, "%.0f%%", it)) }
        }
        q?.let { s ->
            KeyValue("Stock", px(s.price))
            val k = inst.strike
            if (short && k != null && ((inst.type == AssetType.CALL && s.price >= k) || (inst.type == AssetType.PUT && s.price <= k))) {
                Text("In the money: assignment is likely", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            s.nextEarnings?.let { e ->
                val exp = inst.expiry
                if (exp != null && !e.isBefore(LocalDate.now()) && !e.isAfter(exp)) {
                    Text("Earnings $e, before expiry", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
