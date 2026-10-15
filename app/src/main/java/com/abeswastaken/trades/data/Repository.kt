package com.abeswastaken.trades.data

import androidx.room.withTransaction
import com.abeswastaken.trades.core.engine.AnthropicLlm
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.engine.LlmException
import com.abeswastaken.trades.core.engine.LlmRequest
import com.abeswastaken.trades.core.engine.PastRec
import com.abeswastaken.trades.core.engine.RecContext
import com.abeswastaken.trades.core.engine.Recommendation
import com.abeswastaken.trades.core.engine.Recommender
import com.abeswastaken.trades.core.ledger.Ledger
import com.abeswastaken.trades.core.ledger.LedgerState
import com.abeswastaken.trades.core.ledger.OptionEvents
import com.abeswastaken.trades.core.ledger.Position
import com.abeswastaken.trades.core.market.FallbackMarketData
import com.abeswastaken.trades.core.market.FinnhubMarketData
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.MarketData
import com.abeswastaken.trades.core.market.OptionMark
import com.abeswastaken.trades.core.market.OptionMarks
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.market.YahooMarketData
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import com.abeswastaken.trades.core.scanner.CoveredCallScanner
import com.abeswastaken.trades.core.scanner.ScanResult
import com.abeswastaken.trades.util.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class Repository(private val db: TradesDb, val settings: SettingsStore) {
    private val dao = db.dao()
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val yahoo = YahooMarketData(http)

    val fills = dao.fills()
    val recs = dao.recs()
    val watchlist = dao.watchlist()
    val spend = dao.spend()

    fun market(): MarketData {
        val fh = settings.state.value.finnhubKey.takeIf { it.isNotBlank() }?.let { FinnhubMarketData(it, http) }
        return FallbackMarketData(listOfNotNull(yahoo, fh)) { AppLog.i(it) }
    }

    suspend fun ledger(): LedgerState =
        Ledger.compute(dao.allFills().map { it.toFill() }, settings.state.value.startingCash)

    // ---- Fills ---------------------------------------------------------------------------------

    suspend fun addFill(f: Fill): Long {
        val id = dao.insertFill(FillEntity.from(f.copy(id = 0)))
        f.recommendationId?.let { dao.setRecStatus(it, RecStatus.TAKEN) }
        AppLog.i("Logged fill $id: ${f.side} ${f.quantity} ${f.instrument.label} @ ${f.price}")
        return id
    }

    suspend fun deleteFill(id: Long) {
        dao.deleteFill(id)
        AppLog.i("Deleted fill $id")
    }

    /** Short option position expired worthless: buy back each lot at zero. */
    suspend fun expire(p: Position, date: LocalDate) {
        val fills = p.lots.map { OptionEvents.expire(it, 0, date) }
        dao.insertFills(fills.map(FillEntity::from))
        AppLog.i("Expired ${p.instrument.label}")
    }

    /** Short call assigned: close the calls and sell the shares at the strike. */
    suspend fun assign(p: Position, date: LocalDate) {
        val fills = p.lots.flatMap { OptionEvents.assignShortCall(it, abs(it.quantity), 0, date) }
        dao.insertFills(fills.map { FillEntity.from(it.copy(id = 0)) })
        AppLog.i("Assigned ${p.instrument.label}")
    }

    // ---- Watchlist -----------------------------------------------------------------------------

    suspend fun addWatch(ticker: String) {
        val t = ticker.trim().uppercase()
        if (t.matches(Regex("[A-Z][A-Z0-9.\\-]{0,9}"))) dao.addWatch(WatchEntity(t))
    }

    suspend fun removeWatch(ticker: String) = dao.removeWatch(ticker)

    suspend fun quotes(symbols: List<String>): Map<String, Quote> =
        if (symbols.isEmpty()) emptyMap() else market().quotes(symbols.distinct())

    suspend fun news(symbol: String): List<Headline> = market().news(symbol, 8)

    suspend fun optionMarks(positions: List<Position>): Map<Instrument, OptionMark> =
        OptionMarks.fetch(market(), positions, LocalDate.now(ZoneId.of("America/New_York"))) { AppLog.i(it) }

    /** One tiny Haiku call (a fraction of a cent) to prove the key works. */
    suspend fun testAnthropicKey(): String {
        val key = settings.apiKey() ?: return "No key saved."
        return try {
            val r = AnthropicLlm(key).send(LlmRequest(ClaudeModel.HAIKU, "Reply with the single word OK.", "ping", 256, "low"))
            "Key works (answered by ${r.servedModel})."
        } catch (e: Exception) {
            AppLog.e("Key test failed", e)
            e.message ?: "Key test failed."
        }
    }

    suspend fun testFinnhubKey(key: String): String = try {
        val q = FinnhubMarketData(key.trim(), http).quotes(listOf("AAPL"))
        if (q.isEmpty()) "Finnhub answered but returned no quote." else "Key works (AAPL ${"%.2f".format(q.values.first().price)})."
    } catch (e: Exception) {
        AppLog.e("Finnhub test failed", e)
        "Finnhub rejected the key: ${e.message}"
    }

    // ---- Recommendations -----------------------------------------------------------------------

    suspend fun skip(recId: Long) = dao.setRecStatus(recId, RecStatus.SKIPPED)

    suspend fun rec(id: Long) = dao.rec(id)

    /** Realized P&L from the lots opened or closed by fills that were logged against a recommendation. */
    fun realizedByRec(ledger: LedgerState, fills: List<Fill>): Map<Long, Double> {
        val recOf = fills.filter { it.recommendationId != null }.associate { it.id to it.recommendationId!! }
        val out = mutableMapOf<Long, Double>()
        for (lot in ledger.closedLots) {
            val rec = recOf[lot.openFillId] ?: recOf[lot.closeFillId] ?: continue
            out[rec] = (out[rec] ?: 0.0) + lot.realized
        }
        return out
    }

    suspend fun recommend(model: ClaudeModel, focus: String?, onProgress: (String) -> Unit): Long {
        val s = settings.state.value
        val key = settings.apiKey() ?: throw LlmException("Add your Anthropic API key in Settings first.")
        val fills = dao.allFills().map { it.toFill() }
        val ledger = Ledger.compute(fills, s.startingCash)
        val watch = dao.allWatch().map { it.ticker }
        val held = ledger.positions.map { it.instrument.ticker }
        val universe = (held + watch).distinct()
        val market = market()
        val today = LocalDate.now(ZoneId.of("America/New_York"))

        onProgress("Getting quotes")
        val quotes = runCatching { market.quotes(universe) }.getOrElse {
            AppLog.e("Quotes failed", it); emptyMap()
        }

        onProgress("Scanning option chains")
        val scan: ScanResult = CoveredCallScanner.scan(market, universe, ledger, today, s.rules, quotes)
        scan.errors.forEach { (sym, e) -> AppLog.i("Scan $sym: $e") }

        onProgress("Reading the news")
        val headlines = coroutineScope {
            universe.take(MAX_NEWS_TICKERS).map { sym ->
                async { sym to runCatching { market.news(sym, 6) }.getOrDefault(emptyList()) }
            }.awaitAll().toMap()
        }

        onProgress("Pricing open options")
        val marks = OptionMarks.fetch(market, ledger.positions, today) { AppLog.i(it) }

        val realized = realizedByRec(ledger, fills)
        val history = dao.allRecs().sortedByDescending { it.createdAt }.take(10).map {
            PastRec(
                date = Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate(),
                summary = it.summary, status = it.status.lowercase(), realized = realized[it.id],
            )
        }

        val ctx = RecContext(
            today = today, ledger = ledger, startingCash = s.startingCash, quotes = quotes,
            candidates = scan.candidates, newsDigest = null, history = history, focus = focus,
            watchlist = watch, optionMarks = marks,
        )
        onProgress(if (s.useNewsDigest) "Asking Claude (news digest, then ${model.label})" else "Asking ${model.label}")
        val result = Recommender(AnthropicLlm(key, onDowngrade = { AppLog.i(it) })).recommend(model, ctx, headlines, s.useNewsDigest)

        val now = System.currentTimeMillis()
        val id = dao.insertRec(
            RecEntity(
                createdAt = now, model = model.id,
                servedModel = result.spend.last().servedModel,
                json = result.rawJson, summary = result.recommendation.summary,
                action = result.recommendation.action, costUsd = result.spend.sumOf { it.cost },
                digest = result.digest, focus = focus,
            )
        )
        dao.insertSpend(result.spend.map {
            SpendEntity(
                ts = now, purpose = it.purpose, model = it.servedModel,
                inputTokens = it.usage.inputTokens, outputTokens = it.usage.outputTokens,
                cacheReadTokens = it.usage.cacheReadTokens, cacheWriteTokens = it.usage.cacheWriteTokens,
                costUsd = it.cost,
            )
        })
        AppLog.i("Recommendation $id via ${model.id}: ${result.recommendation.summary} (\$${"%.4f".format(result.spend.sumOf { it.cost })})")
        return id
    }

    // ---- Backup --------------------------------------------------------------------------------

    suspend fun exportJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", Instant.now().toString())
        val s = settings.state.value
        root.put("settings", JSONObject().put("startingCash", s.startingCash).put("defaultModel", s.defaultModel.name))
        root.put("fills", JSONArray(dao.allFills().map { f ->
            JSONObject().put("id", f.id).put("epochDay", f.epochDay).put("ticker", f.ticker).put("type", f.type)
                .put("strike", f.strike ?: JSONObject.NULL).put("expiryEpochDay", f.expiryEpochDay ?: JSONObject.NULL)
                .put("side", f.side).put("quantity", f.quantity).put("price", f.price).put("fees", f.fees)
                .put("note", f.note).put("recommendationId", f.recommendationId ?: JSONObject.NULL).put("createdAt", f.createdAt)
        }))
        root.put("recommendations", JSONArray(dao.allRecs().map { r ->
            JSONObject().put("id", r.id).put("createdAt", r.createdAt).put("model", r.model).put("servedModel", r.servedModel)
                .put("json", r.json).put("summary", r.summary).put("action", r.action).put("status", r.status)
                .put("costUsd", r.costUsd).put("digest", r.digest ?: JSONObject.NULL).put("focus", r.focus ?: JSONObject.NULL)
        }))
        root.put("watchlist", JSONArray(dao.allWatch().map { it.ticker }))
        root.put("spend", JSONArray(dao.allSpend().map { x ->
            JSONObject().put("ts", x.ts).put("purpose", x.purpose).put("model", x.model).put("inputTokens", x.inputTokens)
                .put("outputTokens", x.outputTokens).put("cacheReadTokens", x.cacheReadTokens)
                .put("cacheWriteTokens", x.cacheWriteTokens).put("costUsd", x.costUsd)
        }))
        return root.toString(2)
    }

    suspend fun importJson(text: String) {
        val root = JSONObject(text)
        require(root.optInt("version") == 1) { "Not a Colvin Trades backup" }
        fun JSONObject.optLongOrNull(k: String) = if (isNull(k) || !has(k)) null else getLong(k)
        fun JSONObject.optDoubleOrNull(k: String) = if (isNull(k) || !has(k)) null else getDouble(k)
        fun JSONObject.optStringOrNull(k: String) = if (isNull(k) || !has(k)) null else getString(k)
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

        val fills = root.getJSONArray("fills").objects().map { o ->
            FillEntity(
                id = o.getLong("id"), epochDay = o.getLong("epochDay"), ticker = o.getString("ticker"),
                type = o.getString("type"), strike = o.optDoubleOrNull("strike"),
                expiryEpochDay = o.optLongOrNull("expiryEpochDay"), side = o.getString("side"),
                quantity = o.getDouble("quantity"), price = o.getDouble("price"), fees = o.optDouble("fees", 0.0),
                note = o.optString("note"), recommendationId = o.optLongOrNull("recommendationId"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        // Validate before touching the database: every fill must load and the ledger must replay.
        Ledger.compute(fills.map { it.toFill() }, 0.0)
        val recs = root.optJSONArray("recommendations")?.objects().orEmpty().map { o ->
            RecEntity(
                id = o.getLong("id"), createdAt = o.getLong("createdAt"), model = o.getString("model"),
                servedModel = o.optString("servedModel", o.getString("model")), json = o.getString("json"),
                summary = o.getString("summary"), action = o.getString("action"), status = o.getString("status"),
                costUsd = o.optDouble("costUsd", 0.0), digest = o.optStringOrNull("digest"), focus = o.optStringOrNull("focus"),
            )
        }
        val watch = root.optJSONArray("watchlist")?.let { a -> (0 until a.length()).map { WatchEntity(a.getString(it)) } }.orEmpty()
        val spend = root.optJSONArray("spend")?.objects().orEmpty().map { o ->
            SpendEntity(
                ts = o.getLong("ts"), purpose = o.getString("purpose"), model = o.getString("model"),
                inputTokens = o.getLong("inputTokens"), outputTokens = o.getLong("outputTokens"),
                cacheReadTokens = o.optLong("cacheReadTokens"), cacheWriteTokens = o.optLong("cacheWriteTokens"),
                costUsd = o.getDouble("costUsd"),
            )
        }
        db.withTransaction {
            dao.clearFills(); dao.clearRecs(); dao.clearWatch(); dao.clearSpend()
            dao.putFills(fills); dao.putRecs(recs); dao.putWatch(watch); dao.putSpend(spend)
        }
        root.optJSONObject("settings")?.let { st ->
            settings.update {
                copy(
                    startingCash = st.optDouble("startingCash", startingCash),
                    defaultModel = ClaudeModel.byName(st.optString("defaultModel", defaultModel.name)),
                )
            }
        }
        AppLog.i("Restored backup: ${fills.size} fills, ${recs.size} recommendations")
    }

    companion object {
        private const val MAX_NEWS_TICKERS = 8

        /** Turns a recommendation into a prefilled fill for the Log Fill screen. */
        fun draftFrom(recId: Long, r: Recommendation): FillDraft {
            val option = r.isOption
            return FillDraft(
                ticker = r.ticker.orEmpty(),
                type = if (option) AssetType.CALL else AssetType.STOCK,
                side = when (r.actionEnum.name) {
                    "SELL", "SELL_CALL" -> Side.SELL
                    else -> Side.BUY
                },
                quantity = r.quantity?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }.orEmpty(),
                price = r.limitPrice?.toString().orEmpty(),
                strike = if (option) r.strike?.toString().orEmpty() else "",
                expiry = if (option) r.expiry.orEmpty() else "",
                recommendationId = recId,
                recommendationSummary = r.summary,
            )
        }
    }
}

/** Editable form state for Log Fill (strings, so half-typed numbers survive). */
data class FillDraft(
    val ticker: String = "",
    val type: AssetType = AssetType.STOCK,
    val side: Side = Side.BUY,
    val quantity: String = "",
    val price: String = "",
    val fees: String = "",
    val date: String = LocalDate.now().toString(),
    val strike: String = "",
    val expiry: String = "",
    val note: String = "",
    val recommendationId: Long? = null,
    val recommendationSummary: String? = null,
) {
    /** Returns the fill, or an error message for the first bad field. */
    fun toFill(): Result<Fill> = runCatching {
        val t = ticker.trim().uppercase()
        require(t.isNotEmpty()) { "Ticker is required" }
        val qty = quantity.toDoubleOrNull()?.takeIf { it > 0 } ?: error("Quantity must be a positive number")
        val px = price.toDoubleOrNull()?.takeIf { it >= 0 } ?: error("Price must be a number")
        val fee = fees.ifBlank { "0" }.toDoubleOrNull()?.takeIf { it >= 0 } ?: error("Fees must be a number")
        val d = runCatching { LocalDate.parse(date.trim()) }.getOrElse { error("Date must be YYYY-MM-DD") }
        val inst = if (type == AssetType.STOCK) Instrument.stock(t) else {
            val k = strike.toDoubleOrNull()?.takeIf { it > 0 } ?: error("Strike is required for options")
            val e = runCatching { LocalDate.parse(expiry.trim()) }.getOrElse { error("Expiry must be YYYY-MM-DD") }
            Instrument(t, type, k, e)
        }
        Fill(0, d, inst, side, qty, px, fee, note.trim(), recommendationId)
    }
}
