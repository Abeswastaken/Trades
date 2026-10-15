package com.abeswastaken.trades.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.engine.MarketPick
import com.abeswastaken.trades.core.scanner.ScreenerHit
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.Work
import com.abeswastaken.trades.ui.RecRun
import com.abeswastaken.trades.ui.theme.pnlColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val shortDate = DateTimeFormatter.ofPattern("MMM d")
private val stamp = DateTimeFormatter.ofPattern("MMM d, h:mm a")

/** Market-wide scan: Claude picks watchlist ideas from movers and the day's headlines. */
@Composable
fun MarketTab(vm: AppViewModel, openRec: (Long) -> Unit) {
    val settings by vm.settings.collectAsState()
    val scan by vm.scan.collectAsState()
    val news by vm.marketNews.collectAsState()
    val newsLoading by vm.marketNewsLoading.collectAsState()
    val watch by vm.watchlist.collectAsState()
    var model by remember(settings.defaultModel) { mutableStateOf(settings.defaultModel) }
    var focus by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadMarketNews() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            AskProgress(vm)
            InfoCard(Modifier.padding(top = 12.dp)) {
                Text("Scan the whole market", fontWeight = FontWeight.SemiBold)
                Muted(
                    "Pulls today's most active stocks, top gainers and losers, and trending names, keeps the ones that pass " +
                        "your price and volume rules, and has Claude pick the few worth watching from the market news.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    ClaudeModel.entries.forEach { m -> FilterChip(selected = model == m, onClick = { model = m }, label = { Text(m.label) }) }
                }
                OutlinedTextField(
                    focus, { focus = it }, label = { Text("Anything to look for? (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), maxLines = 3,
                )
                Spacer(Modifier.height(10.dp))
                when (val s = scan) {
                    is Work.Running -> Progress(s.step)
                    else -> Button(onClick = { vm.marketScan(model, focus) }, modifier = Modifier.fillMaxWidth().testTag("market_scan")) {
                        Text("Scan with ${model.label}")
                    }
                }
                if (scan is Work.Failed) Text((scan as Work.Failed).message, color = MaterialTheme.colorScheme.error)
            }
        }

        (scan as? Work.Done)?.value?.let { saved ->
            item {
                SectionTitle("Market read")
                InfoCard {
                    Text(saved.reply.marketRead)
                    Muted(
                        "${Instant.ofEpochMilli(saved.at).atZone(ZoneId.systemDefault()).format(stamp)} · " +
                            "${saved.candidates} stocks, ${saved.headlines} headlines · ${money(saved.costUsd)}",
                        Modifier.padding(top = 6.dp),
                    )
                }
                SectionTitle("Picks")
                if (saved.reply.picks.isEmpty()) Muted("Nothing cleared the bar today.")
            }
            items(saved.reply.picks, key = { "pick_" + it.ticker }) { p ->
                PickCard(p, watched = p.ticker in watch, onWatch = { vm.addWatch(p.ticker) }) {
                    vm.askAbout(
                        p.ticker,
                        "Market scan pick: ${p.ticker} (${p.idea}). ${p.thesis} Check it against the numbers and " +
                            "recommend a trade on ${p.ticker} if it holds up; HOLD if not.",
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Market news", Modifier.weight(1f))
                IconButton(onClick = { vm.loadMarketNews(force = true) }, enabled = !newsLoading) { Icon(Icons.Filled.Refresh, "Refresh news") }
            }
            Muted("From ${settings.newsSources.joinToString { it.label }}. Change sources in Settings.")
            Spacer(Modifier.height(6.dp))
            when {
                news == null || (newsLoading && news.isNullOrEmpty()) -> Muted("Loading headlines…")
                news!!.isEmpty() -> Muted("No headlines. Check your connection or news sources.")
            }
        }
        items(news.orEmpty(), key = { "news_" + it.title }) { h ->
            HeadlineRow(h)
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PickCard(p: MarketPick, watched: Boolean, onWatch: () -> Unit, onAsk: () -> Unit) {
    InfoCard(Modifier.padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.ticker, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AssistChip(onClick = {}, label = { Text(ideaLabel(p.idea)) })
            Spacer(Modifier.width(6.dp))
            Muted(p.confidence)
        }
        Text(p.thesis)
        p.catalyst?.takeIf { it.isNotBlank() }?.let { Muted("Catalyst: $it", Modifier.padding(top = 4.dp)) }
        Muted("Risk: ${p.risk}", Modifier.padding(top = 2.dp))
        WatchAskButtons(watched, onWatch, onAsk)
    }
}

private fun ideaLabel(idea: String) = when (idea) {
    "COVERED_CALL" -> "Covered call"
    "BUY" -> "Buy"
    else -> "Watch"
}

@Composable
private fun WatchAskButtons(watched: Boolean, onWatch: () -> Unit, onAsk: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = onWatch, enabled = !watched) { Text(if (watched) "Watching" else "+ Watch") }
        Button(onClick = onAsk) { Text("Ask Claude") }
    }
}

/** Shows when an "Ask Claude" from these tabs is running, since the recommendation opens when it's done. */
@Composable
private fun AskProgress(vm: AppViewModel) {
    val run by vm.recRun.collectAsState()
    when (val r = run) {
        is RecRun.Running -> InfoCard(Modifier.padding(top = 12.dp)) { Progress("Recommendation: ${r.step}") }
        is RecRun.Failed -> InfoCard(Modifier.padding(top = 12.dp)) { Text(r.message, color = MaterialTheme.colorScheme.error) }
        else -> {}
    }
}

@Composable
private fun Progress(step: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text("$step…")
    }
}

/** "Which stocks between $x and $y have weekly calls worth writing?" No Claude, so it's free. */
@Composable
fun ScreenerTab(vm: AppViewModel, openRec: (Long) -> Unit) {
    val settings by vm.settings.collectAsState()
    val ledger by vm.ledger.collectAsState()
    val job by vm.screener.collectAsState()
    val watch by vm.watchlist.collectAsState()
    val r = settings.screener
    var minPrice by remember(r) { mutableStateOf(plain(r.minPrice)) }
    var maxPrice by remember(r) { mutableStateOf(plain(r.maxPrice)) }
    var maxDte by remember(r) { mutableStateOf(r.maxDte.toString()) }
    var minVol by remember(r) { mutableStateOf(r.minAvgVolume.toString()) }
    var otm by remember(r) { mutableStateOf(plain(r.minOtmPercent)) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            AskProgress(vm)
            InfoCard(Modifier.padding(top = 12.dp)) {
                Text("Covered-call finder", fontWeight = FontWeight.SemiBold)
                Muted("Finds liquid stocks in your price range and the nearest out-of-the-money call to write on each. Plain code, no Claude cost.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    NumField("Min price", minPrice, { minPrice = it }, Modifier.weight(1f).testTag("scr_min"))
                    NumField("Max price", maxPrice, { maxPrice = it }, Modifier.weight(1f).testTag("scr_max"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    NumField("Max days to expiry", maxDte, { maxDte = it }, Modifier.weight(1f))
                    NumField("Min % OTM", otm, { otm = it }, Modifier.weight(1f))
                }
                NumField("Min avg daily volume", minVol, { minVol = it }, Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Weekly options only")
                        Muted("Skip names that only list monthly expiries.")
                    }
                    Switch(r.weekliesOnly, { v -> vm.updateSettings { copy(screener = screener.copy(weekliesOnly = v)) } })
                }
                Spacer(Modifier.height(10.dp))
                when (val j = job) {
                    is Work.Running -> Progress(j.step)
                    else -> Button(
                        onClick = {
                            vm.updateSettings {
                                copy(
                                    screener = screener.copy(
                                        minPrice = minPrice.toDoubleOrNull() ?: screener.minPrice,
                                        maxPrice = maxPrice.toDoubleOrNull() ?: screener.maxPrice,
                                        maxDte = maxDte.toIntOrNull()?.coerceIn(1, 60) ?: screener.maxDte,
                                        minAvgVolume = minVol.toLongOrNull() ?: screener.minAvgVolume,
                                        minOtmPercent = otm.toDoubleOrNull() ?: screener.minOtmPercent,
                                    )
                                )
                            }
                            vm.runScreener()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("screener_run"),
                    ) { Text("Find covered calls") }
                }
                if (job is Work.Failed) Text((job as Work.Failed).message, color = MaterialTheme.colorScheme.error)
            }
        }
        (job as? Work.Done)?.value?.let { res ->
            item {
                SectionTitle("${res.hits.size} found")
                Muted(
                    "Checked ${res.scanned} stocks from the ${res.universeSource}" +
                        (if (res.noWeeklies > 0) ", ${res.noWeeklies} had no weeklies" else "") +
                        (if (res.errors.isNotEmpty()) ", ${res.errors.size} chains didn't load" else "") +
                        ". Sorted by annualized premium.",
                )
                if (res.hits.isEmpty()) Muted("Nothing passed. Try a wider price range, more days, or turn off weeklies only.", Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(8.dp))
            }
            items(res.hits, key = { "hit_" + it.quote.symbol }) { h ->
                HitCard(h, cash = ledger.buyingPower(settings.margin, mapOf(h.quote.symbol to h.quote.price)), watched = h.quote.symbol in watch, onWatch = { vm.addWatch(h.quote.symbol) }) {
                    val o = h.option
                    vm.askAbout(
                        h.quote.symbol,
                        "Covered-call finder hit: buy 100 ${h.quote.symbol} near ${px(h.quote.price)} and sell the " +
                            "${o.expiry} ${px(o.strike)} call for about ${px(o.premium)} (${String.format(java.util.Locale.US, "%.1f", h.annualizedPercent)}% annualized). " +
                            "Check it and recommend it, a better strike or expiry, or HOLD.",
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HitCard(h: ScreenerHit, cash: Double, watched: Boolean, onWatch: () -> Unit, onAsk: () -> Unit) {
    val o = h.option
    InfoCard(Modifier.padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.quote.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                h.quote.name?.let { Muted(it) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(px(h.quote.price))
                h.quote.changePercent?.let { Text(pct(it), color = pnlColor(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text(
            "Sell ${o.expiry.format(shortDate)} ${px(o.strike)}C · " +
                (if (o.liveBid) "bid ${px(o.bid)}" else "last ${px(o.premium)}") + " · ${h.dte} days",
            modifier = Modifier.padding(top = 6.dp),
        )
        KeyValue("Premium", String.format(java.util.Locale.US, "%.2f%% (%.0f%% a year)", h.premiumPercent, h.annualizedPercent))
        KeyValue("If called away", String.format(java.util.Locale.US, "%.2f%%", h.ifCalledPercent))
        KeyValue("100 shares less premium", money(h.netCostPer100))
        if (o.liveBid) KeyValue("Open interest", o.openInterest.toString())
        val notes = listOfNotNull(
            if (h.weeklies) "weeklies" else "monthlies only",
            if (!o.liveBid) "market closed: premium is the last trade" else null,
            h.quote.nextEarnings?.takeIf { !it.isAfter(o.expiry) }?.let { "earnings ${it.format(shortDate)} before expiry" },
            if (cash > 0 && h.netCostPer100 > cash) "more than your cash" else null,
        )
        Muted(notes.joinToString(" · "))
        WatchAskButtons(watched, onWatch, onAsk)
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) }, label = { Text(label) }, singleLine = true,
        modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun plain(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
