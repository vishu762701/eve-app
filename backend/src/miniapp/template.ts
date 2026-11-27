// ============================================================================
// Telegram Admin Mini App HTML Document Shell
// ============================================================================

import { MINIAPP_CSS } from "./styles";
import { MINIAPP_SCRIPT } from "./script";

export function renderMiniAppHtml(): string {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
  <title>EVE Admin Control Center</title>
  <script src="https://telegram.org/js/telegram-web-app.js"></script>
  <style>${MINIAPP_CSS}</style>
</head>
<body>
  <div id="toast"></div>
  <header class="topbar">
    <div class="topbar-title-wrap">
      <div id="topTitle" class="topbar-title">EVE Admin Control Center</div>
      <div id="topSub" class="topbar-sub">Owner Control Hub</div>
    </div>
    <span id="syncPill" class="sync-pill"><span class="sync-dot"></span><span id="syncText">Syncing</span></span>
  </header>
  <main id="viewRoot" class="main-wrap"></main>
  <div id="sheetBackdrop" class="sheet-backdrop" data-act="close-sheet-backdrop">
    <div id="sheetPanel" class="sheet-panel">
      <div class="sheet-handle"></div>
      <div id="sheetRoot"></div>
    </div>
  </div>
  <script>${MINIAPP_SCRIPT}</script>
</body>
</html>`;
}
