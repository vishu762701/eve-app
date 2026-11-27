// ============================================================================
// Telegram-Native Styling for EVE Admin Mini App (1:1 Control Center Look)
// Uses official --tg-theme-* CSS variables for automatic Light/Dark parity.
// ============================================================================

export const MINIAPP_CSS = `
:root {
  --bg: var(--tg-theme-bg-color, #ffffff);
  --sec-bg: var(--tg-theme-secondary-bg-color, #f1f3f5);
  --card: var(--tg-theme-section-bg-color, var(--tg-theme-bg-color, #ffffff));
  --text: var(--tg-theme-text-color, #111827);
  --hint: var(--tg-theme-hint-color, #6b7280);
  --link: var(--tg-theme-link-color, #2481cc);
  --btn: var(--tg-theme-button-color, #2481cc);
  --btn-text: var(--tg-theme-button-text-color, #ffffff);
  --danger: var(--tg-theme-destructive-text-color, #e53935);
  --sep: var(--tg-theme-section-separator-color, rgba(128, 128, 128, 0.22));
  --accent-green: #2e9e5b;
  --accent-amber: #f59e0b;
  --accent-indigo: #5856d6;
  --accent-teal: #0ea5e9;
  --accent-purple: #8b5cf6;
}
* {
  box-sizing: border-box;
  -webkit-tap-highlight-color: transparent;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
}
html, body {
  margin: 0;
  padding: 0;
  background: var(--sec-bg);
  color: var(--text);
  font-size: 15px;
  line-height: 1.38;
  overscroll-behavior-y: contain;
}
.topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 16px 10px;
  background: var(--sec-bg);
  backdrop-filter: blur(10px);
}
.topbar-title-wrap {
  min-width: 0;
  flex: 1;
}
.topbar-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.topbar-sub {
  font-size: 12px;
  color: var(--hint);
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sync-pill {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  background: rgba(36, 129, 204, 0.14);
  color: var(--link);
}
.sync-pill.ok {
  background: rgba(46, 158, 91, 0.16);
  color: var(--accent-green);
}
.sync-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.main-wrap {
  padding: 4px 14px 32px;
  max-width: 720px;
  margin: 0 auto;
}
.tg-section-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--link);
  padding: 10px 14px 6px;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}
.tg-card {
  background: var(--card);
  border-radius: 14px;
  margin-bottom: 14px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.tg-card.pad {
  padding: 14px;
}
.tg-row {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 12px 15px;
  position: relative;
  cursor: pointer;
  transition: background-color 0.12s ease;
  text-decoration: none;
  color: inherit;
}
.tg-row.static {
  cursor: default;
}
.tg-row:not(.static):active {
  background: rgba(128, 128, 128, 0.1);
}
/* Subtle 0.5px inset divider aligned after the icon */
.tg-card.inset-dividers > .tg-row:not(:last-child)::after {
  content: "";
  position: absolute;
  left: 60px;
  right: 0;
  bottom: 0;
  height: 0.5px;
  background: var(--sep);
}
.tg-card.full-dividers > .tg-row:not(:last-child)::after {
  content: "";
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 0;
  height: 0.5px;
  background: var(--sep);
}
.tg-icon-box {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #ffffff;
}
.tg-icon-box svg {
  width: 18px;
  height: 18px;
  display: block;
}
.ic-blue { background: #3b82f6; }
.ic-amber { background: #f59e0b; }
.ic-green { background: #22c55e; }
.ic-teal { background: #0ea5e9; }
.ic-indigo { background: #6366f1; }
.ic-purple { background: #8b5cf6; }
.ic-red { background: #ef4444; }
.ic-slate { background: #64748b; }
.tg-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 15px;
  color: #ffffff;
  background: var(--btn);
  overflow: hidden;
}
.tg-avatar.lg {
  width: 58px;
  height: 58px;
  font-size: 22px;
}
.tg-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.tg-row-body {
  flex: 1;
  min-width: 0;
}
.tg-row-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--text);
  word-break: break-word;
}
.tg-row-sub {
  font-size: 13px;
  color: var(--hint);
  margin-top: 2px;
  word-break: break-word;
}
.tg-right-val {
  font-size: 14px;
  font-weight: 600;
  color: var(--link);
  flex-shrink: 0;
}
.tg-chevron {
  color: var(--hint);
  opacity: 0.65;
  flex-shrink: 0;
  font-size: 16px;
}
/* Native iOS / Telegram animated toggle switch */
.tg-switch {
  position: relative;
  width: 46px;
  height: 27px;
  border-radius: 999px;
  background: rgba(128, 128, 128, 0.34);
  border: none;
  padding: 0;
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color 0.2s ease;
}
.tg-switch.on {
  background: var(--btn);
}
.tg-switch-knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 23px;
  height: 23px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.28);
  transition: transform 0.2s cubic-bezier(0.22, 1, 0.36, 1);
}
.tg-switch.on .tg-switch-knob {
  transform: translateX(19px);
}
/* Pills, Badges, Stats & Buttons */
.pills {
  display: flex;
  gap: 7px;
  overflow-x: auto;
  padding: 2px 2px 10px;
  scrollbar-width: none;
}
.pills::-webkit-scrollbar { display: none; }
.pill {
  flex-shrink: 0;
  border: none;
  border-radius: 999px;
  padding: 7px 13px;
  font-size: 13px;
  font-weight: 600;
  background: var(--card);
  color: var(--hint);
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.pill.active {
  background: var(--btn);
  color: var(--btn-text);
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}
.stat-grid.four {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}
.stat-box {
  background: var(--card);
  border-radius: 12px;
  padding: 10px 8px;
  text-align: center;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.stat-val {
  font-size: 18px;
  font-weight: 700;
  color: var(--text);
}
.stat-lbl {
  font-size: 11px;
  color: var(--hint);
  margin-top: 2px;
}
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  background: rgba(36, 129, 204, 0.14);
  color: var(--link);
}
.badge.ok { background: rgba(46, 158, 91, 0.16); color: var(--accent-green); }
.badge.warn { background: rgba(245, 158, 11, 0.16); color: var(--accent-amber); }
.badge.err { background: rgba(229, 57, 53, 0.16); color: var(--danger); }
.btn-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.tg-btn {
  border: none;
  border-radius: 11px;
  padding: 10px 14px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  background: var(--btn);
  color: var(--btn-text);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.tg-btn.sm { padding: 6px 10px; font-size: 12px; border-radius: 9px; }
.tg-btn.full { width: 100%; }
.tg-btn.sec {
  background: rgba(128, 128, 128, 0.14);
  color: var(--text);
}
.tg-btn.ok { background: var(--accent-green); color: #ffffff; }
.tg-btn.danger { background: rgba(229, 57, 53, 0.14); color: var(--danger); }
.tg-btn.danger.solid { background: var(--danger); color: #ffffff; }
.tg-field { margin-bottom: 11px; }
.tg-field label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: var(--hint);
  margin-bottom: 5px;
}
.tg-input, .tg-select, .tg-textarea {
  width: 100%;
  padding: 10px 12px;
  border-radius: 11px;
  border: 1px solid var(--sep);
  background: var(--sec-bg);
  color: var(--text);
  font-size: 14px;
  outline: none;
}
.tg-input:focus, .tg-select:focus, .tg-textarea:focus {
  border-color: var(--link);
}
.search-bar {
  width: 100%;
  padding: 10px 13px;
  border-radius: 12px;
  border: 1px solid var(--sep);
  background: var(--card);
  color: var(--text);
  font-size: 14px;
  margin-bottom: 10px;
  outline: none;
}
.poll-bar-bg {
  height: 7px;
  border-radius: 999px;
  background: rgba(128, 128, 128, 0.18);
  overflow: hidden;
  margin-top: 4px;
}
.poll-bar-fill {
  height: 100%;
  background: var(--btn);
  border-radius: 999px;
}
.banner-thumb {
  width: 72px;
  height: 44px;
  border-radius: 8px;
  object-fit: cover;
  background: var(--sec-bg);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--hint);
  overflow: hidden;
}
.banner-thumb img { width: 100%; height: 100%; object-fit: cover; }
/* Modal Bottom Sheet */
.sheet-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 60;
  display: none;
  align-items: flex-end;
  justify-content: center;
}
.sheet-backdrop.open { display: flex; }
.sheet-panel {
  width: 100%;
  max-width: 720px;
  max-height: 90vh;
  overflow-y: auto;
  background: var(--card);
  color: var(--text);
  border-radius: 18px 18px 0 0;
  padding: 14px 16px 28px;
  box-shadow: 0 -6px 28px rgba(0, 0, 0, 0.22);
}
.sheet-handle {
  width: 38px;
  height: 4px;
  border-radius: 999px;
  background: var(--sep);
  margin: 0 auto 12px;
}
.sheet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.sheet-title { font-size: 17px; font-weight: 700; }
#toast {
  position: fixed;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(17, 24, 39, 0.92);
  color: #ffffff;
  padding: 8px 15px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 500;
  z-index: 90;
  display: none;
  max-width: 88vw;
  text-align: center;
}
.empty-card {
  padding: 22px 16px;
  text-align: center;
  color: var(--hint);
  font-size: 14px;
}
.radio-pills {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}
.radio-pill {
  padding: 9px;
  border-radius: 10px;
  border: 1px solid var(--sep);
  background: var(--sec-bg);
  color: var(--text);
  font-weight: 700;
  cursor: pointer;
}
.radio-pill.active {
  background: var(--btn);
  color: var(--btn-text);
  border-color: var(--btn);
}
`;
