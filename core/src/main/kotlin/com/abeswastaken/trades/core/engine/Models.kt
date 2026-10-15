package com.abeswastaken.trades.core.engine

import kotlinx.serialization.Serializable

/** Models the app can call, with Anthropic list prices in USD per million tokens (checked 2026-10-15). */
enum class ClaudeModel(val id: String, val label: String, val inputPerM: Double, val outputPerM: Double) {
    HAIKU("claude-haiku-5-5", "Haiku", 0.10, 0.50),
    SONNET("claude-sonnet-5-5", "Sonnet", 2.00, 10.00),
    OPUS("claude-opus-5-5", "Opus", 4.00, 20.00);

    /** Server-side refusal fallback is offered for Sonnet 5.5 and Opus 5.5, not Haiku. */
    val supportsFallback get() = this != HAIKU

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun byName(name: String?) = entries.firstOrNull { it.name == name } ?: SONNET
    }
}

data class Usage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {
    operator fun plus(o: Usage) = Usage(
        inputTokens + o.inputTokens, outputTokens + o.outputTokens,
        cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens,
    )
}

object Pricing {
    /** Cache reads bill at 0.1x input, cache writes (5-minute) at 1.25x input. */
    fun cost(model: ClaudeModel, u: Usage): Double =
        (u.inputTokens * model.inputPerM +
            u.cacheReadTokens * model.inputPerM * 0.1 +
            u.cacheWriteTokens * model.inputPerM * 1.25 +
            u.outputTokens * model.outputPerM) / 1_000_000.0
}

/** How much risk the owner wants in recommendations and scans. Set in Settings. */
enum class RiskLevel(val label: String, val rules: String) {
    LOW(
        "Low",
        "LOW. Protect capital first. Prefer covered calls further out of the money on steady, profitable names; " +
            "skip anything with earnings before expiry, recent 10%+ moves or heavy social-media hype. HOLD is a fine answer.",
    ),
    NORMAL(
        "Normal",
        "NORMAL. Balance premium against assignment and drawdown risk. Momentum names are fine with a clear catalyst; " +
            "avoid pure hype.",
    ),
    HIGH(
        "High",
        "HIGH. The owner accepts bigger swings for bigger premium or upside: nearer strikes, volatile and momentum names, " +
            "catalyst plays and crowd-driven runs are in scope. Still size so one bad trade can't sink the account, and say plainly when something is a gamble.",
    );

    companion object {
        fun byName(name: String?) = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

enum class RecAction { BUY, SELL, SELL_CALL, BUY_TO_CLOSE, HOLD }

@Serializable
data class RecMath(
    /** Cash out (positive) or in (negative) if the order fills at the limit, before fees. */
    val cashImpact: Double? = null,
    val maxGain: Double? = null,
    val maxLoss: Double? = null,
    val breakeven: Double? = null,
    val annualizedYieldPercent: Double? = null,
)

/** One recommendation, exactly as Claude returns it (structured output). */
@Serializable
data class Recommendation(
    val action: String,
    val ticker: String? = null,
    /** Shares for stock; contracts for options. */
    val quantity: Double? = null,
    val limitPrice: Double? = null,
    val strike: Double? = null,
    /** ISO date (YYYY-MM-DD) for option expiry. */
    val expiry: String? = null,
    val summary: String,
    val rationale: String,
    val risks: String,
    val exitPlan: String? = null,
    val confidence: String = "medium",
    val math: RecMath = RecMath(),
) {
    val actionEnum get() = runCatching { RecAction.valueOf(action) }.getOrDefault(RecAction.HOLD)
    val isOption get() = actionEnum == RecAction.SELL_CALL || actionEnum == RecAction.BUY_TO_CLOSE
}
