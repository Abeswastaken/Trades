package com.abeswastaken.trades.core.export

import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

/** What an import would do, shown before anything is saved. */
data class ImportPreview(
    /** New fills in date order, ready to insert. */
    val fills: List<Fill>,
    /** Rows that match a fill already in the ledger. */
    val duplicates: Int,
    /** Rows that aren't trades (dividends, transfers, interest), counted by action. */
    val ignored: Map<String, Int>,
    /** Trade rows that couldn't be read, with the reason. */
    val problems: List<String>,
)

/**
 * Reads Schwab's "Transactions" CSV export (Accounts > History > Export). Columns are found by header name:
 * Date, Action, Symbol, Description, Quantity, Price, Fees & Comm, Amount. Options look like "F 10/30/2026 13.50 C".
 * Not a broker link: the owner exports the file and shares it to the app.
 */
object SchwabImport {
    private val mdy = DateTimeFormatter.ofPattern("MM/dd/yyyy")
    private val dateRe = Regex("(\\d{1,2}/\\d{1,2}/\\d{4})")
    private val optionRe = Regex("^([A-Z][A-Z0-9./]*)\\s+(\\d{1,2}/\\d{1,2}/\\d{4})\\s+([\\d.]+)\\s+([CP])$")

    private enum class Kind { BUY, SELL, EXPIRED, ASSIGNED, EXERCISE }

    private val ACTIONS = mapOf(
        "buy" to Kind.BUY, "buy to open" to Kind.BUY, "buy to close" to Kind.BUY, "reinvest shares" to Kind.BUY,
        "sell" to Kind.SELL, "sell to open" to Kind.SELL, "sell to close" to Kind.SELL, "sell short" to Kind.SELL,
        "expired" to Kind.EXPIRED, "assigned" to Kind.ASSIGNED, "exchange or exercise" to Kind.EXERCISE,
    )

    fun preview(csv: String, existing: List<Fill>): ImportPreview {
        val rows = rows(csv)
        val header = rows.indexOfFirst { r -> r.any { it.trim().equals("Action", true) } && r.any { it.trim().equals("Symbol", true) } }
        require(header >= 0) { "This doesn't look like a Schwab transactions export (no Action and Symbol columns)." }
        val cols = rows[header].map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> cols.indexOf(n).takeIf { it >= 0 } }
        val cDate = col("date") ?: error("No Date column")
        val cAction = col("action")!!
        val cSymbol = col("symbol")!!
        val cQty = col("quantity", "qty") ?: error("No Quantity column")
        val cPrice = col("price")
        val cFees = col("fees & comm", "fees & commissions", "fees")
        val cAmount = col("amount")

        val ignored = sortedMapOf<String, Int>()
        val problems = mutableListOf<String>()
        data class Row(val date: LocalDate, val kind: Kind, val inst: Instrument, val qty: Double, val price: Double, val fees: Double, val amount: Double?, val line: String)
        val parsed = mutableListOf<Row>()
        for (r in rows.drop(header + 1)) {
            fun at(i: Int?) = i?.let { r.getOrNull(it) }?.trim().orEmpty()
            val action = at(cAction)
            if (action.isEmpty() || at(cDate).startsWith("Transactions Total", true)) continue
            val kind = ACTIONS[action.lowercase()]
            if (kind == null) { ignored.merge(action, 1, Int::plus); continue }
            val line = r.joinToString(", ") { it.trim() }.take(90)
            try {
                val dates = dateRe.findAll(at(cDate)).map { LocalDate.parse(it.value.padDate(), mdy) }.toList()
                // "10/14/2026 as of 10/13/2026": the "as of" date is when it traded.
                val date = dates.lastOrNull() ?: error("no date")
                val inst = instrument(at(cSymbol)) ?: error("can't read symbol \"${at(cSymbol)}\"")
                val qty = abs(number(at(cQty)) ?: error("no quantity"))
                if (qty == 0.0) error("zero quantity")
                parsed += Row(date, kind, inst, qty, number(at(cPrice)) ?: 0.0, abs(number(at(cFees)) ?: 0.0), number(at(cAmount)), line)
            } catch (e: Exception) {
                problems += "$line (${e.message})"
            }
        }

        // Schwab lists newest first; replay oldest first so expirations know which way the position faced.
        val ordered = parsed.withIndex().sortedWith(compareBy({ it.value.date }, { -it.index })).map { it.value }
        val pool = existing.groupBy { key(it) }.mapValues { it.value.size }.toMutableMap()
        val out = mutableListOf<Fill>()
        var duplicates = 0
        for (row in ordered) {
            // Expirations, assignments and the option leg of an exercise close whatever is held, at 0.
            val closesOption = row.kind == Kind.EXPIRED || row.kind == Kind.ASSIGNED || (row.kind == Kind.EXERCISE && row.inst.isOption)
            val side = if (closesOption) {
                // Any date: the app dates an expiry on the Friday, Schwab often posts it the next business day.
                val held = (existing + out).filter { it.instrument == row.inst }.sumOf { it.signedQuantity }
                if (abs(held) < 1e-9) {
                    // Already closed (logged in the app) or never held: never open a position from this row.
                    duplicates++
                    continue
                }
                if (held < 0) Side.BUY else Side.SELL
            } else when (row.kind) {
                Kind.BUY -> Side.BUY
                Kind.SELL -> Side.SELL
                else -> if ((row.amount ?: 0.0) > 0) Side.SELL else Side.BUY
            }
            val price = if (closesOption) 0.0 else row.price
            val note = when (row.kind) {
                Kind.EXPIRED -> "Expired"
                Kind.ASSIGNED -> "Assigned"
                Kind.EXERCISE -> "Exercised"
                else -> "Schwab import"
            }
            val f = Fill(0, row.date, row.inst, side, row.qty, price, row.fees, note)
            val k = key(f)
            val left = pool[k] ?: 0
            if (left > 0) { pool[k] = left - 1; duplicates++; continue }
            out += f
        }
        // Flag anything that would leave short stock (shares sold that were bought before the export
        // window) or calls written on shares that aren't there. A cash account can't hold either.
        val held = mutableMapOf<Instrument, Double>()
        for (f in (existing + out).sortedWith(compareBy({ it.date }, { if (it.id == 0L) Long.MAX_VALUE else it.id }))) {
            held.merge(f.instrument, f.signedQuantity, Double::plus)
            if (f.id != 0L) continue
            val stock = held[Instrument.stock(f.instrument.ticker)] ?: 0.0
            if (f.instrument.type == AssetType.STOCK && stock < -1e-9) {
                problems += "${f.date} ${f.side} ${fmt(f.quantity)} ${f.instrument.ticker} leaves ${fmt(-stock)} shares short: were they bought before this export starts? Log that buy first."
            }
            if (f.instrument.type == AssetType.CALL && f.side == Side.SELL) {
                val short = held.filterKeys { it.ticker == f.instrument.ticker && it.type == AssetType.CALL }.values.filter { it < 0 }.sum()
                if (-short * 100 > stock + 1e-9) problems += "${f.date} ${f.instrument.label}: more calls written than shares held (${fmt(stock)} shares)."
            }
        }
        return ImportPreview(out, duplicates, ignored, problems)
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    /** Same day, contract, side, size and price (to the cent) counts as the same fill. */
    private fun key(f: Fill) = listOf(f.date, f.instrument, f.side, round(f.quantity * 10000), round(f.price * 100))

    fun instrument(symbol: String): Instrument? {
        val s = symbol.trim().uppercase()
        if (s.isEmpty()) return null
        optionRe.find(s)?.let { m ->
            val (t, d, k, cp) = m.destructured
            return Instrument(t, if (cp == "C") AssetType.CALL else AssetType.PUT, k.toDouble(), LocalDate.parse(d.padDate(), mdy))
        }
        return if (s.matches(Regex("[A-Z][A-Z0-9./]{0,9}"))) Instrument.stock(s) else null
    }

    private fun String.padDate(): String = split("/").let { (m, d, y) -> "${m.padStart(2, '0')}/${d.padStart(2, '0')}/$y" }

    /** "$1,234.56", "-$0.66", "(12.00)" and "1,000" all read as numbers. */
    fun number(s: String): Double? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val negative = t.startsWith("-") || (t.startsWith("(") && t.endsWith(")"))
        val v = t.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
        return if (negative) -v else v
    }

    /** Minimal CSV reader: quoted fields, doubled quotes, commas inside quotes. */
    fun rows(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val t = text.removePrefix("﻿")
        while (i < t.length) {
            val c = t[i]
            when {
                quoted && c == '"' && t.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && t.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotBlank() }) out += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) out += row
        return out
    }
}
