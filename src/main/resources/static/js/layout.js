(function () {
  'use strict';

  const NAV_ITEMS = [
    { href: './', label: '财务分析', match: ['/', '/index.html', '/gp', '/gp/', '/gp/index.html'] },
    { href: 'invest.html', label: '龙江投资', match: ['invest.html'] },
    { href: 'market-recap.html', label: '每日复盘', match: ['market-recap.html'] },
    { href: 'tech-ai.html', label: '科技AI', match: ['tech-ai.html'] },
    { href: 'prosperity-pick.html', label: '景气度选股', match: ['prosperity-pick.html'] },
    { href: 'prosperity-strong.html', label: '强势股选股', match: ['prosperity-strong.html'] },
    { href: 'study.html', label: '学习搭子', match: ['study.html', 'course.html', 'node.html', 'card.html', 'quiz.html'] },
  ];

  function isActive(item) {
    const pathname = window.location.pathname;
    return item.match.some(function (part) {
      if (part.charAt(0) === '/') return pathname === part;
      return pathname.endsWith('/' + part);
    });
  }

  function renderHeader() {
    const mount = document.getElementById('siteHeader');
    if (!mount) return;

    const links = NAV_ITEMS.map(function (item) {
      return '<a href="' + item.href + '" class="nav-link' + (isActive(item) ? ' active' : '') + '">' + item.label + '</a>';
    }).join('');

    mount.innerHTML =
      '<header class="top-nav">' +
        '<div class="nav-inner">' +
          '<a href="./" class="brand">' +
            '<span class="brand-mark">↗</span>' +
            '<span class="brand-name">投资助手</span>' +
          '</a>' +
          '<nav class="nav-links">' + links + '</nav>' +
        '</div>' +
      '</header>';
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

  renderHeader();
  renderFooter();
}());
