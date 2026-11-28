# EVE App Handoff (Authoritative Android Base = `android/`)
- Branch: `main` only (no alternate branches/PRs, direct commit+push to `main`).
- Latest batch (2026-11-28):
  1. Fixed `EveTestPlayerFragment` `Start Test` UI-thread crash (`SimpleGlass.setViews` + guarded `glassEngine.invalidate()` + lifecycle timer/draft/render guards) & redesigned Start Test launch popup in `EveExamTestsFragment` as an official Telegram `BottomSheet` (`Start Test` -> `Leaderboard` -> red `Cancel`).
  2. Anchored Telegram `EditTextEmoji`/`EmojiView` strictly to bottom keyboard slot via `EmojiHostSizeNotifierFrameLayout` & fixed `EditTextEmoji` cursor fallback; decoded `[tg_emoji:...]` everywhere including ActionBars, `EveExamDialogsMapper`, `DialogCell`, and `PremiumPreviewFragment` (EVE Premium).
  3. Implemented live animated emoji avatars (`tg_anim:<docId>:<hex>` via `VectorAvatarThumbDrawable`), unlocked `AvatarConstructorFragment` background gradients + custom color picker in `eveLocalAvatarMode = true` ONLY (leaving personal Telegram MTProto locked), fixed `01.01.70` zero-date in `DialogCell`, switched `EveAnnouncementsFragment` to `Theme.key_windowBackgroundWhite` + inset hairline dividers, kept only Night Mode in `Exams` 3-dot menu (`DialogsActivity`), and compacted Home/Admin banner height to `120dp`.
- Backend check: `cd backend && npm run build && npm test` passed `420/420`.
- Next: Monitor GitHub Actions CI on `main`, then deploy Cloudflare Worker (`backend/`) if avatar descriptor header persistence is needed live, and test `:TMessagesProj_App:assembleAfatDebug` APK on phone.
