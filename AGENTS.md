# AGENTS.md — the only rulebook for Eve

Eve: free mock test / exam prep Android app (Indian govt & entrance exams). Android (Java Telegram UI + Kotlin glue), Firebase Auth/Firestore, Cloudflare Worker + D1 backend. App UI language is English only.

## Scope
1. The owner's latest message defines the task. Do exactly that and nothing else: no extra redesign, refactor or cleanup.
2. One screen (or one feature) = one mission. Do not start the next until the owner marks the current one ACCEPTED.
3. If an instruction is unclear, wrong or would not work in a real app, say so and propose the fix. Do not follow it blindly.

## Telegram source (the only truth for Telegram-style screens)
4. The reference is the official Telegram Android source, pinned to commit `21f7d109da6ab3506c9e9577f98c6a38771ab335`. The owner provides it with each mission (it is NOT stored in this repo). Do not use any other implementation.
5. A Telegram-style screen must run the original Telegram Java classes (Fragment/Cells/Components) and their original custom views, physics and animations. Kotlin is only glue: auth, data, repositories, navigation, lifecycle host.
6. Never recreate a Telegram-looking screen with XML, Compose or Material. A copied class that is never launched is not a port.
7. Mapping rule: wherever Telegram shows chats, Eve shows exams (title = exam name, subtitle = next test info, badge = pending tests). Each mission states the exact mapping for its screen.

## Evidence and honesty
8. A screen is ACCEPTED only with all three: (a) a green GitHub Actions run URL, (b) the owner's phone screenshot/video compared to Telegram, (c) no crash in the owner's logcat. Anything else stays OPEN.
9. Never write "verified", "passed" or "fixed" for something you did not actually run. Write `UNVERIFIED` and say why. Every claim about code quotes a file path and line number.
10. Every crash fix needs: the stack trace, the root-cause line, the fix, and a regression test or an explicit note why none is possible.

## Repo hygiene
11. Delete only files proven obsolete for the screen you just finished (grep shows 0 callers). List them in the commit message.
12. No new report/ledger/provenance/checkpoint documents. The only living docs are `AGENTS.md`, `README.md`, `HANDOFF.md`, `PORT_MANIFEST.json` and `BUILD_FAILURE_RULES.md`.
13. After every mission update `HANDOFF.md` (max 30 lines): Done, In progress, Next, Known bugs, Open decisions.
14. Work on `main` in small commits. Never force-push. After pushing, verify the remote SHA and the Actions result.

## Security (never weaken)
15. No secrets in the repo (service-account JSON, release keystore, tokens). Admin authority is enforced on the backend/rules only, never trusted from the client. Do not weaken auth, Firestore/Storage rules, scoring or answer integrity to make a build pass.

## Licensing
16. Eve is open source (GPL). Telegram-derived files keep their original authorship and license headers. Do not use the Telegram name, logo or branding in the app.
17. On any CI failure follow BUILD_FAILURE_RULES.md. A mission may make it stricter, never looser.

## Telegram fidelity and port verification
18. Telegram UI fidelity is enforced by the pinned source verifier (`.github/scripts/verify_telegram_port.py`), not by comments or agent claims.
19. `VERBATIM`, `CUT`, `SHIM`, `COMPOSED`, `TRANSPLANT`, and `NEW` have precise meanings. Only byte-identical blocks may be called `TRANSPLANT`/`EVE-COPY`; adapted code is `COMPOSED`/`EVE-REF` and must be separately reviewed.
20. A `COMPOSED` screen may bind Eve data, lifecycle, navigation and events to original Telegram UI components, but must not recreate Telegram rendering, sizing, gesture physics, or animation with custom views/handwritten approximations. Any exception must be explicit, narrowly scoped, hash-bound, and remain `OPEN` until reviewed.
21. A copied class that is not actually reached/launched is not a port. Every screen mission must identify its runtime entry point and prove the original Telegram component classes are present in the running view hierarchy; later screen missions must add appropriate runtime instrumentation and manual device evidence.
22. Green compilation means only build success, not Telegram fidelity or screen acceptance.
23. Mission 01B-R remains OPEN until the owner supplies device comparison evidence and crash-free logcat. Do not start 01C.
