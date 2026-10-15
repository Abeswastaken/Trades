# Colvin Trades: context for Claude

Personal trade decision-support app for Jake. Read `README.md` for features. The v1 rebuild (2026-10-15) replaced the Python/KivyMD v0.1 with Kotlin + Compose.

## Hard lines (don't suggest otherwise, even if asked)
- No auto-executor. The app never places orders.
- No broker API integration. The ledger is entered by hand.
- No in-app scoreboard. The comparison lives outside the app (two Schwab balances).

## Design rules
- **The ledger is the spine.** `core/.../ledger/Ledger.kt` replays all fills (FIFO) into lots, closed lots, cash and positions. Never regress it; run `./gradlew -p core test` before any ledger change.
- **Pre-filter in plain code, then Claude ranks.** The scanner rejects by price/volume/DTE/OI/premium before any tokens are spent.
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
- Yahoo endpoints are unofficial; Finnhub only backs up quotes and news, not chains.
- Parked: push alerts, "Brief me on X", Schwab CSV import, widget.
