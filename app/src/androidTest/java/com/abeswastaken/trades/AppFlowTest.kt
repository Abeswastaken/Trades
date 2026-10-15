package com.abeswastaken.trades

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real UI: every tab opens, a fill can be logged, and it shows up in the ledger and positions. */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) = try {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("No text \"$text\" on screen after 10s", e)
    }

    @Test fun everyTabOpens() {
        rule.onNodeWithText("Colvin Trades").assertExists()
        rule.onNodeWithTag("market_clock").assertExists()
        rule.onNodeWithTag("tab_positions").performClick(); waitForText("Stock")
        rule.onNodeWithTag("tab_ledger").performClick(); waitForText("Fills (")
        rule.onNodeWithTag("tab_watch").performClick(); waitForText("Add ticker")
        rule.onNodeWithTag("watch_tab_1").performClick(); waitForText("Scan the whole market")
        rule.onNodeWithTag("market_scan").assertExists()
        rule.onNodeWithTag("watch_tab_2").performClick(); waitForText("Claude's cross-check")
        rule.onNodeWithTag("watch_tab_3").performClick(); waitForText("Covered-call finder")
        rule.onNodeWithTag("screener_run").assertExists()
        rule.onNodeWithTag("watch_tab_0").performClick(); waitForText("Add ticker")
        rule.onNodeWithTag("tab_settings").performClick(); waitForText("Anthropic Console")
        waitForText("News sources")
        rule.onNodeWithTag("news_GOOGLE").assertExists()
        rule.onNodeWithTag("news_REDDIT").assertExists()
        rule.onNodeWithTag("risk_HIGH").assertExists()
        rule.onNodeWithTag("tab_home").performClick(); waitForText("Needs you")
        rule.onNodeWithTag("home_alerts").assertExists()
    }

    @Test fun logAFillAndSeeItEverywhere() {
        rule.onNodeWithTag("tab_ledger").performClick()
        rule.onNodeWithTag("log_fill_fab").performClick()
        rule.onNodeWithTag("field_ticker").performTextInput("TST")
        rule.onNodeWithTag("field_qty").performTextInput("100")
        rule.onNodeWithTag("field_price").performTextInput("10.50")
        rule.onNodeWithTag("save_fill").performScrollTo().assertIsEnabled().performClick()
        waitForText("Buy 100 TST")
        rule.onAllNodesWithText("Buy 100 TST").onFirst().assertExists()
        rule.onNodeWithTag("tab_positions").performClick()
        waitForText("TST")
        rule.onAllNodesWithText("10.50").onFirst().assertExists()
    }

    @Test fun coveredCallFromSharesThenExpireAndEdit() {
        // Shares in, then the covered call comes prefilled from the position.
        rule.onNodeWithTag("tab_ledger").performClick()
        rule.onNodeWithTag("log_fill_fab").performClick()
        rule.onNodeWithTag("field_ticker").performTextInput("ZZQX")
        rule.onNodeWithTag("field_qty").performTextInput("100")
        rule.onNodeWithTag("field_price").performTextInput("10")
        rule.onNodeWithTag("save_fill").performScrollTo().performClick()
        waitForText("Buy 100 ZZQX")

        rule.onNodeWithTag("tab_positions").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("cc_ZZQX").fetchSemanticsNodes().isNotEmpty() }
        // Quotes refresh as Positions opens and shift the list, which can swallow a touch; invoke the button's click directly.
        rule.onNodeWithTag("cc_ZZQX").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(25_000) { rule.onAllNodesWithTag("field_strike", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("field_qty").assertTextContains("1")
        rule.onNodeWithTag("field_strike").performTextClearance()
        rule.onNodeWithTag("field_strike").performTextInput("11")
        rule.onNodeWithTag("field_price").performTextClearance()
        rule.onNodeWithTag("field_price").performTextInput("0.20")
        rule.onNodeWithTag("save_fill").performScrollTo().performClick()

        // Tap the option, mark it expired.
        waitForText("11C")
        rule.onAllNodes(hasText("11C", substring = true) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("option_expire").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("option_expire").performClick()
        rule.onNodeWithText("Confirm").performClick()
        rule.onNodeWithTag("tab_ledger").performClick()
        waitForText("(expired)")

        // Tap a fill to edit it.
        rule.onAllNodesWithText("Buy 100 ZZQX").onFirst().performClick()
        waitForText("Edit fill")
        rule.onNodeWithTag("field_price").performTextClearance()
        rule.onNodeWithTag("field_price").performTextInput("9.5")
        rule.onNodeWithTag("save_fill").performScrollTo().performClick()
        waitForText("@ 9.50")
    }
}
