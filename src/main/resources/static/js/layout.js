(async function () {
  'use strict';

  // ============================================================
  // 皮肤系统：localStorage 记忆 + 顶栏切换
  // ============================================================
  var SKIN_STORAGE_KEY = 'gp.skin';
  var SKIN_DEFAULT = 'tech';
  var VALID_SKINS = ['tech', 'bull'];

  function getStoredSkin() {
    try {
      var v = localStorage.getItem(SKIN_STORAGE_KEY);
      if (VALID_SKINS.indexOf(v) !== -1) return v;
    } catch (e) { /* 隐私模式可能抛错 */ }
    return SKIN_DEFAULT;
  }

  function applySkin(skin, options) {
    if (VALID_SKINS.indexOf(skin) === -1) skin = SKIN_DEFAULT;
    document.documentElement.setAttribute('data-theme', skin);
    if (!options || options.persist !== false) {
      try { localStorage.setItem(SKIN_STORAGE_KEY, skin); } catch (e) { /* ignore */ }
    }
    // 同步所有切换器
    document.querySelectorAll('.skin-switch').forEach(function (sw) {
      sw.setAttribute('data-skin', skin);
      // 更新按钮上的 label + dot
      var label = sw.querySelector('.skin-toggle .label');
      if (label) label.textContent = skin === 'bull' ? '牛市红' : '科技蓝';
      // 更新菜单项的 aria-selected
      sw.querySelectorAll('.skin-menu li[data-skin]').forEach(function (li) {
        li.setAttribute('aria-selected', li.dataset.skin === skin ? 'true' : 'false');
      });
    });
    // 通知其他模块（chart、地图、图表等）重新画
    try { document.dispatchEvent(new CustomEvent('skin:changed', { detail: { skin: skin } })); } catch (e) { /* ignore */ }
  }

  function closeSkinMenu(sw) {
    var menu = sw.querySelector('.skin-menu');
    var toggle = sw.querySelector('.skin-toggle');
    if (menu) menu.hidden = true;
    if (toggle) toggle.setAttribute('aria-expanded', 'false');
  }

  function toggleSkinMenu(sw) {
    var menu = sw.querySelector('.skin-menu');
    var toggle = sw.querySelector('.skin-toggle');
    if (!menu || !toggle) return;
    var isOpen = !menu.hidden;
    if (isOpen) {
      closeSkinMenu(sw);
    } else {
      // 先关掉其他已展开的菜单
      document.querySelectorAll('.skin-switch .skin-menu').forEach(function (m) {
        if (m !== menu) m.hidden = true;
      });
      document.querySelectorAll('.skin-toggle').forEach(function (t) {
        t.setAttribute('aria-expanded', 'false');
      });
      menu.hidden = false;
      toggle.setAttribute('aria-expanded', 'true');
    }
  }

  // 启动时立刻应用，避免 FOUC（与 <head> 里内联脚本或提前渲染冲突时为兜底）
  applySkin(getStoredSkin(), { persist: false });

  function bindSkinSwitch() {
    document.querySelectorAll('.skin-switch').forEach(function (sw) {
      // 防止重复绑定
      if (sw.dataset.bound === '1') return;
      sw.dataset.bound = '1';

      var toggle = sw.querySelector('.skin-toggle');
      var menu = sw.querySelector('.skin-menu');

      // 点击按钮 → 展开/收起
      if (toggle) {
        toggle.addEventListener('click', function (e) {
          e.stopPropagation();
          toggleSkinMenu(sw);
        });
      }

      // 点击菜单项 → 切主题 + 收起
      if (menu) {
        menu.addEventListener('click', function (e) {
          var li = e.target.closest('li[data-skin]');
          if (!li) return;
          applySkin(li.dataset.skin);
          closeSkinMenu(sw);
        });
      }
    });

    // 点击页面其它位置 → 关闭所有皮肤菜单（只绑一次）
    if (!document.body.dataset.skinOutsideBound) {
      document.body.dataset.skinOutsideBound = '1';
      document.addEventListener('click', function () {
        document.querySelectorAll('.skin-switch .skin-menu').forEach(function (m) {
          if (!m.hidden) m.hidden = true;
        });
        document.querySelectorAll('.skin-toggle[aria-expanded="true"]').forEach(function (t) {
          t.setAttribute('aria-expanded', 'false');
        });
      });
      // ESC 键关闭
      document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
          document.querySelectorAll('.skin-switch .skin-menu').forEach(function (m) { m.hidden = true; });
          document.querySelectorAll('.skin-toggle[aria-expanded="true"]').forEach(function (t) { t.setAttribute('aria-expanded', 'false'); });
        }
      });
    }
  }

  // ============================================================
  // Header 渲染
  // ============================================================
  function isActive(matches) {
    const pathname = window.location.pathname;
    return matches.some(function (part) {
      if (part.charAt(0) === '/') return pathname === part;
      return pathname.endsWith('/' + part);
    });
  }

  async function renderHeader() {
    const mount = document.getElementById('siteHeader');
    if (!mount) return;

    try {
      const response = await fetch('header.html?v=20260629-profile-link', { cache: 'no-cache' });
      if (!response.ok) throw new Error('header load failed');
      mount.innerHTML = await response.text();
    }           catch (e) {
      mount.innerHTML =
        '<header class="top-nav">' +
          '<div class="nav-inner">' +
            '<a href="./" class="brand"><span class="brand-mark">↗</span><span class="brand-name">投资助手</span></a>' +
            '<nav class="nav-links">' +
              '<a href="./" class="nav-link" data-match="/,/index.html,/gp,/gp/,/gp/index.html">财务分析</a>' +
              '<a href="xiebo-invest.html" class="nav-link" data-match="xiebo-invest.html">林奇投资</a>' +
              '<a href="invest.html" class="nav-link" data-match="invest.html">谢博投资</a>' +
              '<a href="market-recap.html" class="nav-link nav-link-recap" data-match="market-recap.html">' +
                '<span class="nav-link-recap-label">每日复盘</span>' +
                '<span class="nav-recap-count" id="navRecapCount" hidden></span>' +
              '</a>' +
              '<a href="tech-ai.html" class="nav-link" data-match="tech-ai.html">AI监控</a>' +
              '<a href="potential.html" class="nav-link" data-match="potential.html">潜力监控</a>' +
              '<a href="prosperity-pick.html" class="nav-link" data-match="prosperity-pick.html,stock-analysis.html">个股研究</a>' +
              '<a href="prosperity-strong.html" class="nav-link" data-match="prosperity-strong.html">热点强势选股</a>' +
              '<a href="study.html" class="nav-link" data-match="study.html,course.html,node.html,card.html,quiz.html">学习搭子</a>' +
            '</nav>' +
            '<div class="skin-switch" id="skinSwitch" data-skin="tech">' +
              '<button type="button" class="skin-toggle" aria-haspopup="listbox" aria-expanded="false" aria-controls="skinMenu" title="切换皮肤">' +
                '<span class="dot"></span>' +
                '<span class="label">科技蓝</span>' +
                '<svg class="caret" viewBox="0 0 12 12" width="10" height="10" aria-hidden="true">' +
                  '<path d="M2 4 L6 8 L10 4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>' +
                '</svg>' +
              '</button>' +
              '<ul id="skinMenu" class="skin-menu" role="listbox" aria-label="皮肤" hidden>' +
                '<li role="option" data-skin="tech" aria-selected="true"><span class="dot"></span>科技蓝</li>' +
                '<li role="option" data-skin="bull" aria-selected="false"><span class="dot"></span>牛市红</li>' +
              '</ul>' +
            '</div>' +
              '<div id="authArea" class="auth-area">' +
              '<button id="authLoginBtn" class="auth-btn auth-btn-login" type="button">登录</button>' +
              '<div id="authUser" class="auth-user hidden">' +
                '<a id="authProfileBtn" href="profile.html" class="auth-btn auth-btn-profile" type="button">👤 个人中心</a>' +
                '<a id="authAdminBtn" href="admin-users.html" class="auth-btn auth-btn-admin hidden" type="button">⚙️ 管理后台</a>' +
                '<span id="authUsername" class="auth-username"></span>' +
                '<span id="authRole" class="auth-role"></span>' +
                '<button id="authLogoutBtn" class="auth-btn auth-btn-logout" type="button">退出</button>' +
              '</div>' +
            '</div>' +
          '</div>' +
        '</header>' +
        '<div id="authModal" class="modal-overlay hidden" role="dialog" aria-modal="true">' +
          '<div class="modal-box">' +
            '<div class="modal-header">' +
              '<span class="modal-title">登录</span>' +
              '<button id="authModalClose" class="modal-close" type="button">×</button>' +
            '</div>' +
            '<div class="modal-body">' +
              '<div id="authError" class="auth-error hidden"></div>' +
              '<div class="form-group">' +
                '<label for="authCodeInput">登录码</label>' +
                '<input id="authCodeInput" class="form-input" type="text" placeholder="输入管理员提供的登录码" maxlength="30" autocomplete="off" />' +
              '</div>' +
              '<button id="authSubmitBtn" class="primary-btn wide" type="button">确认登录</button>' +
            '</div>' +
          '</div>' +
        '</div>';
    }

    mount.querySelectorAll('.nav-link').forEach(function (link) {
      const matches = (link.dataset.match || '').split(',').filter(Boolean);
      link.classList.toggle('active', isActive(matches));
    });

    // 关键修复：#authModal 必须挂在 body 直接子元素上。
    // 否则 .modal-overlay 的 position:fixed 在多层 flex container 中失效，
    // 表现就是"透明弹窗"——半透明遮罩能看到背后的搜索框/导航，二维码也看不见。
    bindSkinSwitch();
    loadRecapBadge(mount);

    // ── 修复 #1：把 authModal 提升到 body 直接子元素 ──────────────────────
    var authModal = document.getElementById('authModal');
    if (authModal && authModal.parentNode !== document.body) {
      document.body.appendChild(authModal);
    }
    if (authModal) {
      // ── 修复 #2：兜底 inline style，防止 var(--surface) 解析失败或 inset 不识别 ─
      authModal.style.position = 'fixed';
      authModal.style.top = '0';
      authModal.style.right = '0';
      authModal.style.bottom = '0';
      authModal.style.left = '0';
      authModal.style.zIndex = '99999';
      authModal.style.background = 'rgba(0,0,0,0.6)';
      authModal.style.display = 'flex';
      authModal.style.alignItems = 'center';
      authModal.style.justifyContent = 'center';
      var modalBox = authModal.querySelector('.modal-box');
      if (modalBox) {
        modalBox.style.background = '#fff';
        modalBox.style.position = 'relative';
        modalBox.style.zIndex = '1';
        modalBox.style.maxWidth = '92vw';
        modalBox.style.maxHeight = '92vh';
        modalBox.style.overflow = 'auto';
      }
    }
  }

  function loadRecapBadge(mount) {
    const badge = mount.querySelector('#navRecapCount');
    const recapLink = mount.querySelector('.nav-link-recap');
    if (!badge || !recapLink) return;

    fetch('api/market-recaps/badge', { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data) return;
        const today = Number(data.today) || 0;
        const yesterday = Number(data.yesterday) || 0;
        const total = today + yesterday;
        if (total <= 0) {
          badge.hidden = true;
          badge.textContent = '';
          return;
        }
        badge.hidden = false;
        // 紧凑显示: 今 1 · 昨 2
        badge.textContent = '今 ' + today + ' · 昨 ' + yesterday;
        badge.title = data.latestTradeDate
          ? ('最近一篇复盘：' + data.latestTradeDate + '（点击查看完整复盘页）')
          : '点击查看完整复盘页';
        if (data.latestId) {
          recapLink.setAttribute('href', 'market-recap.html?id=' + encodeURIComponent(String(data.latestId)));
        }
      })
      .catch(function () {
        // 静默失败:不显示角标
        badge.hidden = true;
      });
  }

  function renderFooter() {
    const mount = document.getElementById('siteFooter');
    if (!mount) return;

    mount.innerHTML =
      '<footer class="site-footer" data-collapsed="true">' +
        '<div id="siteFooterPanel" class="site-footer-row">' +
          '<section class="donate-card" aria-label="打赏支持">' +
            '<div class="donate-qr">' +
              '<img src="donate-qr.png" alt="扫码支付">' +
            '</div>' +
            '<div class="donate-text">' +
              '<div class="donate-title">祝你账户长红 🔥</div>' +
              '<div class="donate-line">内容有用随心支持</div>' +
              '<div class="donate-line">助力网站稳定更新！</div>' +
            '</div>' +
          '</section>' +
          '<section class="wishpool-shell" aria-labelledby="wishPoolTitle">' +
            '<div class="wishpool-panel">' +
              '<div class="wishpool-header">' +
                '<div class="wishpool-kicker">✦ 许愿池</div>' +
                '<button id="wishPoolSubmit" class="wishpool-submit" type="button">提交许愿 →</button>' +
              '</div>' +
              '<input id="wishPoolInput" class="wishpool-input" type="text" maxlength="500" ' +
                'placeholder="输入你的愿望，例如：希望增加每日复盘导出功能，让我可以分享给更多人（功能描述越详细越快实现）">' +
              '<div class="wishpool-row">' +
                '<input id="wishPoolEmail" class="wishpool-input wishpool-input-email" type="email" maxlength="120" ' +
                  'placeholder="可选：留下你的邮箱方便沟通需求">' +
                '<div id="wishPoolStatus" class="wishpool-status" aria-live="polite"></div>' +
              '</div>' +
            '</div>' +
          '</section>' +
        '</div>' +
        '<button class="site-footer-toggle" type="button" aria-expanded="false" aria-controls="siteFooterPanel">' +
          '<span class="toggle-icons" aria-hidden="true">' +
            '<span class="toggle-icon">💖</span>' +
            '<span class="toggle-icon">✨</span>' +
          '</span>' +
          '<span class="toggle-text">支持作者 / 提个需求</span>' +
          '<span class="toggle-hint">悬停或点击展开</span>' +
          '<span class="toggle-chevron" aria-hidden="true">▾</span>' +
        '</button>' +
      '</footer>';
  }

  function bindFooterToggle() {
    const footer = document.querySelector('.site-footer');
    if (!footer) return;
    const toggle = footer.querySelector('.site-footer-toggle');
    if (!toggle) return;

    // 点击 toggle：锁定 / 解锁展开状态
    toggle.addEventListener('click', function (event) {
      event.stopPropagation();
      const isLocked = footer.classList.toggle('is-locked');
      footer.setAttribute('data-collapsed', isLocked ? 'false' : 'true');
      toggle.setAttribute('aria-expanded', isLocked ? 'true' : 'false');
      // 锁定展开时让表单拿到焦点更顺手
      if (isLocked) {
        const input = document.getElementById('wishPoolInput');
        if (input) setTimeout(function () { input.focus({ preventScroll: true }); }, 360);
      }
    });

    // 点击空白区域时如果处于锁定展开 → 收起（贴心一点）
    document.addEventListener('click', function (event) {
      if (!footer.classList.contains('is-locked')) return;
      if (footer.contains(event.target)) return;
      footer.classList.remove('is-locked');
      footer.setAttribute('data-collapsed', 'true');
      toggle.setAttribute('aria-expanded', 'false');
    });
  }

  function bindWishPool() {
    const input = document.getElementById('wishPoolInput');
    const emailInput = document.getElementById('wishPoolEmail');
    const submitBtn = document.getElementById('wishPoolSubmit');
    const status = document.getElementById('wishPoolStatus');
    if (!input || !submitBtn || !status) return;

    const setStatus = function (text, tone) {
      status.textContent = text || '';
      status.className = 'wishpool-status' + (tone ? ' ' + tone : '');
    };

    const submitWish = async function () {
      const wish = input.value.trim();
      if (!wish) {
        setStatus('请先写下你希望平台实现的能力。', 'error');
        input.focus();
        return;
      }
      const email = emailInput ? emailInput.value.trim() : '';
      if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        setStatus('邮箱格式不对哦，请检查一下～', 'error');
        if (emailInput) emailInput.focus();
        return;
      }

      submitBtn.disabled = true;
      submitBtn.textContent = '提交中...';
      setStatus('正在投递到许愿池...', 'pending');

      try {
        const body = {
          wish: wish,
          page: window.location.pathname
        };
        if (email) body.email = email;

        const response = await fetch('api/wishes', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        const data = await response.json().catch(function () { return {}; });
        if (!response.ok) {
          throw new Error(data.message || '提交失败，请稍后再试');
        }
        input.value = '';
        if (emailInput) emailInput.value = '';
        setStatus(data.message || '已收到许愿，我们会认真评估。', 'success');
      } catch (error) {
        setStatus(error.message || '提交失败，请稍后再试。', 'error');
      } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = '提交许愿 →';
      }
    };

    submitBtn.addEventListener('click', submitWish);
    input.addEventListener('keydown', function (event) {
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        event.preventDefault();
        submitWish();
      }
    });
    if (emailInput) {
      emailInput.addEventListener('keydown', function (event) {
        if (event.key === 'Enter') {
          event.preventDefault();
          submitWish();
        }
      });
    }
  }

  // Header 渲染后再统一绑一次切换器（兜底：fallback HTML 路径下也有按钮）
  await renderHeader();
  bindSkinSwitch();
  renderFooter();
  bindWishPool();
  bindFooterToggle();
  bindAuth();
  initPageViewTracker();
}());

// ============================================================
//  页面访问追踪（静默，不阻塞导航）
// ============================================================
function initPageViewTracker() {
  var SESSION_KEY = 'gp_session_id';
  var PAGE_START_KEY = 'gp_page_start_time';

  function getOrCreateSessionId() {
    try {
      var sid = sessionStorage.getItem(SESSION_KEY);
      if (!sid) {
        sid = 's' + Date.now() + '-' + Math.random().toString(36).slice(2, 9);
        sessionStorage.setItem(SESSION_KEY, sid);
      }
    } catch (e) { sid = 'noid'; }
    return sid;
  }

  var sessionId = getOrCreateSessionId();
  var pagePath = window.location.pathname;

  // 记录本页面开始时间（用于计算停留时长）
  var pageStartTime = Date.now();
  try { sessionStorage.setItem(PAGE_START_KEY + pagePath, pageStartTime); } catch (e) {}

  // 页面卸载前尝试上报（同步 XHR，兼容性最好）
  window.addEventListener('beforeunload', function () {
    var duration = Math.round((Date.now() - pageStartTime) / 1000);
    var token = (function () {
      try { return localStorage.getItem('gp_auth_token'); } catch (e) { return null; }
    }());
    var userId = null;
    if (token) {
      try {
        var payload = JSON.parse(atob(token.split('.')[1]));
        userId = payload.sub || payload.userId || null;
      } catch (e) {}
    }
    // 用 navigator.sendBeacon 静默发送，兜底用图片请求
    var body = JSON.stringify({
      pagePath: pagePath,
      sessionId: sessionId,
      userId: userId
    });
    if (navigator.sendBeacon) {
      navigator.sendBeacon('/gp/api/stats/page-view', new Blob([body], { type: 'application/json' }));
    } else {
      var img = new Image();
      img.src = '/gp/api/stats/page-view?payload=' + encodeURIComponent(body);
    }
  });

  // 立即上报一次（PV）
  setTimeout(function () {
    reportPageView(pagePath, sessionId);
  }, 500);
}

function reportPageView(pagePath, sessionId) {
  var token = (function () {
    try { return localStorage.getItem('gp_auth_token'); } catch (e) { return null; }
  }());
  var userId = null;
  if (token) {
    try {
      var payload = JSON.parse(atob(token.split('.')[1]));
      userId = payload.sub || payload.userId || null;
    } catch (e) {}
  }
  fetch('/gp/api/stats/page-view', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': String(userId || '') },
    body: JSON.stringify({ pagePath: pagePath, sessionId: sessionId || '' }),
    keepalive: true
  }).catch(function () {}); // 静默
}

// ============================================================
//  认证：登录 / 状态 / 登出
// ============================================================
var AUTH_TOKEN_KEY = 'gp_auth_token';
var AUTH_USER_KEY = 'gp_auth_user';
var QR_POLL_HANDLE = null;
var QR_POLL_SESSION = null;
var QR_POLL_EXPIRES_AT = 0;

function bindAuth() {
  var loginBtn = document.getElementById('authLoginBtn');
  var authUser = document.getElementById('authUser');
  var authUsername = document.getElementById('authUsername');
  var authRole = document.getElementById('authRole');
  var logoutBtn = document.getElementById('authLogoutBtn');
  var modal = document.getElementById('authModal');
  var modalClose = document.getElementById('authModalClose');
  var codeInput = document.getElementById('authCodeInput');
  var submitBtn = document.getElementById('authSubmitBtn');
  var authError = document.getElementById('authError');
  var tabScan = document.getElementById('authTabScan');
  var tabCode = document.getElementById('authTabCode');
  var paneScan = document.getElementById('authPaneScan');
  var paneCode = document.getElementById('authPaneCode');
  var scanLoading = document.getElementById('authScanLoading');
  var scanReady = document.getElementById('authScanReady');
  var scanFallback = document.getElementById('authScanFallback');
  var scanUnavailable = document.getElementById('authScanUnavailable');
  var scanImg = document.getElementById('authScanImg');
  var scanStatus = document.getElementById('authScanStatus');
  var scanMask = document.getElementById('authScanMask');
  var scanMaskText = document.getElementById('authScanMaskText');
  var scanRefresh = document.getElementById('authScanRefresh');
  var scanOauth = document.getElementById('authScanOAuth');

  if (!loginBtn) return;

  // ── 弹窗显隐 ──────────────────────────────────────────
  function showModal() {
    authError.classList.add('hidden');
    authError.textContent = '';
    codeInput.value = '';
    modal.classList.remove('hidden');
    activateTab('scan');
  }
  function hideModal() {
    modal.classList.add('hidden');
    stopQrPoll();
  }

  // ── Tab 切换 ────────────────────────────────────────
  function activateTab(name) {
    if (name === 'scan') {
      tabScan.classList.add('is-active'); tabScan.setAttribute('aria-selected', 'true');
      tabCode.classList.remove('is-active'); tabCode.setAttribute('aria-selected', 'false');
      paneScan.classList.remove('hidden');
      paneCode.classList.add('hidden');
      initQrLogin();
    } else {
      tabCode.classList.add('is-active'); tabCode.setAttribute('aria-selected', 'true');
      tabScan.classList.remove('is-active'); tabScan.setAttribute('aria-selected', 'false');
      paneCode.classList.remove('hidden');
      paneScan.classList.add('hidden');
      stopQrPoll();
      setTimeout(function(){ codeInput && codeInput.focus(); }, 50);
    }
  }

  function setScanPhase(phase) {
    scanLoading.classList.toggle('hidden', phase !== 'loading');
    scanReady.classList.toggle('hidden', phase !== 'ready');
    scanFallback.classList.toggle('hidden', phase !== 'fallback');
    scanUnavailable.classList.toggle('hidden', phase !== 'unavailable');
  }

  // ── 微信扫码流程 ─────────────────────────────────────────
  function initQrLogin() {
    stopQrPoll();
    setScanPhase('loading');
    fetch('/gp/api/auth/wechat/qr-info', { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (info) {
        if (!info) { setScanPhase('unavailable'); return; }
        if (info.mpReady) {
          startMpQr();
        } else if (info.oauthReady) {
          setScanPhase('fallback');
        } else {
          setScanPhase('unavailable');
        }
      })
      .catch(function () { setScanPhase('unavailable'); });
  }

  function startMpQr() {
    setScanPhase('loading');
    fetch('/gp/api/auth/wechat/mp/qr')
      .then(function (r) { return r.ok ? r.json() : Promise.reject(r); })
      .then(function (data) {
        if (!data || !data.ready) { setScanPhase('unavailable'); return; }
        scanImg.src = data.qrUrl;
        scanStatus.textContent = '等待扫码…';
        scanMask.classList.add('hidden');
        setScanPhase('ready');
        QR_POLL_EXPIRES_AT = Date.now() + (data.expireSeconds || 300) * 1000;
        startQrPoll(data.sessionId);
      })
      .catch(function () { setScanPhase('unavailable'); });
  }

  function startQrPoll(sessionId) {
    QR_POLL_SESSION = sessionId;
    stopQrPoll();
    QR_POLL_HANDLE = setInterval(function () {
      if (QR_POLL_SESSION !== sessionId) return;
      if (QR_POLL_EXPIRES_AT && Date.now() > QR_POLL_EXPIRES_AT) {
        scanStatus.textContent = '二维码已过期，请刷新';
        stopQrPoll();
        return;
      }
      fetch('/gp/api/auth/wechat/mp/poll?sessionId=' + encodeURIComponent(sessionId))
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (data) {
          if (!data) return;
          if (data.status === 'SCANNING') {
            scanStatus.textContent = '等待扫码…';
          } else if (data.status === 'SCANNED') {
            scanStatus.textContent = '已扫码，请在手机上确认';
            scanMask.classList.remove('hidden');
            scanMaskText.textContent = '已扫码';
          } else if (data.status === 'CONFIRMED') {
            scanStatus.textContent = '已确认，正在登录…';
            scanMaskText.textContent = '已确认';
          } else if (data.status === 'LOGGED_IN') {
            scanStatus.textContent = '登录成功';
            completeLogin(data.accessToken, data.user);
          } else if (data.status === 'EXPIRED') {
            scanStatus.textContent = '二维码已过期，请刷新';
            stopQrPoll();
          }
        })
        .catch(function () { /* 忽略下一次重试 */ });
    }, 1500);
  }

  function stopQrPoll() {
    if (QR_POLL_HANDLE) { clearInterval(QR_POLL_HANDLE); QR_POLL_HANDLE = null; }
    QR_POLL_SESSION = null;
  }

  function completeLogin(token, user) {
    if (!token) return;
    localStorage.setItem(AUTH_TOKEN_KEY, token);
    if (user) {
      try { localStorage.setItem(AUTH_USER_KEY, JSON.stringify(user)); } catch (e) {}
      updateAuthUI(user);
    } else {
      checkAuthStatus();
    }
    stopQrPoll();
    hideModal();
  }

  // ── 事件绑定 ────────────────────────────────────────────
  loginBtn.addEventListener('click', showModal);
  if (modalClose) modalClose.addEventListener('click', hideModal);
  modal.addEventListener('click', function (e) {
    if (e.target === modal) hideModal();
  });
  if (tabScan) tabScan.addEventListener('click', function () { activateTab('scan'); });
  if (tabCode) tabCode.addEventListener('click', function () { activateTab('code'); });
  if (scanRefresh) scanRefresh.addEventListener('click', function () {
    scanMask.classList.add('hidden');
    startMpQr();
  });
  if (scanOauth) scanOauth.addEventListener('click', function () {
    fetch('/gp/api/auth/wechat/qr-url')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data && data.ready && data.authorizeUrl) {
          window.open(data.authorizeUrl, '_blank');
          authError.classList.remove('hidden');
          authError.textContent = '请在微信中完成扫码，登录后将自动回到此页面。';
        } else {
          authError.classList.remove('hidden');
          authError.textContent = (data && data.note) || '微信登录暂不可用';
        }
      })
      .catch(function () {
        authError.classList.remove('hidden');
        authError.textContent = '网络错误，请稍后再试';
      });
  });

  // ── 登录码（管理员/经理） ────────────────────────────────
  submitBtn.addEventListener('click', function () {
    var code = codeInput.value.trim();
    if (!code) {
      authError.textContent = '请输入登录码';
      authError.classList.remove('hidden');
      return;
    }
    authError.classList.add('hidden');
    submitBtn.disabled = true;
    submitBtn.textContent = '登录中…';
    fetch('/gp/api/auth/login-code', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: code })
    })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.error) {
          authError.textContent = data.error;
          authError.classList.remove('hidden');
        } else {
          localStorage.setItem(AUTH_TOKEN_KEY, data.accessToken);
          if (data.user) {
            try { localStorage.setItem(AUTH_USER_KEY, JSON.stringify(data.user)); } catch (e) {}
          }
          updateAuthUI(data.user);
          hideModal();
        }
      })
      .catch(function () {
        authError.textContent = '网络错误，请稍后重试';
        authError.classList.remove('hidden');
      })
      .finally(function () {
        submitBtn.disabled = false;
        submitBtn.textContent = '确认登录';
      });
  });

  codeInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); submitBtn.click(); }
  });

  logoutBtn.addEventListener('click', function () {
    localStorage.removeItem(AUTH_TOKEN_KEY);
    localStorage.removeItem(AUTH_USER_KEY);
    loginBtn.classList.remove('hidden');
    authUser.classList.add('hidden');
  });

  // 初始化：检查登录状态
  checkAuthStatus();
}

// ── 监听 OAuth 回调（或公众号回调跳转）后写回的 token ────────────────
// OAuth 跳转在新窗口打开 callback 写 localStorage；主窗口靠 storage 事件感知。
window.addEventListener('storage', function (e) {
  if (e.key === AUTH_USER_KEY) {
    try {
      var user = JSON.parse(e.newValue || 'null');
      if (user) updateAuthUI(user);
    } catch (_) {}
  } else if (e.key === AUTH_TOKEN_KEY && e.newValue) {
    checkAuthStatus();
  }
});
window.addEventListener('message', function (e) {
  if (e.data && e.data.type === 'gp-auth-success') {
    checkAuthStatus();
    var m = document.getElementById('authModal');
    if (m) m.classList.add('hidden');
  }
});

function checkAuthStatus() {
  var token = localStorage.getItem(AUTH_TOKEN_KEY);
  if (!token) return;
  fetch('/gp/api/auth/me', {
    headers: { 'Authorization': 'Bearer ' + token }
  })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (user) {
      if (user) updateAuthUI(user);
      else localStorage.removeItem(AUTH_TOKEN_KEY);
    })
    .catch(function () { localStorage.removeItem(AUTH_TOKEN_KEY); });
}

function updateAuthUI(user) {
  var loginBtn = document.getElementById('authLoginBtn');
  var authUser = document.getElementById('authUser');
  var authUsername = document.getElementById('authUsername');
  var authRole = document.getElementById('authRole');
  var authAdminBtn = document.getElementById('authAdminBtn');
  if (!loginBtn) return;
  loginBtn.classList.add('hidden');
  authUser.classList.remove('hidden');
  authUsername.textContent = user.username || user.phone || '用户';
  authRole.textContent = roleLabel(user.role);
  authRole.dataset.role = user.role;
  // 把当前用户角色缓存进 GPAuth，供业务模块做按钮级权限判断
  if (window.GPAuth && typeof GPAuth.setRole === 'function') {
    GPAuth.setRole(user.role);
  }
  // ADMIN 用户显示管理后台入口
  if (authAdminBtn) {
    if (user.role === 'ADMIN') {
      authAdminBtn.classList.remove('hidden');
    } else {
      authAdminBtn.classList.add('hidden');
    }
  }
}

function roleLabel(role) {
  if (role === 'ADMIN') return '管理员';
  if (role === 'MANAGER') return '经理';
  if (role === 'USER') return '用户';
  return role || '';
}

// 供其他模块获取当前 token / 角色
window.GPAuth = (function () {
  var currentRole = '';
  return {
    token: function () { return localStorage.getItem('gp_auth_token'); },
    headers: function () {
      var t = this.token();
      return t ? { 'Authorization': 'Bearer ' + t } : {};
    },
    setRole: function (role) {
      currentRole = role || '';
      // 角色确定后通知业务模块重新做权限判断（修 init() 早于 /api/auth/me 的竞态）
      document.dispatchEvent(new CustomEvent('gp:role-changed', { detail: { role: currentRole } }));
    },
    role: function () { return currentRole; },
    // 是否拥有股票池修改权限（MANAGER 或 ADMIN）
    canManageInvest: function () {
      return currentRole === 'MANAGER' || currentRole === 'ADMIN';
    }
  };
})();
