# Colvin Trades

A personal Android app for one experiment: one Schwab account trades only on what Claude recommends, another on Jake's own picks, and the two balances get compared at month-end. The app gathers the context, asks Claude for **one** recommendation at a time, shows the math, and Jake places the order in Schwab by hand. It never places orders and never connects to a broker.

## Install
Every push to `main` builds a debug APK and publishes it under [Releases](../../releases). Download it on the phone and install over the previous version. Builds are all signed with the same key (`app/debug.keystore`), so updating keeps the ledger. **Never uninstall**: that wipes the ledger. Back up first from Settings if in doubt.

## First run
1. **Settings > Claude:** paste an Anthropic API key. It's stored encrypted with the Android Keystore.
2. **Settings > Account:** enter the starting cash of the Claude account.
3. **Ledger > Log fill:** enter any positions already open (e.g. 300 F @ 12.08 and 3 F 10/30 13.50 calls sold @ 0.14).
4. **Watchlist:** add tickers you'd be happy to own.
5. **Home > Ask Sonnet.**

## How a recommendation is made
1. Quotes for the watchlist and everything held (Yahoo Finance; Finnhub as an optional backup).
2. **Covered-call scanner** in plain code: price, volume, DTE, OTM %, open interest, bid and premium filters (editable in Settings). Only survivors reach Claude.
3. Headlines per ticker, digested by **Haiku** (toggle in Settings).
4. One structured call to **Sonnet** by default (Haiku or Opus per call) with cash, positions, candidates, earnings dates, the news digest, the last 10 recommendations with their outcomes, and an optional note.
5. The reply is a structured recommendation: action, order details, rationale, risks, exit plan, and the math.

**Mark as taken** opens Log Fill prefilled. The saved fill links back to the recommendation, so its realized P&L shows up on Home and is fed back into later calls.

## Ledger
- FIFO lots for stock and options (options x100). Sell-to-open/buy-to-close, plus **Expired** and **Assigned** buttons on short options.
- Cash = starting cash ± every fill, fees included.
- Wash-sale flags (IRS §1091, stock) are recomputed over the whole history after every change, so a rebuy logged later flips the flag on the earlier loss.
- CSV export of fills and closed trades; JSON backup/restore (without the API key).

## Code
- `core/` is pure Kotlin with no Android: ledger, scanner, market data, prompts, and the Claude client (official Anthropic Java SDK). Tests: `./gradlew -p core test`, which runs without the Android SDK.
- `app/` is Jetpack Compose with Room.
- CI (`.github/workflows/build.yml`) runs all tests and builds the APK.

## Hard lines
No auto-execution, no broker API, no in-app scoreboard. See `CLAUDE.md`.
