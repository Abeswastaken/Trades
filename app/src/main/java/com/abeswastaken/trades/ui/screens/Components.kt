package com.abeswastaken.trades.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

private val usd = NumberFormat.getCurrencyInstance(Locale.US)

fun money(v: Double): String = (if (v < -0.004) "-" else "") + usd.format(abs(v))
fun signedMoney(v: Double): String = (if (v > 0.004) "+" else "") + money(v)
fun px(v: Double): String = String.format(Locale.US, "%.2f", v)
fun qty(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.4f", v).trimEnd('0')
fun pct(v: Double): String = String.format(Locale.US, "%+.1f%%", v)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(
            value, color = valueColor, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * Cash, or on a margin account the loan and an estimated buying power. Negative cash on a cash
 * account usually means a deposit or margin wasn't recorded, so it says where to fix that.
 */
@Composable
fun CashRows(ledger: com.abeswastaken.trades.core.ledger.LedgerState, margin: Boolean, prices: Map<String, Double>) {
    when {
        margin && ledger.cash < 0 -> KeyValue("Margin loan", money(-ledger.cash))
        else -> KeyValue("Cash", money(ledger.cash), if (ledger.cash < 0) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
    if (margin) KeyValue("Buying power (est.)", money(ledger.buyingPower(true, prices)))
    else if (ledger.cash < 0) Muted("Cash is below zero. If you trade on margin, turn on Margin account in Settings; if you added money, raise starting cash.")
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

/** Writes [text] to a cache file and opens the share sheet for it. */
fun shareText(context: Context, fileName: String, mime: String, text: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName).apply { writeText(text) }
    shareFile(context, file, mime)
}

fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, file.name))
}
