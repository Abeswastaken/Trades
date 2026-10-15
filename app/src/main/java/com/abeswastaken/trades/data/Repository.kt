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
import com.abeswastaken.trades.core.engine.MarketCandidates
import com.abeswastaken.trades.core.engine.MarketScanContext
import com.abeswastaken.trades.core.engine.MarketScanReply
import com.abeswastaken.trades.core.engine.MarketScanner
import com.abeswastaken.trades.core.engine.SpendItem
import com.abeswastaken.trades.core.market.MARKET
import com.abeswastaken.trades.core.market.NewsHub
import com.abeswastaken.trades.core.market.NewsSourceId
import com.abeswastaken.trades.core.market.NewsSources
import com.abeswastaken.trades.core.scanner.CoveredCallScreener
import com.abeswastaken.trades.core.scanner.ScreenerResult
import com.abeswastaken.trades.core.engine.BuzzCheck
import com.abeswastaken.trades.core.engine.BuzzReport
import com.abeswastaken.trades.core.engine.BuzzScreen
import com.abeswastaken.trades.core.engine.BuzzVerifier
import com.abeswastaken.trades.core.export.ImportPreview
import com.abeswastaken.trades.core.export.SchwabImport
import com.abeswastaken.trades.core.market.ApeWisdom
import com.abeswastaken.trades.core.market.RedditNews
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class Repository(private val db: TradesDb, val settings: SettingsStore, private val filesDir: File) {
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

    /** Replaces a logged fill in place. It keeps its id, so its order in the FIFO replay doesn't change. */
    suspend fun updateFill(id: Long, f: Fill) {
        val old = dao.fill(id) ?: error("That fill no longer exists")
        val next = FillEntity.from(f.copy(id = id)).copy(createdAt = old.createdAt)
        // Replay first so an edit that breaks the ledger (e.g. a close bigger than the position) is refused.
        Ledger.compute(dao.allFills().map { if (it.id == id) next.toFill() else it.toFill() }, 0.0)
        dao.updateFill(next)
        AppLog.i("Edited fill $id: ${f.side} ${f.quantity} ${f.instrument.label} @ ${f.price}")
    }

    /** Option expired. Short lots are bought back at 0, long lots sold at 0. */
    suspend fun expire(p: Position, date: LocalDate) {
        val fills = p.lots.map { OptionEvents.expire(it, 0, date) }
        dao.insertFills(fills.map(FillEntity::from))
        AppLog.i("Expired ${p.instrument.label} on $date")
    }

    /**
     * Short option assigned. A call closes at 0 and sells the shares at the strike; a put closes at 0 and
     * buys them. Refuses a call assignment that would sell shares that aren't held.
     */
    suspend fun assign(p: Position, date: LocalDate) {
        val contracts = abs(p.quantity)
        if (p.instrument.type == AssetType.CALL) {
            val held = ledger().sharesHeld(p.instrument.ticker)
            require(held + 1e-9 >= contracts * 100) {
                "Only ${held.toLong()} ${p.instrument.ticker} shares are held, but assignment sells ${(contracts * 100).toLong()}. Log the shares first, or mark it expired."
            }
        }
        val fills = p.lots.flatMap {
            if (p.instrument.type == AssetType.CALL) OptionEvents.assignShortCall(it, abs(it.quantity), 0, date)
            else OptionEvents.assignShortPut(it, abs(it.quantity), 0, date)
        }
        dao.insertFills(fills.map { FillEntity.from(it.copy(id = 0)) })
        AppLog.i("Assigned ${p.instrument.label} on $date")
    }

    suspend fun roll(fills: List<Fill>) {
        db.withTransaction { dao.insertFills(fills.map { FillEntity.from(it.copy(id = 0)) }) }
        AppLog.i("Rolled ${fills.first().instrument.label} to ${fills.last().instrument.label}")
    }

    /** Today's chain for the nearest weekly, with the call to write on shares already held. */
    suspend fun suggestCall(ticker: String, avgCost: Double): Pair<Quote?, com.abeswastaken.trades.core.market.OptionQuote?> {
        val today = today()
        val q = runCatching { market().quotes(listOf(ticker))[ticker] }.getOrNull()
        val snap = runCatching { yahoo.nearestCalls(ticker, today, 4, 10) }
            .onFailure { AppLog.i("Chain for $ticker failed: ${it.message}") }.getOrNull()
        val pick = if (q == null || snap == null) null else CoveredCallScreener.callToWrite(q.price, avgCost, snap.calls)
        return q to pick
    }

    // ---- Watchlist -----------------------------------------------------------------------------

    suspend fun addWatch(ticker: String) {
        val t = ticker.trim().uppercase()
        if (t.matches(Regex("[A-Z][A-Z0-9.\\-]{0,9}"))) dao.addWatch(WatchEntity(t))
    }

    suspend fun removeWatch(ticker: String) = dao.removeWatch(ticker)

    suspend fun quotes(symbols: List<String>): Map<String, Quote> =
        if (symbols.isEmpty()) emptyMap() else market().quotes(symbols.distinct())

    fun newsHub(): NewsHub {
        val s = settings.state.value
        return NewsHub(NewsSources.build(http, s.newsSources, s.newsKeys)) { AppLog.i(it) }
    }

    suspend fun news(symbol: String): List<Headline> = newsHub().ticker(symbol, 10)

    suspend fun marketNews(): List<Headline> = newsHub().market(30)

    /** Pulls three AAPL headlines from one source, so a key can be checked before relying on it. */
    suspend fun testNewsSource(id: NewsSourceId, key: String): String = try {
        val p = NewsSources.build(http, setOf(id), mapOf(id to key)).firstOrNull() ?: return "Enter a key first."
        val n = p.ticker("AAPL", 3).ifEmpty { p.market(3) }
        if (n.isEmpty()) "${id.label} answered but had no headlines." else "Works: \"${n.first().title.take(70)}\""
    } catch (e: Exception) {
        AppLog.e("${id.label} test failed", e)
        "${id.label} failed: ${e.message}"
    }

    // ---- Discovery -----------------------------------------------------------------------------

    suspend fun screen(onProgress: (String) -> Unit): ScreenerResult {
        val r = CoveredCallScreener.run(yahoo, market(), today(), settings.state.value.screener, onProgress)
        r.errors.forEach { (sym, e) -> AppLog.i("Screener $sym: $e") }
        AppLog.i("Screener: ${r.hits.size} hits from ${r.scanned} (${r.universeSource}), ${r.noWeeklies} without weeklies")
        return r
    }

    /** A saved market scan, so the last one survives a restart. */
    data class SavedScan(val at: Long, val model: String, val costUsd: Double, val reply: MarketScanReply, val candidates: Int, val headlines: Int)

    private val scanFile get() = File(filesDir, "market_scan.json")

    fun lastScan(): SavedScan? = runCatching {
        val o = JSONObject(scanFile.readText())
        SavedScan(
            o.getLong("at"), o.getString("model"), o.getDouble("costUsd"),
            MarketScanner.parse(o.getString("reply")), o.optInt("candidates"), o.optInt("headlines"),
        )
    }.getOrNull()

    suspend fun marketScan(model: ClaudeModel, focus: String?, onProgress: (String) -> Unit): SavedScan {
        val s = settings.state.value
        val key = settings.apiKey() ?: throw LlmException("Add your Anthropic API key in Settings first.")
        val ledger = ledger()
        val exclude = (dao.allWatch().map { it.ticker } + ledger.positions.map { it.instrument.ticker }).toSet()
        onProgress("Finding movers, most active and trending stocks")
        val buzz = runCatching { buzz() }.getOrNull()?.checks.orEmpty()
        val extra = buzz.take(15).associate { it.ticker to if (it.longShot) "reddit long shot" else "reddit buzz" }
        val candidates = MarketCandidates.gather(yahoo, market(), s.rules, ledger.cash, exclude, extra = extra) { AppLog.i(it) }
        val names = candidates.map { it.quote.symbol }.toSet()
        onProgress("Reading market news from ${s.newsSources.size} sources")
        val headlines = runCatching { marketNews() }.getOrDefault(emptyList())
        onProgress("Asking ${model.label} to pick from ${candidates.size} stocks")
        val result = MarketScanner(AnthropicLlm(key, onDowngrade = { AppLog.i(it) }))
            .scan(model, MarketScanContext(today(), ledger.cash, candidates, headlines, focus, s.risk, buzz.filter { it.ticker in names }.map { it.promptLine() }))
        if (result.dropped.isNotEmpty()) AppLog.i("Market scan dropped picks not in the list: ${result.dropped}")
        val now = System.currentTimeMillis()
        recordSpend(now, result.spend)
        val saved = SavedScan(now, result.spend.last().servedModel, result.spend.sumOf { it.cost }, result.reply, candidates.size, headlines.size)
        scanFile.writeText(
            JSONObject().put("at", saved.at).put("model", saved.model).put("costUsd", saved.costUsd)
                .put("reply", MarketScanner.encode(saved.reply)).put("candidates", saved.candidates).put("headlines", saved.headlines)
                .toString()
        )
        AppLog.i("Market scan via ${model.id}: ${result.reply.picks.map { it.ticker }} (\$${"%.4f".format(saved.costUsd)})")
        return saved
    }

    private suspend fun recordSpend(now: Long, spend: List<SpendItem>) = dao.insertSpend(spend.map {
        SpendEntity(
            ts = now, purpose = it.purpose, model = it.servedModel,
            inputTokens = it.usage.inputTokens, outputTokens = it.usage.outputTokens,
            cacheReadTokens = it.usage.cacheReadTokens, cacheWriteTokens = it.usage.cacheWriteTokens,
            costUsd = it.cost,
        )
    })

    private fun today() = LocalDate.now(ZoneId.of("America/New_York"))

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

    // ---- Reddit buzz ---------------------------------------------------------------------------

    data class Buzz(val at: Long, val checks: List<BuzzCheck>, val source: String)

    @Volatile private var buzzCache: Buzz? = null
    private val apeWisdom = ApeWisdom(http)

    fun cachedBuzz(): Buzz? = buzzCache

    /**
     * Free cross-check of Reddit chatter: ApeWisdom mention counts, then Yahoo quotes and real news on the
     * names that are moving. Cached for 10 minutes.
     */
    suspend fun buzz(force: Boolean = false): Buzz {
        buzzCache?.takeIf { !force && System.currentTimeMillis() - it.at < 10 * 60_000 }?.let { return it }
        val items = apeWisdom.top("all-stocks").filter { MarketCandidates.looksLikeStock(it.ticker) }.take(30)
        val quotes = runCatching { market().quotes(items.map { it.ticker }) }.getOrElse { AppLog.e("Buzz quotes failed", it); emptyMap() }
        val now = System.currentTimeMillis() / 1000
        // News for the names most worth checking: rising fastest, or moving most.
        val worth = items.sortedByDescending { i ->
            (i.mentionRatio ?: 1.0) + kotlin.math.abs(quotes[i.ticker]?.changePercent ?: 0.0) / 5
        }.take(BUZZ_NEWS).map { it.ticker }.toSet()
        val hub = NewsHub(NewsSources.build(http, settings.state.value.newsSources - NewsSourceId.REDDIT, settings.state.value.newsKeys)) { AppLog.i(it) }
        val news = coroutineScope {
            worth.map { t -> async { t to runCatching { hub.ticker(t, 4) }.getOrDefault(emptyList()) } }.awaitAll().toMap()
        }
        val checks = BuzzScreen.rank(items.map { BuzzScreen.check(it, quotes[it.ticker], news[it.ticker].orEmpty(), now) })
        AppLog.i("Buzz: ${checks.size} names, long shots ${checks.filter { it.longShot }.map { it.ticker }}")
        return Buzz(System.currentTimeMillis(), checks, "ApeWisdom").also { buzzCache = it }
    }

    data class SavedBuzzReport(val at: Long, val model: String, val costUsd: Double, val report: BuzzReport)

    private val buzzFile get() = File(filesDir, "buzz_report.json")

    fun lastBuzzReport(): SavedBuzzReport? = runCatching {
        val o = JSONObject(buzzFile.readText())
        SavedBuzzReport(o.getLong("at"), o.getString("model"), o.getDouble("costUsd"), BuzzVerifier.parse(o.getString("report")))
    }.getOrNull()

    /** Claude reads the Reddit posts next to the numbers and says what holds up. */
    suspend fun verifyBuzz(model: ClaudeModel, onProgress: (String) -> Unit): SavedBuzzReport {
        val key = settings.apiKey() ?: throw LlmException("Add your Anthropic API key in Settings first.")
        onProgress("Getting Reddit mentions, quotes and news")
        val checks = buzz().checks.take(BUZZ_VERIFY)
        onProgress("Reading the Reddit posts")
        val reddit = RedditNews(http)
        val posts = coroutineScope {
            checks.map { c -> async { c.ticker to runCatching { reddit.ticker(c.ticker, 6) }.getOrDefault(emptyList()) } }.awaitAll().toMap()
        }
        onProgress("Asking ${model.label} to check ${checks.size} names")
        val (report, spend) = BuzzVerifier(AnthropicLlm(key, onDowngrade = { AppLog.i(it) })).verify(model, checks, posts, settings.state.value.risk)
        val now = System.currentTimeMillis()
        recordSpend(now, spend)
        val saved = SavedBuzzReport(now, spend.last().servedModel, spend.sumOf { it.cost }, report)
        buzzFile.writeText(
            JSONObject().put("at", now).put("model", saved.model).put("costUsd", saved.costUsd).put("report", BuzzVerifier.encode(report)).toString()
        )
        AppLog.i("Reddit check via ${model.id}: ${report.notes.map { it.ticker + ":" + it.verdict }}")
        return saved
    }

    // ---- Schwab import -------------------------------------------------------------------------

    suspend fun previewImport(csv: String): ImportPreview = SchwabImport.preview(csv, dao.allFills().map { it.toFill() })

    suspend fun importFills(fills: List<Fill>) {
        // Inserted oldest first, so ids (the FIFO tie-break) follow trade order.
        db.withTransaction { dao.insertFills(fills.sortedBy { it.date }.map { FillEntity.from(it.copy(id = 0)) }) }
        AppLog.i("Imported ${fills.size} fills from Schwab")
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
        val hub = newsHub()
        val headlines = coroutineScope {
            val perTicker = universe.take(MAX_NEWS_TICKERS).map { sym ->
                async { sym to runCatching { hub.ticker(sym, 6) }.getOrDefault(emptyList()) }
            }
            val wide = async { MARKET to runCatching { hub.market(8) }.getOrDefault(emptyList()) }
            (perTicker.awaitAll() + wide.await()).toMap()
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
            watchlist = watch, optionMarks = marks, risk = s.risk,
            buzz = cachedBuzz()?.checks.orEmpty().filter { it.ticker in universe }.map { it.promptLine() },
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
        recordSpend(now, result.spend)
        AppLog.i("Recommendation $id via ${model.id}: ${result.recommendation.summary} (\$${"%.4f".format(result.spend.sumOf { it.cost })})")
        return id
    }

    // ---- Backup --------------------------------------------------------------------------------

    suspend fun exportJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", Instant.now().toString())
        val s = settings.state.value
        root.put("settings", JSONObject().put("startingCash", s.startingCash).put("defaultModel", s.defaultModel.name)
            .put("risk", s.risk.name).put("newsSources", JSONArray(s.newsSources.map { it.name })))
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
        // Keep what's there now, so a wrong file can be undone by restoring this one.
        runCatching { File(filesDir, "pre-restore-${System.currentTimeMillis()}.json").writeText(exportJson()) }
            .onFailure { AppLog.e("Pre-restore snapshot failed", it) }
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
                    risk = com.abeswastaken.trades.core.engine.RiskLevel.byName(st.optString("risk", risk.name)),
                    newsSources = st.optJSONArray("newsSources")?.let { a -> NewsSourceId.parse((0 until a.length()).map(a::getString)) } ?: newsSources,
                )
            }
        }
        AppLog.i("Restored backup: ${fills.size} fills, ${recs.size} recommendations")
    }

    companion object {
        private const val MAX_NEWS_TICKERS = 8
        private const val BUZZ_NEWS = 8
        private const val BUZZ_VERIFY = 10

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
    /** Set when editing an existing fill instead of logging a new one. */
    val editId: Long? = null,
    /** A line shown at the top of the form saying where the prefill came from. */
    val context: String? = null,
) {
    companion object {
        private fun num(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

        fun edit(f: Fill) = FillDraft(
            ticker = f.instrument.ticker, type = f.instrument.type, side = f.side, quantity = num(f.quantity),
            price = num(f.price), fees = if (f.fees > 0) num(f.fees) else "", date = f.date.toString(),
            strike = f.instrument.strike?.let(::num).orEmpty(), expiry = f.instrument.expiry?.toString().orEmpty(),
            note = f.note, recommendationId = f.recommendationId, editId = f.id,
        )

        /** Closes an open position: the opposite side, same contract, same size. */
        fun close(p: Position, price: Double? = null) = FillDraft(
            ticker = p.instrument.ticker, type = p.instrument.type,
            side = if (p.quantity < 0) Side.BUY else Side.SELL,
            quantity = num(abs(p.quantity)), price = price?.let { "%.2f".format(java.util.Locale.US, it) }.orEmpty(),
            strike = p.instrument.strike?.let(::num).orEmpty(), expiry = p.instrument.expiry?.toString().orEmpty(),
            note = "Close", context = "Closing ${p.instrument.label}",
        )

        /** Writes calls against shares already held, one contract per 100 uncovered shares. */
        fun coveredCall(ticker: String, contracts: Int, expiry: LocalDate, strike: Double?, premium: Double?, context: String) = FillDraft(
            ticker = ticker, type = AssetType.CALL, side = Side.SELL, quantity = contracts.toString(),
            price = premium?.let { "%.2f".format(java.util.Locale.US, it) }.orEmpty(),
            strike = strike?.let(::num).orEmpty(), expiry = expiry.toString(), note = "Covered call", context = context,
        )
    }

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
        Fill(editId ?: 0, d, inst, side, qty, px, fee, note.trim(), recommendationId)
    }
}
