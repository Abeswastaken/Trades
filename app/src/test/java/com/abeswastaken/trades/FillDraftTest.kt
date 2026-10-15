package com.abeswastaken.trades

import com.abeswastaken.trades.core.engine.Recommendation
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Side
import com.abeswastaken.trades.data.FillDraft
import com.abeswastaken.trades.data.Repository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FillDraftTest {
    @Test fun `a covered-call recommendation prefills a sell-to-open call`() {
        val rec = Recommendation(
            action = "SELL_CALL", ticker = "F", quantity = 3.0, limitPrice = 0.14, strike = 13.5,
            expiry = "2026-10-30", summary = "Sell 3 F calls", rationale = "", risks = "",
        )
        val d = Repository.draftFrom(7, rec)
        assertEquals(AssetType.CALL, d.type)
        assertEquals(Side.SELL, d.side)
        assertEquals("3", d.quantity)
        val f = d.toFill().getOrThrow()
        assertEquals(LocalDate.of(2026, 10, 30), f.instrument.expiry)
        assertEquals(7L, f.recommendationId)
        assertEquals(42.0, f.cashFlow, 1e-9)
    }

    @Test fun `bad input names the field`() {
        val e = FillDraft(ticker = "F", quantity = "0", price = "1").toFill().exceptionOrNull()
        assertTrue(e!!.message!!.contains("Quantity"))
        val e2 = FillDraft(ticker = "F", type = AssetType.CALL, quantity = "1", price = "1", strike = "13").toFill().exceptionOrNull()
        assertTrue(e2!!.message!!.contains("Expiry"))
    }
}
