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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface RecRun {
    data object Idle : RecRun
    data class Running(val step: String) : RecRun
    data class Failed(val message: String) : RecRun
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

    /** Prefill for the Log Fill screen. */
    val draft = MutableStateFlow(FillDraft())

    val toast = MutableStateFlow<String?>(null)

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

    fun loadNews(symbol: String) {
        if (news.value.containsKey(symbol)) return
        viewModelScope.launch {
            val n = runCatching { repo.news(symbol) }.getOrElse { AppLog.e("News $symbol failed", it); emptyList() }
            news.value = news.value + (symbol to n)
        }
    }

    fun recommend(model: ClaudeModel, focus: String, onDone: (Long) -> Unit) {
        if (recRun.value is RecRun.Running) return
        recRun.value = RecRun.Running("Starting")
        viewModelScope.launch {
            try {
                val id = repo.recommend(model, focus.ifBlank { null }) { step -> recRun.value = RecRun.Running(step) }
                recRun.value = RecRun.Idle
                onDone(id)
            } catch (e: Exception) {
                AppLog.e("Recommendation failed", e)
                recRun.value = RecRun.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun startDraftFromRec(rec: RecEntity): Boolean = runCatching {
        draft.value = Repository.draftFrom(rec.id, Recommender.parse(rec.json))
    }.isSuccess

    fun startDraft(d: FillDraft = FillDraft()) { draft.value = d }

    fun saveDraft(d: FillDraft, onSaved: () -> Unit) {
        val fill = d.toFill().getOrElse { toast.value = it.message; return }
        viewModelScope.launch {
            repo.addFill(fill)
            draft.value = FillDraft()
            toast.value = "Fill saved"
            onSaved()
        }
    }

    fun deleteFill(id: Long) = viewModelScope.launch { repo.deleteFill(id); toast.value = "Fill deleted" }

    fun expire(p: Position) = viewModelScope.launch { repo.expire(p, LocalDate.now()); toast.value = "Marked expired" }

    fun assign(p: Position) = viewModelScope.launch { repo.assign(p, LocalDate.now()); toast.value = "Marked assigned" }

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

    val finnhubTest = MutableStateFlow<String?>(null)

    fun testFinnhubKey(key: String) {
        finnhubTest.value = "Testing…"
        viewModelScope.launch { finnhubTest.value = repo.testFinnhubKey(key) }
    }
}
