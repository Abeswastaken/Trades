package com.abeswastaken.trades.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abeswastaken.trades.App
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.engine.Recommender
import com.abeswastaken.trades.core.ledger.Ledger
import com.abeswastaken.trades.core.ledger.LedgerState
import com.abeswastaken.trades.core.ledger.Position
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.data.AppSettings
import com.abeswastaken.trades.data.FillDraft
import com.abeswastaken.trades.data.RecEntity
import com.abeswastaken.trades.data.Repository
import com.abeswastaken.trades.data.SpendEntity
import com.abeswastaken.trades.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface RecRun {
    data object Idle : RecRun
    data class Running(val step: String) : RecRun
    data class Failed(val message: String) : RecRun
    /** Finished; the app opens the recommendation, then sets this back to Idle. */
    data class Done(val id: Long) : RecRun
}

/** State of a longer job (market scan, screener) that reports progress. */
sealed interface Work<out T> {
    data object Idle : Work<Nothing>
    data class Running(val step: String) : Work<Nothing>
    data class Done<T>(val value: T) : Work<T>
    data class Failed(val message: String) : Work<Nothing>
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo: Repository = (app as App).repo

    val settings: StateFlow<AppSettings> = repo.settings.state

    val fills: StateFlow<List<Fill>> = repo.fills.map { l -> l.map { it.toFill() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val ledger: StateFlow<LedgerState> = combine(fills, settings) { f, s -> Ledger.compute(f, s.startingCash) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Ledger.compute(emptyList(), 0.0))

    val recs: StateFlow<List<RecEntity>> = repo.recs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Realized P&L per recommendation id, from fills logged against it. */
    val realizedByRec: StateFlow<Map<Long, Double>> = combine(ledger, fills) { l, f -> repo.realizedByRec(l, f) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val watchlist: StateFlow<List<String>> = repo.watchlist.map { l -> l.map { it.ticker } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val spendThisMonth: StateFlow<Double> = repo.spend.map { l -> l.filter { inThisMonth(it) }.sumOf { it.costUsd } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    val quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val quotesError = MutableStateFlow<String?>(null)
    val quotesLoading = MutableStateFlow(false)

    val optionMarks = MutableStateFlow<Map<com.abeswastaken.trades.core.model.Instrument, com.abeswastaken.trades.core.market.OptionMark>>(emptyMap())

    val news = MutableStateFlow<Map<String, List<Headline>>>(emptyMap())

    val recRun = MutableStateFlow<RecRun>(RecRun.Idle)

    /** The Log Fill form. Compose state held here, so typing is synchronous and survives rotation. */
    var draft by androidx.compose.runtime.mutableStateOf(FillDraft())

    val toast = MutableStateFlow<String?>(null)

    /** Which Watchlist sub-tab is showing, so Home can jump straight to Reddit or Market. */
    val watchTab = MutableStateFlow(0)

    private fun inThisMonth(s: SpendEntity): Boolean {
        val d = Instant.ofEpochMilli(s.ts).atZone(ZoneId.systemDefault()).toLocalDate()
        val now = LocalDate.now()
        return d.year == now.year && d.month == now.month
    }

    fun refreshQuotes() {
        val symbols = (ledger.value.positions.map { it.instrument.ticker } + watchlist.value).distinct()
        if (symbols.isEmpty() || quotesLoading.value) return
        quotesLoading.value = true
        viewModelScope.launch {
            try {
                quotes.value = quotes.value + repo.quotes(symbols)
                quotesError.value = null
                val options = ledger.value.positions.filter { it.instrument.isOption }
                if (options.isNotEmpty()) optionMarks.value = repo.optionMarks(options)
            } catch (e: Exception) {
                AppLog.e("Quote refresh failed", e)
                quotesError.value = "Couldn't load quotes: ${e.message}"
            } finally {
                quotesLoading.value = false
            }
        }
    }

    private val newsAt = mutableMapOf<String, Long>()

    /** Headlines per ticker, kept for 15 minutes. Empty results (often a failed fetch) aren't kept. */
    fun loadNews(symbol: String, force: Boolean = false) {
        val fresh = System.currentTimeMillis() - (newsAt[symbol] ?: 0) < 15 * 60_000
        if (!force && fresh && news.value[symbol]?.isNotEmpty() == true) return
        viewModelScope.launch {
            val n = runCatching { repo.news(symbol) }.getOrElse { AppLog.e("News $symbol failed", it); emptyList() }
            if (n.isNotEmpty()) newsAt[symbol] = System.currentTimeMillis()
            news.value = news.value + (symbol to n)
        }
    }

    /** Runs a recommendation; the app navigates to it when [recRun] turns Done (survives rotation). */
    fun recommend(model: ClaudeModel, focus: String) {
        if (recRun.value is RecRun.Running) return
        recRun.value = RecRun.Running("Starting")
        viewModelScope.launch {
            try {
                val id = repo.recommend(model, focus.ifBlank { null }) { step -> recRun.value = RecRun.Running(step) }
                recRun.value = RecRun.Done(id)
            } catch (e: Exception) {
                AppLog.e("Recommendation failed", e)
                recRun.value = RecRun.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun startDraftFromRec(rec: RecEntity): Boolean = runCatching {
        draft = Repository.draftFrom(rec.id, Recommender.parse(rec.json))
    }.isSuccess

    fun startDraft(d: FillDraft = FillDraft()) { draft = d }

    val saving = MutableStateFlow(false)

    fun saveDraft(d: FillDraft, onSaved: () -> Unit) {
        if (saving.value) return
        val fill = d.toFill().getOrElse { toast.value = it.message; return }
        saving.value = true
        viewModelScope.launch {
            try {
                if (d.editId != null) repo.updateFill(d.editId, fill) else repo.addFill(fill)
                draft = FillDraft()
                toast.value = if (d.editId != null) "Fill updated" else "Fill saved"
                onSaved()
            } catch (e: Exception) {
                AppLog.e("Save fill failed", e)
                toast.value = "Not saved: ${e.message}"
            } finally {
                saving.value = false
            }
        }
    }

    fun deleteFill(id: Long) = viewModelScope.launch { repo.deleteFill(id); toast.value = "Fill deleted" }

    private fun act(done: String, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block(); toast.value = done
        } catch (e: Exception) {
            AppLog.e("$done failed", e)
            toast.value = e.message ?: "Failed"
        }
    }

    fun expire(p: Position, date: LocalDate) = act("Marked expired") { repo.expire(p, date) }

    fun assign(p: Position, date: LocalDate) = act("Marked assigned") { repo.assign(p, date) }

    fun roll(fills: List<Fill>) = act("Rolled") { repo.roll(fills) }

    /** Prefills a covered call on held shares, with a strike from today's chain when Yahoo answers. */
    val suggesting = MutableStateFlow<String?>(null)

    fun coveredCallDraft(p: Position, contracts: Int, open: () -> Unit) {
        if (suggesting.value != null) return
        val t = p.instrument.ticker
        suggesting.value = t
        viewModelScope.launch {
            val (q, o) = runCatching { repo.suggestCall(t, p.avgCost) }.getOrDefault(null to null)
            val expiry = o?.expiry ?: com.abeswastaken.trades.core.scanner.CoveredCallScreener.nextFriday(LocalDate.now())
            val context = when {
                o == null -> "Covered call on $contracts x 100 $t. No chain right now: enter the strike and premium from Schwab."
                o.strike < p.avgCost -> "Nearest strike with a bid is below your cost (${"%.2f".format(p.avgCost)}): assignment would lock in a loss."
                else -> "Suggested: nearest strike above your cost and the price (${q?.price?.let { "%.2f".format(it) } ?: "?"}). " +
                    (if (o.liveBid) "Bid ${"%.2f".format(o.bid)}." else "Market closed: premium is the last trade.") + " Use what Schwab fills."
            }
            draft = FillDraft.coveredCall(t, contracts, expiry, o?.strike, o?.premium, context)
            suggesting.value = null
            open()
        }
    }

    fun skip(id: Long) = viewModelScope.launch { repo.skip(id) }

    fun addWatch(t: String) = viewModelScope.launch { repo.addWatch(t); refreshQuotes() }

    fun removeWatch(t: String) = viewModelScope.launch { repo.removeWatch(t) }

    fun updateSettings(block: AppSettings.() -> AppSettings) = repo.settings.update(block)

    fun setApiKey(k: String?) = repo.settings.setApiKey(k)

    val keyTest = MutableStateFlow<String?>(null)

    fun testAnthropicKey() {
        keyTest.value = "Testing…"
        viewModelScope.launch { keyTest.value = repo.testAnthropicKey() }
    }

    // ---- Discovery -----------------------------------------------------------------------------

    val marketNews = MutableStateFlow<List<Headline>?>(null)
    val marketNewsLoading = MutableStateFlow(false)

    fun loadMarketNews(force: Boolean = false) {
        if (marketNewsLoading.value || (!force && marketNews.value != null)) return
        marketNewsLoading.value = true
        viewModelScope.launch {
            marketNews.value = runCatching { repo.marketNews() }.getOrElse { AppLog.e("Market news failed", it); emptyList() }
            marketNewsLoading.value = false
        }
    }

    val scan = MutableStateFlow<Work<Repository.SavedScan>>(Work.Idle)

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repo.lastScan()?.let { if (scan.value is Work.Idle) scan.value = Work.Done(it) }
        }
        // Quote whatever is held or watched as soon as the database answers, and again when it changes.
        viewModelScope.launch {
            combine(ledger, watchlist) { l, w -> (l.positions.map { it.instrument.ticker } + w).toSet() }
                .distinctUntilChanged()
                .collect { if (it.isNotEmpty()) refreshQuotes() }
        }
    }

    fun marketScan(model: ClaudeModel, focus: String) {
        if (scan.value is Work.Running) return
        val previous = scan.value
        scan.value = Work.Running("Starting")
        viewModelScope.launch {
            scan.value = try {
                Work.Done(repo.marketScan(model, focus.ifBlank { null }) { scan.value = Work.Running(it) })
            } catch (e: Exception) {
                AppLog.e("Market scan failed", e)
                toast.value = "Market scan failed: ${e.message}"
                if (previous is Work.Done) previous else Work.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    val screener = MutableStateFlow<Work<com.abeswastaken.trades.core.scanner.ScreenerResult>>(Work.Idle)

    fun runScreener() {
        if (screener.value is Work.Running) return
        screener.value = Work.Running("Starting")
        viewModelScope.launch {
            screener.value = try {
                Work.Done(repo.screen { screener.value = Work.Running(it) })
            } catch (e: Exception) {
                AppLog.e("Screener failed", e)
                Work.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Adds the ticker to the watchlist and asks for a recommendation focused on it. */
    fun askAbout(ticker: String, note: String) {
        viewModelScope.launch {
            repo.addWatch(ticker)
            recommend(settings.value.defaultModel, note)
        }
    }

    // ---- Reddit buzz ---------------------------------------------------------------------------

    val buzz = MutableStateFlow<Work<Repository.Buzz>>(repo.cachedBuzz()?.let { Work.Done(it) } ?: Work.Idle)

    fun loadBuzz(force: Boolean = false) {
        if (buzz.value is Work.Running) return
        val previous = buzz.value
        if (!force && previous is Work.Done && System.currentTimeMillis() - previous.value.at < 10 * 60_000) return
        buzz.value = Work.Running("Checking Reddit")
        viewModelScope.launch {
            buzz.value = try {
                Work.Done(repo.buzz(force))
            } catch (e: Exception) {
                AppLog.e("Buzz failed", e)
                if (previous is Work.Done) previous else Work.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    val buzzReport = MutableStateFlow<Work<Repository.SavedBuzzReport>>(Work.Idle)

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repo.lastBuzzReport()?.let { if (buzzReport.value is Work.Idle) buzzReport.value = Work.Done(it) }
        }
    }

    fun verifyBuzz(model: ClaudeModel) {
        if (buzzReport.value is Work.Running) return
        val previous = buzzReport.value
        buzzReport.value = Work.Running("Starting")
        viewModelScope.launch {
            buzzReport.value = try {
                Work.Done(repo.verifyBuzz(model) { buzzReport.value = Work.Running(it) })
            } catch (e: Exception) {
                AppLog.e("Reddit check failed", e)
                toast.value = "Reddit check failed: ${e.message}"
                if (previous is Work.Done) previous else Work.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---- Schwab import -------------------------------------------------------------------------

    val importPreview = MutableStateFlow<com.abeswastaken.trades.core.export.ImportPreview?>(null)

    fun previewImport(text: String) = viewModelScope.launch {
        try {
            importPreview.value = repo.previewImport(text)
        } catch (e: Exception) {
            AppLog.e("Import preview failed", e)
            toast.value = e.message ?: "Couldn't read that file"
        }
    }

    fun confirmImport() {
        val p = importPreview.value ?: return
        importPreview.value = null
        act("Imported ${p.fills.size} fill${if (p.fills.size == 1) "" else "s"}") { repo.importFills(p.fills) }
    }

    val newsTest = MutableStateFlow<Map<com.abeswastaken.trades.core.market.NewsSourceId, String>>(emptyMap())

    fun testNewsSource(id: com.abeswastaken.trades.core.market.NewsSourceId, key: String) {
        newsTest.value = newsTest.value + (id to "Testing…")
        viewModelScope.launch { newsTest.value = newsTest.value + (id to repo.testNewsSource(id, key)) }
    }

    val finnhubTest = MutableStateFlow<String?>(null)

    fun testFinnhubKey(key: String) {
        finnhubTest.value = "Testing…"
        viewModelScope.launch { finnhubTest.value = repo.testFinnhubKey(key) }
    }
}
