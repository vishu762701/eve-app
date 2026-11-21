## Done
- Mission 01A: 8 placeholder exam rows rendered by official Telegram DialogCell (cut, not rewritten) inside Telegram ActionBar and RecyclerListView.
- Mission 00C: Added BUILD_FAILURE_RULES.md and linked from AGENTS.md.
- Mission 01B: Wrapped 01A placeholder exam rows in official Telegram chat-list screen components:
  - Collapsing header with pull-down search bar and search icon (FragmentSearchField, same animation as upstream).
  - 3-dot visual menu (ActionBarMenuItem with settings sub-item).
  - Floating bottom glass tab bar (MainTabsLayout verbatim, AnimatedLinearLayout verbatim, ClickHelper verbatim, GlassTabView cut). Tabs: Exams | Leaderboard | Settings | Profile. Non-exam tabs launch existing activities.
  - Floating action button (FragmentFloatingButton cut, R.drawable.filled_fab_compose_32 verbatim). Compose button launches PracticeActivity; camera subButton removed.
  - Local search filtering via DialogsSearchAdapter (empty, results, and no-results states).
  - Updated PORT_MANIFEST.json with 35 tracked entries (21 VERBATIM, 5 CUT, 6 SHIM, 3 NEW) with byte-exact sha256 sums.
- Green CI build: run 44944178791 passed and generated Eve-debug-apk artifact.

## In progress
- None (Mission 01B implementation complete; pending owner device acceptance).

## Next
- Mission 01C / 01D per owner roadmap.

## Known bugs
- None.

## Open decisions
- None.
