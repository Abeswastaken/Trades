package com.abeswastaken.trades

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
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

    private fun waitForText(text: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun everyTabOpens() {
        rule.onNodeWithText("Colvin Trades").assertExists()
        rule.onNodeWithTag("tab_positions").performClick(); waitForText("Stock")
        rule.onNodeWithTag("tab_ledger").performClick(); waitForText("Fills (")
        rule.onNodeWithTag("tab_watch").performClick(); waitForText("Add ticker")
        rule.onNodeWithTag("watch_tab_1").performClick(); waitForText("Scan the whole market")
        rule.onNodeWithTag("market_scan").assertExists()
        rule.onNodeWithTag("watch_tab_2").performClick(); waitForText("Covered-call finder")
        rule.onNodeWithTag("screener_run").assertExists()
        rule.onNodeWithTag("watch_tab_0").performClick(); waitForText("Add ticker")
        rule.onNodeWithTag("tab_settings").performClick(); waitForText("Anthropic Console")
        waitForText("News sources")
        rule.onNodeWithTag("news_GOOGLE").assertExists()
        rule.onNodeWithTag("tab_home").performClick(); waitForText("Get a recommendation")
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
}
