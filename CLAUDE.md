# Colvin Trades: context for Claude

Personal trade decision-support app for Jake. Read `README.md` for features. The v1 rebuild (2026-10-15) replaced the Python/KivyMD v0.1 with Kotlin + Compose.

## Hard lines (don't suggest otherwise, even if asked)
- No auto-executor. The app never places orders.
- No broker API integration. The ledger is entered by hand or imported from a Schwab CSV export (`core/.../export/SchwabImport.kt`). Jake asked about the Schwab API on 2026-10-15 and chose CSV; revisit only if he gets an approved Schwab developer app.
- No in-app scoreboard. The comparison lives outside the app (two Schwab balances).

## Design rules
- **The ledger is the spine.** `core/.../ledger/Ledger.kt` replays all fills (FIFO) into lots, closed lots, cash and positions. Never regress it; run `./gradlew -p core test` before any ledger change.
- **Pre-filter in plain code, then Claude ranks.** The scanner rejects by price/volume/DTE/OI/premium before any tokens are spent. The market scan does the same: `MarketCandidates` filters Yahoo's lists, and Claude may only pick from them (other tickers are dropped). The covered-call screener uses no Claude at all.
- Reddit: `ApeWisdom` gives mention counts; `BuzzScreen` (core/engine/Buzz.kt) cross-checks them against quotes, volume and non-Reddit news in plain code and flags long shots. Claude (`BuzzVerifier`) is opt-in. Reddit headlines carry "Reddit" in their source and are labelled unverified in prompts.
- Margin (Settings toggle, off by default): `LedgerState.buyingPower` gives a Reg T estimate that replaces cash for sizing in scans, recommendations and tickets; the account type goes in the request, not the cached system prompt.
- `RiskLevel` (Settings) is appended to the recommendation and market-scan requests, not the cached system prompt.
- News goes through `NewsHub` (core/market/News.kt): one provider per source, toggled in Settings; keyed ones are skipped without a key. Add a source there, not in `MarketData`.
- **Sonnet by default** for recommendations; Haiku pre-digests news; Opus is per-call opt-in. Model IDs and prices live in `core/.../engine/Models.kt`.
- **Mark-as-taken loop:** recommendation leads to a prefilled Log Fill, and the saved fill carries `recommendationId`. Outcomes feed later prompts. Keep this working.
- Updates must install over the old APK: keep `app/debug.keystore` and never change `applicationId`.
- One feature per change, built and tested. Push back on UI for features that don't serve the experiment.

## Jake's style
Terse and direct. Has accepted that LLM picks are "educated friend", not edge; don't re-raise disclaimers.

## Layout
- `core/` is pure Kotlin (testable without Android): `model/`, `ledger/`, `market/` (Yahoo with crumb + Finnhub fallback), `scanner/`, `engine/` (prompts, schema, Anthropic Java SDK client, recommender), `export/`.
- `app/` is Android: `data/` (Room, settings with Keystore-encrypted key, repository, backup), `ui/` (Compose screens), `util/AppLog.kt` (file log viewable in Settings).
- Builds happen in GitHub Actions: JVM tests, emulator tests (`app/src/androidTest`), then `main` publishes a Release with the APK. Release builds are signed with the same committed key as debug.

## Known limits / parked
- Option marks cover calls only (Yahoo chain call); puts show premium and DTE.
- Wash sale is a flag plus disallowed amount, not a basis-adjustment engine.
- Yahoo endpoints are unofficial (quote, options, screener, saved lists, trending); Finnhub only backs up quotes, not chains. CI's "Live check" step prints what every real feed returns, without failing the build.
- Room is still at version 1. Before any entity change, commit `app/schemas/` (CI prints it in the "Print the Room schema" step) and add a Migration with a test.
- Dividends and transfers aren't in the ledger's cash.
- Parked: "Brief me on X", widget.

## Alerts
- `core/.../engine/Alerts.kt`: `AlertEngine` (plain-code rules, pure), `AlertSchedule` (9:31 ET, every 15/30 min, close minus 30, trading days only), `OpenBriefer` (the one Claude call at the open) and `MoveExplainer` ("why is it moving?" plus a lesson). `core/.../market/Edgar.kt` reads SEC filings (User-Agent is "ColvinTrades" plus the SimpleLogin alias Jake gave for the SEC; the SEC 403s without a contact email).
- App: `alerts/` holds the exact alarm (`AlertScheduler`, one alarm at a time, rescheduled after each run, boot and update), `AlertReceiver`, and `AlertService` (a short dataSync foreground service). Inbox, notebook and status are JSON files via `data/AlertStore.kt`, not Room. Alert settings persist with an `al_` prefix and are in the backup.
- Quiet mode and levels: only `AlertLevel.CRITICAL` (money at risk) buzzes in quiet mode. Alert keys dedupe (ticker + day, and threshold step for moves).
