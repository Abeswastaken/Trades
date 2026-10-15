package com.abeswastaken.trades.core.scanner

import com.abeswastaken.trades.core.ledger.LedgerState
import com.abeswastaken.trades.core.market.MarketData
import com.abeswastaken.trades.core.market.OptionQuote
import com.abeswastaken.trades.core.market.Quote
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Filter thresholds. Editable in Settings; defaults match the spec. */
data class ScanRules(
    val minPrice: Double = 2.0,
    val maxPrice: Double = 60.0,
    val minAvgVolume: Long = 500_000,
    val minDte: Int = 7,
    val maxDte: Int = 45,
    val minOtmPercent: Double = 2.0,
    val minOpenInterest: Long = 100,
    val minBid: Double = 0.05,
    val minPremiumPercent: Double = 0.5,
    val maxCandidates: Int = 8,
)

enum class CandidateKind {
    /** Write a call against shares already held. */
    COVERED_CALL,
    /** Buy 100 shares and write a call in the same go. */
    BUY_WRITE,
}

data class CallCandidate(
    val kind: CandidateKind,
    val quote: Quote,
    val option: OptionQuote,
    val dte: Long,
    /** Bid as a percent of the stock price. */
    val premiumPercent: Double,
    /** Premium percent scaled to a year. */
    val annualizedPercent: Double,
    val otmPercent: Double,
    val contracts: Int,
    /** For buy-writes: cash needed per contract (100 shares minus the premium). */
    val netCostPerContract: Double,
)

data class ScanResult(
    val candidates: List<CallCandidate>,
    val rejectedUnderlyings: Map<String, String>,
    val errors: Map<String, String>,
)

/**
 * Plain-code pre-filter: everything here is free, so Claude only sees contracts that already
 * pass the rules (no tokens spent rejecting illiquid or out-of-budget names).
 */
object CoveredCallScanner {

    /** Why a stock is not worth scanning, or null if it passes. */
    fun rejectUnderlying(q: Quote, rules: ScanRules, held: Boolean): String? = when {
        held -> null // Always consider calls on what we already own.
        q.price < rules.minPrice -> "price below ${rules.minPrice}"
        q.price > rules.maxPrice -> "price above ${rules.maxPrice}"
        (q.avgVolume ?: 0) < rules.minAvgVolume -> "average volume below ${rules.minAvgVolume}"
        else -> null
    }

    fun passesContract(q: Quote, o: OptionQuote, today: LocalDate, rules: ScanRules): Boolean {
        val dte = ChronoUnit.DAYS.between(today, o.expiry)
        if (dte < rules.minDte || dte > rules.maxDte) return false
        if (o.strike < q.price * (1 + rules.minOtmPercent / 100)) return false
        if (!o.meetsOpenInterest(rules.minOpenInterest)) return false
        if (o.premium < rules.minBid) return false
        if (o.premium / q.price * 100 < rules.minPremiumPercent) return false
        return true
    }

    fun toCandidate(kind: CandidateKind, q: Quote, o: OptionQuote, today: LocalDate, contracts: Int): CallCandidate {
        val dte = ChronoUnit.DAYS.between(today, o.expiry).coerceAtLeast(1)
        val prem = o.premium / q.price * 100
        return CallCandidate(
            kind = kind, quote = q, option = o, dte = dte,
            premiumPercent = prem,
            annualizedPercent = prem * 365.0 / dte,
            otmPercent = (o.strike / q.price - 1) * 100,
            contracts = contracts,
            netCostPerContract = (q.price - o.premium) * 100,
        )
    }

    /**
     * Pure ranking step, separated so it can be tested without the network: given quotes and chains,
     * return the best contracts by annualized premium.
     */
    fun rank(
        quotes: Map<String, Quote>,
        chains: Map<String, List<OptionQuote>>,
        ledger: LedgerState,
        today: LocalDate,
        rules: ScanRules,
        spendable: Double = ledger.cash,
    ): List<CallCandidate> {
        val out = mutableListOf<CallCandidate>()
        for ((sym, chain) in chains) {
            val q = quotes[sym] ?: continue
            val coverable = ledger.coverableContracts(sym)
            val (kind, contracts) = when {
                coverable > 0 -> CandidateKind.COVERED_CALL to coverable
                else -> {
                    val per = q.price * 100
                    val affordable = if (per > 0) (spendable / per).toInt() else 0
                    if (affordable < 1) continue
                    CandidateKind.BUY_WRITE to affordable
                }
            }
            chain.filter { passesContract(q, it, today, rules) }
                .forEach { out += toCandidate(kind, q, it, today, contracts) }
        }
        // Held shares first (that's income on what we own), then best annualized yield.
        return out.sortedWith(
            compareBy<CallCandidate> { if (it.kind == CandidateKind.COVERED_CALL) 0 else 1 }
                .thenByDescending { it.annualizedPercent }
        ).distinctBy { Triple(it.quote.symbol, it.option.strike, it.option.expiry) }
            .take(rules.maxCandidates)
    }

    suspend fun scan(
        market: MarketData,
        universe: List<String>,
        ledger: LedgerState,
        today: LocalDate,
        rules: ScanRules,
        quotes: Map<String, Quote>? = null,
        spendable: Double = ledger.cash,
    ): ScanResult {
        val q = quotes ?: market.quotes(universe)
        val rejected = mutableMapOf<String, String>()
        val errors = mutableMapOf<String, String>()
        val chains = mutableMapOf<String, List<OptionQuote>>()
        for (sym in universe) {
            val quote = q[sym]
            if (quote == null) { errors[sym] = "no quote"; continue }
            val held = ledger.sharesHeld(sym) >= 100
            val why = rejectUnderlying(quote, rules, held)
            if (why != null) { rejected[sym] = why; continue }
            if (!held && quote.price * 100 > spendable) { rejected[sym] = "100 shares cost more than you can spend"; continue }
            try {
                chains[sym] = market.calls(sym, today, rules.minDte, rules.maxDte)
            } catch (e: Exception) {
                errors[sym] = e.message ?: e.javaClass.simpleName
            }
        }
        return ScanResult(rank(q, chains, ledger, today, rules, spendable), rejected, errors)
    }
}
