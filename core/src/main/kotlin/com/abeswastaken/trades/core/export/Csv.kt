package com.abeswastaken.trades.core.export

import com.abeswastaken.trades.core.ledger.ClosedLot
import com.abeswastaken.trades.core.model.Fill

object Csv {
    private fun esc(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun row(vararg v: Any?) = v.joinToString(",") { esc(it) }

    fun fills(fills: List<Fill>): String = buildString {
        appendLine(row("id", "date", "ticker", "type", "strike", "expiry", "side", "quantity", "price", "fees", "cash_flow", "recommendation_id", "note"))
        fills.sortedWith(compareBy({ it.date }, { it.seq })).forEach { f ->
            appendLine(row(
                f.id, f.date, f.instrument.ticker, f.instrument.type, f.instrument.strike, f.instrument.expiry,
                f.side, f.quantity, f.price, f.fees, "%.2f".format(java.util.Locale.US, f.cashFlow), f.recommendationId, f.note,
            ))
        }
    }

    fun closedLots(lots: List<ClosedLot>): String = buildString {
        appendLine(row("instrument", "direction", "quantity", "open_date", "close_date", "open_price", "close_price", "realized", "wash_sale", "wash_disallowed"))
        lots.sortedBy { it.closeDate }.forEach { l ->
            appendLine(row(
                l.instrument.label, if (l.wasShort) "short" else "long", l.quantity, l.openDate, l.closeDate,
                "%.4f".format(java.util.Locale.US, l.openPrice), l.closePrice,
                "%.2f".format(java.util.Locale.US, l.realized), l.washSale, "%.2f".format(java.util.Locale.US, l.washDisallowed),
            ))
        }
    }
}
