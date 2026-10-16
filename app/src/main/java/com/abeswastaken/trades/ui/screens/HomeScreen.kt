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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.ledger.LedgerState
import com.abeswastaken.trades.core.market.MarketClock
import com.abeswastaken.trades.core.market.MarketSession
import com.abeswastaken.trades.core.market.OptionMark
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.data.RecEntity
import com.abeswastaken.trades.data.RecStatus
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.RecRun
import com.abeswastaken.trades.ui.Work
import com.abeswastaken.trades.ui.theme.pnlColor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val recDate = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private val clockTime = DateTimeFormatter.ofPattern("h:mm a")
private val clockDay = DateTimeFormatter.ofPattern("EEE h:mm a")

/** One thing on the dashboard that wants a decision. */
private data class Alert(val text: String, val urgent: Boolean, val instrument: Instrument? = null)

@Composable
fun HomeScreen(vm: AppViewModel, openRec: (Long) -> Unit, openPositions: () -> Unit, openWatch: (Int) -> Unit, openInbox: () -> Unit = {}) {
    val ledger by vm.ledger.collectAsState()
    val settings by vm.settings.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val recs by vm.recs.collectAsState()
    val realized by vm.realizedByRec.collectAsState()
    val spend by vm.spendThisMonth.collectAsState()
    val run by vm.recRun.collectAsState()
    val marks by vm.optionMarks.collectAsState()
    val buzz by vm.buzz.collectAsState()
    val report by vm.buzzReport.collectAsState()
    val scan by vm.scan.collectAsState()
    val brief by vm.brief.collectAsState()
    val quotesAt by vm.quotesAt.collectAsState()

    // Reddit buzz refreshes on open (cached 10 minutes); quotes refresh as soon as the ledger loads.
    LaunchedEffect(Unit) {
        vm.loadBuzz(); vm.refreshQuotes()
        // While the market's open, keep prices and option marks fresh without a tap.
        while (true) {
            delay(5 * 60_000L)
            if (MarketClock.session(ZonedDateTime.now()).phase == MarketSession.Phase.OPEN) { vm.refreshQuotes(); vm.loadBuzz() }
        }
    }

    var model by remember(settings.defaultModel) { mutableStateOf(settings.defaultModel) }
    var why by remember { mutableStateOf<String?>(null) }
    why?.let { WhyDialog(vm, it) { why = null } }
    var focus by rememberSaveable { mutableStateOf("") }

    // Stock at last quote; options at the current mid when known, otherwise at their open price.
    val stockValue = ledger.positions.filter { it.instrument.type == AssetType.STOCK }.sumOf { p ->
        (quotes[p.instrument.ticker]?.price ?: p.avgCost) * p.quantity
    }
    val optionValue = ledger.positions.filter { it.instrument.isOption }.sumOf { p ->
        marks[p.instrument]?.let { it.mark * p.quantity * 100 } ?: p.basis
    }
    val equity = ledger.cash + stockValue + optionValue
    val now = LocalDate.now()
    val monthRealized = ledger.realizedBetween(now.withDayOfMonth(1), now)
    val alerts = alerts(ledger, quotes, marks, now)
    val checks = (buzz as? Work.Done)?.value?.checks.orEmpty()
    val notes = (report as? Work.Done)?.value?.report?.notes.orEmpty().associateBy { it.ticker.uppercase() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
                Text("Colvin Trades", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                InboxBell(vm, openInbox)
                MarketClockPill()
            }
            InfoCard {
                Text("Account value (est.)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(equity), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                if (settings.startingCash > 0) {
                    val change = equity - settings.startingCash
                    Text("${signedMoney(change)} (${pct(change / settings.startingCash * 100)}) since start", color = pnlColor(change))
                }
                Spacer(Modifier.height(8.dp))
                CashRows(ledger, settings.margin, quotes.mapValues { it.value.price })
                KeyValue("Realized this month", signedMoney(monthRealized), pnlColor(monthRealized))
                KeyValue("Claude spend this month", money(spend))
                KeyValue("Risk setting", settings.risk.label)
                if (settings.startingCash == 0.0) {
                    Muted("Set your starting cash in Settings so cash and buying power are right.", Modifier.padding(top = 6.dp))
                }
            }
        }

        item {
            SectionTitle("Needs you")
            InfoCard(Modifier.testTag("home_alerts")) {
                if (alerts.isEmpty()) Muted("Nothing needs a decision right now.")
                // Each alert opens straight to what can be done about it.
                alerts.forEach { a ->
                    Text(
                        "• ${a.text}${if (a.instrument != null) "  ›" else ""}", style = MaterialTheme.typography.bodyMedium,
                        color = if (a.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { vm.focusPosition.value = a.instrument; openPositions() }.padding(vertical = 4.dp),
                    )
                }
            }
        }

        val held = ledger.positions.sortedWith(compareBy({ it.instrument.ticker }, { it.instrument.isOption }, { it.instrument.expiry }))
        if (held.isNotEmpty()) item {
            SectionTitle("Holdings")
            InfoCard(Modifier.testTag("home_holdings")) {
                held.forEach { p ->
                    val inst = p.instrument
                    val q = quotes[inst.ticker]
                    Row(
                        Modifier.fillMaxWidth().clickable { if (inst.isOption) vm.focusPosition.value = inst; openPositions() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (inst.isOption) inst.label.removePrefix(inst.ticker + " ") else inst.ticker, fontWeight = if (inst.isOption) FontWeight.Normal else FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (!inst.isOption) {
                            Text("${qty(p.quantity)} @ ${q?.let { px(it.price) } ?: "?"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                            q?.changePercent?.let { Text(pct(it), color = pnlColor(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp)) }
                            q?.let { val u = (it.price - p.avgCost) * p.quantity; Text(signedMoney(u), color = pnlColor(u), style = MaterialTheme.typography.bodySmall) }
                            Text(
                                "Why?", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clickable { why = inst.ticker }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                            )
                        } else {
                            val dte = inst.expiry?.let { ChronoUnit.DAYS.between(now, it) }
                            Text("${if (p.quantity < 0) "short" else "long"} ${qty(abs(p.quantity))} · ${dte ?: "?"}d", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                            marks[inst]?.let { m ->
                                m.premiumCapturedPercent?.let { Text("${it.toInt()}% kept", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp)) }
                                Text(signedMoney(m.unrealized), color = pnlColor(m.unrealized), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                quotesAt?.let { Muted("Prices as of ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(clockTime)}" + if (MarketClock.session(ZonedDateTime.now()).phase == MarketSession.Phase.OPEN) " · refreshes every 5 min" else "") }
            }
        }

        item {
            SectionTitle("News on your names")
            InfoCard(Modifier.testTag("home_brief")) {
                when (val b = brief) {
                    is Work.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(b.step + "…")
                    }
                    is Work.Done -> {
                        Text(b.value.text, style = MaterialTheme.typography.bodyMedium)
                        Muted("${Instant.ofEpochMilli(b.value.at).atZone(ZoneId.systemDefault()).format(recDate)} · ${b.value.tickers.size} names · ${money(b.value.costUsd)}", Modifier.padding(top = 4.dp))
                    }
                    is Work.Failed -> Text(b.message, color = MaterialTheme.colorScheme.error)
                    else -> Muted("Haiku reads every source's latest headlines on what you hold and watch, plus the market, and says what matters. A fraction of a cent.")
                }
                if (brief !is Work.Running) TextButton(onClick = { vm.loadBrief() }, enabled = settings.hasApiKey) {
                    Text(if (brief is Work.Done) "Refresh the brief" else "Brief me")
                }
            }
        }

        val shots = checks.filter { it.longShot }.take(3)
        if (shots.isNotEmpty()) {
            item { SectionTitle("Long shots") }
            items(shots, key = { "home_shot_" + it.ticker }) { c -> LongShotCard(c, notes[c.ticker], vm) }
        }

        item {
            SectionTitle("Reddit buzz")
            InfoCard(Modifier.clickable { openWatch(2) }.testTag("home_buzz")) {
                when {
                    checks.isNotEmpty() -> checks.filter { !it.longShot }.take(4).forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(c.ticker, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
                            Text(
                                c.standouts.firstOrNull() ?: "${c.item.mentions} mentions",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1,
                            )
                            c.quote?.changePercent?.let { Text(pct(it), color = pnlColor(it), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    buzz is Work.Running -> Muted("Checking Reddit…")
                    buzz is Work.Failed -> Muted("Reddit didn't answer. Tap to retry.")
                    else -> Muted("Tap to see what Reddit is talking about.")
                }
                (report as? Work.Done)?.value?.report?.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                Text("Open Reddit ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            }
        }

        item {
            SectionTitle("Market read")
            InfoCard(Modifier.clickable { openWatch(1) }) {
                val saved = (scan as? Work.Done)?.value
                if (saved == null) Muted("No market scan yet. Run one from Watchlist › Market to find names worth watching.")
                else {
                    Text(saved.reply.marketRead)
                    if (saved.reply.picks.isNotEmpty()) Text("Picks: " + saved.reply.picks.joinToString(", ") { it.ticker }, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    Muted(Instant.ofEpochMilli(saved.at).atZone(ZoneId.systemDefault()).format(recDate), Modifier.padding(top = 4.dp))
                }
                Text("Open Market ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            }
        }

        item {
            SectionTitle("Ask Claude")
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
                            onClick = { vm.recommend(model, focus); focus = "" },
                            enabled = settings.hasApiKey,
                            modifier = Modifier.fillMaxWidth().testTag("ask_claude"),
                        ) { Text("Ask ${model.label} (${settings.risk.label.lowercase()} risk)") }
                        if (!settings.hasApiKey) Muted("Add your Anthropic API key in Settings first.", Modifier.padding(top = 6.dp))
                        if (alerts.any { it.urgent && it.text.contains("past expiry") }) {
                            Muted("Resolve the options past expiry first, or Claude works from stale positions.", Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }

        item { SectionTitle("Recent recommendations") }
        if (recs.isEmpty()) item { Muted("None yet.") }
        items(recs.take(8), key = { it.id }) { r ->
            RecRow(r, realized[r.id]) { openRec(r.id) }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** "Open · closes 4:00 PM" in the phone's own time zone, ticking every 30 seconds. */
@Composable
private fun MarketClockPill() {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = ZonedDateTime.now() } }
    val s = MarketClock.session(now)
    val local = s.nextChange.withZoneSameInstant(ZoneId.systemDefault())
    val sameDay = local.toLocalDate() == now.toLocalDate()
    val at = local.format(if (sameDay) clockTime else clockDay)
    val (dot, text) = when (s.phase) {
        MarketSession.Phase.OPEN -> Color(0xFF2E7D32) to "Open · closes $at${if (s.earlyClose) " (early)" else ""}"
        MarketSession.Phase.PRE -> Color(0xFFF9A825) to "Pre-market · opens $at"
        MarketSession.Phase.AFTER -> Color(0xFFF9A825) to "After hours · opens $at"
        MarketSession.Phase.CLOSED -> Color(0xFFC62828) to "Closed${s.holiday?.let { " ($it)" }.orEmpty()} · opens $at"
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.testTag("market_clock")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Surface(shape = CircleShape, color = dot, modifier = Modifier.size(8.dp)) {}
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** What on the account wants a decision, most urgent first. */
private fun alerts(ledger: LedgerState, quotes: Map<String, Quote>, marks: Map<Instrument, OptionMark>, today: LocalDate): List<Alert> {
    val out = mutableListOf<Alert>()
    for (p in ledger.positions.filter { it.instrument.isOption }) {
        val inst = p.instrument
        val exp = inst.expiry ?: continue
        val dte = ChronoUnit.DAYS.between(today, exp)
        val short = p.quantity < 0
        val q = quotes[inst.ticker]
        val k = inst.strike
        if (dte < 0) { out += Alert("${inst.label} is past expiry: mark it expired or assigned", true, inst); continue }
        val itm = short && q != null && k != null &&
            ((inst.type == AssetType.CALL && q.price >= k) || (inst.type == AssetType.PUT && q.price <= k))
        if (itm) out += Alert("${inst.label} is in the money (stock ${px(q!!.price)}): assignment likely", true, inst)
        if (dte <= 5) out += Alert("${inst.label} expires in $dte day${if (dte == 1L) "" else "s"}", false, inst)
        q?.nextEarnings?.takeIf { !it.isBefore(today) && !it.isAfter(exp) }?.let {
            out += Alert("${inst.ticker} reports earnings $it, before ${inst.label} expires", true, inst)
        }
        marks[inst]?.premiumCapturedPercent?.takeIf { short && it >= 80 && dte > 2 }?.let {
            out += Alert("${inst.label}: ${it.toInt()}% of the premium is captured, worth buying back?", false, inst)
        }
    }
    for (p in ledger.positions.filter { it.instrument.type == AssetType.STOCK }) {
        val n = ledger.coverableContracts(p.instrument.ticker)
        if (n > 0) out += Alert("${qty(abs(p.quantity))} ${p.instrument.ticker} shares: $n covered call${if (n > 1) "s" else ""} can be written", false)
    }
    return out.sortedByDescending { it.urgent }
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
