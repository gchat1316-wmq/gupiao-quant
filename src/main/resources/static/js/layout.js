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
      const response = await fetch('header.html?v=20260611-shared-header', { cache: 'no-cache' });
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
              '<a href="market-recap.html" class="nav-link" data-match="market-recap.html">每日复盘</a>' +
              '<a href="tech-ai.html" class="nav-link" data-match="tech-ai.html">AI监控</a>' +
              '<a href="potential.html" class="nav-link" data-match="potential.html">潜力监控</a>' +
              '<a href="prosperity-pick.html" class="nav-link" data-match="prosperity-pick.html,stock-analysis.html">个股研究</a>' +
              '<a href="prosperity-strong.html" class="nav-link" data-match="prosperity-strong.html">强势股选股</a>' +
              '<a href="study.html" class="nav-link" data-match="study.html,course.html,node.html,card.html,quiz.html">学习搭子</a>' +
            '</nav>' +
          '</div>' +
        '</header>';
    }

    mount.querySelectorAll('.nav-link').forEach(function (link) {
      const matches = (link.dataset.match || '').split(',').filter(Boolean);
      link.classList.toggle('active', isActive(matches));
    });
  }

  function renderFooter() {
    const mount = document.getElementById('siteFooter');
    if (!mount) return;

    mount.innerHTML =
      '<footer class="site-footer">' +
        '<section class="wishpool-shell" aria-labelledby="wishPoolTitle">' +
          '<div class="wishpool-panel">' +
            '<div class="wishpool-kicker">✦ 许愿池</div>' +
            '<input id="wishPoolInput" class="wishpool-input" type="text" maxlength="500" ' +
              'placeholder="输入你的愿望，例如：希望增加复盘摘要导出">' +
            '<div id="wishPoolStatus" class="wishpool-status" aria-live="polite"></div>' +
            '<button id="wishPoolSubmit" class="wishpool-submit" type="button">提交许愿 →</button>' +
          '</div>' +
        '</section>' +
      '</footer>';
  }

  function bindWishPool() {
    const input = document.getElementById('wishPoolInput');
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

      submitBtn.disabled = true;
      submitBtn.textContent = '提交中...';
      setStatus('正在投递到许愿池...', 'pending');

      try {
        const response = await fetch('api/wishes', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            wish: wish,
            page: window.location.pathname
          })
        });
        const data = await response.json().catch(function () { return {}; });
        if (!response.ok) {
          throw new Error(data.message || '提交失败，请稍后再试');
        }
        input.value = '';
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
  }

  await renderHeader();
  renderFooter();
  bindWishPool();
}());
