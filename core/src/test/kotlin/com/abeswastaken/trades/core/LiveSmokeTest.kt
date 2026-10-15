package com.abeswastaken.trades.core

import com.abeswastaken.trades.core.engine.MarketCandidates
import com.abeswastaken.trades.core.market.AlphaVantageNews
import com.abeswastaken.trades.core.market.NewsSourceId
import com.abeswastaken.trades.core.market.NewsSources
import com.abeswastaken.trades.core.market.YahooMarketData
import com.abeswastaken.trades.core.scanner.CoveredCallScreener
import com.abeswastaken.trades.core.scanner.ScanRules
import com.abeswastaken.trades.core.scanner.ScreenerRules
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Hits the real feeds. Skipped unless LIVE_SMOKE is set; CI runs it as an informational step so we
 * learn when Yahoo or a news site changes, without blocking a build on someone else's outage.
 */
class LiveSmokeTest {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun report(name: String, block: suspend () -> String) = runBlocking {
        val line = runCatching { block() }.fold({ "OK   $name: $it" }, { "FAIL $name: ${it.message}" })
        println("LIVE $line")
    }

    @Test fun live() {
        assumeTrue(System.getenv("LIVE_SMOKE") != null)
        val today = LocalDate.now()
        val yahoo = YahooMarketData(http)
        report("yahoo quote") { yahoo.quotes(listOf("F", "AAPL")).values.joinToString { "${it.symbol} ${it.price}" } }
        report("yahoo screener 5-20") { yahoo.screen(5.0, 20.0, 1_000_000, 25).take(8).joinToString { it.symbol } }
        for (l in listOf("most_actives", "day_gainers", "day_losers")) {
            report("yahoo $l") { yahoo.predefined(l, 25).take(8).joinToString { "${it.symbol}(${it.avgVolume})" } }
        }
        report("yahoo trending") { yahoo.trending(15).joinToString() }
        report("yahoo nearest calls F") {
            val s = yahoo.nearestCalls("F", today, 1, 10)
            "${s.expirations.take(6)} calls=${s.calls.size} weeklies=${CoveredCallScreener.hasWeeklies(s.expirations, today)}"
        }
        report("screener run") {
            val r = CoveredCallScreener.run(yahoo, yahoo, today, ScreenerRules(universe = 20))
            "${r.universeSource} scanned=${r.scanned} noWeeklies=${r.noWeeklies} errors=${r.errors.size} top=" +
                r.hits.take(5).joinToString { "${it.quote.symbol} ${it.option.strike}C ${it.option.expiry} ${"%.1f".format(it.annualizedPercent)}%" }
        }
        report("market candidates") {
            MarketCandidates.gather(yahoo, yahoo, ScanRules(), 10_000.0, emptySet()).joinToString { "${it.quote.symbol}${it.tags}" }
        }
        val providers = NewsSources.build(http, NewsSourceId.entries.toSet(), emptyMap())
        for (p in providers) {
            report("news ${p.id.label} ticker") { p.ticker("AAPL", 3).joinToString(" | ") { "${it.title} @${it.publishedEpochSec}" } }
            report("news ${p.id.label} market") { p.market(3).joinToString(" | ") { "${it.title} @${it.publishedEpochSec}" } }
        }
        report("news Alpha Vantage demo") { AlphaVantageNews(http, "demo").ticker("AAPL", 3).joinToString(" | ") { "${it.title} ${it.sentiment}" } }
    }
}
