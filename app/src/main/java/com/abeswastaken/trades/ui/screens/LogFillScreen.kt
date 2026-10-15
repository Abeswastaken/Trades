package com.abeswastaken.trades.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Side
import com.abeswastaken.trades.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogFillScreen(vm: AppViewModel, done: () -> Unit) {
    val initial by vm.draft.collectAsState()
    var d by remember(initial) { mutableStateOf(initial) }
    val preview = d.toFill()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Log fill") },
            navigationIcon = { IconButton(onClick = done) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            d.recommendationSummary?.let {
                InfoCard {
                    Text("From recommendation", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(it)
                    Muted("Check the numbers against what actually filled in Schwab.")
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val types = listOf(AssetType.STOCK to "Stock", AssetType.CALL to "Call", AssetType.PUT to "Put")
                types.forEachIndexed { i, (t, label) ->
                    SegmentedButton(
                        selected = d.type == t, onClick = { d = d.copy(type = t) },
                        shape = SegmentedButtonDefaults.itemShape(i, types.size),
                    ) { Text(label) }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val sides = listOf(Side.BUY to "Buy", Side.SELL to "Sell")
                sides.forEachIndexed { i, (s, label) ->
                    SegmentedButton(
                        selected = d.side == s, onClick = { d = d.copy(side = s) },
                        shape = SegmentedButtonDefaults.itemShape(i, sides.size),
                    ) { Text(label) }
                }
            }

            OutlinedTextField(
                d.ticker, { d = d.copy(ticker = it.uppercase()) }, label = { Text("Ticker") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            if (d.type != AssetType.STOCK) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(d.strike, { d = d.copy(strike = it) }, "Strike", Modifier.weight(1f))
                    OutlinedTextField(
                        d.expiry, { d = d.copy(expiry = it) }, label = { Text("Expiry (YYYY-MM-DD)") },
                        singleLine = true, modifier = Modifier.weight(1.4f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(d.quantity, { d = d.copy(quantity = it) }, if (d.type == AssetType.STOCK) "Shares" else "Contracts", Modifier.weight(1f))
                NumberField(d.price, { d = d.copy(price = it) }, if (d.type == AssetType.STOCK) "Price" else "Premium / share", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(d.fees, { d = d.copy(fees = it) }, "Fees", Modifier.weight(1f))
                OutlinedTextField(
                    d.date, { d = d.copy(date = it) }, label = { Text("Date") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(d.note, { d = d.copy(note = it) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())

            preview.onSuccess { f -> Muted("Cash effect: ${signedMoney(f.cashFlow)}") }
                .onFailure { Muted(it.message ?: "") }

            Button(onClick = { vm.saveDraft(d, done) }, enabled = preview.isSuccess, modifier = Modifier.fillMaxWidth()) {
                Text("Save fill")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
