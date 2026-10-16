package com.abeswastaken.trades

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollToNode
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

    /** Waits for [ok], and names what it was waiting for if it times out. */
    private fun waitFor(what: String, ms: Long = 10_000, ok: () -> Boolean) = try {
        rule.waitUntil(ms, ok)
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("Timed out after ${ms / 1000}s waiting for: $what", e)
    }

    private fun waitForText(text: String) = waitFor("text \"$text\"") {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun hasTag(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Clicks through the semantics action, so a layout shift mid-tap (quotes, a lookup landing) can't swallow it. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    /** Home is a lazy list, so scroll to the section rather than assume it's composed on a small screen. */
    private fun homeShowsNeedsYou() {
        waitFor("the Home list") { hasTag("home_list") }
        rule.onNodeWithTag("home_list").performScrollToNode(hasText("Needs you"))
        waitForText("Needs you")
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
        rule.onNodeWithTag("margin_switch").performScrollTo().assertExists()
        rule.onNodeWithTag("tab_home").performClick(); homeShowsNeedsYou()
        rule.onNodeWithTag("home_alerts").assertExists()
    }

    @Test fun alertSettingsAndTestAlertReachTheInbox() {
        rule.onNodeWithTag("tab_settings").performClick(); waitForText("Anthropic Console")
        rule.onNodeWithTag("alerts_switch").performScrollTo().assertExists()
        rule.onNodeWithTag("alerts_quiet").performScrollTo().assertExists()
        rule.onNodeWithTag("alerts_insider").performScrollTo().assertExists()
        rule.onNodeWithTag("alert_test").performScrollTo().tap()
        rule.onNodeWithTag("tab_home").performClick(); homeShowsNeedsYou()
        rule.onNodeWithTag("home_list").performScrollToNode(androidx.compose.ui.test.hasTestTag("inbox_button"))
        rule.onNodeWithTag("inbox_button").tap()
        waitForText("Notifications work")
        rule.onNodeWithTag("open_notebook").tap()
        waitForText("Every \"why is it moving?\" answer")
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
        waitFor("covered-call button") { hasTag("cc_ZZQX") }
        rule.onNodeWithTag("cc_ZZQX").performScrollTo().tap()
        waitFor("Log Fill with a strike field", 25_000) { hasTag("field_strike") }
        // Let the chain lookup land (it rewrites the context line) before typing over it.
        waitFor("chain lookup to finish", 25_000) {
            rule.onAllNodesWithText("Looking up a strike", substring = true).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("field_qty").assertTextContains("1")
        rule.onNodeWithTag("field_strike").performTextClearance()
        rule.onNodeWithTag("field_strike").performTextInput("11")
        rule.onNodeWithTag("field_price").performTextClearance()
        rule.onNodeWithTag("field_price").performTextInput("0.20")
        rule.onNodeWithTag("save_fill").performScrollTo().assertIsEnabled().tap()

        // Saved: the form closes. Then scroll the positions list down to the option and tap it.
        waitFor("covered call to save and the form to close") { !hasTag("field_strike") }
        rule.onNodeWithTag("positions_list").performScrollToNode(hasText("11C", substring = true))
        rule.onAllNodes(hasText("11C", substring = true) and hasClickAction()).onFirst().tap()
        waitFor("option action sheet") { hasTag("option_expire") }
        rule.onNodeWithTag("option_expire").tap()
        waitForText("Confirm")
        rule.onNodeWithText("Confirm").tap()
        rule.onNodeWithTag("tab_ledger").performClick()
        waitForText("Expired")

        // Tap a fill to edit it.
        rule.onAllNodes(hasText("Buy 100 ZZQX", substring = true) and hasClickAction()).onFirst().tap()
        waitForText("Edit fill")
        rule.onNodeWithTag("field_price").performTextClearance()
        rule.onNodeWithTag("field_price").performTextInput("9.5")
        rule.onNodeWithTag("save_fill").performScrollTo().tap()
        waitForText("@ 9.50")
    }
}
