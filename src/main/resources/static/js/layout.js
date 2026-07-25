(async function () {
  'use strict';

  // ============================================================
  // 样式注入：许愿池浮动卡片
  // ============================================================
  (function injectWishMarqueeCss() {
    if (document.getElementById('wish-marquee-css')) return;
    var link = document.createElement('link');
    link.id = 'wish-marquee-css';
    link.rel = 'stylesheet';
    link.href = 'css/wish-marquee.css?v=20260707-marquee-v1';
    document.head.appendChild(link);
  })();

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

  // 下拉菜单（更多 ▾）：点击展开 + 当前项高亮 + 外部点击 / ESC 关闭
  function bindNavDropdown(scope) {
    var dropdown = (scope || document).querySelector('.nav-dropdown');
    if (!dropdown) return;
    var toggle = dropdown.querySelector('.nav-dropdown-toggle');
    var menu = dropdown.querySelector('.nav-dropdown-menu');
    if (!toggle || !menu) return;

    function setOpen(open) {
      menu.hidden = !open;
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      dropdown.classList.toggle('open', open);
    }

    // 当前页对应的下拉项高亮
    var currentPath = window.location.pathname;
    var activeItem = null;
    menu.querySelectorAll('.nav-dropdown-item').forEach(function (a) {
      var matches = (a.dataset.match || '').split(',').filter(Boolean);
      if (isActive(matches)) {
        a.classList.add('active');
        activeItem = a;
      }
    });
    // 只要下拉里任何一项处于当前页面，就给 toggle 一个 active 状态
    if (activeItem) toggle.classList.add('active');

    toggle.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      setOpen(menu.hidden);
    });

    // 点击下拉项后让浏览器正常跳转，无需手动关闭
    menu.querySelectorAll('.nav-dropdown-item').forEach(function (a) {
      a.addEventListener('click', function () {
        setOpen(false);
      });
    });

    // 点击页面其它位置 → 关闭
    if (!document.body.dataset.navDropdownOutsideBound) {
      document.body.dataset.navDropdownOutsideBound = '1';
      document.addEventListener('click', function (event) {
        if (!dropdown.contains(event.target)) {
          setOpen(false);
        }
      });
      document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') setOpen(false);
      });
    }
  }

  async function renderHeader() {
    const mount = document.getElementById('siteHeader');
    if (!mount) return;

    try {
      const response = await fetch('header.html?v=20260630-pm-v2', { cache: 'no-cache' });
      if (!response.ok) throw new Error('header load failed');
      mount.innerHTML = await response.text();
    }           catch (e) {
      mount.innerHTML =
        '<header class="top-nav">' +
          '<div class="nav-inner">' +
            '<a href="./" class="brand"><span class="brand-mark">↗</span><span class="brand-name">投资助手</span></a>' +
            '<nav class="nav-links">' +
              '<a href="./" class="nav-link" data-match="/,/index.html,/gp,/gp/,/gp/index.html">财务分析</a>' +
              '<a href="market-recap.html" class="nav-link nav-link-recap" data-match="market-recap.html">' +
                '<span class="nav-link-recap-label">每日复盘</span>' +
                '<span class="nav-recap-count" id="navRecapCount" hidden></span>' +
              '</a>' +
              '<a href="invest.html" class="nav-link" data-match="invest.html">谢博投资</a>' +
              '<a href="prosperity-pick.html" class="nav-link" data-match="prosperity-pick.html,stock-analysis.html">个股研究</a>' +
              '<a href="prosperity-strong.html" class="nav-link" data-match="prosperity-strong.html">热点强势选股</a>' +
              '<a href="monitor.html" class="nav-link" data-match="monitor.html">📊 统一监控</a>' +
              '<a href="swing.html" class="nav-link" data-match="swing.html">趋势波段</a>' +
              '<div class="nav-dropdown" id="navMoreDropdown">' +
                '<button type="button" class="nav-link nav-dropdown-toggle" aria-haspopup="menu" aria-expanded="false" aria-controls="navMoreMenu">' +
                  '更多' +
                  '<svg class="caret" viewBox="0 0 12 12" width="10" height="10" aria-hidden="true">' +
                    '<path d="M2 4 L6 8 L10 4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>' +
                  '</svg>' +
                '</button>' +
                '<ul id="navMoreMenu" class="nav-dropdown-menu" role="menu" hidden>' +
                  '<li role="none"><a href="xiebo-invest.html" role="menuitem" class="nav-dropdown-item" data-match="xiebo-invest.html">林奇投资</a></li>' +
                  '<li role="none"><a href="position-management.html" role="menuitem" class="nav-dropdown-item" data-match="position-management.html">仓位管理</a></li>' +
                  '<li role="none"><a href="study.html" role="menuitem" class="nav-dropdown-item" data-match="study.html">学习搭子</a></li>' +
                '</ul>' +
              '</div>' +
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

    // 下拉菜单（更多 ▾）：标记当前项 + 绑定展开/收起
    bindNavDropdown(mount);

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
          badge.removeAttribute('title');
          return;
        }
        badge.hidden = false;
        // 文字不再渲染,仅靠 CSS 小红点提示;悬停时给出详情
        badge.textContent = '';
        badge.title = (today > 0 ? ('今日新增 ' + today + ' 篇复盘') : '今日无新增') +
          ' · ' +
          (yesterday > 0 ? ('昨日 ' + yesterday + ' 篇') : '昨日无') +
          (data.latestTradeDate ? '\n最近一篇复盘：' + data.latestTradeDate : '') +
          '\n点击查看完整复盘页';
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
  bindForgotAuth();
  initPageViewTracker();
  // 右下角浮动卡片(异步,失败静默)
  renderWishMarquee();
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
  // ── 5 个 Tab（phone / emailPwd / emailCode / scan / code）──
  var tabPhone = document.getElementById('authTabPhone');
  var tabEmailPwd = document.getElementById('authTabEmailPwd');
  var tabEmailCode = document.getElementById('authTabEmailCode');
  var tabScan = document.getElementById('authTabScan');
  var tabCode = document.getElementById('authTabCode');
  var panePhone = document.getElementById('authPanePhone');
  var paneEmailPwd = document.getElementById('authPaneEmailPwd');
  var paneEmailCode = document.getElementById('authPaneEmailCode');
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

  // 手机号 + 验证码
  var phoneInput = document.getElementById('authPhoneInput');
  var phoneCodeInput = document.getElementById('authPhoneCodeInput');
  var phoneSendBtn = document.getElementById('authPhoneSendBtn');
  var phoneSubmitBtn = document.getElementById('authPhoneSubmitBtn');
  var phoneGotoPwd = document.getElementById('authPhoneGotoPwd');
  var phoneForgot = document.getElementById('authPhoneForgot');

  // 邮箱 + 密码
  var emailInput = document.getElementById('authEmailInput');
  var emailPwdInput = document.getElementById('authEmailPwdInput');
  var emailPwdLoginBtn = document.getElementById('authEmailPwdLoginBtn');
  var emailPwdGotoRegister = document.getElementById('authEmailPwdGotoRegister');
  var emailPwdForgot = document.getElementById('authEmailPwdForgot');

  // 邮箱 + 验证码
  var emailCodeAddr = document.getElementById('authEmailCodeAddr');
  var emailCodeInput = document.getElementById('authEmailCodeInput');
  var emailCodeSendBtn = document.getElementById('authEmailCodeSendBtn');
  var emailCodeSubmitBtn = document.getElementById('authEmailCodeSubmitBtn');

  var forgotModal = document.getElementById('forgotModal');

  if (!loginBtn) return;

  var ALL_TABS = {
    phone:     { btn: tabPhone,     pane: panePhone },
    emailPwd:  { btn: tabEmailPwd,  pane: paneEmailPwd },
    emailCode: { btn: tabEmailCode, pane: paneEmailCode },
    scan:      { btn: tabScan,      pane: paneScan },
    code:      { btn: tabCode,      pane: paneCode }
  };

  function showError(msg) {
    if (!authError) return;
    authError.style.background = '#fff0ee';
    authError.style.color = '#e53';
    authError.style.borderColor = '#fcc';
    authError.textContent = msg;
    authError.classList.remove('hidden');
  }
  function showInfo(msg) {
    if (!authError) return;
    authError.style.background = '#f0fff5';
    authError.style.color = '#07c160';
    authError.style.borderColor = '#b7eccc';
    authError.textContent = msg;
    authError.classList.remove('hidden');
  }
  function clearError() {
    if (!authError) return;
    authError.textContent = '';
    authError.classList.add('hidden');
    authError.style.background = '';
    authError.style.color = '';
    authError.style.borderColor = '';
  }

  /** 通用 API POST，自动解析错误 */
  function apiPost(path, body) {
    return fetch('/gp' + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(function (r) {
      return r.json().then(function (d) { return { ok: r.ok, data: d }; });
    }).then(function (res) {
      if (!res.ok || (res.data && res.data.error)) {
        showError((res.data && res.data.error) || '请求失败');
        return null;
      }
      return res.data;
    }).catch(function () { showError('网络错误，请稍后重试'); return null; });
  }

  /** 60s 倒计时挂到发送按钮上 */
  function startSendCooldown(btn) {
    btn.dataset.cooldownLeft = '60';
    var handle = setInterval(function () {
      var left = parseInt(btn.dataset.cooldownLeft || '0', 10);
      if (left > 0) {
        btn.disabled = true;
        btn.textContent = left + 's 后重发';
        btn.dataset.cooldownLeft = String(left - 1);
      } else {
        btn.disabled = false;
        btn.textContent = '发送验证码';
        delete btn.dataset.cooldownLeft;
        clearInterval(handle);
      }
    }, 1000);
  }

  // ── 弹窗显隐 ──────────────────────────────────────────
  function showModal() {
    clearError();
    if (codeInput) codeInput.value = '';
    if (modal) modal.classList.remove('hidden');
    activateTab('phone');
  }
  function hideModal() {
    if (modal) modal.classList.add('hidden');
    stopQrPoll();
  }

  // ── Tab 切换（5 tab 通用） ─────────────────────────────
  function activateTab(name) {
    clearError();
    Object.keys(ALL_TABS).forEach(function (k) {
      var t = ALL_TABS[k];
      var active = k === name;
      if (t.btn) {
        t.btn.classList.toggle('is-active', active);
        t.btn.setAttribute('aria-selected', active ? 'true' : 'false');
      }
      if (t.pane) t.pane.classList.toggle('hidden', !active);
    });
    if (name === 'scan') {
      initQrLogin();
    } else if (name === 'code') {
      stopQrPoll();
      setTimeout(function () { codeInput && codeInput.focus(); }, 50);
    } else if (name === 'phone') {
      setTimeout(function () { phoneInput && phoneInput.focus(); }, 50);
    } else if (name === 'emailPwd') {
      setTimeout(function () { emailInput && emailInput.focus(); }, 50);
    } else if (name === 'emailCode') {
      setTimeout(function () { emailCodeAddr && emailCodeAddr.focus(); }, 50);
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
  if (tabPhone) tabPhone.addEventListener('click', function () { activateTab('phone'); });
  if (tabEmailPwd) tabEmailPwd.addEventListener('click', function () { activateTab('emailPwd'); });
  if (tabEmailCode) tabEmailCode.addEventListener('click', function () { activateTab('emailCode'); });
  if (tabScan) tabScan.addEventListener('click', function () { activateTab('scan'); });
  if (tabCode) tabCode.addEventListener('click', function () { activateTab('code'); });

  // ── 手机号 + 验证码 提交 ────────────────────────────────
  if (phoneSendBtn) {
    phoneSendBtn.addEventListener('click', function () {
      var phone = phoneInput.value.trim();
      if (!/^1[3-9]\d{9}$/.test(phone)) {
        showError('手机号格式不正确');
        phoneInput.focus();
        return;
      }
      clearError();
      phoneSendBtn.disabled = true;
      phoneSendBtn.textContent = '发送中…';
      apiPost('/api/auth/send-code', { phone: phone }).then(function (data) {
        if (!data) {
          phoneSendBtn.disabled = false;
          phoneSendBtn.textContent = '发送验证码';
          return;
        }
        // 后端仅在 dev/mock 模式（未配 SMS 服务商）才回传 code 字段
        // 真服务上线后这里不会被填，用户去查短信
        if (data.code && phoneCodeInput) {
          phoneCodeInput.value = data.code;
          showInfo('验证码已自动填入，可直接登录');
        } else {
          showInfo('验证码已发送，请查收短信');
        }
        startSendCooldown(phoneSendBtn);
      });
    });
  }
  function submitPhoneLogin() {
    var phone = phoneInput.value.trim();
    var code = phoneCodeInput.value.trim();
    if (!/^1[3-9]\d{9}$/.test(phone)) { showError('手机号不正确'); phoneInput.focus(); return; }
    if (!/^\d{6}$/.test(code)) { showError('请输入 6 位数字验证码'); phoneCodeInput.focus(); return; }
    clearError();
    phoneSubmitBtn.disabled = true;
    phoneSubmitBtn.textContent = '登录中…';
    apiPost('/api/auth/verify-code', { phone: phone, code: code }).then(function (data) {
      if (!data) {
        phoneSubmitBtn.disabled = false;
        phoneSubmitBtn.textContent = '登录 / 注册';
        return;
      }
      completeLogin(data.accessToken, data.user);
    });
  }
  if (phoneSubmitBtn) phoneSubmitBtn.addEventListener('click', submitPhoneLogin);
  if (phoneCodeInput) phoneCodeInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); submitPhoneLogin(); }
  });
  if (phoneGotoPwd) phoneGotoPwd.addEventListener('click', function () { activateTab('emailPwd'); });
  if (phoneForgot) phoneForgot.addEventListener('click', function () {
    hideModal();
    if (window.GPAuth && typeof GPAuth.showForgot === 'function') {
      GPAuth.showForgot('phone');
    }
  });

  // ── 邮箱 + 密码 登录 ────────────────────────────────────
  function submitEmailPwdLogin() {
    var email = emailInput.value.trim();
    var pwd = emailPwdInput.value;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { showError('邮箱格式不正确'); emailInput.focus(); return; }
    if (!pwd || pwd.length < 8) { showError('密码至少 8 位'); emailPwdInput.focus(); return; }
    clearError();
    emailPwdLoginBtn.disabled = true;
    emailPwdLoginBtn.textContent = '登录中…';
    apiPost('/api/auth/login', { username: email, password: pwd }).then(function (data) {
      if (!data) {
        emailPwdLoginBtn.disabled = false;
        emailPwdLoginBtn.textContent = '登录';
        return;
      }
      completeLogin(data.accessToken, data.user);
    });
  }
  if (emailPwdLoginBtn) emailPwdLoginBtn.addEventListener('click', submitEmailPwdLogin);
  if (emailPwdInput) emailPwdInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); submitEmailPwdLogin(); }
  });
  if (emailPwdGotoRegister) emailPwdGotoRegister.addEventListener('click', function () { activateTab('emailCode'); });
  if (emailPwdForgot) emailPwdForgot.addEventListener('click', function () {
    hideModal();
    if (window.GPAuth && typeof GPAuth.showForgot === 'function') {
      GPAuth.showForgot('email');
    }
  });

  // ── 邮箱 + 验证码 提交 ──────────────────────────────────
  if (emailCodeSendBtn) {
    emailCodeSendBtn.addEventListener('click', function () {
      var email = emailCodeAddr.value.trim();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        showError('邮箱格式不正确');
        emailCodeAddr.focus();
        return;
      }
      clearError();
      emailCodeSendBtn.disabled = true;
      emailCodeSendBtn.textContent = '发送中…';
      apiPost('/api/auth/send-email-code', { email: email }).then(function (data) {
        if (!data) {
          emailCodeSendBtn.disabled = false;
          emailCodeSendBtn.textContent = '发送验证码';
          return;
        }
        // 后端仅在 dev/mock 模式（未配邮件服务）才回传 code 字段
        // 真服务上线后这里不会被填，用户去查邮箱
        if (data.code && emailCodeInput) {
          emailCodeInput.value = data.code;
          showInfo('验证码已自动填入，可直接登录');
        } else {
          showInfo('验证码已发送，请查收邮箱');
        }
        startSendCooldown(emailCodeSendBtn);
      });
    });
  }
  function submitEmailCodeLogin() {
    var email = emailCodeAddr.value.trim();
    var code = emailCodeInput.value.trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { showError('邮箱不正确'); emailCodeAddr.focus(); return; }
    if (!/^\d{6}$/.test(code)) { showError('请输入 6 位数字验证码'); emailCodeInput.focus(); return; }
    clearError();
    emailCodeSubmitBtn.disabled = true;
    emailCodeSubmitBtn.textContent = '登录中…';
    apiPost('/api/auth/verify-email-code', { email: email, code: code }).then(function (data) {
      if (!data) {
        emailCodeSubmitBtn.disabled = false;
        emailCodeSubmitBtn.textContent = '登录 / 注册';
        return;
      }
      completeLogin(data.accessToken, data.user);
    });
  }
  if (emailCodeSubmitBtn) emailCodeSubmitBtn.addEventListener('click', submitEmailCodeLogin);
  if (emailCodeInput) emailCodeInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); submitEmailCodeLogin(); }
  });
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

// ============================================================
//  许愿池 · 右下角浮动卡片（5s 自动切换，可手动 ←/→，可关闭）
// ============================================================
async function renderWishMarquee() {
  var LS_KEY = 'gp.wish_marquee_closed';
  if (localStorage.getItem(LS_KEY) === '1') return;

  // 单例：如果已经有 root 就不重复
  if (document.getElementById('wishMarqueeRoot')) return;

  let rows;
  try {
    const r = await fetch('api/wishes/public?size=20', { headers: { Accept: 'application/json' } });
    if (!r.ok) return;
    rows = await r.json();
  } catch (e) {
    return;
  }
  if (!Array.isArray(rows) || rows.length === 0) return;

  const root = document.createElement('div');
  root.id = 'wishMarqueeRoot';
  root.innerHTML = `
    <div class="wish-marquee-card" id="wishMarqueeCard">
      <div class="wish-marquee-header">
        <span class="wish-marquee-kicker">✦ 许愿池</span>
        <button class="wish-marquee-close" type="button" id="wishMarqueeClose" aria-label="关闭">×</button>
      </div>
      <div class="wish-marquee-wish" id="wishMarqueeWish"></div>
      <div class="wish-marquee-reply">
        <div class="wish-marquee-reply-label">↩ 站长回复</div>
        <div id="wishMarqueeReply"></div>
      </div>
      <div class="wish-marquee-reply-meta">
        <span id="wishMarqueeMeta"></span>
      </div>
      <div class="wish-marquee-nav">
        <button type="button" id="wishMarqueePrev">‹ 上一条</button>
        <span class="wish-marquee-nav-count" id="wishMarqueeCount">1 / ${rows.length}</span>
        <button type="button" id="wishMarqueeNext">下一条 ›</button>
      </div>
    </div>
  `;
  document.body.appendChild(root);

  const card    = root.querySelector('#wishMarqueeCard');
  const wishEl  = root.querySelector('#wishMarqueeWish');
  const replyEl = root.querySelector('#wishMarqueeReply');
  const metaEl  = root.querySelector('#wishMarqueeMeta');
  const countEl = root.querySelector('#wishMarqueeCount');
  const prevBtn = root.querySelector('#wishMarqueePrev');
  const nextBtn = root.querySelector('#wishMarqueeNext');
  const closeBtn = root.querySelector('#wishMarqueeClose');

  function fmtTime(iso) {
    if (!iso) return '';
    try {
      const d = new Date(iso);
      if (isNaN(d.getTime())) return iso;
      const p = function (n) { return n < 10 ? '0' + n : '' + n; };
      return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
    } catch (e) { return iso; }
  }

  function render(idx) {
    const w = rows[idx];
    if (!w) return;
    wishEl.textContent = w.wish || '';
    replyEl.textContent = w.reply || '';
    const author = w.replyBy ? '—— ' + w.replyBy : '—— 站长';
    metaEl.textContent = (w.replyAt ? fmtTime(w.replyAt) : '') +
      (author ? (metaEl.textContent === '' ? author : ' · ' + author) : '');
    // 简化: meta 一行展示
    metaEl.textContent = author + (w.replyAt ? ' · ' + fmtTime(w.replyAt) : '');
    countEl.textContent = (idx + 1) + ' / ' + rows.length;
    prevBtn.disabled = idx <= 0;
    nextBtn.disabled = idx >= rows.length - 1;
  }

  let cur = 0;
  let timer = null;

  function show() {
    card.classList.add('is-visible');
  }
  function hideAndNext() {
    card.classList.remove('is-visible');
    setTimeout(function () {
      cur = (cur + 1) % rows.length;
      render(cur);
      show();
    }, 400);
  }

  function start() {
    stop();
    timer = setInterval(hideAndNext, 5000);
  }
  function stop() {
    if (timer) { clearInterval(timer); timer = null; }
  }

  prevBtn.addEventListener('click', function () {
    if (cur <= 0) return;
    stop();
    card.classList.remove('is-visible');
    setTimeout(function () {
      cur -= 1;
      render(cur);
      show();
      start();
    }, 200);
  });
  nextBtn.addEventListener('click', function () {
    stop();
    hideAndNext();
    setTimeout(start, 1000);
  });
  closeBtn.addEventListener('click', function () {
    stop();
    if (root.parentNode) root.parentNode.removeChild(root);
    try { localStorage.setItem(LS_KEY, '1'); } catch (e) {}
  });

  render(cur);
  setTimeout(show, 80);
  start();
}

// ============================================================
//  忘记密码 modal（手机号 / 邮箱 两种重置路径）
// ============================================================
function bindForgotAuth() {
  var modal = document.getElementById('forgotModal');
  if (!modal) return;

  var closeBtn = document.getElementById('forgotModalClose');
  var tabPhone = document.getElementById('forgotTabPhone');
  var tabEmail = document.getElementById('forgotTabEmail');
  var panePhone = document.getElementById('forgotPanePhone');
  var paneEmail = document.getElementById('forgotPaneEmail');
  var errEl = document.getElementById('forgotError');
  var okEl = document.getElementById('forgotMessage');

  var phoneInput = document.getElementById('forgotPhoneInput');
  var phoneCode = document.getElementById('forgotPhoneCodeInput');
  var phoneSend = document.getElementById('forgotPhoneSendBtn');
  var phonePwd = document.getElementById('forgotPhoneNewPwd');
  var phoneSubmit = document.getElementById('forgotPhoneSubmitBtn');

  var emailInput = document.getElementById('forgotEmailInput');
  var emailCode = document.getElementById('forgotEmailCodeInput');
  var emailSend = document.getElementById('forgotEmailSendBtn');
  var emailPwd = document.getElementById('forgotEmailNewPwd');
  var emailSubmit = document.getElementById('forgotEmailSubmitBtn');

  function showErr(msg) {
    if (!errEl) return;
    okEl.classList.add('hidden');
    errEl.textContent = msg;
    errEl.classList.remove('hidden');
  }
  function showOk(msg) {
    if (!okEl) return;
    errEl.classList.add('hidden');
    okEl.textContent = msg;
    okEl.classList.remove('hidden');
  }
  function clearAll() {
    if (errEl) { errEl.classList.add('hidden'); errEl.textContent = ''; }
    if (okEl) { okEl.classList.add('hidden'); okEl.textContent = ''; }
  }
  function activateTab(name) {
    var isPhone = name === 'phone';
    if (tabPhone) {
      tabPhone.classList.toggle('is-active', isPhone);
      tabPhone.setAttribute('aria-selected', isPhone ? 'true' : 'false');
    }
    if (tabEmail) {
      tabEmail.classList.toggle('is-active', !isPhone);
      tabEmail.setAttribute('aria-selected', !isPhone ? 'true' : 'false');
    }
    if (panePhone) panePhone.classList.toggle('hidden', !isPhone);
    if (paneEmail) paneEmail.classList.toggle('hidden', isPhone);
  }
  function hide() { modal.classList.add('hidden'); }

  // 手机号重置
  if (phoneSend) {
    phoneSend.addEventListener('click', function () {
      var phone = phoneInput.value.trim();
      if (!/^1[3-9]\d{9}$/.test(phone)) { showErr('手机号不正确'); phoneInput.focus(); return; }
      clearAll();
      phoneSend.disabled = true;
      phoneSend.textContent = '发送中…';
      fetch('/gp/api/auth/send-code', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone: phone })
      }).then(function (r) { return r.json(); }).then(function (data) {
        if (data.error) {
          showErr(data.error);
          phoneSend.disabled = false;
          phoneSend.textContent = '发送验证码';
          return;
        }
        // 后端仅在 dev/mock 模式（未配 SMS 服务商）才回传 code 字段
        // 真服务上线后这里不会被填，用户去查短信
        if (data.code && phoneCode) {
          phoneCode.value = data.code;
          showOk('验证码已自动填入，可直接重置');
        } else {
          showOk('验证码已发送，请查收短信');
        }
        phoneSend.dataset.cooldownLeft = '60';
        var handle = setInterval(function () {
          var left = parseInt(phoneSend.dataset.cooldownLeft || '0', 10);
          if (left > 0) {
            phoneSend.textContent = left + 's 后重发';
            phoneSend.dataset.cooldownLeft = String(left - 1);
          } else {
            phoneSend.disabled = false;
            phoneSend.textContent = '发送验证码';
            delete phoneSend.dataset.cooldownLeft;
            clearInterval(handle);
          }
        }, 1000);
      }).catch(function () {
        showErr('网络错误');
        phoneSend.disabled = false;
        phoneSend.textContent = '发送验证码';
      });
    });
  }
  if (phoneSubmit) {
    phoneSubmit.addEventListener('click', function () {
      var phone = phoneInput.value.trim();
      var code = phoneCode.value.trim();
      var pwd = phonePwd.value;
      if (!/^1[3-9]\d{9}$/.test(phone)) { showErr('手机号不正确'); return; }
      if (!/^\d{6}$/.test(code)) { showErr('请输入 6 位验证码'); return; }
      if (!pwd || pwd.length < 8) { showErr('新密码至少 8 位'); return; }
      clearAll();
      phoneSubmit.disabled = true;
      phoneSubmit.textContent = '重置中…';
      fetch('/gp/api/auth/reset-password-sms', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone: phone, code: code, newPassword: pwd })
      }).then(function (r) { return r.json(); }).then(function (data) {
        if (data.error) {
          showErr(data.error);
          phoneSubmit.disabled = false;
          phoneSubmit.textContent = '重置密码';
          return;
        }
        showOk('重置成功，请使用新密码登录');
        phoneInput.value = ''; phoneCode.value = ''; phonePwd.value = '';
        phoneSubmit.disabled = false;
        phoneSubmit.textContent = '重置密码';
        setTimeout(function () { hide(); }, 1500);
      }).catch(function () {
        showErr('网络错误');
        phoneSubmit.disabled = false;
        phoneSubmit.textContent = '重置密码';
      });
    });
  }

  // 邮箱重置
  if (emailSend) {
    emailSend.addEventListener('click', function () {
      var email = emailInput.value.trim();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { showErr('邮箱格式不正确'); emailInput.focus(); return; }
      clearAll();
      emailSend.disabled = true;
      emailSend.textContent = '发送中…';
      fetch('/gp/api/auth/send-email-code', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email })
      }).then(function (r) { return r.json(); }).then(function (data) {
        if (data.error) {
          showErr(data.error);
          emailSend.disabled = false;
          emailSend.textContent = '发送验证码';
          return;
        }
        // 后端仅在 dev/mock 模式（未配邮件服务）才回传 code 字段
        // 真服务上线后这里不会被填，用户去查邮箱
        if (data.code && emailCode) {
          emailCode.value = data.code;
          showOk('验证码已自动填入，可直接重置');
        } else {
          showOk('验证码已发送，请查收邮箱');
        }
        emailSend.dataset.cooldownLeft = '60';
        var handle = setInterval(function () {
          var left = parseInt(emailSend.dataset.cooldownLeft || '0', 10);
          if (left > 0) {
            emailSend.textContent = left + 's 后重发';
            emailSend.dataset.cooldownLeft = String(left - 1);
          } else {
            emailSend.disabled = false;
            emailSend.textContent = '发送验证码';
            delete emailSend.dataset.cooldownLeft;
            clearInterval(handle);
          }
        }, 1000);
      }).catch(function () {
        showErr('网络错误');
        emailSend.disabled = false;
        emailSend.textContent = '发送验证码';
      });
    });
  }
  if (emailSubmit) {
    emailSubmit.addEventListener('click', function () {
      var email = emailInput.value.trim();
      var code = emailCode.value.trim();
      var pwd = emailPwd.value;
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { showErr('邮箱不正确'); return; }
      if (!/^\d{6}$/.test(code)) { showErr('请输入 6 位验证码'); return; }
      if (!pwd || pwd.length < 8) { showErr('新密码至少 8 位'); return; }
      clearAll();
      emailSubmit.disabled = true;
      emailSubmit.textContent = '重置中…';
      fetch('/gp/api/auth/reset-password-email', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email, code: code, newPassword: pwd })
      }).then(function (r) { return r.json(); }).then(function (data) {
        if (data.error) {
          showErr(data.error);
          emailSubmit.disabled = false;
          emailSubmit.textContent = '重置密码';
          return;
        }
        showOk('重置成功，请使用新密码登录');
        emailInput.value = ''; emailCode.value = ''; emailPwd.value = '';
        emailSubmit.disabled = false;
        emailSubmit.textContent = '重置密码';
        setTimeout(function () { hide(); }, 1500);
      }).catch(function () {
        showErr('网络错误');
        emailSubmit.disabled = false;
        emailSubmit.textContent = '重置密码';
      });
    });
  }

  if (tabPhone) tabPhone.addEventListener('click', function () { clearAll(); activateTab('phone'); });
  if (tabEmail) tabEmail.addEventListener('click', function () { clearAll(); activateTab('email'); });
  if (closeBtn) closeBtn.addEventListener('click', hide);
  modal.addEventListener('click', function (e) { if (e.target === modal) hide(); });

  // 把 showForgot 暴露给 GPAuth，供 bindAuth() 里"忘记密码"链接调用
  window.GPAuth = window.GPAuth || {};
  window.GPAuth.showForgot = function (initialTab) {
    clearAll();
    activateTab(initialTab === 'email' ? 'email' : 'phone');
    modal.classList.remove('hidden');
  };
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
  // 管理后台链接始终隐藏（用户通过 URL 直接访问 admin-users.html）
  if (authAdminBtn) {
    authAdminBtn.classList.add('hidden');
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
