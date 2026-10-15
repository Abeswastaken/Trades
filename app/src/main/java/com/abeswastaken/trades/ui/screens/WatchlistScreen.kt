package com.abeswastaken.trades.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.theme.pnlColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(vm: AppViewModel, openRec: (Long) -> Unit) {
    var tab by vm.watchTab.collectAsMutableInt()
    val loading by vm.quotesLoading.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Watchlist") },
            actions = {
                if (tab == 0) IconButton(onClick = { vm.refreshQuotes() }, enabled = !loading) { Icon(Icons.Filled.Refresh, "Refresh") }
            },
        )
        androidx.compose.material3.ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            listOf("Watchlist", "Market", "Reddit", "Covered calls").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }, modifier = Modifier.testTag("watch_tab_$i"))
            }
        }
        when (tab) {
            0 -> WatchTab(vm)
            1 -> MarketTab(vm, openRec)
            2 -> RedditTab(vm)
            else -> ScreenerTab(vm, openRec)
        }
    }
}

/** Reads and writes an Int StateFlow as Compose state (the sub-tab lives in the ViewModel so Home can pick it). */
@Composable
private fun kotlinx.coroutines.flow.MutableStateFlow<Int>.collectAsMutableInt(): androidx.compose.runtime.MutableState<Int> {
    val v by collectAsState()
    val flow = this
    return remember(v) {
        object : androidx.compose.runtime.MutableState<Int> {
            override var value: Int
                get() = v
                set(x) { flow.value = x }
            override fun component1() = v
            override fun component2(): (Int) -> Unit = { flow.value = it }
        }
    }
}

@Composable
private fun WatchTab(vm: AppViewModel) {
    val watch by vm.watchlist.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val news by vm.news.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(watch) { vm.refreshQuotes() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val add = { if (input.isNotBlank()) { vm.addWatch(input); input = "" } }
            OutlinedTextField(
                input, { input = it.uppercase() }, label = { Text("Add ticker") }, singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            Spacer(Modifier.padding(4.dp))
            Button(onClick = add) { Text("Add") }
        }
        Muted(
            "Claude looks at these plus everything you hold. Tap one for headlines.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            if (watch.isEmpty()) item { Muted("Empty. Add a few tickers you'd be happy to own.") }
            items(watch, key = { it }) { sym ->
                val q = quotes[sym]
                Column(
                    Modifier.fillMaxWidth().clickable {
                        open = if (open == sym) null else sym
                        if (open == sym) vm.loadNews(sym)
                    }.padding(vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sym, fontWeight = FontWeight.SemiBold)
                            q?.name?.let { Muted(it) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(q?.let { px(it.price) } ?: "–")
                            q?.changePercent?.let { Text(pct(it), color = pnlColor(it), style = MaterialTheme.typography.bodySmall) }
                        }
                        IconButton(onClick = { vm.removeWatch(sym) }) { Icon(Icons.Filled.Close, "Remove $sym") }
                    }
                    q?.nextEarnings?.let { Muted("Earnings $it") }
                    if (open == sym) {
                        val list = news[sym]
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when {
                                list == null -> Muted("Loading headlines…")
                                list.isEmpty() -> Muted("No headlines found.")
                                else -> list.forEach { h -> HeadlineRow(h) }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** One headline: tap to open it; shows the outlet, age and sentiment when the source scores stories. */
@Composable
fun HeadlineRow(h: com.abeswastaken.trades.core.market.Headline) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clickable(enabled = h.url != null) {
            h.url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
        }.padding(vertical = 4.dp)
    ) {
        Text(h.title, style = MaterialTheme.typography.bodySmall)
        val meta = listOfNotNull(
            h.source.takeIf { it.isNotBlank() },
            h.publishedEpochSec?.let { age(it) },
            h.sentiment?.let { String.format(java.util.Locale.US, "sentiment %+.2f", it) },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Muted(meta)
    }
}

private fun age(epochSec: Long): String {
    val mins = (System.currentTimeMillis() / 1000 - epochSec) / 60
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 48 * 60 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}
