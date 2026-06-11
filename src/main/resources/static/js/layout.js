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
              '<a href="invest.html" class="nav-link" data-match="invest.html">龙江投资</a>' +
              '<a href="market-recap.html" class="nav-link" data-match="market-recap.html">每日复盘</a>' +
              '<a href="tech-ai.html" class="nav-link" data-match="tech-ai.html">AI监控</a>' +
              '<a href="potential.html" class="nav-link" data-match="potential.html">潜力监控</a>' +
              '<a href="prosperity-pick.html" class="nav-link" data-match="prosperity-pick.html">景气度选股</a>' +
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
        '<span>联系方式：新功能提需求，发送到邮箱 </span>' +
        '<a href="mailto:gchat1316@gmail.com">gchat1316@gmail.com</a>' +
      '</footer>';
  }

  await renderHeader();
  renderFooter();
}());
