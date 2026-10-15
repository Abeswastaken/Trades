package com.abeswastaken.trades.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.core.export.Csv
import com.abeswastaken.trades.core.ledger.ClosedLot
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Side
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.theme.pnlColor
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LedgerScreen(vm: AppViewModel, openLog: () -> Unit) {
    val fills by vm.fills.collectAsState()
    val ledger by vm.ledger.collectAsState()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Fill?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Ledger") },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.IosShare, "Export") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Export fills (CSV)") }, onClick = {
                                menu = false
                                shareText(context, "fills-${LocalDate.now()}.csv", "text/csv", Csv.fills(fills))
                            })
                            DropdownMenuItem(text = { Text("Export closed trades (CSV)") }, onClick = {
                                menu = false
                                shareText(context, "closed-${LocalDate.now()}.csv", "text/csv", Csv.closedLots(ledger.closedLots))
                            })
                        }
                    }
                },
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Fills (${fills.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Closed (${ledger.closedLots.size})") })
            }
            LazyColumn(Modifier.padding(horizontal = 16.dp)) {
                if (tab == 0) {
                    if (fills.isEmpty()) item { Muted("No fills yet. Tap + to log one.", Modifier.padding(top = 16.dp)) }
                    items(fills, key = { it.id }) { f ->
                        FillRow(f, Modifier.combinedClickable(onClick = {}, onLongClick = { deleting = f }))
                        HorizontalDivider()
                    }
                } else {
                    item {
                        InfoCard(Modifier.padding(top = 12.dp)) {
                            KeyValue("Realized total", signedMoney(ledger.realizedTotal), pnlColor(ledger.realizedTotal), bold = true)
                            val wash = ledger.closedLots.filter { it.washSale }
                            if (wash.isNotEmpty()) KeyValue("Wash-sale losses disallowed", money(wash.sumOf { it.washDisallowed }))
                        }
                    }
                    if (ledger.closedLots.isEmpty()) item { Muted("Nothing closed yet.", Modifier.padding(top = 16.dp)) }
                    items(ledger.closedLots.sortedByDescending { it.closeDate }) { c ->
                        ClosedRow(c)
                        HorizontalDivider()
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { vm.startDraft(); openLog() },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Log fill") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this fill?") },
            text = { Text("${f.date}: ${f.side} ${qty(f.quantity)} ${f.instrument.label} @ ${px(f.price)}. The ledger is recalculated without it.") },
            confirmButton = { TextButton(onClick = { vm.deleteFill(f.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FillRow(f: Fill, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${if (f.side == Side.BUY) "Buy" else "Sell"} ${qty(f.quantity)} ${f.instrument.label}", Modifier.weight(1f))
            Text(signedMoney(f.cashFlow), color = pnlColor(f.cashFlow))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Muted(f.date.toString())
            Muted("@ ${px(f.price)}")
            if (f.fees > 0) Muted("fees ${money(f.fees)}")
            if (f.recommendationId != null) Muted("from rec")
            if (f.note.isNotBlank()) Muted(f.note)
        }
    }
}

@Composable
private fun ClosedRow(c: ClosedLot) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${qty(c.quantity)} ${c.instrument.label}${if (c.wasShort) " (short)" else ""}", Modifier.weight(1f))
            Text(signedMoney(c.realized), color = pnlColor(c.realized))
        }
        Muted("${c.openDate} @ ${px(c.openPrice)} → ${c.closeDate} @ ${px(c.closePrice)}")
        if (c.washSale) {
            Text(
                "Wash sale: ${money(c.washDisallowed)} of this loss is disallowed",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
