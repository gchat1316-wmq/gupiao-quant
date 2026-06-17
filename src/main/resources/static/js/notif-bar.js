/**
 * 通知轮播条 (demo)
 * - 自动 4s 切换，悬停暂停
 * - 点击圆点可手动跳转
 * - 数据先写死在 HTML 里，后续改为从 API 拉取
 */
(function () {
  'use strict';

  const list = document.getElementById('notifList');
  const dotsWrap = document.getElementById('notifDots');
  if (!list || !dotsWrap) return;

  const items = Array.from(list.querySelectorAll('.notif-item'));
  if (items.length === 0) return;

  const INTERVAL = 4000;
  let current = 0;
  let timer = null;

  // 1) 生成圆点
  dotsWrap.innerHTML = items
    .map((_, i) => `<button class="notif-dot${i === 0 ? ' active' : ''}" data-i="${i}" aria-label="切换到第 ${i + 1} 条"></button>`)
    .join('');

  // 2) 切换函数
  function go(idx) {
    current = (idx + items.length) % items.length;
    items.forEach((el, i) => el.classList.toggle('active', i === current));
    dotsWrap.querySelectorAll('.notif-dot').forEach((d, i) =>
      d.classList.toggle('active', i === current)
    );
  }

  function start() {
    stop();
    timer = setInterval(() => go(current + 1), INTERVAL);
  }

  function stop() {
    if (timer) { clearInterval(timer); timer = null; }
  }

  // 3) 圆点点击
  dotsWrap.addEventListener('click', (e) => {
    const btn = e.target.closest('.notif-dot');
    if (!btn) return;
    go(Number(btn.dataset.i));
    start(); // 重新计时
  });

  // 4) 悬停暂停
  const bar = document.getElementById('notifBar');
  if (bar) {
    bar.addEventListener('mouseenter', stop);
    bar.addEventListener('mouseleave', start);
  }

  // 5) 启动
  items[0].classList.add('active');
  start();
})();

/* ===== 搜索区空状态自适应居中 ===== */
(function () {
  'use strict';
  const stage = document.getElementById('searchStage');
  const resultSection = document.getElementById('resultSection');
  if (!stage || !resultSection) return;

  // 初始态
  sync();
  // 监听 resultSection 的 class 变化（app.js 切 .hidden）
  const obs = new MutationObserver(sync);
  obs.observe(resultSection, { attributes: true, attributeFilter: ['class'] });

  function sync() {
    const hasResult = !resultSection.classList.contains('hidden');
    stage.classList.toggle('is-centered', !hasResult);
  }
})();
