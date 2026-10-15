package com.abeswastaken.trades.core.model

import java.time.LocalDate

enum class AssetType { STOCK, CALL, PUT }

enum class Side { BUY, SELL }

/** One instrument: a stock, or a specific option contract on it. */
data class Instrument(
    val ticker: String,
    val type: AssetType = AssetType.STOCK,
    val strike: Double? = null,
    val expiry: LocalDate? = null,
) {
    val isOption get() = type != AssetType.STOCK

    /** Shares per unit: 1 for stock, 100 for an option contract. */
    val multiplier get() = if (isOption) 100 else 1

    val label: String
        get() = if (!isOption) ticker
        else "$ticker ${expiry} ${fmtStrike(strike ?: 0.0)}${if (type == AssetType.CALL) "C" else "P"}"

    companion object {
        fun stock(ticker: String) = Instrument(ticker.uppercase())
        fun call(ticker: String, strike: Double, expiry: LocalDate) = Instrument(ticker.uppercase(), AssetType.CALL, strike, expiry)
        fun put(ticker: String, strike: Double, expiry: LocalDate) = Instrument(ticker.uppercase(), AssetType.PUT, strike, expiry)
    }
}

private fun fmtStrike(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

/**
 * A fill as entered by hand from Schwab. [price] is per share (an option premium of 0.14 means
 * $14 per contract). [quantity] is shares for stock and contracts for options.
 */
data class Fill(
    val id: Long,
    val date: LocalDate,
    val instrument: Instrument,
    val side: Side,
    val quantity: Double,
    val price: Double,
    val fees: Double = 0.0,
    val note: String = "",
    val recommendationId: Long? = null,
    /** Insertion order, used to break ties between fills on the same date. */
    val seq: Long = id,
) {
    val signedQuantity get() = if (side == Side.BUY) quantity else -quantity
    /** Cash effect of this fill (positive = cash in). */
    val cashFlow get() = (if (side == Side.SELL) 1 else -1) * price * quantity * instrument.multiplier - fees
}
