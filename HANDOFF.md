## Done
- Mission 01A: 8 placeholder exam rows rendered by official Telegram DialogCell inside Telegram ActionBar and RecyclerListView.
- Mission 00C: Added BUILD_FAILURE_RULES.md and linked from AGENTS.md.
- Mission 01B: Added collapsing header, pull-down search field, floating bottom tabs, and floating compose button.
- Mission 01B-R (Exam list repair): Replaced hand-written approximations with transplanted Telegram code:
  - Header search field now added with upstream createFrame(MATCH_PARENT, 48, Gravity.TOP, 7, -2, 7, 0) per DialogsActivity:5255.
  - Pull-down animation uses official BoolAnimator animatorSearchVisible and -lerp(dp(4), dp(48), ...) offset per DialogsActivity:310, 1626-1628.
  - Tabs padding fixed to dp(MAIN_TABS_MARGIN + 4) = 12dp and max width 344dp per MainTabsActivity:326-327, layout params per MainTabsActivity:401.
  - Floating button uses FragmentFloatingButton.createDefaultLayoutParams() per DialogsActivity:4658 and offset logic per DialogsActivity:2901, 8801.
  - Removed 4 empty catch blocks around startActivity; activities launched directly.
  - Updated PORT_MANIFEST.json with 12 tracked transplanted blocks and recomputed sha256 hashes.

## In progress
- None (Mission 01B-R repair complete; awaiting owner device testing).

## Next
- Owner device acceptance testing ("ACCEPTED 01B-R").
- Mission 01C / 01D per roadmap.

## Known bugs
- None verified in static analysis/unit build; phone runtime UNVERIFIED.

## Open decisions
- None.
