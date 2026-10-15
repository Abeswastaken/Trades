package com.abeswastaken.trades.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.core.engine.BuzzCheck
import com.abeswastaken.trades.core.engine.BuzzNote
import com.abeswastaken.trades.core.engine.BuzzVerdict
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.Work
import com.abeswastaken.trades.ui.theme.pnlColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val stamp = DateTimeFormatter.ofPattern("MMM d, h:mm a")

/** Reddit chatter, checked against the real numbers. Free on open; Claude's read is one tap. */
@Composable
fun RedditTab(vm: AppViewModel) {
    val buzz by vm.buzz.collectAsState()
    val report by vm.buzzReport.collectAsState()
    val settings by vm.settings.collectAsState()
    val watch by vm.watchlist.collectAsState()
    LaunchedEffect(Unit) { vm.loadBuzz() }

    val checks = (buzz as? Work.Done)?.value?.checks.orEmpty()
    val notes = (report as? Work.Done)?.value?.report?.notes.orEmpty().associateBy { it.ticker.uppercase() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Muted(
                "What Reddit (r/wallstreetbets, r/stocks, r/options and more) is talking about, from ApeWisdom's mention counts, " +
                    "checked against Yahoo price, volume and real news.",
                Modifier.padding(top = 12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                when (val b = buzz) {
                    is Work.Running -> { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(b.step + "…") }
                    is Work.Failed -> Text("Couldn't load: ${b.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    is Work.Done -> Muted("Updated ${Instant.ofEpochMilli(b.value.at).atZone(ZoneId.systemDefault()).format(stamp)}", Modifier.weight(1f))
                    else -> {}
                }
                TextButton(onClick = { vm.loadBuzz(force = true) }, enabled = buzz !is Work.Running) { Text("Refresh") }
            }
        }

        item {
            InfoCard(Modifier.padding(top = 8.dp)) {
                Text("Claude's cross-check", fontWeight = FontWeight.SemiBold)
                when (val r = report) {
                    is Work.Running -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(r.step + "…")
                    }
                    is Work.Done -> {
                        Text(r.value.report.summary, modifier = Modifier.padding(top = 4.dp))
                        Muted("${Instant.ofEpochMilli(r.value.at).atZone(ZoneId.systemDefault()).format(stamp)} · ${money(r.value.costUsd)}")
                    }
                    is Work.Failed -> Text(r.message, color = MaterialTheme.colorScheme.error)
                    else -> Muted("${settings.defaultModel.label} reads the top posts next to the numbers and says what holds up. A few cents.")
                }
                if (report !is Work.Running) Button(
                    onClick = { vm.verifyBuzz(settings.defaultModel) }, enabled = settings.hasApiKey && checks.isNotEmpty(),
                    modifier = Modifier.padding(top = 8.dp).testTag("reddit_verify"),
                ) { Text("Cross-check with ${settings.defaultModel.label}") }
            }
        }

        val shots = checks.filter { it.longShot }
        if (shots.isNotEmpty()) {
            item { SectionTitle("Long shots") }
            items(shots, key = { "shot_" + it.ticker }) { c -> LongShotCard(c, notes[c.ticker]) }
        }
        item { SectionTitle("Most talked about") }
        if (checks.isEmpty() && buzz is Work.Done) item { Muted("Nothing came back from Reddit right now.") }
        items(checks.filter { !it.longShot }, key = { "buzz_" + it.ticker }) { c ->
            BuzzCard(c, notes[c.ticker], watched = c.ticker in watch, onWatch = { vm.addWatch(c.ticker) }) {
                vm.askAbout(c.ticker, askNote(c))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

fun askNote(c: BuzzCheck) =
    "Reddit is talking about ${c.ticker}: ${c.standouts.joinToString("; ")}. Check whether the numbers back it, " +
        "and recommend a trade on ${c.ticker} that fits my risk setting, or HOLD."

/** The "black mark": a crowd pile-in on a stock already making a huge move. */
@Composable
fun LongShotCard(c: BuzzCheck, note: BuzzNote?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("longshot_${c.ticker}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E0E), contentColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE53935)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("■ LONG SHOT", color = Color(0xFFFF5252), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("lottery-ticket odds", color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(c.ticker, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                c.quote?.let { q ->
                    Text(
                        px(q.price) + (q.changePercent?.let { "  " + pct(it) } ?: ""),
                        color = q.changePercent?.let { if (it >= 0) Color(0xFF69F0AE) else Color(0xFFFF8A80) } ?: Color.White,
                    )
                }
            }
            c.longShotWhy?.let { Text(it, color = Color(0xFFE0E0E0)) }
            c.standouts.forEach { Text("• $it", color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodySmall) }
            note?.let {
                Text("Claude: ${it.checksOut}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                if (it.redFlags.isNotBlank()) Text("Red flags: ${it.redFlags}", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BuzzCard(c: BuzzCheck, note: BuzzNote?, watched: Boolean, onWatch: () -> Unit, onAsk: () -> Unit) {
    val i = c.item
    InfoCard(Modifier.padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${c.ticker}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                i.name?.let { Muted(it) }
            }
            Column(horizontalAlignment = Alignment.End) {
                c.quote?.let { q ->
                    Text(px(q.price))
                    q.changePercent?.let { Text(pct(it), color = pnlColor(it), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text(
            "#${i.rank} · ${i.mentions} mentions" +
                (i.mentions24hAgo?.let { " (was $it)" } ?: "") +
                (c.quote?.volumeRatio?.let { String.format(Locale.US, " · volume %.1fx", it) } ?: ""),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            c.verdict.label,
            color = when (c.verdict) {
                BuzzVerdict.CONFIRMED -> MaterialTheme.colorScheme.primary
                BuzzVerdict.HYPE_ONLY -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp),
        )
        c.standouts.forEach { Muted("• $it") }
        note?.let {
            Text("Crowd says: ${it.claim}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Text("Checks out: ${it.checksOut}", style = MaterialTheme.typography.bodySmall)
            if (it.redFlags.isNotBlank()) Text("Red flags: ${it.redFlags}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = onWatch, enabled = !watched) { Text(if (watched) "Watching" else "Watch") }
            Button(onClick = onAsk) { Text("Ask Claude") }
        }
    }
}
