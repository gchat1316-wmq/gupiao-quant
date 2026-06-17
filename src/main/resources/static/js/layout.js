(async function () {
  'use strict';

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
      const response = await fetch('header.html?v=20260617-recap-badge', { cache: 'no-cache' });
      if (!response.ok) throw new Error('header load failed');
      mount.innerHTML = await response.text();
    } catch (e) {
      mount.innerHTML =
        '<header class="top-nav">' +
          '<div class="nav-inner">' +
            '<a href="./" class="brand"><span class="brand-mark">↗</span><span class="brand-name">投资助手</span></a>' +
            '<nav class="nav-links">' +
              '<a href="./" class="nav-link" data-match="/,/index.html,/gp,/gp/,/gp/index.html">财务分析</a>' +
              '<a href="lynch-invest.html" class="nav-link" data-match="lynch-invest.html">林奇投资</a>' +
              '<a href="invest.html" class="nav-link" data-match="invest.html">龙江投资</a>' +
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
          '</div>' +
        '</header>';
    }

    mount.querySelectorAll('.nav-link').forEach(function (link) {
      const matches = (link.dataset.match || '').split(',').filter(Boolean);
      link.classList.toggle('active', isActive(matches));
    });

    loadRecapBadge(mount);
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
      '<footer class="site-footer">' +
        '<div class="site-footer-row">' +
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
      '</footer>';
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

  await renderHeader();
  renderFooter();
  bindWishPool();
}());
