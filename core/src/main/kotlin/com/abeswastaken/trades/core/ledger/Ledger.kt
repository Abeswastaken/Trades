package com.abeswastaken.trades.core.ledger

import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min

/** An open lot. Positive quantity is long, negative is short (e.g. a sold covered call). */
data class OpenLot(
    val instrument: Instrument,
    val openFillId: Long,
    val openDate: LocalDate,
    val quantity: Double,
    /** Per-share price including the opening fee spread over the lot. */
    val costPerShare: Double,
) {
    val isShort get() = quantity < 0
    /** Total basis in dollars (negative for a short lot = premium received). */
    val basis get() = quantity * costPerShare * instrument.multiplier
}

data class ClosedLot(
    val instrument: Instrument,
    val openFillId: Long,
    val closeFillId: Long,
    val openDate: LocalDate,
    val closeDate: LocalDate,
    /** Always positive; [wasShort] says which direction. */
    val quantity: Double,
    val wasShort: Boolean,
    val openPrice: Double,
    val closePrice: Double,
    val realized: Double,
    val washSale: Boolean = false,
    /** Loss disallowed by the wash-sale rule (positive number). */
    val washDisallowed: Double = 0.0,
)

data class Position(
    val instrument: Instrument,
    val quantity: Double,
    val avgCost: Double,
    val lots: List<OpenLot>,
) {
    val basis get() = lots.sumOf { it.basis }
}

data class LedgerState(
    val openLots: List<OpenLot>,
    val closedLots: List<ClosedLot>,
    val cash: Double,
    val positions: List<Position>,
) {
    val realizedTotal get() = closedLots.sumOf { it.realized }
    fun realizedBetween(from: LocalDate, to: LocalDate) =
        closedLots.filter { !it.closeDate.isBefore(from) && !it.closeDate.isAfter(to) }.sumOf { it.realized }

    /** Shares of [ticker] held long. */
    fun sharesHeld(ticker: String) = openLots
        .filter { it.instrument.ticker == ticker && it.instrument.type == AssetType.STOCK && !it.isShort }
        .sumOf { it.quantity }

    /** Short call contracts open on [ticker]. */
    fun shortCallContracts(ticker: String) = openLots
        .filter { it.instrument.ticker == ticker && it.instrument.type == AssetType.CALL && it.isShort }
        .sumOf { -it.quantity }

    /** Whole contracts that could still be written against held shares. */
    fun coverableContracts(ticker: String) =
        (sharesHeld(ticker) / 100).toInt() - shortCallContracts(ticker).toInt()
}

object Ledger {
    private const val EPS = 1e-9

    /** Replays every fill in order (FIFO) and returns the full state. Pure: same input, same output. */
    fun compute(fills: List<Fill>, startingCash: Double): LedgerState {
        val ordered = fills.sortedWith(compareBy<Fill> { it.date }.thenBy { it.seq })
        val books = LinkedHashMap<Instrument, ArrayDeque<OpenLot>>()
        val closed = mutableListOf<ClosedLot>()
        var cash = startingCash

        for (fill in ordered) {
            require(fill.quantity > 0) { "Fill ${fill.id} has non-positive quantity" }
            cash += fill.cashFlow
            val inst = fill.instrument
            val mult = inst.multiplier
            val book = books.getOrPut(inst) { ArrayDeque() }
            val sign = if (fill.side == Side.BUY) 1.0 else -1.0
            var remaining = fill.quantity
            val feePerUnit = fill.fees / fill.quantity

            // Close against opposite-signed lots first, oldest first.
            while (remaining > EPS && book.isNotEmpty() && book.first().quantity * sign < 0) {
                val lot = book.removeFirst()
                val lotQty = abs(lot.quantity)
                val matched = min(lotQty, remaining)
                val closeFee = feePerUnit * matched
                val realized = if (lot.isShort) {
                    (lot.costPerShare - fill.price) * matched * mult - closeFee
                } else {
                    (fill.price - lot.costPerShare) * matched * mult - closeFee
                }
                closed += ClosedLot(
                    instrument = inst, openFillId = lot.openFillId, closeFillId = fill.id,
                    openDate = lot.openDate, closeDate = fill.date, quantity = matched,
                    wasShort = lot.isShort, openPrice = lot.costPerShare, closePrice = fill.price,
                    realized = realized,
                )
                val left = lotQty - matched
                if (left > EPS) book.addFirst(lot.copy(quantity = if (lot.isShort) -left else left))
                remaining -= matched
            }

            if (remaining > EPS) {
                // Opening: fold the fee into the per-share basis (raises a long's cost, lowers a short's credit).
                val feePerShare = feePerUnit / mult
                val cost = if (sign > 0) fill.price + feePerShare else fill.price - feePerShare
                book.addLast(OpenLot(inst, fill.id, fill.date, sign * remaining, cost))
            }
        }

        val openLots = books.values.flatten()
        val positions = books.filterValues { it.isNotEmpty() }.map { (inst, lots) ->
            val qty = lots.sumOf { it.quantity }
            val avg = lots.sumOf { it.quantity * it.costPerShare } / qty
            Position(inst, qty, avg, lots.toList())
        }
        val flagged = WashSale.flag(closed, ordered)
        return LedgerState(openLots, flagged, cash, positions)
    }
}

/**
 * IRS §1091 wash-sale flagging for stock. A loss on a closed long stock lot is a wash sale when
 * shares of the same ticker were bought within 30 days before or after the sale (other than the
 * shares that made up the sold lot). Recomputed over the full history every time, so a rebuy
 * logged after the loss sale flips the flag retroactively.
 *
 * This is a flag for tax prep, not a full basis-adjustment engine.
 */
object WashSale {
    fun flag(closed: List<ClosedLot>, fills: List<Fill>): List<ClosedLot> {
        val stockBuys = fills.filter { it.instrument.type == AssetType.STOCK && it.side == Side.BUY }
        // Each replacement buy can only absorb as many shares as it has.
        val capacity = stockBuys.associate { it.id to it.quantity }.toMutableMap()

        return closed.sortedWith(compareBy({ it.closeDate }, { it.closeFillId })).map { lot ->
            if (lot.instrument.type != AssetType.STOCK || lot.wasShort || lot.realized >= 0) return@map lot
            val window = stockBuys.filter {
                it.instrument.ticker == lot.instrument.ticker &&
                    it.id != lot.openFillId &&
                    abs(ChronoUnit.DAYS.between(lot.closeDate, it.date)) <= 30 &&
                    (capacity[it.id] ?: 0.0) > 0
            }
            if (window.isEmpty()) return@map lot
            var need = lot.quantity
            var replaced = 0.0
            for (buy in window) {
                if (need <= 0) break
                val take = min(need, capacity[buy.id] ?: 0.0)
                capacity[buy.id] = (capacity[buy.id] ?: 0.0) - take
                replaced += take
                need -= take
            }
            if (replaced <= 0) lot
            else lot.copy(washSale = true, washDisallowed = -lot.realized * (replaced / lot.quantity))
        }
    }
}

/** Helpers that turn option events into the fills the ledger understands. */
object OptionEvents {
    /** A short option that expired worthless: buy it back at 0. */
    fun expire(lot: OpenLot, id: Long, date: LocalDate) = Fill(
        id = id, date = date, instrument = lot.instrument,
        side = if (lot.isShort) Side.BUY else Side.SELL,
        quantity = abs(lot.quantity), price = 0.0, note = "Expired",
    )

    /** A short call assigned: close the call at 0 and sell 100 shares per contract at the strike. */
    fun assignShortCall(lot: OpenLot, contracts: Double, firstId: Long, date: LocalDate): List<Fill> {
        require(lot.instrument.type == AssetType.CALL && lot.isShort) { "Only short calls can be assigned here" }
        val strike = lot.instrument.strike ?: error("Call has no strike")
        return listOf(
            Fill(firstId, date, lot.instrument, Side.BUY, contracts, 0.0, note = "Assigned"),
            Fill(firstId + 1, date, Instrument.stock(lot.instrument.ticker), Side.SELL, contracts * 100, strike, note = "Called away"),
        )
    }
}
