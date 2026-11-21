# Eve

Eve is a free mock test and exam prep Android app for Indian government and entrance exams.

## Repository Layout
- **app/**: Android application (official Telegram Java UI components + Kotlin glue).
- **backend/**: Cloudflare Worker API and D1 database service.
- **functions/**: Firebase Functions and server-side validation.
- **firestore.rules**, **storage.rules**: Firebase security rules and configuration.

## CI Build
GitHub Actions compiles the debug APK on pushes to main via Gradle (:app:assembleDebug) and publishes the verified artifact.

## Rules & Guidelines
See [AGENTS.md](AGENTS.md) first. It is the only rulebook for Eve development.

## License
Eve is licensed under the GNU General Public License (GPL). Telegram-derived files keep their original authorship and upstream headers. Eve is not affiliated with Telegram and does not use Telegram logos or branding.
