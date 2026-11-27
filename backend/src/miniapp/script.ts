// ============================================================================
// Client Controller Script for Telegram Admin Mini App
// Pure ES6+ Browser JS (no external build step) with Telegram BackButton stack,
// HapticFeedback, and 0ms localStorage SWR (eve_miniapp_swr_v2:*).
// Note: Uses explicit '+' concatenation instead of template interpolation so TS
// template string never conflicts with browser expressions.
// ============================================================================

export const MINIAPP_SCRIPT = `
(function () {
  var tg = window.Telegram && window.Telegram.WebApp;
  if (tg) {
    try { tg.ready(); tg.expand(); } catch (e) {}
  }
  var initData = (tg && tg.initData) || '';
  var SWR_PREFIX = 'eve_miniapp_swr_v2:';
  var renderedCache = {};
  var stateCache = {};
  var navStack = [{ view: 'hub', params: {} }];
  var currentSheetHandler = null;
  var searchDebounce = null;

  // Compact inline SVGs matching Telegram Settings square icon tiles
  var ICONS = {
    exams: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>',
    content: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="14" rx="2"/><path d="M8 21h8"/><path d="M12 18v3"/></svg>',
    students: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
    orders: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="5" width="20" height="14" rx="2"/><line x1="2" y1="10" x2="22" y2="10"/></svg>',
    premium: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>',
    subs: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l3 6 6 .9-4.5 4.4 1.1 6.2L12 16.5l-5.6 3 1.1-6.2L3 8.9 9 8z"/></svg>',
    analytics: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 3v18h18"/><path d="M7 14l4-4 4 3 5-6"/></svg>',
    config: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>',
    roles: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>',
    audit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="17" x2="14" y2="17"/></svg>'
  };

  function haptic(kind) {
    try {
      if (tg && tg.HapticFeedback) {
        if (kind === 'success' || kind === 'error' || kind === 'warning') {
          tg.HapticFeedback.notificationOccurred(kind);
        } else {
          tg.HapticFeedback.impactOccurred(kind || 'light');
        }
      }
    } catch (e) {}
  }

  function confirmDialog(message, onYes) {
    if (tg && typeof tg.showConfirm === 'function') {
      tg.showConfirm(message, function (ok) {
        if (ok) onYes();
      });
    } else if (window.confirm(message)) {
      onYes();
    }
  }

  function esc(s) {
    return String(s === undefined || s === null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function toast(msg) {
    var el = document.getElementById('toast');
    if (!el) return;
    el.textContent = msg;
    el.style.display = 'block';
    clearTimeout(window.__eveToastTimer);
    window.__eveToastTimer = setTimeout(function () { el.style.display = 'none'; }, 2400);
  }

  function setSyncPill(text, ok) {
    var pill = document.getElementById('syncPill');
    var label = document.getElementById('syncText');
    if (label) label.textContent = text;
    if (pill) pill.className = 'sync-pill' + (ok ? ' ok' : '');
  }

  function setHeader(title, sub) {
    var t = document.getElementById('topTitle');
    var s = document.getElementById('topSub');
    if (t) t.textContent = title;
    if (s) s.textContent = sub || '';
  }

  function swrGet(key) {
    try {
      var raw = localStorage.getItem(SWR_PREFIX + key);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  function swrSet(key, data) {
    try {
      var raw = JSON.stringify(data);
      localStorage.setItem(SWR_PREFIX + key, raw);
      stateCache[key] = data;
      return raw;
    } catch (e) { return ''; }
  }

  function swrInvalidate(prefixes) {
    try {
      var list = Array.isArray(prefixes) ? prefixes : [prefixes];
      var removeKeys = [];
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && k.indexOf(SWR_PREFIX) === 0) {
          var shortKey = k.slice(SWR_PREFIX.length);
          if (list.some(function (p) { return shortKey.indexOf(p) === 0; })) {
            removeKeys.push(k);
          }
        }
      }
      removeKeys.forEach(function (k) { localStorage.removeItem(k); });
      Object.keys(renderedCache).forEach(function (k) {
        if (list.some(function (p) { return k.indexOf(p) === 0; })) {
          delete renderedCache[k];
        }
      });
    } catch (e) {}
  }

  function api(path, opts) {
    opts = opts || {};
    return fetch('/api/telegram/miniapp' + path, {
      method: opts.method || 'GET',
      body: opts.body,
      headers: Object.assign({
        'Content-Type': 'application/json',
        'X-Telegram-Init-Data': initData
      }, opts.headers || {})
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (!res.ok || data.success === false) {
          throw new Error(data.error || ('HTTP ' + res.status));
        }
        return data;
      });
    });
  }

  function swrLoad(cacheKey, apiPath, renderer) {
    var cached = swrGet(cacheKey);
    if (cached) {
      stateCache[cacheKey] = cached;
      renderedCache[cacheKey] = JSON.stringify(cached);
      renderer(cached);
      setSyncPill('Cached · Syncing', false);
    } else {
      document.getElementById('viewRoot').innerHTML = '<div class="tg-card pad empty-card">Loading...</div>';
      setSyncPill('Syncing', false);
    }
    return api(apiPath).then(function (resp) {
      var fresh = resp.data;
      var freshJson = JSON.stringify(fresh);
      stateCache[cacheKey] = fresh;
      if (renderedCache[cacheKey] !== freshJson) {
        renderedCache[cacheKey] = freshJson;
        if (currentRouteKey() === cacheKey || currentRoute().view === cacheKey.split(':')[0]) {
          renderer(fresh);
        }
      }
      swrSet(cacheKey, fresh);
      setSyncPill('Live', true);
      return fresh;
    }).catch(function (err) {
      setSyncPill(cached ? 'Offline Cache' : 'Error', false);
      if (!cached) {
        document.getElementById('viewRoot').innerHTML = '<div class="tg-card pad empty-card">' + esc(err.message) + '<br><br><button class="tg-btn sec sm" data-act="reload-view">Retry</button></div>';
      } else {
        toast(err.message);
      }
    });
  }

  function currentRoute() {
    return navStack[navStack.length - 1] || { view: 'hub', params: {} };
  }

  function currentRouteKey() {
    var r = currentRoute();
    return r.view + (r.params && r.params.id ? ':' + r.params.id : '');
  }

  function syncBackButton() {
    if (!tg || !tg.BackButton) return;
    try {
      if (navStack.length > 1) tg.BackButton.show();
      else tg.BackButton.hide();
    } catch (e) {}
  }

  function navigate(view, params) {
    haptic('light');
    closeSheet();
    navStack.push({ view: view, params: params || {} });
    syncBackButton();
    renderCurrentView();
    window.scrollTo(0, 0);
  }

  function goBack() {
    if (document.getElementById('sheetBackdrop').classList.contains('open')) {
      closeSheet();
      return;
    }
    if (navStack.length > 1) {
      haptic('light');
      navStack.pop();
      syncBackButton();
      renderCurrentView();
      window.scrollTo(0, 0);
    }
  }

  if (tg && tg.BackButton) {
    try { tg.BackButton.onClick(goBack); } catch (e) {}
  }

  function openSheet(title, bodyHtml, onSubmit) {
    currentSheetHandler = onSubmit || null;
    document.getElementById('sheetRoot').innerHTML =
      '<div class="sheet-header"><span class="sheet-title">' + esc(title) + '</span>' +
      '<button class="tg-btn sec sm" data-act="close-sheet">Close</button></div>' +
      bodyHtml;
    document.getElementById('sheetBackdrop').classList.add('open');
  }

  function closeSheet() {
    currentSheetHandler = null;
    document.getElementById('sheetBackdrop').classList.remove('open');
    document.getElementById('sheetRoot').innerHTML = '';
  }

  function renderSwitch(on, act, id, extraAttr) {
    return '<button type="button" class="tg-switch' + (on ? ' on' : '') + '" data-act="' + esc(act) + '"' +
      (id ? ' data-id="' + esc(id) + '"' : '') + (extraAttr || '') +
      '><span class="tg-switch-knob"></span></button>';
  }

  function avatarHtml(name, avatarUrl, emoji, large) {
    var cls = 'tg-avatar' + (large ? ' lg' : '');
    if (avatarUrl) {
      return '<div class="' + cls + '"><img src="' + esc(avatarUrl) + '" alt="" onerror="this.remove()" /></div>';
    }
    var txt = emoji || (String(name || '?').trim().slice(0, 2).toUpperCase() || '?');
    return '<div class="' + cls + '">' + esc(txt) + '</div>';
  }

  function formatDate(ms) {
    if (!ms) return '—';
    try {
      return new Date(Number(ms)).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    } catch (e) { return '—'; }
  }

  function readFileAsBase64(file, cb) {
    var reader = new FileReader();
    reader.onload = function () {
      var res = String(reader.result || '');
      var idx = res.indexOf(',');
      cb(idx >= 0 ? res.slice(idx + 1) : res, file.type || '', file.name || '');
    };
    reader.readAsDataURL(file);
  }

  // ===================== VIEW RENDERERS =====================

  function renderCurrentView() {
    var r = currentRoute();
    var p = r.params || {};
    if (r.view === 'hub') {
      setHeader('EVE Admin Control Center', 'Telegram Owner Workspace');
      swrLoad('overview', '/overview', renderHub);
    } else if (r.view === 'exams') {
      setHeader('Exams & Sub-Exams', 'Catalog, Avatars & AI Factory');
      swrLoad('exams', '/exams', renderExamsList);
    } else if (r.view === 'examDetail') {
      setHeader(p.examName || 'Exam Detail', 'Settings, AI Studio, Tests & Bank');
      swrLoad('exams', '/exams', function (list) { renderExamDetail(list, p.id); });
    } else if (r.view === 'questionBank') {
      setHeader((p.examName || 'Exam') + ' · Question Bank', 'Verified Questions & Bulk JSON Import');
      swrLoad('qbank:' + p.id, '/exams/' + encodeURIComponent(p.id) + '/questions', function (qs) { renderQuestionBank(qs, p); });
    } else if (r.view === 'contentHub') {
      setHeader('Content & Moderation', 'Banners, Broadcasts, Polls, Flags & Feedback');
      var tab = p.tab || 'banners';
      swrLoad('content', '/content', function (data) { renderContentHub(data, tab); });
    } else if (r.view === 'flaggedFixer') {
      setHeader('Flagged Question Fixer', 'Inspect, Repair & Resolve');
      swrLoad('report:' + p.id, '/reports/' + encodeURIComponent(p.id) + '/detail', function (detail) { renderFlaggedFixer(detail); });
    } else if (r.view === 'students') {
      setHeader('Students Directory', 'Search, Filter & Account Actions');
      var filter = p.filter || 'all';
      var q = p.q || '';
      swrLoad('students:' + filter + ':' + q, '/students?filter=' + encodeURIComponent(filter) + '&q=' + encodeURIComponent(q), function (data) { renderStudentsDir(data, filter, q); });
    } else if (r.view === 'studentProfile') {
      setHeader(p.name || 'Student Profile', 'Performance, Attempts & Premium');
      swrLoad('student:' + p.id, '/students/' + encodeURIComponent(p.id), renderStudentProfile);
    } else if (r.view === 'orders') {
      setHeader('Payment Orders', 'UPI Approval Queue & Ledger');
      var st = p.status || 'pending';
      swrLoad('orders:' + st, '/orders?status=' + encodeURIComponent(st), function (data) { renderOrders(data, st); });
    } else if (r.view === 'premiumConfig') {
      setHeader('Premium Plans & UPI', 'Pricing, Validity & Merchant QR/UPI');
      swrLoad('premiumConfig', '/premium/config', renderPremiumConfig);
    } else if (r.view === 'subscribers') {
      setHeader('Premium Subscribers', 'Active & Lifetime Entitlements');
      swrLoad('subscribers', '/premium/subscribers', renderSubscribers);
    } else if (r.view === 'analyticsHealth') {
      setHeader('Analytics & Health', 'Exams, Crashes, Security & AI Usage');
      var atab = p.tab || 'exams';
      swrLoad('analyticsHealth', '/analytics-health', function (data) { renderAnalyticsHealth(data, atab); });
    } else if (r.view === 'config') {
      setHeader('Maintenance & AI Config', 'Global App State & Multi-Provider AI Router');
      swrLoad('overview', '/overview', function (data) { renderConfigView(data.config || {}); });
    } else if (r.view === 'roles') {
      setHeader('Admin Roles', 'Team Access & Granular Permissions');
      swrLoad('roles', '/roles', renderRolesList);
    } else if (r.view === 'roleEdit') {
      setHeader(p.email || 'Admin Permissions', 'Telegram-style Rights Editor');
      swrLoad('roles', '/roles', function (data) { renderRoleEdit(data, p.email); });
    } else if (r.view === 'auditLog') {
      setHeader('Audit Log', 'Categorized Admin Activity History');
      var cat = p.category || 'all';
      var aq = p.q || '';
      swrLoad('audit:' + cat + ':' + aq, '/audit-log?category=' + encodeURIComponent(cat) + '&q=' + encodeURIComponent(aq), function (data) { renderAuditLog(data, cat, aq); });
    }
  }

  // -------- 1. EXACT 3-CARD HOME HUB --------
  function renderHub(data) {
    var k = (data && data.kpis) || {};
    var cfg = (data && data.config) || {};
    var maintBadge = cfg.maintenanceMode ? 'Maintenance ON' : ('AI: ' + (cfg.aiProvider || 'auto'));
    setHeader('EVE Admin Control Center', maintBadge + ' · Owner Mode');

    var html = '';
    html += '<div class="tg-section-label">Exams &amp; Content</div>';
    html += '<div class="tg-card">';
    html += hubRow('ic-blue', ICONS.exams, 'Exams &amp; Sub-Exams', (k.liveExamsCount || k.totalExams || 0) + ' active exams &amp; question banks', (k.totalExams || 0), 'nav-exams');
    html += hubRow('ic-amber', ICONS.content, 'Content &amp; Moderation', (k.pendingFlags || 0) + ' flags · ' + (k.bannersCount || 0) + ' banners · ' + (k.pollsCount || 0) + ' polls', ((k.pendingFlags || 0) + (k.bannersCount || 0) + (k.pollsCount || 0)), 'nav-content');
    html += '</div>';

    html += '<div class="tg-section-label">Students &amp; Revenue</div>';
    html += '<div class="tg-card">';
    html += hubRow('ic-green', ICONS.students, 'Students', (k.onlineStudents || 0) + ' online now · Manage accounts', (k.totalStudents || 0), 'nav-students');
    html += hubRow('ic-teal', ICONS.orders, 'Payment Orders', '₹' + (k.revenueInr || 0) + ' earned · ' + (k.pendingOrders || 0) + ' pending', (k.pendingOrders || 0) + '/' + (k.totalOrders || 0), 'nav-orders');
    html += hubRow('ic-amber', ICONS.premium, 'Premium Plans &amp; UPI', 'Pricing, duration &amp; UPI config', cfg.maintenanceMode ? 'Paused' : 'UPI', 'nav-premium-cfg');
    html += hubRow('ic-purple', ICONS.subs, 'Premium Subscribers', 'Active plans &amp; manual entitlements', (k.activePremium || 0), 'nav-subscribers');
    html += '</div>';

    html += '<div class="tg-section-label">System &amp; Access</div>';
    html += '<div class="tg-card">';
    html += hubRow('ic-indigo', ICONS.analytics, 'Analytics &amp; Health', (k.totalAttempts || 0) + ' attempts · Avg ' + (k.avgScore || 0) + ' · ' + (k.openCrashes || 0) + ' crashes', (k.openCrashes || 0), 'nav-analytics');
    html += hubRow('ic-slate', ICONS.config, 'Maintenance &amp; AI Config', (cfg.maintenanceMode ? '🔴 Maintenance ON' : '🟢 App Live') + ' · Router: ' + esc(cfg.aiProvider || 'auto'), esc(cfg.aiProvider || 'auto'), 'nav-config');
    html += hubRow('ic-red', ICONS.roles, 'Admin Roles', 'Manage team &amp; permissions', (k.adminsCount || 1), 'nav-roles');
    html += hubRow('ic-blue', ICONS.audit, 'Audit Log', 'Categorized admin action history', 'History', 'nav-audit');
    html += '</div>';

    document.getElementById('viewRoot').innerHTML = html;
  }

  function hubRow(iconClass, svg, title, sub, rightVal, act) {
    return '<div class="tg-row" data-act="' + act + '">' +
      '<span class="tg-icon-box ' + iconClass + '">' + svg + '</span>' +
      '<div class="tg-row-body"><div class="tg-row-title">' + title + '</div><div class="tg-row-sub">' + sub + '</div></div>' +
      '<span class="tg-right-val">' + esc(rightVal) + '</span>' +
      '</div>';
  }

  // -------- 2. EXAMS & SUB-EXAMS --------
  function renderExamsList(list) {
    list = Array.isArray(list) ? list : [];
    var topExams = list.filter(function (e) { return !e.parentExamId; });
    var subCountMap = {};
    list.forEach(function (e) {
      if (e.parentExamId) subCountMap[e.parentExamId] = (subCountMap[e.parentExamId] || 0) + 1;
    });

    var html = '<div class="btn-row" style="margin-bottom:10px">' +
      '<button class="tg-btn full" data-act="open-create-exam">+ Create Top-Level Exam</button>' +
      '</div>';

    if (!topExams.length) {
      html += '<div class="tg-card pad empty-card">No exams yet. Tap "+ Create Top-Level Exam" above.</div>';
    } else {
      html += '<div class="tg-card inset-dividers">';
      topExams.forEach(function (ex) {
        var subs = subCountMap[ex.id] || 0;
        var modeBadge = '<span class="badge ' + (ex.publishMode === 'paused' ? 'warn' : 'ok') + '">' + esc(ex.publishMode || 'inherit') + '</span>';
        var subBadge = subs > 0 ? ' <span class="badge">' + subs + ' sub</span>' : '';
        html += '<div class="tg-row" data-act="open-exam" data-id="' + esc(ex.id) + '" data-name="' + esc(ex.examName) + '">' +
          avatarHtml(ex.examName, ex.avatarUrl, ex.avatarEmoji, false) +
          '<div class="tg-row-body"><div class="tg-row-title">' + esc(ex.examName) + ' ' + modeBadge + subBadge + '</div>' +
          '<div class="tg-row-sub">' + esc(ex.category || 'General') + ' · ' + (ex.questionCount || 20) + ' Qs · ' + (ex.tests ? ex.tests.length : 0) + ' tests · ' + (ex.questionsCount || 0) + ' bank Qs</div></div>' +
          '<span class="tg-chevron">›</span>' +
          '</div>';
      });
      html += '</div>';
    }
    document.getElementById('viewRoot').innerHTML = html;
  }

  function renderExamDetail(list, examId) {
    list = Array.isArray(list) ? list : [];
    var ex = list.find(function (e) { return e.id === examId; });
    if (!ex) {
      document.getElementById('viewRoot').innerHTML = '<div class="tg-card pad empty-card">Exam not found.</div>';
      return;
    }
    setHeader(ex.examName, (ex.category || 'General') + (ex.parentExamId ? ' · Sub-Exam' : ' · Exam'));
    var subExams = list.filter(function (e) { return e.parentExamId === ex.id; });
    var tests = ex.tests || [];

    var html = '';
    // Header Avatar + Edit Settings + Upload Photo
    html += '<div class="tg-card pad"><div class="tg-row static" style="padding:0">' +
      avatarHtml(ex.examName, ex.avatarUrl, ex.avatarEmoji, true) +
      '<div class="tg-row-body"><div class="tg-row-title" style="font-size:17px;font-weight:700">' + esc(ex.examName) + '</div>' +
      '<div class="tg-row-sub">' + esc(ex.category) + ' · ' + (ex.timeLimitMinutes || 30) + ' min · ' + (ex.questionCount || 20) + ' Qs · Neg ' + esc(ex.negativeMarkingText || '0') + '</div></div>' +
      '</div>' +
      '<div class="btn-row" style="margin-top:12px">' +
      '<label class="tg-btn sec sm" style="cursor:pointer">Upload Avatar Photo<input type="file" accept="image/*" style="display:none" data-act="upload-exam-avatar" data-id="' + esc(ex.id) + '" /></label>' +
      '<button class="tg-btn sec sm" data-act="open-edit-exam" data-id="' + esc(ex.id) + '">Edit Settings</button>' +
      '<button class="tg-btn danger sm" data-act="delete-exam" data-id="' + esc(ex.id) + '">Delete Exam</button>' +
      '</div></div>';

    // Sub-Exams Card (for top-level exams)
    if (!ex.parentExamId) {
      html += '<div class="tg-section-label">Sub-Exams (' + subExams.length + ')</div>';
      html += '<div class="tg-card inset-dividers">';
      subExams.forEach(function (sub) {
        html += '<div class="tg-row" data-act="open-exam" data-id="' + esc(sub.id) + '" data-name="' + esc(sub.examName) + '">' +
          avatarHtml(sub.examName, sub.avatarUrl, sub.avatarEmoji, false) +
          '<div class="tg-row-body"><div class="tg-row-title">' + esc(sub.examName) + '</div>' +
          '<div class="tg-row-sub">' + (sub.questionCount || 20) + ' Qs · ' + (sub.tests ? sub.tests.length : 0) + ' tests</div></div>' +
          '<span class="tg-chevron">›</span></div>';
      });
      html += '<div class="tg-row" data-act="open-create-subexam" data-id="' + esc(ex.id) + '">' +
        '<span class="tg-icon-box ic-blue">+</span>' +
        '<div class="tg-row-body"><div class="tg-row-title" style="color:var(--link)">Add Sub-Exam</div></div></div>';
      html += '</div>';
    }

    // AI Prompt & Syllabus Studio
    html += '<div class="tg-section-label">AI Prompt &amp; Syllabus Studio</div>';
    html += '<div class="tg-card pad">' +
      '<div class="btn-row" style="margin-bottom:10px">' +
      '<div style="flex:1"><div class="tg-field"><label>AI Language</label><select id="exLang" class="tg-select">' +
        '<option value="english"' + (ex.aiLanguage === 'english' ? ' selected' : '') + '>English</option>' +
        '<option value="hindi"' + (ex.aiLanguage === 'hindi' ? ' selected' : '') + '>Hindi (Devanagari)</option>' +
        '<option value="bilingual"' + (ex.aiLanguage === 'bilingual' ? ' selected' : '') + '>Bilingual (EN + HI)</option>' +
      '</select></div></div>' +
      '<div style="flex:1"><div class="tg-field"><label>AI Difficulty</label><select id="exDiff" class="tg-select">' +
        '<option value="mixed"' + (ex.aiDifficulty === 'mixed' ? ' selected' : '') + '>Mixed</option>' +
        '<option value="easy"' + (ex.aiDifficulty === 'easy' ? ' selected' : '') + '>Easy</option>' +
        '<option value="medium"' + (ex.aiDifficulty === 'medium' ? ' selected' : '') + '>Medium</option>' +
        '<option value="hard"' + (ex.aiDifficulty === 'hard' ? ' selected' : '') + '>Hard</option>' +
      '</select></div></div></div>' +
      '<div class="tg-field"><label>Exam Custom AI Prompt &amp; Paper-Setter Notes</label>' +
      '<textarea id="exPrompt" rows="3" class="tg-textarea" placeholder="Topic distribution, PYQ focus...">' + esc(ex.aiPrompt || '') + '</textarea></div>' +
      '<div class="tg-field"><label>Syllabus PDF: ' + (ex.syllabusFileName ? '<strong>' + esc(ex.syllabusFileName) + '</strong>' : 'None attached') + '</label>' +
      '<div class="btn-row">' +
      '<label class="tg-btn sec sm" style="cursor:pointer">Attach Syllabus PDF<input type="file" accept="application/pdf,.pdf" style="display:none" data-act="upload-syllabus" data-id="' + esc(ex.id) + '" /></label>' +
      (ex.syllabusUrl ? '<button class="tg-btn danger sm" data-act="delete-syllabus" data-id="' + esc(ex.id) + '">Remove PDF</button>' : '') +
      '</div></div>' +
      '<div class="btn-row" style="margin-top:8px">' +
      '<button class="tg-btn sec" style="flex:1" data-act="save-exam-prompt" data-id="' + esc(ex.id) + '">Save Studio</button>' +
      '<button class="tg-btn" style="flex:1" data-act="gen-ai-test" data-id="' + esc(ex.id) + '">Generate AI Mock Test Now</button>' +
      '</div>' +
      '<div style="margin-top:8px"><button class="tg-btn ok full" data-act="assemble-bank-test" data-id="' + esc(ex.id) + '">Assemble Test from Question Bank (₹0 / 0ms)</button></div>' +
      '</div>';

    // Mock Tests List
    html += '<div class="tg-section-label">Mock Tests (' + tests.length + ') &amp; Question Bank (' + (ex.questionsCount || 0) + ')</div>';
    html += '<div class="tg-card full-dividers">';
    html += '<div class="tg-row" data-act="open-qbank" data-id="' + esc(ex.id) + '" data-name="' + esc(ex.examName) + '">' +
      '<span class="tg-icon-box ic-indigo">' + ICONS.exams + '</span>' +
      '<div class="tg-row-body"><div class="tg-row-title">Open Question Bank</div><div class="tg-row-sub">' + (ex.questionsCount || 0) + ' verified questions · Add, Edit, Delete, Bulk JSON Import</div></div>' +
      '<span class="tg-chevron">›</span></div>';

    if (!tests.length) {
      html += '<div class="empty-card">No mock tests generated yet for this exam.</div>';
    } else {
      tests.forEach(function (t) {
        var isLive = t.status === 'live' || t.status === 'published';
        html += '<div class="tg-row static"><div class="tg-row-body">' +
          '<div class="tg-row-title">' + esc(t.title || t.testNumber) + ' <span class="badge ' + (isLive ? 'ok' : 'warn') + '">' + esc(t.status) + '</span></div>' +
          '<div class="tg-row-sub">' + esc(t.testNumber) + ' · ' + (t.questionCount || 0) + ' Qs · ' + formatDate(t.generatedAt) + '</div></div>' +
          '<div class="btn-row">' +
          renderSwitch(isLive, 'toggle-test-live', t.id, ' data-next="' + (isLive ? 'draft' : 'live') + '"') +
          '<button class="tg-btn danger sm" data-act="delete-test" data-id="' + esc(t.id) + '">Del</button>' +
          '</div></div>';
      });
    }
    html += '</div>';
    document.getElementById('viewRoot').innerHTML = html;
  }

  // -------- 3. QUESTION BANK + BULK JSON IMPORT --------
  function renderQuestionBank(list, p) {
    list = Array.isArray(list) ? list : [];
    var html = '<div class="btn-row" style="margin-bottom:10px">' +
      '<button class="tg-btn" style="flex:1" data-act="open-add-q" data-id="' + esc(p.id) + '">+ Add Question</button>' +
      '<button class="tg-btn ok" style="flex:1" data-act="open-bulk-q" data-id="' + esc(p.id) + '">Bulk JSON Import</button>' +
      '</div>';
    if (!list.length) {
      html += '<div class="tg-card pad empty-card">Question Bank is empty for this exam.</div>';
    } else {
      html += '<div class="tg-card full-dividers">';
      list.forEach(function (q, idx) {
        html += '<div class="tg-row static"><div class="tg-row-body">' +
          '<div class="tg-row-title">' + (idx + 1) + '. ' + esc(q.questionText) + '</div>' +
          '<div class="tg-row-sub">Answer: <strong>' + esc(q.correctAnswer) + '</strong>' + (q.topic ? ' · ' + esc(q.topic) : '') + '</div></div>' +
          '<div class="btn-row">' +
          '<button class="tg-btn sec sm" data-act="open-edit-q" data-exam="' + esc(p.id) + '" data-id="' + esc(q.id) + '">Edit</button>' +
          '<button class="tg-btn danger sm" data-act="delete-q" data-exam="' + esc(p.id) + '" data-id="' + esc(q.id) + '">Del</button>' +
          '</div></div>';
      });
      html += '</div>';
    }
    document.getElementById('viewRoot').innerHTML = html;
  }

  // -------- 4. CONTENT & MODERATION HUB --------
  function renderContentHub(data, activeTab) {
    data = data || {};
    var banners = data.banners || [];
    var broadcasts = data.broadcasts || [];
    var polls = data.polls || [];
    var flags = data.flags || [];
    var feedback = data.feedback || [];
    var cfg = data.config || {};

    var html = '<div class="pills">' +
      pillBtn('banners', 'Banners (' + banners.length + ')', activeTab, 'content-tab') +
      pillBtn('flags', 'Flags (' + flags.length + ')', activeTab, 'content-tab') +
      pillBtn('polls', 'Polls (' + polls.length + ')', activeTab, 'content-tab') +
      pillBtn('broadcasts', 'Broadcasts (' + broadcasts.length + ')', activeTab, 'content-tab') +
      pillBtn('feedback', 'Feedback (' + feedback.length + ')', activeTab, 'content-tab') +
      '</div>';

    if (activeTab === 'banners') {
      html += '<div style="margin-bottom:10px"><button class="tg-btn full" data-act="open-upload-banner">+ Upload Photo / Video Banner</button></div>';
      if (!banners.length) {
        html += '<div class="tg-card pad empty-card">No Home banners uploaded yet.</div>';
      } else {
        html += '<div class="tg-card full-dividers">';
        banners.forEach(function (b) {
          var isVideo = b.mediaType === 'video';
          var thumb = isVideo
            ? '<div class="banner-thumb">VIDEO</div>'
            : '<div class="banner-thumb"><img src="' + esc(b.imageUrl) + '" alt="" /></div>';
          html += '<div class="tg-row static">' + thumb +
            '<div class="tg-row-body"><div class="tg-row-title">' +
              '<span class="badge">' + (isVideo ? 'Video' : 'Photo') + '</span> ' +
              (b.active ? '<span class="badge ok">Active</span>' : '<span class="badge warn">Hidden</span>') +
            '</div><div class="tg-row-sub">' + esc(b.linkUrl || 'No CTA link') + (b.linkLabel ? ' (' + esc(b.linkLabel) + ')' : '') + '</div></div>' +
            '<div class="btn-row">' +
            renderSwitch(!!b.active, 'toggle-banner', b.id, ' data-next="' + (!b.active) + '"') +
            '<button class="tg-btn sec sm" data-act="reorder-banner" data-id="' + esc(b.id) + '" data-up="1">↑</button>' +
            '<button class="tg-btn sec sm" data-act="reorder-banner" data-id="' + esc(b.id) + '" data-up="0">↓</button>' +
            '<button class="tg-btn sec sm" data-act="open-banner-link" data-id="' + esc(b.id) + '">Link</button>' +
            '<button class="tg-btn danger sm" data-act="delete-banner" data-id="' + esc(b.id) + '">Del</button>' +
            '</div></div>';
        });
        html += '</div>';
      }
    } else if (activeTab === 'flags') {
      if (!flags.length) {
        html += '<div class="tg-card pad empty-card">Zero pending flagged questions. Everything looks clean!</div>';
      } else {
        html += '<div class="tg-card full-dividers">';
        flags.forEach(function (f) {
          html += '<div class="tg-row" data-act="open-flag-fixer" data-id="' + esc(f.id) + '">' +
            '<span class="tg-icon-box ic-amber">!</span>' +
            '<div class="tg-row-body"><div class="tg-row-title">' + esc(f.examName || 'Exam') + ' · <span class="badge err">' + esc(f.reason) + '</span></div>' +
            '<div class="tg-row-sub">' + esc(String(f.questionText || '').slice(0, 95)) + '</div></div>' +
            '<span class="tg-chevron">›</span></div>';
        });
        html += '</div>';
      }
    } else if (activeTab === 'polls') {
      html += '<div style="margin-bottom:10px"><button class="tg-btn full" data-act="open-create-poll">+ Create Student Poll</button></div>';
      if (!polls.length) {
        html += '<div class="tg-card pad empty-card">No polls created yet.</div>';
      } else {
        polls.forEach(function (p) {
          var totalVotes = 0;
          (p.options || []).forEach(function (_, idx) { totalVotes += Number((p.voteCounts && p.voteCounts[String(idx)]) || 0); });
          var optsHtml = (p.options || []).map(function (opt, idx) {
            var count = Number((p.voteCounts && p.voteCounts[String(idx)]) || 0);
            var pct = totalVotes > 0 ? Math.round((count * 100) / totalVotes) : 0;
            return '<div style="margin-top:6px"><div style="display:flex;justify-content:space-between;font-size:12px"><span>' + esc(opt) + '</span><span>' + count + ' (' + pct + '%)</span></div>' +
              '<div class="poll-bar-bg"><div class="poll-bar-fill" style="width:' + pct + '%"></div></div></div>';
          }).join('');
          html += '<div class="tg-card pad"><div class="btn-row" style="justify-content:space-between;align-items:center">' +
            '<strong>' + esc(p.question) + '</strong>' +
            '<div class="btn-row">' + renderSwitch(!!p.active, 'toggle-poll', p.id, ' data-next="' + (!p.active) + '"') +
            '<button class="tg-btn danger sm" data-act="delete-poll" data-id="' + esc(p.id) + '">Del</button></div></div>' +
            '<div class="tg-row-sub">' + totalVotes + ' total votes · ' + (p.active ? 'Active' : 'Closed') + '</div>' + optsHtml + '</div>';
        });
      }
    } else if (activeTab === 'broadcasts') {
      html += '<div class="tg-card pad">' +
        '<div style="font-weight:700;margin-bottom:8px">Official Announcements Channel</div>' +
        '<div class="tg-field"><label>Channel Display Name</label><input id="chDispName" class="tg-input" value="' + esc(cfg.announcementChannelName || 'EVE Announcements') + '" /></div>' +
        '<div class="tg-field"><label>Official Telegram Channel (@username)</label><input id="chTgUser" class="tg-input" value="' + esc(cfg.officialTelegramChannelUsername || '') + '" placeholder="@yourchannel" /></div>' +
        '<button class="tg-btn sec full" data-act="save-channel-meta">Save Channel Info</button></div>';

      html += '<div class="tg-card pad"><div style="font-weight:700;margin-bottom:8px">Send Broadcast Notification</div>' +
        '<div class="tg-field"><label>Title</label><input id="bcTitle" class="tg-input" placeholder="New Mock Test Live!" /></div>' +
        '<div class="tg-field"><label>Message</label><textarea id="bcMsg" rows="3" class="tg-textarea" placeholder="Announcement message for all students..."></textarea></div>' +
        '<button class="tg-btn full" data-act="send-broadcast">Send Broadcast Now</button></div>';

      html += '<div class="tg-section-label">Broadcast History (' + broadcasts.length + ')</div>';
      html += '<div class="tg-card full-dividers">';
      if (!broadcasts.length) {
        html += '<div class="empty-card">No broadcasts sent yet.</div>';
      } else {
        broadcasts.forEach(function (bc) {
          html += '<div class="tg-row static"><div class="tg-row-body"><div class="tg-row-title">' + esc(bc.title) + '</div>' +
            '<div class="tg-row-sub">' + esc(bc.message) + '<br>' + formatDate(bc.sentAt) + '</div></div>' +
            '<button class="tg-btn danger sm" data-act="delete-broadcast" data-id="' + esc(bc.id) + '">Del</button></div>';
        });
      }
      html += '</div>';
    } else if (activeTab === 'feedback') {
      if (!feedback.length) {
        html += '<div class="tg-card pad empty-card">No student feedback messages yet.</div>';
      } else {
        feedback.forEach(function (fb) {
          html += '<div class="tg-card pad"><div class="btn-row" style="justify-content:space-between">' +
            '<strong>' + esc(fb.userName || fb.userEmail || 'Student') + '</strong>' +
            '<span class="badge ' + (fb.read ? 'ok' : 'warn') + '">' + (fb.read ? 'Read' : 'Unread') + '</span></div>' +
            '<div class="tg-row-sub">' + esc(fb.userEmail) + ' · ' + formatDate(fb.timestamp) + '</div>' +
            '<div style="margin:8px 0">' + esc(fb.message) + '</div>' +
            (fb.replyText ? '<div class="tg-row-sub" style="color:var(--link)">Admin reply: ' + esc(fb.replyText) + '</div>' : '') +
            '<div class="btn-row" style="margin-top:8px">' +
            '<button class="tg-btn sm" data-act="open-reply-feedback" data-id="' + esc(fb.id) + '">Reply</button>' +
            (!fb.read ? '<button class="tg-btn sec sm" data-act="read-feedback" data-id="' + esc(fb.id) + '">Mark Read</button>' : '') +
            '<button class="tg-btn danger sm" data-act="delete-feedback" data-id="' + esc(fb.id) + '">Delete</button>' +
            '</div></div>';
        });
      }
    }
    document.getElementById('viewRoot').innerHTML = html;
  }

  function pillBtn(val, label, activeVal, act) {
    return '<button type="button" class="pill' + (val === activeVal ? ' active' : '') + '" data-act="' + esc(act) + '" data-val="' + esc(val) + '">' + esc(label) + '</button>';
  }

  // -------- 5. FLAGGED QUESTION FIXER --------
  function renderFlaggedFixer(detail) {
    if (!detail || !detail.report) {
      document.getElementById('viewRoot').innerHTML = '<div class="tg-card pad empty-card">Flagged report not found.</div>';
      return;
    }
    var r = detail.report;
    var q = detail.question || {
      questionText: r.questionText || '',
      optionA: '', optionB: '', optionC: '', optionD: '',
      correctAnswer: 'A', explanation: '', topic: ''
    };
    var ans = String(q.correctAnswer || 'A').toUpperCase();
    var html = '<div class="tg-card pad">' +
      '<span class="badge err">' + esc(r.reason) + '</span> <span class="badge">' + esc(detail.sourceType || 'report') + '</span>' +
      '<div class="tg-row-sub" style="margin-top:6px">Exam: <strong>' + esc(r.examName || 'Exam') + '</strong> · Student: ' + esc(r.studentEmail || 'student') + '</div>' +
      (r.comment ? '<div style="margin-top:6px;font-size:13px">Complaint note: <em>' + esc(r.comment) + '</em></div>' : '') +
      '</div>';

    html += '<div class="tg-card pad">' +
      '<div class="tg-field"><label>Question Text</label><textarea id="fixQ" rows="3" class="tg-textarea">' + esc(q.questionText) + '</textarea></div>' +
      '<div class="tg-field"><label>Option A</label><input id="fixA" class="tg-input" value="' + esc(q.optionA) + '" /></div>' +
      '<div class="tg-field"><label>Option B</label><input id="fixB" class="tg-input" value="' + esc(q.optionB) + '" /></div>' +
      '<div class="tg-field"><label>Option C</label><input id="fixC" class="tg-input" value="' + esc(q.optionC) + '" /></div>' +
      '<div class="tg-field"><label>Option D</label><input id="fixD" class="tg-input" value="' + esc(q.optionD) + '" /></div>' +
      '<div class="tg-field"><label>Correct Answer</label><div class="radio-pills" id="fixAnsGroup">' +
      ['A', 'B', 'C', 'D'].map(function (opt) {
        return '<button type="button" class="radio-pill' + (ans === opt ? ' active' : '') + '" data-act="pick-fix-ans" data-val="' + opt + '">' + opt + '</button>';
      }).join('') +
      '</div><input type="hidden" id="fixAnsVal" value="' + esc(ans) + '" /></div>' +
      '<div class="tg-field"><label>Explanation</label><textarea id="fixExp" rows="3" class="tg-textarea">' + esc(q.explanation || '') + '</textarea></div>' +
      '<div class="btn-row" style="margin-top:10px">' +
      '<button class="tg-btn ok full" data-act="save-flag-fix" data-id="' + esc(r.id) + '">Save Fix &amp; Auto-Resolve Flag</button>' +
      '<button class="tg-btn sec" style="flex:1" data-act="dismiss-flag-fixer" data-id="' + esc(r.id) + '">Dismiss False Flag</button>' +
      '<button class="tg-btn danger" style="flex:1" data-act="delete-bad-question" data-id="' + esc(r.id) + '">Delete Bad Question</button>' +
      '</div></div>';
    document.getElementById('viewRoot').innerHTML = html;
  }

  // -------- 6. STUDENTS DIRECTORY & PROFILE --------
  function renderStudentsDir(data, filter, q) {
    data = data || {};
    var sum = data.summary || { total: 0, online: 0, premium: 0, banned: 0 };
    var students = data.students || [];

    var html = '<div class="stat-grid four">' +
      statBox(sum.total, 'Registered') +
      statBox(sum.premium, 'Premium') +
      statBox(sum.online, 'Online') +
      statBox(sum.banned, 'Banned') +
      '</div>';

    html += '<input id="stuSearchInput" class="search-bar" placeholder="Search student name, email or EVE ID..." value="' + esc(q) + '" />';
    html += '<div class="pills">' +
      pillBtn('all', 'All', filter, 'stu-filter') +
      pillBtn('online', 'Online Now', filter, 'stu-filter') +
      pillBtn('premium', 'Premium', filter, 'stu-filter') +
      pillBtn('banned', 'Banned', filter, 'stu-filter') +
      '</div>';

    if (!students.length) {
      html += '<div class="tg-card pad empty-card">No matching students found.</div>';
    } else {
      html += '<div class="tg-card inset-dividers">';
      students.forEach(function (u) {
        var badges = (u.isOnline ? ' <span class="badge ok">Online</span>' : '') +
          (u.isPremium ? ' <span class="badge">Premium</span>' : '') +
          (u.disabled ? ' <span class="badge err">Banned</span>' : '');
        html += '<div class="tg-row" data-act="open-student" data-id="' + esc(u.id) + '" data-name="' + esc(u.displayName) + '">' +
          avatarHtml(u.displayName, u.photoUrl, '', false) +
          '<div class="tg-row-body"><div class="tg-row-title">' + esc(u.displayName) + badges + '</div>' +
          '<div class="tg-row-sub">' + esc(u.email) + (u.eveId ? ' · ' + esc(u.eveId) : '') + '</div></div>' +
          '<span class="tg-chevron">›</span></div>';
      });
      html += '</div>';
    }
    document.getElementById('viewRoot').innerHTML = html;
  }

  function statBox(val, lbl) {
    return '<div class="stat-box"><div class="stat-val">' + esc(val) + '</div><div class="stat-lbl">' + esc(lbl) + '</div></div>';
  }

  function renderStudentProfile(data) {
    if (!data || !data.student) {
      document.getElementById('viewRoot').innerHTML = '<div class="tg-card pad empty-card">Student not found.</div>';
      return;
    }
    var u = data.student;
    var st = data.stats || { totalTests: 0, avgScore: 0, bestScore: 0, accuracy: 0 };
    var ent = data.entitlement;
    var attempts = data.attempts || [];
    setHeader(u.displayName || 'Student Profile', u.email || u.id);

    var html = '<div class="tg-card pad"><div class="tg-row static" style="padding:0">' +
      avatarHtml(u.displayName, u.photoUrl, '', true) +
      '<div class="tg-row-body"><div class="tg-row-title" style="font-size:17px;font-weight:700">' + esc(u.displayName) +
        (u.disabled ? ' <span class="badge err">Banned</span>' : '') +
        (ent && ent.isActive ? ' <span class="badge ok">Premium</span>' : '') + '</div>' +
      '<div class="tg-row-sub">' + esc(u.email) + (u.eveId ? '<br>EVE ID: ' + esc(u.eveId) : '') +
        (u.phone ? ' · ' + esc(u.phone) : '') + (u.username ? ' · @' + esc(u.username) : '') + '</div></div></div>' +
      (u.bio ? '<div class="tg-row-sub" style="margin-top:8px">' + esc(u.bio) + '</div>' : '') +
      '<div class="btn-row" style="margin-top:12px">' +
      '<button class="tg-btn ok sm" data-act="grant-prem-30" data-id="' + esc(u.id) + '">+30d Premium</button>' +
      '<button class="tg-btn sm" data-act="grant-prem-life" data-id="' + esc(u.id) + '">Lifetime</button>' +
      (ent && ent.isActive ? '<button class="tg-btn danger sm" data-act="revoke-prem" data-id="' + esc(u.id) + '">Revoke Premium</button>' : '') +
      '<button class="tg-btn ' + (u.disabled ? 'ok' : 'danger') + ' sm" data-act="toggle-ban-student" data-id="' + esc(u.id) + '" data-next="' + (!u.disabled) + '">' + (u.disabled ? 'Unban Student' : 'Ban Student') + '</button>' +
      '</div></div>';

    if (ent) {
      html += '<div class="tg-card pad"><div style="font-weight:700">Premium Entitlement</div>' +
        '<div class="tg-row-sub" style="margin-top:4px">' + esc(ent.planName) + ' · Status: <strong>' + esc(ent.status) + '</strong> · ' +
        (ent.isLifetime ? 'Lifetime Access' : ('Expires ' + formatDate(ent.expiresAt))) + '</div></div>';
    }

    html += '<div class="tg-section-label">Overall Performance &amp; Progress</div>';
    html += '<div class="stat-grid four">' +
      statBox(st.totalTests, 'Tests') +
      statBox(st.avgScore, 'Avg Score') +
      statBox(st.bestScore, 'Best Score') +
      statBox(st.accuracy + '%', 'Accuracy') +
      '</div>';

    html += '<div class="tg-section-label">Attempt History (' + attempts.length + ')</div>';
    html += '<div class="tg-card full-dividers">';
    if (!attempts.length) {
      html += '<div class="empty-card">No test attempts recorded yet.</div>';
    } else {
      attempts.forEach(function (a) {
        html += '<div class="tg-row static"><div class="tg-row-body">' +
          '<div class="tg-row-title">' + esc(a.examName || a.examId) + '</div>' +
          '<div class="tg-row-sub">Score: <strong>' + esc(a.score) + '/' + esc(a.total) + '</strong> · ' + formatDate(a.timestamp) + '</div></div></div>';
      });
    }
    html += '</div>';
    document.getElementById('viewRoot').innerHTML = html;
  }

  // -------- 7. ORDERS, PLANS & SUBSCRIBERS --------
  function renderOrders(list, statusFilter) {
    list = Array.isArray(list) ? list : [];
    var html = '<div class="pills">' +
      pillBtn('pending', 'Pending', statusFilter, 'orders-filter') +
      pillBtn('paid', 'Paid / Approved', statusFilter, 'orders-filter') +
      pillBtn('rejected', 'Rejected', statusFilter, 'orders-filter') +
      pillBtn('all', 'All Orders', statusFilter, 'orders-filter') +
      '</div>';

    if (!list.length) {
      html += '<div class="tg-card pad empty-card">No ' + esc(statusFilter) + ' UPI orders found.</div>';
    } else {
      html += '<div class="tg-card full-dividers">';
      list.forEach(function (o) {
        var st = String(o.status || '').toUpperCase();
        var isPending = st === 'CREATED' || st === 'PENDING';
        var bCls = (st === 'SUCCESS' || st === 'PAID') ? 'ok' : (isPending ? 'warn' : 'err');
        html += '<div class="tg-row static"><div class="tg-row-body">' +
          '<div class="tg-row-title">' + esc(o.userEmail || o.userId) + ' · ₹' + esc(o.amount) + ' <span class="badge ' + bCls + '">' + esc(st) + '</span></div>' +
          '<div class="tg-row-sub">Order: ' + esc(o.orderId) + (o.providerPaymentId ? ' · Ref/UTR: ' + esc(o.providerPaymentId) : '') + '<br>' + formatDate(o.createdAt) + '</div></div>' +
          (isPending ? '<div class="btn-row"><button class="tg-btn ok sm" data-act="approve-order" data-id="' + esc(o.orderId) + '">Approve</button><button class="tg-btn danger sm" data-act="reject-order" data-id="' + esc(o.orderId) + '">Reject</button></div>' : '') +
          '</div>';
      });
      html += '</div>';
    }
    document.getElementById('viewRoot').innerHTML = html;
  }

  function renderPremiumConfig(cfg) {
    cfg = cfg || {};
    var html = '<div class="tg-card pad">' +
      '<div class="tg-row static" style="padding:0 0 10px"><div class="tg-row-body"><div class="tg-row-title">Premium Store Enabled</div><div class="tg-row-sub">Allow students to purchase Premium in-app</div></div>' +
      renderSwitch(!!cfg.isEnabled, 'toggle-local-switch', 'premEnabled') + '</div>' +
      '<div class="tg-field"><label>Plan Name</label><input id="premName" class="tg-input" value="' + esc(cfg.planName || 'Premium Pro') + '" /></div>' +
      '<div class="btn-row"><div style="flex:1" class="tg-field"><label>Price (INR)</label><input id="premPrice" type="number" class="tg-input" value="' + esc(cfg.priceInr || 99) + '" /></div>' +
      '<div style="flex:1" class="tg-field"><label>Duration (Days)</label><input id="premDays" type="number" class="tg-input" value="' + esc(cfg.durationDays || 30) + '" /></div></div>' +
      '<div class="tg-row static" style="padding:6px 0"><div class="tg-row-body"><div class="tg-row-title">Lifetime Plan</div></div>' +
      renderSwitch(!!cfg.isLifetime, 'toggle-local-switch', 'premLifetime') + '</div>' +
      '<div class="tg-field" style="margin-top:8px"><label>Merchant UPI VPA</label><input id="premVpa" class="tg-input" value="' + esc(cfg.merchantVpa || '') + '" /></div>' +
      '<div class="tg-field"><label>Merchant Display Name</label><input id="premMerchant" class="tg-input" value="' + esc(cfg.merchantName || 'Eve Mock Test') + '" /></div>' +
      '<div class="tg-row static" style="padding:6px 0"><div class="tg-row-body"><div class="tg-row-title">UPI Intent Enabled</div></div>' +
      renderSwitch(!!cfg.upiEnabled, 'toggle-local-switch', 'premUpi') + '</div>' +
      '<div class="tg-row static" style="padding:6px 0"><div class="tg-row-body"><div class="tg-row-title">QR Code Payment Enabled</div></div>' +
      renderSwitch(!!cfg.qrEnabled, 'toggle-local-switch', 'premQr') + '</div>' +
      '<button class="tg-btn full" style="margin-top:12px" data-act="save-prem-config">Save Plan &amp; UPI Settings</button>' +
      '</div>';
    document.getElementById('viewRoot').innerHTML = html;
  }

  function renderSubscribers(list) {
    list = Array.isArray(list) ? list : [];
    var html = '';
    if (!list.length) {
      html = '<div class="tg-card pad empty-card">No premium subscribers yet.</div>';
    } else {
      html += '<div class="tg-card inset-dividers">';
      list.forEach(function (s) {
        var isActive = s.status === 'ACTIVE';
        html += '<div class="tg-row" data-act="open-student" data-id="' + esc(s.userId) + '" data-name="' + esc(s.displayName) + '">' +
          avatarHtml(s.displayName, '', '', false) +
          '<div class="tg-row-body"><div class="tg-row-title">' + esc(s.displayName || s.email || s.userId) +
            ' <span class="badge ' + (isActive ? 'ok' : 'err') + '">' + esc(s.status) + '</span></div>' +
          '<div class="tg-row-sub">' + esc(s.email || s.userId) + ' · ' + (s.isLifetime ? 'Lifetime' : ('Expires ' + formatDate(s.expiresAt))) + '</div></div>' +
          '<span class="tg-chevron">›</span></div>';
      });
      html += '</div>';
    }
    document.getElementById('viewRoot').innerHTML = html;
  }

  // -------- 8. ANALYTICS, CRASHES, SECURITY, AI USAGE, HEALTH, LEADERBOARD --------
  function renderAnalyticsHealth(data, activeTab) {
    data = data || {};
    var exams = data.examAnalytics || [];
    var crashes = data.crashes || [];
    var events = data.securityEvents || [];
    var usage = data.usage || {};
    var health = data.infraHealth || {};
    var lb = data.leaderboard || [];
    var crashFilter = stateCache.__crashKind || 'crashes';
    var crashStatus = stateCache.__crashStatus || 'open';

    var html = '<div class="pills">' +
      pillBtn('exams', 'Exam Stats', activeTab, 'analytics-tab') +
      pillBtn('crashes', 'Crashes &amp; Alerts (' + crashes.filter(function (c) { return c.status === 'open'; }).length + ')', activeTab, 'analytics-tab') +
      pillBtn('usage', 'AI Usage &amp; Health', activeTab, 'analytics-tab') +
      pillBtn('leaderboard', 'Leaderboard', activeTab, 'analytics-tab') +
      '</div>';

    if (activeTab === 'exams') {
      if (!exams.length) {
        html += '<div class="tg-card pad empty-card">No exam attempt data yet.</div>';
      } else {
        html += '<div class="tg-card full-dividers">';
        exams.forEach(function (ex) {
          html += '<div class="tg-row" data-act="open-exam" data-id="' + esc(ex.examId) + '" data-name="' + esc(ex.examName) + '">' +
            '<span class="tg-icon-box ic-indigo">' + ICONS.analytics + '</span>' +
            '<div class="tg-row-body"><div class="tg-row-title">' + esc(ex.examName) + '</div>' +
            '<div class="tg-row-sub">' + (ex.attemptCount || 0) + ' attempts · Avg ' + (ex.averageScore || 0) + ' · Best ' + (ex.highestScore || 0) + '</div></div>' +
            '<span class="tg-chevron">›</span></div>';
        });
        html += '</div>';
      }
    } else if (activeTab === 'crashes') {
      html += '<div class="pills">' +
        pillBtn('crashes', 'Crashes (' + crashes.length + ')', crashFilter, 'crash-kind') +
        pillBtn('errors', 'Errors', crashFilter, 'crash-kind') +
        pillBtn('security', 'Security', crashFilter, 'crash-kind') +
        (crashFilter === 'crashes' ? pillBtn(crashStatus === 'open' ? 'resolved' : 'open', 'Showing: ' + crashStatus, '', 'toggle-crash-status') : '') +
        '</div>';

      if (crashFilter === 'crashes') {
        var shown = crashes.filter(function (c) { return String(c.status || 'open') === crashStatus; });
        if (!shown.length) {
          html += '<div class="tg-card pad empty-card">Zero ' + esc(crashStatus) + ' crash signatures!</div>';
        } else {
          shown.forEach(function (cr) {
            html += '<div class="tg-card pad"><div class="btn-row" style="justify-content:space-between">' +
              '<strong>' + esc(cr.exceptionClass) + '</strong><span class="badge err">×' + esc(cr.occurrenceCount) + '</span></div>' +
              '<div style="margin-top:6px;font-size:14px">' + esc(cr.plainEnglishSummary) + '</div>' +
              '<div class="tg-row-sub" style="margin-top:4px">' + esc(cr.whereItHappened) + ' · Last seen ' + formatDate(cr.lastSeen) + '</div>' +
              '<div class="btn-row" style="margin-top:10px">' +
              '<button class="tg-btn sec sm" data-act="copy-crash" data-id="' + esc(cr.signature) + '">Copy Report</button>' +
              (cr.status !== 'resolved' ? '<button class="tg-btn ok sm" data-act="resolve-crash" data-id="' + esc(cr.signature) + '">Mark Fixed</button>' : '') +
              '<button class="tg-btn danger sm" data-act="delete-crash" data-id="' + esc(cr.signature) + '">Delete</button>' +
              '</div></div>';
          });
        }
      } else {
        var targetKind = crashFilter === 'security' ? 'security' : 'app_error';
        var evList = events.filter(function (e) { return e.kind === targetKind; });
        if (!evList.length) {
          html += '<div class="tg-card pad empty-card">No ' + esc(crashFilter) + ' events recorded.</div>';
        } else {
          html += '<div class="tg-card full-dividers">';
          evList.forEach(function (ev) {
            html += '<div class="tg-row static"><div class="tg-row-body">' +
              '<div class="tg-row-title">' + esc(ev.plainEnglishTitle) + ' <span class="badge ' + (ev.severity === 'high' ? 'err' : 'warn') + '">' + esc(ev.severity) + '</span></div>' +
              '<div class="tg-row-sub">' + esc(ev.plainEnglishDetail) + '<br>' + formatDate(ev.timestamp) + '</div></div></div>';
          });
          html += '</div>';
        }
      }
    } else if (activeTab === 'usage') {
      var calls = Number(usage.geminiCalls || 0);
      var okCalls = Number(usage.geminiSuccessfulCalls || 0);
      var succPct = calls > 0 ? Math.round((okCalls * 100) / calls) + '%' : '100%';
      html += '<div class="tg-section-label">' + esc(usage.monthDisplayName || 'Current Month') + ' AI Usage</div>';
      html += '<div class="stat-grid four">' +
        statBox(calls, 'AI Calls') +
        statBox(succPct, 'Success %') +
        statBox(usage.geminiFailedCalls || 0, 'Failed') +
        statBox(usage.testSubmissions || 0, 'Submissions') +
        '</div>';
      html += '<div class="tg-card pad"><div class="tg-row-sub">Documents Created This Month: <strong>' + esc(usage.documentsCreated || 0) + '</strong></div></div>';

      html += '<div class="tg-section-label">Service &amp; Infrastructure Health</div>';
      html += '<div class="tg-card full-dividers">';
      ['backend', 'database', 'firebase', 'media', 'ai'].forEach(function (svcKey) {
        var item = health[svcKey] || { status: 'unknown', detail: '' };
        var isGood = item.status === 'verified' || item.status === 'configured';
        html += '<div class="tg-row static"><div class="tg-row-body"><div class="tg-row-title">' + esc(item.label || svcKey.toUpperCase()) + '</div>' +
          '<div class="tg-row-sub">' + esc(item.detail) + '</div></div>' +
          '<span class="badge ' + (isGood ? 'ok' : 'err') + '">' + esc(item.status) + '</span></div>';
      });
      html += '</div>';
    } else if (activeTab === 'leaderboard') {
      if (!lb.length) {
        html += '<div class="tg-card pad empty-card">No leaderboard attempts recorded yet.</div>';
      } else {
        html += '<div class="tg-card inset-dividers">';
        lb.forEach(function (row, i) {
          var medal = i === 0 ? '🥇' : (i === 1 ? '🥈' : (i === 2 ? '🥉' : '#' + (i + 1)));
          html += '<div class="tg-row" data-act="open-student" data-id="' + esc(row.userId) + '" data-name="' + esc(row.displayName) + '">' +
            '<span class="tg-icon-box ' + (i < 3 ? 'ic-amber' : 'ic-slate') + '" style="font-size:14px;font-weight:700">' + medal + '</span>' +
            '<div class="tg-row-body"><div class="tg-row-title">' + esc(row.displayName) + '</div>' +
            '<div class="tg-row-sub">Score ' + esc(row.score) + ' · Accuracy ' + esc(row.accuracy || 0) + '%</div></div>' +
            '<span class="tg-chevron">›</span></div>';
        });
        html += '</div>';
      }
    }
    document.getElementById('viewRoot').innerHTML = html;
  }

  // -------- 9. MAINTENANCE & AI CONFIG --------
  function renderConfigView(cfg) {
    cfg = cfg || {};
    var html = '<div class="tg-card pad">' +
      '<div class="tg-row static" style="padding:0 0 12px"><div class="tg-row-body"><div class="tg-row-title">Maintenance Mode</div>' +
      '<div class="tg-row-sub">' + (cfg.maintenanceMode ? 'ACTIVE — Students see maintenance screen' : 'OFF — App open to students') + '</div></div>' +
      renderSwitch(!!cfg.maintenanceMode, 'toggle-local-switch', 'cfgMaint') + '</div>' +
      '<div class="tg-field"><label>Maintenance Banner Message</label><input id="cfgMaintMsg" class="tg-input" value="' + esc(cfg.maintenanceMessage || '') + '" /></div>' +
      '<div class="btn-row"><div style="flex:1" class="tg-field"><label>Min Version Code</label><input id="cfgMinVer" type="number" class="tg-input" value="' + esc(cfg.minimumSupportedVersionCode || 1) + '" /></div>' +
      '<div style="flex:1" class="tg-field"><label>Default Publish Mode</label><select id="cfgPubMode" class="tg-select">' +
        '<option value="paused"' + (cfg.defaultPublishMode === 'paused' ? ' selected' : '') + '>Paused (Review)</option>' +
        '<option value="live"' + (cfg.defaultPublishMode === 'live' ? ' selected' : '') + '>Live Immediately</option>' +
        '<option value="draft"' + (cfg.defaultPublishMode === 'draft' ? ' selected' : '') + '>Draft</option>' +
      '</select></div></div>' +
      '<div class="tg-field"><label>AI Provider Router</label><select id="cfgAiProvider" class="tg-select">' +
        '<option value="auto"' + ((cfg.aiProvider || 'auto') === 'auto' ? ' selected' : '') + '>auto (OpenRouter -> Groq -> OpenAI -> Gemini)</option>' +
        '<option value="openrouter"' + (cfg.aiProvider === 'openrouter' ? ' selected' : '') + '>openrouter</option>' +
        '<option value="groq"' + (cfg.aiProvider === 'groq' ? ' selected' : '') + '>groq</option>' +
        '<option value="openai"' + (cfg.aiProvider === 'openai' ? ' selected' : '') + '>openai</option>' +
        '<option value="gemini"' + (cfg.aiProvider === 'gemini' ? ' selected' : '') + '>gemini</option>' +
      '</select></div>' +
      '<div class="tg-field"><label>AI Model ID Override (optional)</label><input id="cfgAiModel" class="tg-input" value="' + esc(cfg.aiModel || '') + '" placeholder="e.g. llama-3.3-70b-versatile or google/gemini-2.5-pro" /></div>' +
      '<div class="tg-field"><label>Official Telegram Channel (@username)</label><input id="cfgTgCh" class="tg-input" value="' + esc(cfg.officialTelegramChannelUsername || '') + '" placeholder="@eve_channel" /></div>' +
      '<div class="tg-field"><label>Official Support (@username)</label><input id="cfgTgSup" class="tg-input" value="' + esc(cfg.officialTelegramSupportUsername || '') + '" placeholder="@eve_support" /></div>' +
      '<button class="tg-btn full" data-act="save-system-config">Save Maintenance &amp; AI Settings</button>' +
      '</div>';
    document.getElementById('viewRoot').innerHTML = html;
  }

  // -------- 10. ADMIN ROLES & PERMISSIONS --------
  function renderRolesList(data) {
    data = data || {};
    var admins = data.admins || [];
    var html = '<div style="margin-bottom:10px"><button class="tg-btn full" data-act="open-add-admin">+ Add Admin</button></div>';
    html += '<div class="tg-card inset-dividers">';
    admins.forEach(function (a) {
      html += '<div class="tg-row" data-act="open-role-edit" data-email="' + esc(a.email) + '">' +
        avatarHtml(a.customTitle || a.email, '', '', false) +
        '<div class="tg-row-body"><div class="tg-row-title">' + esc(a.email) + ' <span class="badge ' + (a.isOwner ? 'ok' : '') + '">' + esc(a.customTitle || 'Admin') + '</span></div>' +
        '<div class="tg-row-sub">' + (a.isOwner ? 'Full Built-In Owner Access' : ((a.rights ? a.rights.length : 0) + ' granted permissions')) + '</div></div>' +
        '<span class="tg-chevron">›</span></div>';
    });
    html += '</div>';
    document.getElementById('viewRoot').innerHTML = html;
  }

  function renderRoleEdit(data, email) {
    data = data || {};
    var admins = data.admins || [];
    var allRights = data.allRights || [];
    var target = admins.find(function (a) { return String(a.email).toLowerCase() === String(email).toLowerCase(); });
    if (!target) {
      document.getElementById('viewRoot').innerHTML = '<div class="tg-card pad empty-card">Admin member not found.</div>';
      return;
    }
    var granted = target.rights || [];
    var html = '<div class="tg-card pad"><div class="tg-row-title" style="font-size:16px;font-weight:700">' + esc(target.email) + '</div>' +
      '<div class="tg-row-sub">' + (target.isOwner ? 'Built-in Owner rights are immutable.' : 'Customize role title and granular permissions.') + '</div>' +
      (!target.isOwner ? '<div class="tg-field" style="margin-top:10px"><label>Custom Role Title</label><input id="roleTitleInput" class="tg-input" value="' + esc(target.customTitle || 'Admin') + '" /></div>' : '') +
      '</div>';

    html += '<div class="tg-section-label">Permissions</div>';
    html += '<div class="tg-card full-dividers">';
    allRights.forEach(function (rightKey) {
      var has = target.isOwner || granted.indexOf(rightKey) >= 0;
      html += '<div class="tg-row static"><div class="tg-row-body"><div class="tg-row-title">' + esc(rightKey.replace(/_/g, ' ')) + '</div></div>' +
        renderSwitch(has, target.isOwner ? 'noop' : 'toggle-local-right', rightKey) + '</div>';
    });
    html += '</div>';

    if (!target.isOwner) {
      html += '<div class="btn-row"><button class="tg-btn full" data-act="save-admin-rights" data-email="' + esc(target.email) + '">Save Permissions</button></div>' +
        '<div style="margin-top:8px"><button class="tg-btn danger full" data-act="remove-admin" data-email="' + esc(target.email) + '">Remove Admin</button></div>';
    }
    document.getElementById('viewRoot').innerHTML = html;
  }

  // -------- 11. CATEGORIZED AUDIT LOG --------
  function formatAuditTitle(entry) {
    if (entry.description) return entry.description;
    return String(entry.actionType || 'Admin Action').replace(/_/g, ' ');
  }

  function renderAuditLog(list, cat, aq) {
    list = Array.isArray(list) ? list : [];
    var html = '<input id="auditSearchInput" class="search-bar" placeholder="Search admin activity..." value="' + esc(aq) + '" />';
    html += '<div class="pills">' +
      pillBtn('all', 'All Activity', cat, 'audit-cat') +
      pillBtn('exams', 'Exams &amp; Tests', cat, 'audit-cat') +
      pillBtn('students', 'Students &amp; Premium', cat, 'audit-cat') +
      pillBtn('content', 'Content &amp; Polls', cat, 'audit-cat') +
      pillBtn('system', 'Admin &amp; System', cat, 'audit-cat') +
      '</div>';
    html += '<div style="margin-bottom:10px"><button class="tg-btn danger sm" data-act="clear-audit-log" data-cat="' + esc(cat) + '">Clear ' + esc(cat === 'all' ? 'All Audit Logs' : (cat + ' Logs')) + '</button></div>';

    if (!list.length) {
      html += '<div class="tg-card pad empty-card">No audit entries in this category.</div>';
    } else {
      html += '<div class="tg-card full-dividers">';
      list.forEach(function (item) {
        html += '<div class="tg-row static"><div class="tg-row-body">' +
          '<div class="tg-row-title">' + esc(formatAuditTitle(item)) + '</div>' +
          '<div class="tg-row-sub"><span class="badge">' + esc(item.actionType) + '</span> ' + esc(item.adminEmail || 'owner') + ' · ' + formatDate(item.timestamp) + '</div></div></div>';
      });
      html += '</div>';
    }
    document.getElementById('viewRoot').innerHTML = html;
  }

  // ===================== EVENT DELEGATION & ACTIONS =====================

  document.addEventListener('input', function (ev) {
    var target = ev.target;
    if (!target) return;
    if (target.id === 'stuSearchInput' || target.id === 'auditSearchInput') {
      clearTimeout(searchDebounce);
      searchDebounce = setTimeout(function () {
        var r = currentRoute();
        r.params.q = target.value;
        renderCurrentView();
      }, 260);
    }
  });

  document.addEventListener('change', function (ev) {
    var target = ev.target;
    if (!target || !target.dataset || !target.dataset.act) return;
    var act = target.dataset.act;
    var id = target.dataset.id;
    var file = target.files && target.files[0];
    if (!file) return;

    if (act === 'upload-exam-avatar') {
      toast('Uploading avatar...');
      readFileAsBase64(file, function (b64, mime) {
        api('/exams/' + encodeURIComponent(id) + '/avatar', {
          method: 'POST',
          body: JSON.stringify({ base64: b64, mimeType: mime })
        }).then(function () {
          haptic('success');
          swrInvalidate(['exams', 'overview']);
          toast('Avatar updated!');
          renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
    } else if (act === 'upload-syllabus') {
      toast('Uploading syllabus PDF...');
      readFileAsBase64(file, function (b64, _, fname) {
        api('/exams/' + encodeURIComponent(id) + '/syllabus', {
          method: 'POST',
          body: JSON.stringify({ base64: b64, fileName: fname })
        }).then(function () {
          haptic('success');
          swrInvalidate('exams');
          toast('Syllabus PDF attached!');
          renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
    }
  });

  document.addEventListener('click', function (ev) {
    var node = ev.target && ev.target.closest ? ev.target.closest('[data-act]') : null;
    if (!node) return;
    if (node.tagName === 'INPUT') return; // file input handled via change

    var act = node.dataset.act;
    var id = node.dataset.id || '';
    if (act === 'close-sheet') { closeSheet(); return; }
    if (act === 'close-sheet-backdrop') { if (ev.target === node) closeSheet(); return; }
    if (act === 'reload-view') { renderCurrentView(); return; }
    if (act === 'noop') return;

    // Navigation Hub Actions
    if (act === 'nav-exams') return navigate('exams');
    if (act === 'nav-content') return navigate('contentHub', { tab: 'banners' });
    if (act === 'nav-students') return navigate('students', { filter: 'all', q: '' });
    if (act === 'nav-orders') return navigate('orders', { status: 'pending' });
    if (act === 'nav-premium-cfg') return navigate('premiumConfig');
    if (act === 'nav-subscribers') return navigate('subscribers');
    if (act === 'nav-analytics') return navigate('analyticsHealth', { tab: 'exams' });
    if (act === 'nav-config') return navigate('config');
    if (act === 'nav-roles') return navigate('roles');
    if (act === 'nav-audit') return navigate('auditLog', { category: 'all', q: '' });
    if (act === 'open-exam') return navigate('examDetail', { id: id, examName: node.dataset.name });
    if (act === 'open-qbank') return navigate('questionBank', { id: id, examName: node.dataset.name });
    if (act === 'open-flag-fixer') return navigate('flaggedFixer', { id: id });
    if (act === 'open-student') return navigate('studentProfile', { id: id, name: node.dataset.name });
    if (act === 'open-role-edit') return navigate('roleEdit', { email: node.dataset.email });

    // Local animated toggle switches
    if (act === 'toggle-local-switch' || act === 'toggle-local-right') {
      haptic('light');
      node.classList.toggle('on');
      return;
    }

    // Tab/Filter Pills
    if (act === 'content-tab' || act === 'analytics-tab') {
      haptic('light');
      currentRoute().params.tab = node.dataset.val;
      renderCurrentView();
      return;
    }
    if (act === 'crash-kind') {
      haptic('light');
      stateCache.__crashKind = node.dataset.val;
      renderCurrentView();
      return;
    }
    if (act === 'toggle-crash-status') {
      haptic('light');
      stateCache.__crashStatus = (stateCache.__crashStatus || 'open') === 'open' ? 'resolved' : 'open';
      renderCurrentView();
      return;
    }
    if (act === 'stu-filter') {
      haptic('light');
      currentRoute().params.filter = node.dataset.val;
      renderCurrentView();
      return;
    }
    if (act === 'orders-filter') {
      haptic('light');
      currentRoute().params.status = node.dataset.val;
      renderCurrentView();
      return;
    }
    if (act === 'audit-cat') {
      haptic('light');
      currentRoute().params.category = node.dataset.val;
      renderCurrentView();
      return;
    }

    // Exams CRUD Sheets & Actions
    if (act === 'open-create-exam' || act === 'open-create-subexam') {
      var parentId = act === 'open-create-subexam' ? id : '';
      openSheet(parentId ? 'Add Sub-Exam' : 'Create Top-Level Exam',
        '<div class="tg-field"><label>Name</label><input id="newExName" class="tg-input" placeholder="e.g. SSC CGL Tier-1" /></div>' +
        '<div class="tg-field"><label>Category</label><input id="newExCat" class="tg-input" value="General" /></div>' +
        '<div class="btn-row"><div style="flex:1" class="tg-field"><label>Duration (min)</label><input id="newExMin" type="number" class="tg-input" value="30" /></div>' +
        '<div style="flex:1" class="tg-field"><label>Questions</label><input id="newExQs" type="number" class="tg-input" value="20" /></div></div>' +
        '<div class="btn-row"><div style="flex:1" class="tg-field"><label>Negative Marking</label><input id="newExNeg" class="tg-input" value="0.25" /></div>' +
        '<div style="flex:1" class="tg-field"><label>Publish Mode</label><select id="newExPub" class="tg-select"><option value="inherit">Inherit</option><option value="live">Live</option><option value="paused">Paused</option></select></div></div>' +
        '<div class="tg-field"><label>Optional Avatar Photo</label><input id="newExPhoto" type="file" accept="image/*" class="tg-input" /></div>' +
        '<button class="tg-btn full" data-act="submit-create-exam" data-parent="' + esc(parentId) + '">Create</button>'
      );
      return;
    }
    if (act === 'submit-create-exam') {
      var payload = {
        examName: document.getElementById('newExName').value,
        category: document.getElementById('newExCat').value,
        timeLimitMinutes: Number(document.getElementById('newExMin').value),
        questionCount: Number(document.getElementById('newExQs').value),
        negativeMarkingText: document.getElementById('newExNeg').value,
        publishMode: document.getElementById('newExPub').value,
        parentExamId: node.dataset.parent || ''
      };
      var photoInput = document.getElementById('newExPhoto');
      var finishCreate = function () {
        api('/exams', { method: 'POST', body: JSON.stringify(payload) }).then(function (resp) {
          var createdId = resp.data && resp.data.id;
          var file = photoInput && photoInput.files && photoInput.files[0];
          if (file && createdId) {
            return new Promise(function (resolve) {
              readFileAsBase64(file, function (b64, mime) {
                api('/exams/' + encodeURIComponent(createdId) + '/avatar', {
                  method: 'POST',
                  body: JSON.stringify({ base64: b64, mimeType: mime })
                }).then(resolve).catch(resolve);
              });
            });
          }
        }).then(function () {
          haptic('success');
          closeSheet();
          swrInvalidate(['exams', 'overview']);
          toast('Created!');
          renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      };
      finishCreate();
      return;
    }
    if (act === 'open-edit-exam') {
      var list = stateCache['exams'] || [];
      var ex = list.find(function (e) { return e.id === id; });
      if (!ex) return;
      openSheet('Edit Exam Settings',
        '<div class="tg-field"><label>Exam Name</label><input id="edExName" class="tg-input" value="' + esc(ex.examName) + '" /></div>' +
        '<div class="tg-field"><label>Category</label><input id="edExCat" class="tg-input" value="' + esc(ex.category) + '" /></div>' +
        '<div class="btn-row"><div style="flex:1" class="tg-field"><label>Duration (min)</label><input id="edExMin" type="number" class="tg-input" value="' + esc(ex.timeLimitMinutes || 30) + '" /></div>' +
        '<div style="flex:1" class="tg-field"><label>Questions</label><input id="edExQs" type="number" class="tg-input" value="' + esc(ex.questionCount || 20) + '" /></div></div>' +
        '<div class="btn-row"><div style="flex:1" class="tg-field"><label>Negative Marking</label><input id="edExNeg" class="tg-input" value="' + esc(ex.negativeMarkingText || '0') + '" /></div>' +
        '<div style="flex:1" class="tg-field"><label>Publish Mode</label><select id="edExPub" class="tg-select">' +
          ['inherit', 'live', 'paused', 'archived'].map(function (m) {
            return '<option value="' + m + '"' + (ex.publishMode === m ? ' selected' : '') + '>' + m + '</option>';
          }).join('') +
        '</select></div></div>' +
        '<button class="tg-btn full" data-act="submit-edit-exam" data-id="' + esc(ex.id) + '">Save Changes</button>'
      );
      return;
    }
    if (act === 'submit-edit-exam') {
      api('/exams/' + encodeURIComponent(id), {
        method: 'PUT',
        body: JSON.stringify({
          examName: document.getElementById('edExName').value,
          category: document.getElementById('edExCat').value,
          timeLimitMinutes: Number(document.getElementById('edExMin').value),
          questionCount: Number(document.getElementById('edExQs').value),
          negativeMarkingText: document.getElementById('edExNeg').value,
          publishMode: document.getElementById('edExPub').value
        })
      }).then(function () {
        haptic('success'); closeSheet(); swrInvalidate(['exams', 'overview']); toast('Exam updated'); renderCurrentView();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'delete-exam') {
      confirmDialog('Delete this exam? Exams with student attempts or live dependencies cannot be removed.', function () {
        api('/exams/' + encodeURIComponent(id), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate(['exams', 'overview']); toast('Exam deleted'); goBack();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'delete-syllabus') {
      confirmDialog('Remove syllabus PDF from this exam?', function () {
        api('/exams/' + encodeURIComponent(id) + '/syllabus', { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate('exams'); toast('Syllabus removed'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'save-exam-prompt') {
      api('/exams/' + encodeURIComponent(id) + '/prompt', {
        method: 'PUT',
        body: JSON.stringify({
          aiPrompt: document.getElementById('exPrompt').value,
          aiLanguage: document.getElementById('exLang').value,
          aiDifficulty: document.getElementById('exDiff').value
        })
      }).then(function () { haptic('success'); swrInvalidate('exams'); toast('AI studio settings saved'); })
        .catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'gen-ai-test') {
      toast('Generating AI mock test...');
      api('/exams/' + encodeURIComponent(id) + '/generate', { method: 'POST' }).then(function () {
        haptic('success'); swrInvalidate(['exams', 'overview']); toast('AI Mock Test generated!'); renderCurrentView();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'assemble-bank-test') {
      api('/exams/' + encodeURIComponent(id) + '/assemble', { method: 'POST' }).then(function () {
        haptic('success'); swrInvalidate(['exams', 'overview']); toast('Assembled from Question Bank!'); renderCurrentView();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'toggle-test-live') {
      var nextSt = node.dataset.next;
      node.classList.toggle('on');
      api('/tests/' + encodeURIComponent(id) + '/status', { method: 'PUT', body: JSON.stringify({ status: nextSt }) }).then(function () {
        haptic('success'); swrInvalidate(['exams', 'overview']); toast('Test set to ' + nextSt); renderCurrentView();
      }).catch(function (e) { node.classList.toggle('on'); haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'delete-test') {
      confirmDialog('Delete this generated test?', function () {
        api('/tests/' + encodeURIComponent(id), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate(['exams', 'overview']); toast('Test deleted'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }

    // Question Bank CRUD + Bulk JSON Import
    if (act === 'open-add-q' || act === 'open-edit-q') {
      var examId = act === 'open-add-q' ? id : node.dataset.exam;
      var qObj = {};
      if (act === 'open-edit-q') {
        qObj = (stateCache['qbank:' + examId] || []).find(function (x) { return x.id === id; }) || {};
      }
      openSheet(act === 'open-add-q' ? 'Add Question' : 'Edit Question',
        '<div class="tg-field"><label>Question Text</label><textarea id="qbText" rows="3" class="tg-textarea">' + esc(qObj.questionText || '') + '</textarea></div>' +
        '<div class="tg-field"><label>Option A</label><input id="qbA" class="tg-input" value="' + esc(qObj.optionA || '') + '" /></div>' +
        '<div class="tg-field"><label>Option B</label><input id="qbB" class="tg-input" value="' + esc(qObj.optionB || '') + '" /></div>' +
        '<div class="tg-field"><label>Option C</label><input id="qbC" class="tg-input" value="' + esc(qObj.optionC || '') + '" /></div>' +
        '<div class="tg-field"><label>Option D</label><input id="qbD" class="tg-input" value="' + esc(qObj.optionD || '') + '" /></div>' +
        '<div class="btn-row"><div style="flex:1" class="tg-field"><label>Correct</label><select id="qbAns" class="tg-select">' +
          ['A', 'B', 'C', 'D'].map(function (c) { return '<option value="' + c + '"' + ((qObj.correctAnswer || 'A') === c ? ' selected' : '') + '>' + c + '</option>'; }).join('') +
        '</select></div><div style="flex:2" class="tg-field"><label>Topic</label><input id="qbTopic" class="tg-input" value="' + esc(qObj.topic || '') + '" /></div></div>' +
        '<div class="tg-field"><label>Explanation</label><textarea id="qbExp" rows="2" class="tg-textarea">' + esc(qObj.explanation || '') + '</textarea></div>' +
        '<button class="tg-btn full" data-act="submit-q" data-exam="' + esc(examId) + '" data-id="' + esc(qObj.id || '') + '">Save Question</button>'
      );
      return;
    }
    if (act === 'submit-q') {
      var body = {
        questionText: document.getElementById('qbText').value,
        optionA: document.getElementById('qbA').value,
        optionB: document.getElementById('qbB').value,
        optionC: document.getElementById('qbC').value,
        optionD: document.getElementById('qbD').value,
        correctAnswer: document.getElementById('qbAns').value,
        topic: document.getElementById('qbTopic').value,
        explanation: document.getElementById('qbExp').value
      };
      var qUrl = id ? ('/questions/' + encodeURIComponent(id)) : ('/exams/' + encodeURIComponent(node.dataset.exam) + '/questions');
      api(qUrl, { method: id ? 'PUT' : 'POST', body: JSON.stringify(body) }).then(function () {
        haptic('success'); closeSheet(); swrInvalidate(['qbank:' + node.dataset.exam, 'exams']); toast('Question saved'); renderCurrentView();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'delete-q') {
      confirmDialog('Delete this question from the Question Bank?', function () {
        api('/questions/' + encodeURIComponent(id), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate(['qbank:' + node.dataset.exam, 'exams']); toast('Question deleted'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'open-bulk-q') {
      openSheet('Bulk JSON Question Import',
        '<div class="tg-field"><label>Paste JSON array of questions (up to 300)</label>' +
        '<textarea id="bulkQJson" rows="7" class="tg-textarea" placeholder="[{&quot;questionText&quot;:&quot;...&quot;,&quot;optionA&quot;:&quot;...&quot;,&quot;optionB&quot;:&quot;...&quot;,&quot;optionC&quot;:&quot;...&quot;,&quot;optionD&quot;:&quot;...&quot;,&quot;correctAnswer&quot;:&quot;A&quot;,&quot;explanation&quot;:&quot;...&quot;}]"></textarea></div>' +
        '<button class="tg-btn ok full" data-act="submit-bulk-q" data-id="' + esc(id) + '">Import Questions</button>'
      );
      return;
    }
    if (act === 'submit-bulk-q') {
      var parsedBulk;
      try { parsedBulk = JSON.parse(document.getElementById('bulkQJson').value); } catch (e) { toast('Invalid JSON format'); return; }
      api('/exams/' + encodeURIComponent(id) + '/questions/import', {
        method: 'POST',
        body: JSON.stringify({ questions: parsedBulk })
      }).then(function (res) {
        haptic('success'); closeSheet(); swrInvalidate(['qbank:' + id, 'exams']); toast('Imported ' + (res.count || '') + ' questions!'); renderCurrentView();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }

    // Content & Moderation: Banners
    if (act === 'open-upload-banner') {
      openSheet('Upload Home Banner',
        '<div class="tg-field"><label>Image (&le;5MB) or Video MP4/WebM (&le;20MB)</label><input id="banFile" type="file" accept="image/*,video/mp4,video/webm" class="tg-input" /></div>' +
        '<div class="tg-field"><label>Optional Link URL</label><input id="banUrl" class="tg-input" placeholder="https://..." /></div>' +
        '<div class="tg-field"><label>Optional Link Label</label><input id="banLabel" class="tg-input" placeholder="Explore Offer" /></div>' +
        '<button class="tg-btn full" data-act="submit-upload-banner">Upload &amp; Publish Banner</button>'
      );
      return;
    }
    if (act === 'submit-upload-banner') {
      var bFileInput = document.getElementById('banFile');
      var bFile = bFileInput && bFileInput.files && bFileInput.files[0];
      if (!bFile) { toast('Select an image or video file'); return; }
      toast('Uploading banner...');
      readFileAsBase64(bFile, function (b64, mime) {
        api('/banners', {
          method: 'POST',
          body: JSON.stringify({
            base64: b64,
            mimeType: mime,
            linkUrl: document.getElementById('banUrl').value,
            linkLabel: document.getElementById('banLabel').value
          })
        }).then(function () {
          haptic('success'); closeSheet(); swrInvalidate(['content', 'overview']); toast('Banner uploaded!'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'toggle-banner') {
      var activeVal = node.dataset.next === 'true';
      node.classList.toggle('on');
      api('/banners/' + encodeURIComponent(id) + '/status', { method: 'PUT', body: JSON.stringify({ active: activeVal }) }).then(function () {
        haptic('success'); swrInvalidate(['content', 'overview']); toast(activeVal ? 'Banner active' : 'Banner hidden'); renderCurrentView();
      }).catch(function (e) { node.classList.toggle('on'); haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'reorder-banner') {
      api('/banners/' + encodeURIComponent(id) + '/reorder', { method: 'PUT', body: JSON.stringify({ moveUp: node.dataset.up === '1' }) }).then(function () {
        haptic('light'); swrInvalidate('content'); renderCurrentView();
      }).catch(function (e) { toast(e.message); });
      return;
    }
    if (act === 'open-banner-link') {
      var bItem = ((stateCache['content'] && stateCache['content'].banners) || []).find(function (x) { return x.id === id; }) || {};
      openSheet('Edit Banner Link',
        '<div class="tg-field"><label>Link URL (leave empty to clear)</label><input id="edBanUrl" class="tg-input" value="' + esc(bItem.linkUrl || '') + '" /></div>' +
        '<div class="tg-field"><label>Link Label</label><input id="edBanLbl" class="tg-input" value="' + esc(bItem.linkLabel || '') + '" /></div>' +
        '<button class="tg-btn full" data-act="submit-banner-link" data-id="' + esc(id) + '">Save Link</button>'
      );
      return;
    }
    if (act === 'submit-banner-link') {
      api('/banners/' + encodeURIComponent(id) + '/link', {
        method: 'PUT',
        body: JSON.stringify({ linkUrl: document.getElementById('edBanUrl').value, linkLabel: document.getElementById('edBanLbl').value })
      }).then(function () { haptic('success'); closeSheet(); swrInvalidate('content'); toast('Banner link updated'); renderCurrentView(); })
        .catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'delete-banner') {
      confirmDialog('Delete this Home banner?', function () {
        api('/banners/' + encodeURIComponent(id), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate(['content', 'overview']); toast('Banner deleted'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }

    // Broadcasts & Polls & Feedback
    if (act === 'save-channel-meta') {
      api('/announcement-channel', {
        method: 'PUT',
        body: JSON.stringify({
          announcementChannelName: document.getElementById('chDispName').value,
          officialTelegramChannelUsername: document.getElementById('chTgUser').value
        })
      }).then(function () { haptic('success'); swrInvalidate(['content', 'overview']); toast('Channel updated'); })
        .catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'send-broadcast') {
      api('/broadcasts', {
        method: 'POST',
        body: JSON.stringify({ title: document.getElementById('bcTitle').value, message: document.getElementById('bcMsg').value })
      }).then(function () { haptic('success'); swrInvalidate('content'); toast('Broadcast sent!'); renderCurrentView(); })
        .catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'delete-broadcast') {
      confirmDialog('Delete this broadcast?', function () {
        api('/broadcasts/' + encodeURIComponent(id), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate('content'); toast('Broadcast deleted'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'open-create-poll') {
      openSheet('Create Student Poll',
        '<div class="tg-field"><label>Poll Question</label><input id="plQ" class="tg-input" placeholder="Which exam test series next?" /></div>' +
        '<div class="tg-field"><label>Options (one per line, 2-6 options)</label><textarea id="plOpts" rows="4" class="tg-textarea" placeholder="SSC CGL\\nUPSC Prelims\\nRRB NTPC"></textarea></div>' +
        '<button class="tg-btn full" data-act="submit-create-poll">Publish Poll</button>'
      );
      return;
    }
    if (act === 'submit-create-poll') {
      var opts = document.getElementById('plOpts').value.split('\\n').map(function (s) { return s.trim(); }).filter(Boolean);
      api('/polls', { method: 'POST', body: JSON.stringify({ question: document.getElementById('plQ').value, options: opts }) }).then(function () {
        haptic('success'); closeSheet(); swrInvalidate(['content', 'overview']); toast('Poll published!'); renderCurrentView();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'toggle-poll') {
      var pNext = node.dataset.next === 'true';
      node.classList.toggle('on');
      api('/polls/' + encodeURIComponent(id) + '/status', { method: 'PUT', body: JSON.stringify({ active: pNext }) }).then(function () {
        haptic('success'); swrInvalidate('content'); toast(pNext ? 'Poll active' : 'Poll closed'); renderCurrentView();
      }).catch(function (e) { node.classList.toggle('on'); haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'delete-poll') {
      confirmDialog('Delete this poll and its votes?', function () {
        api('/polls/' + encodeURIComponent(id), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate(['content', 'overview']); toast('Poll deleted'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'open-reply-feedback') {
      openSheet('Reply to Student Feedback',
        '<div class="tg-field"><label>Admin Reply</label><textarea id="fbReplyTxt" rows="3" class="tg-textarea" placeholder="Thanks for reporting!"></textarea></div>' +
        '<button class="tg-btn full" data-act="submit-reply-feedback" data-id="' + esc(id) + '">Send Reply</button>'
      );
      return;
    }
    if (act === 'submit-reply-feedback') {
      api('/feedback/' + encodeURIComponent(id) + '/reply', { method: 'POST', body: JSON.stringify({ reply: document.getElementById('fbReplyTxt').value }) }).then(function () {
        haptic('success'); closeSheet(); swrInvalidate('content'); toast('Reply saved'); renderCurrentView();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'read-feedback') {
      api('/feedback/' + encodeURIComponent(id) + '/read', { method: 'POST' }).then(function () {
        haptic('success'); swrInvalidate('content'); renderCurrentView();
      }).catch(function (e) { toast(e.message); });
      return;
    }
    if (act === 'delete-feedback') {
      confirmDialog('Delete this feedback message?', function () {
        api('/feedback/' + encodeURIComponent(id), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate('content'); toast('Feedback deleted'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }

    // Flagged Question Fixer Actions
    if (act === 'pick-fix-ans') {
      haptic('light');
      document.getElementById('fixAnsVal').value = node.dataset.val;
      document.querySelectorAll('#fixAnsGroup .radio-pill').forEach(function (b) {
        b.classList.toggle('active', b.dataset.val === node.dataset.val);
      });
      return;
    }
    if (act === 'save-flag-fix') {
      api('/reports/' + encodeURIComponent(id) + '/fix-question', {
        method: 'PUT',
        body: JSON.stringify({
          questionText: document.getElementById('fixQ').value,
          optionA: document.getElementById('fixA').value,
          optionB: document.getElementById('fixB').value,
          optionC: document.getElementById('fixC').value,
          optionD: document.getElementById('fixD').value,
          correctAnswer: document.getElementById('fixAnsVal').value,
          explanation: document.getElementById('fixExp').value
        })
      }).then(function () {
        haptic('success'); swrInvalidate(['content', 'overview', 'report:' + id, 'exams']); toast('Question fixed & flag resolved!'); goBack();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'dismiss-flag-fixer') {
      api('/reports/' + encodeURIComponent(id) + '/dismiss', { method: 'POST' }).then(function () {
        haptic('success'); swrInvalidate(['content', 'overview', 'report:' + id]); toast('False flag dismissed'); goBack();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'delete-bad-question') {
      confirmDialog('Permanently delete this bad question and resolve pending flags?', function () {
        api('/reports/' + encodeURIComponent(id) + '/question', { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate(['content', 'overview', 'exams']); toast('Question deleted'); goBack();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }

    // Students, Premium & Orders Actions
    if (act === 'grant-prem-30' || act === 'grant-prem-life') {
      var isLife = act === 'grant-prem-life';
      api('/users/' + encodeURIComponent(id) + '/premium', {
        method: 'POST',
        body: JSON.stringify(isLife ? { isLifetime: true } : { days: 30 })
      }).then(function () {
        haptic('success'); swrInvalidate(['student:' + id, 'students:', 'subscribers', 'overview']); toast(isLife ? 'Lifetime Premium granted!' : '+30d Premium granted!'); renderCurrentView();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'revoke-prem') {
      confirmDialog('Revoke Premium access from this student?', function () {
        api('/users/' + encodeURIComponent(id) + '/premium/revoke', { method: 'POST' }).then(function () {
          haptic('success'); swrInvalidate(['student:' + id, 'students:', 'subscribers', 'overview']); toast('Premium revoked'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'toggle-ban-student') {
      var banNext = node.dataset.next === 'true';
      confirmDialog((banNext ? 'Ban' : 'Unban') + ' this student account?', function () {
        api('/users/' + encodeURIComponent(id) + '/status', { method: 'POST', body: JSON.stringify({ disabled: banNext }) }).then(function () {
          haptic('success'); swrInvalidate(['student:' + id, 'students:', 'overview']); toast(banNext ? 'Student banned' : 'Student unbanned'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'approve-order' || act === 'reject-order') {
      var isApprove = act === 'approve-order';
      var runOrd = function () {
        api('/orders/' + encodeURIComponent(id) + '/' + (isApprove ? 'approve' : 'reject'), { method: 'POST' }).then(function () {
          haptic('success'); swrInvalidate(['orders:', 'subscribers', 'overview']); toast(isApprove ? 'Order approved!' : 'Order rejected'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      };
      if (!isApprove) confirmDialog('Reject UPI order ' + id + '?', runOrd);
      else runOrd();
      return;
    }
    if (act === 'save-prem-config') {
      var isOn = function (elId) {
        var btn = document.querySelector('[data-id="' + elId + '"]');
        return !!(btn && btn.classList.contains('on'));
      };
      api('/premium/config', {
        method: 'PUT',
        body: JSON.stringify({
          isEnabled: isOn('premEnabled'),
          isLifetime: isOn('premLifetime'),
          upiEnabled: isOn('premUpi'),
          qrEnabled: isOn('premQr'),
          planName: document.getElementById('premName').value,
          priceInr: Number(document.getElementById('premPrice').value),
          durationDays: Number(document.getElementById('premDays').value),
          merchantVpa: document.getElementById('premVpa').value,
          merchantName: document.getElementById('premMerchant').value
        })
      }).then(function () { haptic('success'); swrInvalidate(['premiumConfig', 'overview']); toast('Premium & UPI settings saved!'); renderCurrentView(); })
        .catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }

    // Crashes, Maintenance/AI Config, Admin Roles, Audit Log
    if (act === 'copy-crash') {
      var crashItem = ((stateCache['analyticsHealth'] && stateCache['analyticsHealth'].crashes) || []).find(function (c) { return c.signature === id; });
      if (crashItem && navigator.clipboard) {
        navigator.clipboard.writeText(crashItem.exceptionClass + ' | ' + crashItem.plainEnglishSummary + ' | ' + crashItem.topFrame).then(function () { toast('Crash report copied'); }).catch(function () { toast('Copy blocked'); });
      }
      return;
    }
    if (act === 'resolve-crash') {
      api('/crashes/' + encodeURIComponent(id) + '/resolve', { method: 'POST' }).then(function () {
        haptic('success'); swrInvalidate(['analyticsHealth', 'overview']); toast('Crash marked fixed'); renderCurrentView();
      }).catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'delete-crash') {
      confirmDialog('Delete crash signature permanently?', function () {
        api('/crashes/' + encodeURIComponent(id), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate(['analyticsHealth', 'overview']); toast('Crash deleted'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'save-system-config') {
      var mBtn = document.querySelector('[data-id="cfgMaint"]');
      api('/config', {
        method: 'POST',
        body: JSON.stringify({
          maintenanceMode: !!(mBtn && mBtn.classList.contains('on')),
          maintenanceMessage: document.getElementById('cfgMaintMsg').value,
          minimumSupportedVersionCode: Number(document.getElementById('cfgMinVer').value),
          defaultPublishMode: document.getElementById('cfgPubMode').value,
          aiProvider: document.getElementById('cfgAiProvider').value,
          aiModel: document.getElementById('cfgAiModel').value,
          officialTelegramChannelUsername: document.getElementById('cfgTgCh').value,
          officialTelegramSupportUsername: document.getElementById('cfgTgSup').value
        })
      }).then(function () { haptic('success'); swrInvalidate(['overview', 'content']); toast('System & AI config saved!'); renderCurrentView(); })
        .catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'open-add-admin') {
      openSheet('Add Admin Member',
        '<div class="tg-field"><label>Google / Firebase Email</label><input id="newAdminEmail" class="tg-input" placeholder="teammate@gmail.com" /></div>' +
        '<div class="tg-field"><label>Role Title</label><input id="newAdminTitle" class="tg-input" value="Content Moderator" /></div>' +
        '<button class="tg-btn full" data-act="submit-add-admin">Add Admin</button>'
      );
      return;
    }
    if (act === 'submit-add-admin') {
      api('/roles', {
        method: 'POST',
        body: JSON.stringify({ email: document.getElementById('newAdminEmail').value, customTitle: document.getElementById('newAdminTitle').value })
      }).then(function () { haptic('success'); closeSheet(); swrInvalidate(['roles', 'overview']); toast('Admin added!'); renderCurrentView(); })
        .catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'save-admin-rights') {
      var email = node.dataset.email;
      var selectedRights = [];
      document.querySelectorAll('[data-act="toggle-local-right"].on').forEach(function (el) {
        if (el.dataset.id) selectedRights.push(el.dataset.id);
      });
      api('/roles/' + encodeURIComponent(email) + '/rights', {
        method: 'PUT',
        body: JSON.stringify({
          rights: selectedRights,
          customTitle: document.getElementById('roleTitleInput') ? document.getElementById('roleTitleInput').value : undefined
        })
      }).then(function () { haptic('success'); swrInvalidate('roles'); toast('Permissions updated'); renderCurrentView(); })
        .catch(function (e) { haptic('error'); toast(e.message); });
      return;
    }
    if (act === 'remove-admin') {
      var targetEmail = node.dataset.email;
      confirmDialog('Remove ' + targetEmail + ' from Admin team?', function () {
        api('/roles/' + encodeURIComponent(targetEmail), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate(['roles', 'overview']); toast('Admin removed'); goBack();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
    if (act === 'clear-audit-log') {
      var cCat = node.dataset.cat || 'all';
      confirmDialog('Clear ' + cCat + ' audit log entries permanently?', function () {
        api('/audit-log?category=' + encodeURIComponent(cCat), { method: 'DELETE' }).then(function () {
          haptic('success'); swrInvalidate('audit:'); toast('Audit logs cleared'); renderCurrentView();
        }).catch(function (e) { haptic('error'); toast(e.message); });
      });
      return;
    }
  });

  // Legacy-friendly hooks for smoke verification
  window.eveMiniAppNavigate = navigate;
  window.eveMiniAppGoBack = goBack;
  renderCurrentView();
})();
`;
