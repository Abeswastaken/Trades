package com.abeswastaken.trades.core

import com.abeswastaken.trades.core.ledger.Ledger
import com.abeswastaken.trades.core.ledger.OptionEvents
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LedgerTest {
    private val d = LocalDate.of(2026, 10, 6)
    private val f = Instrument.stock("F")
    private val fCall = Instrument.call("F", 13.5, LocalDate.of(2026, 10, 30))
    private var nextId = 1L
    private fun fill(inst: Instrument, side: Side, qty: Double, price: Double, day: Int = 0, fees: Double = 0.0) =
        Fill(nextId++, d.plusDays(day.toLong()), inst, side, qty, price, fees)

    @Test fun `buy then partial sell realizes FIFO gain and keeps the rest open`() {
        val fills = listOf(
            fill(f, Side.BUY, 100.0, 10.0),
            fill(f, Side.BUY, 100.0, 12.0, day = 1),
            fill(f, Side.SELL, 150.0, 13.0, day = 2),
        )
        val s = Ledger.compute(fills, 5000.0)
        // 100 @10 -> 13 = 300, 50 @12 -> 13 = 50
        assertEquals(350.0, s.realizedTotal, 1e-9)
        assertEquals(1, s.positions.size)
        assertEquals(50.0, s.positions[0].quantity, 1e-9)
        assertEquals(12.0, s.positions[0].avgCost, 1e-9)
        assertEquals(5000 - 1000 - 1200 + 1950.0, s.cash, 1e-9)
    }

    @Test fun `fees go into basis on open and reduce proceeds on close`() {
        val fills = listOf(fill(f, Side.BUY, 100.0, 10.0, fees = 1.0), fill(f, Side.SELL, 100.0, 11.0, day = 1, fees = 1.0))
        val s = Ledger.compute(fills, 2000.0)
        assertEquals(98.0, s.realizedTotal, 1e-9)
        assertEquals(2000 - 1001 + 1099.0, s.cash, 1e-9)
    }

    @Test fun `jake's first trade - 300 F and 3 covered calls, expired worthless`() {
        val buy = fill(f, Side.BUY, 300.0, 12.08)
        val sellCalls = fill(fCall, Side.SELL, 3.0, 0.14)
        val s1 = Ledger.compute(listOf(buy, sellCalls), 5000.0)
        assertEquals(5000 - 3624 + 42.0, s1.cash, 1e-9)
        assertEquals(3, s1.ledgerCalls())
        assertEquals(0, s1.coverableContracts("F"))

        val lot = s1.openLots.first { it.instrument == fCall }
        val expired = OptionEvents.expire(lot, nextId++, LocalDate.of(2026, 10, 30))
        val s2 = Ledger.compute(listOf(buy, sellCalls, expired), 5000.0)
        assertEquals(42.0, s2.realizedTotal, 1e-9)
        assertTrue(s2.closedLots.single().wasShort)
        assertEquals(3, s2.coverableContracts("F"))
    }

    @Test fun `assignment closes the call and sells 100 shares per contract at the strike`() {
        val buy = fill(f, Side.BUY, 300.0, 12.08)
        val sellCalls = fill(fCall, Side.SELL, 3.0, 0.14)
        val lot = Ledger.compute(listOf(buy, sellCalls), 5000.0).openLots.first { it.instrument == fCall }
        val events = OptionEvents.assignShortCall(lot, 3.0, 100, LocalDate.of(2026, 10, 30))
        val s = Ledger.compute(listOf(buy, sellCalls) + events, 5000.0)
        assertTrue(s.positions.isEmpty())
        // premium 42 + stock (13.50 - 12.08) * 300 = 426
        assertEquals(42.0 + 426.0, s.realizedTotal, 1e-6)
        assertEquals(5000 + 42 + 426.0, s.cash, 1e-6)
    }

    @Test fun `buy to close a short call realizes the difference`() {
        val s = Ledger.compute(listOf(fill(fCall, Side.SELL, 2.0, 0.50), fill(fCall, Side.BUY, 2.0, 0.10, day = 3)), 0.0)
        assertEquals(80.0, s.realizedTotal, 1e-9)
    }

    @Test fun `wash sale flags a loss when shares are rebought within 30 days, even if logged later`() {
        val buy = fill(f, Side.BUY, 100.0, 12.0)
        val sell = fill(f, Side.SELL, 100.0, 10.0, day = 10)
        val before = Ledger.compute(listOf(buy, sell), 5000.0)
        assertFalse(before.closedLots.single().washSale)

        val rebuy = fill(f, Side.BUY, 50.0, 10.5, day = 25)
        val after = Ledger.compute(listOf(buy, sell, rebuy), 5000.0)
        val lot = after.closedLots.single()
        assertTrue(lot.washSale)
        assertEquals(100.0, lot.washDisallowed, 1e-9) // half of the 200 loss
    }

    @Test fun `no wash sale outside 30 days or on gains`() {
        val buy = fill(f, Side.BUY, 100.0, 12.0)
        val sell = fill(f, Side.SELL, 100.0, 10.0, day = 10)
        val late = fill(f, Side.BUY, 100.0, 9.0, day = 45)
        assertFalse(Ledger.compute(listOf(buy, sell, late), 5000.0).closedLots.single().washSale)

        val g1 = fill(f, Side.BUY, 100.0, 10.0, day = 50)
        val g2 = fill(f, Side.SELL, 100.0, 11.0, day = 51)
        val g3 = fill(f, Side.BUY, 100.0, 10.5, day = 52)
        val gains = Ledger.compute(listOf(g1, g2, g3), 5000.0).closedLots
        assertFalse(gains.single().washSale)
    }

    @Test fun `fill order is by date then entry order, so backdated fills replay correctly`() {
        val sell = Fill(10, d.plusDays(5), f, Side.SELL, 100.0, 11.0, seq = 10)
        val buy = Fill(11, d, f, Side.BUY, 100.0, 10.0, seq = 11) // entered later, happened earlier
        val s = Ledger.compute(listOf(sell, buy), 2000.0)
        assertEquals(100.0, s.realizedTotal, 1e-9)
        assertTrue(s.positions.isEmpty())
    }

    private fun com.abeswastaken.trades.core.ledger.LedgerState.ledgerCalls() = shortCallContracts("F").toInt()
}
