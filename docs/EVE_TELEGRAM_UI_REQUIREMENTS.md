# EVE — FINAL TELEGRAM UI + SOURCE-CODE REQUIREMENTS

**Status: OWNER-LOCKED FINAL REQUIREMENTS**  
**Effective date:** 2026-11-24  
**Repository:** `vishu762701/eve-app`  
**Telegram source of truth:** `DrKLO/Telegram` at commit `21f7d109da6ab3506c9e9577f98c6a38771ab335`

This is the authoritative product and implementation contract for every EVE agent and developer. If an earlier prompt, handoff, matrix, comment, or document conflicts with this file, this file wins. Do not silently reinterpret or weaken these requirements.

## 1. Product definition

EVE must use the **actual pinned Telegram Android source code and actual Telegram Android UI screens/components**, customized for EVE's identity, data, navigation, and exam-preparation product.

The goal is not merely a Telegram-inspired design, a screenshot imitation, isolated widgets, copied classes that are never used, or custom XML/Compose/Material recreations.

The goal is to integrate and run the original Telegram screen/component implementations and their complete required dependency closure in EVE, with EVE-specific data and actions bound into them.

## 2. Authoritative inputs — do not substitute

### 2.1 Pinned source code

- Repository: https://github.com/DrKLO/Telegram
- Commit: `21f7d109da6ab3506c9e9577f98c6a38771ab335`

Do not silently update this pin. A proposed pin change requires explicit owner approval and a separate source comparison.

### 2.2 Final 25-image reference pack

- Filename: `eve-telegram-ui-reference-pack-25.zip`
- In-repository path: `docs/reference/eve-telegram-ui-reference-pack-25.zip`
- SHA-256: `ca5e07965817862ede93c206ba78dfc41d88234d0426b699ad25503c9d0c0ff7`
- Contents: `README_REFERENCE_INDEX.txt` and exactly 25 original, unedited user-provided screenshots under `screenshots/`.

This is the **only approved visual reference set** for these 25 screens. Inspect the actual image files directly. The README is an index, not a substitute for viewing the images.

Do not use web-search imagery, AI-generated mockups, unrelated screenshots, stock UI examples, or earlier assistant-generated images as substitutes or competing design references. Discard conflicting earlier visual suggestions.

The archive SHA must be checked before every visual mission. If unavailable or mismatched, stop visual acceptance and report the blocker; do not guess or substitute.

### 2.3 Existing EVE product

The current EVE repository, working product logic, backend, user/data models, exam engine, and documented EVE requirements must be preserved and integrated. The screenshot pack does not replace EVE product requirements.

## 3. All 25 reference screens are mandatory

Every image below requires a corresponding customized EVE screen/state/interaction implementation. None may be silently skipped, replaced by a generic settings page, or marked complete from source inspection alone.

| # | Exact reference image | Required EVE screen/state |
|---:|---|---|
| 01 | `01_profile_birthday_context_menu.jpg` | Profile Birthday row and anchored Copy / Change / Remove menu |
| 02 | `02_profile_username_context_menu.jpg` | Profile Username row and anchored Copy username / Change username menu |
| 03 | `03_profile_phone_context_menu.jpg` | Profile Mobile row and anchored Copy / Replace number menu |
| 04 | `04_settings_ask_question_dialog.jpg` | Settings Help dialog with real EVE support, feedback and FAQ actions |
| 05 | `05_telegram_premium_screen.jpg` | EVE Premium presentation using the reference screen structure, hierarchy, selection and CTA; EVE products/billing only |
| 06 | `06_language_settings.jpg` | Language settings, language list, selection and search behavior |
| 07 | `07_power_saving_settings.jpg` | Power Saving screen, threshold control, feature toggles and persisted preferences |
| 08 | `08_data_storage_save_gallery_streaming_proxy.jpg` | Data/storage screen and every displayed EVE-relevant setting/action |
| 09 | `09_data_storage_usage_auto_download.jpg` | Storage/data usage and automatic download controls backed by real EVE storage/network behavior |
| 10 | `10_privacy_security_lower_section.jpg` | Lower Privacy & Security sections and EVE-equivalent actions |
| 11 | `11_privacy_settings.jpg` | Privacy controls and per-field visibility/settings behavior |
| 12 | `12_security_and_privacy_settings.jpg` | Security controls, verification, passcode, device/session and blocked-account behavior where applicable |
| 13 | `13_chat_settings_media_sound.jpg` | EVE app media, sound and interaction preferences using original screen components |
| 14 | `14_chat_settings_app_icon_gesture.jpg` | App icon selection, list-swipe configuration, Auto-Night and related app preferences |
| 15 | `15_chat_settings_chat_list_view.jpg` | List density/row layout and message-corner-style preference mapped to EVE lists/cards |
| 16 | `16_chat_settings_message_size_theme_wallpaper.jpg` | Text-size preview, wallpaper/theme/color selection and night-mode actions adapted to EVE |
| 17 | `17_account_profile_lower_section.jpg` | Account profile lower section and personal links/services mapped to real EVE capabilities |
| 18 | `18_account_profile_edit.jpg` | Account editing: name, last name, bio, phone, username, birthday and account actions |
| 19 | `19_settings_categories_help.jpg` | Settings categories/help section with working EVE equivalents |
| 20 | `20_main_settings_screen.jpg` | Main Settings screen, profile header, categories and navigation |
| 21 | `21_profile_qr_fullscreen.jpg` | Full-screen EVE profile QR, close/scan/share/customize/save actions as supported |
| 22 | `22_profile_overflow_menu.jpg` | Profile overflow menu: change profile color, username, copy profile link and equivalents |
| 23 | `23_profile_main_screen.jpg` | Main profile screen, avatar/actions, account details, linked EVE content and profile tabs |
| 24 | `24_chats_list_bottom_navigation.jpg` | Main list with archive/list rows, floating actions and EVE bottom navigation |
| 25 | `25_chats_list_search_bar.jpg` | Main list with search field, live search, list rows and EVE bottom navigation |

These are mandatory screen-level and state-level requirements, not optional inspiration.

## 4. Source-code integration — no shortcuts

For every screen in Section 3:

1. Find the exact screen implementation in the pinned Telegram source.
2. Identify its original Activity/Fragment, view classes, cells, adapters, layout containers, drawables, dimensions, theme keys, animations, gestures, delegates, helpers, resources and transitive dependencies.
3. Resolve the complete dependency closure required for the original UI to build and run.
4. Integrate/adapt the original implementation into EVE. Preserve original Telegram rendering, measurement, layout, drawing, touch handling, physics, transitions and animations.
5. Bind EVE's real account/product data and EVE actions at the appropriate boundary.
6. Prove the screen is reached through real EVE navigation and original Telegram UI classes participate in its live runtime view hierarchy.
7. Add automated regression/instrumentation coverage and compare emulator output with the corresponding owner screenshot.

A copied class that is never launched is not an implementation. A manifest entry or matching hash alone is not runtime evidence. A custom view that only looks similar is not an acceptable substitute for an available original Telegram component.

Do not replace applicable original Telegram screens/components with XML replicas, Compose replicas, Material components, custom-drawn imitations, third-party UI libraries, or simplified demos.

Kotlin may be used for EVE data/repository/authentication/business-logic glue and lifecycle hosting. It must not replace original Telegram rendering/interaction code where the upstream implementation is available.

If an exact upstream screen cannot be integrated because of a concrete dependency or product boundary, document exact upstream paths, dependency closure, blocker and proposed minimal adaptation. Do not silently substitute another UI. Obtain owner approval for a genuine exception.

## 5. EVE customization rules

Preserve Telegram screen structure and behavior while customizing only what EVE needs:

- EVE identity, branding, labels and product terminology;
- real authenticated EVE user data;
- exam, mock-test, result, bookmark, mistake and admin data;
- EVE navigation destinations and business actions;
- existing EVE backend/API integrations.

Do not hardcode sample names, phone numbers, usernames, email fragments, channel names, subscriber counts, dates or QR contents from screenshots. Bind real EVE data.

Preserve reference layout geometry, spacing, typography, component hierarchy, colors, icon treatment, touch targets, corner shapes, popup anchoring, scroll behavior, animations and transitions. No visual changes based on personal preference. A deviation requires an explicit owner decision recorded in the screen map.

Every visible control must have a real, tested EVE action. No decorative controls, fake switches, dead rows, fabricated statuses/counts or hardcoded success states.

Telegram-only services are not automatically EVE product requirements. Do not add Telegram messaging servers, Telegram Stars, Telegram Premium billing, channels or proxy infrastructure merely because a screenshot contains a related label. Preserve the screen's visual/component implementation and map actions to genuine EVE capabilities. If a row has no honest EVE equivalent, record the product decision as blocked; do not create a fake control or silently omit it.

## 6. EVE product functionality to preserve

Telegram UI integration must not regress or discard existing EVE functionality. Audit and preserve the actual implementations for:

- authentication and user sessions;
- Home and exam discovery;
- exam/category/test selection;
- test instructions and start/resume;
- questions, options, palette, navigation and review;
- monotonic timer, pause/resume and safe submission;
- negative marking and server-authoritative scoring;
- result summary and detailed review;
- attempt history and progress analytics;
- bookmarks, mistakes and practice/PYQ;
- syllabus, leaderboard and notifications;
- profile and account editing;
- admin functionality and authorization;
- Cloudflare Worker, D1, Firebase Auth/Functions and existing data/security rules.

Do not delete an existing EVE implementation until its callers, stored data, backend contracts, migrations, tests and replacement path have been audited. Retain working functionality throughout migration.

## 7. Required per-screen source map

Maintain `docs/EVE_TELEGRAM_SCREEN_SOURCE_MAP.md`. It must contain one row per reference image (01–25) and record:

- reference filename and archive SHA;
- exact pinned Telegram source paths and upstream class names;
- upstream SHA-256 for every imported Telegram-derived file;
- provenance mode in `PORT_MANIFEST.json` (`VERBATIM`, deletion-only `CUT`, minimal `SHIM`, `COMPOSED`, `TRANSPLANT` or EVE-owned `NEW`, using the verifier's exact definitions);
- complete supporting dependency/resource closure;
- EVE destination screen and data/action mapping;
- runtime navigation entry point;
- runtime hierarchy/instrumentation evidence;
- unit/instrumentation test names and results;
- reference-versus-emulator screenshot comparison evidence;
- remaining deviations/blockers and owner decisions.

No screen can be marked complete while required fields/evidence are missing. A hash or source-copy percentage is not a visual/runtime acceptance result.

## 8. Visual and functional acceptance

For each of the 25 screens:

1. Capture the actual EVE screen from a running emulator/device.
2. Compare against its exact supplied reference image under equivalent device size, density, font scale, theme and navigation mode.
3. Verify layout, hierarchy, dimensions, spacing, text wrapping, icons, colors, system bars, popup placement, scrolling and animation states.
4. Verify every visible control's real EVE behavior.
5. Verify back navigation, lifecycle recreation, dark/light behavior and no crash.
6. Store screenshot, comparison/diff and test evidence under a stable per-screen evidence path.
7. Mark `PASS`, `FAIL` or `UNVERIFIED`; never infer PASS from compilation or source review.

All 25 screens and relevant states are required. CI screenshots and automated tests are necessary, but owner physical-device comparison/acceptance remains separate and must never be fabricated.

## 9. Engineering, branch and delivery safety

- Read `AGENTS.md`, `BUILD_FAILURE_RULES.md`, this file, `EVE_PROJECT_STATE.md`, `HANDOFF.md` and `PORT_MANIFEST.json` before a code mission.
- Use a clearly named feature branch and a PR into `main`. Do not push directly to `main`, force-push or rewrite history.
- Inspect live repository/PR/branch state before every mission; do not trust stale summaries.
- Preserve tests, lint, source integrity, authorization, scoring, signing, migrations and production safeguards.
- Never weaken/delete tests, suppress failures, fake green CI, or skip a required screen to report completion.
- Diagnose the first causal error before editing. Make evidence-based, reviewable commits.
- Keep original license/copyright headers and comply with the pinned Telegram source license. Do not imply EVE is the official Telegram app.
- No production deployment, destructive migration or release publication without explicit owner approval.
- Report exact changed paths, commit SHA, workflow URL, test totals, artifacts and all unverified items.

## 10. Definition of complete

EVE is not complete until:

- all 25 screens have source mappings and customized EVE implementations;
- original pinned Telegram screen/component code and complete dependencies are used at runtime;
- no avoidable custom replica replaces an available upstream implementation;
- every visible action works with real EVE behavior/data;
- existing EVE product functionality remains intact;
- every screen passes automated, runtime and visual evidence requirements;
- the full required CI workflow is green on the exact final commit;
- the APK artifact is successfully published and downloadable when requested;
- the owner has reviewed and accepted physical-device results.

A plan, audit matrix, manifest hash, green compile, green CI alone or screenshot-only mockup does not mean EVE is complete.

---
**Authority:** The owner's final 25-image reference pack and these requirements supersede conflicting earlier design prompts and UI suggestions. Only the owner may approve a deviation.
