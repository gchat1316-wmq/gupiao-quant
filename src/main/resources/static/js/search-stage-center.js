/**
 * 首页搜索区空状态自适应居中
 * - 当 resultSection 显示（hasResult = true）时，搜索区不再居中
 */
(function () {
  'use strict';
  const stage = document.getElementById('searchStage');
  const resultSection = document.getElementById('resultSection');
  if (!stage || !resultSection) return;

  function sync() {
    const hasResult = !resultSection.classList.contains('hidden');
    stage.classList.toggle('is-centered', !hasResult);
  }
  sync();
  const obs = new MutationObserver(sync);
  obs.observe(resultSection, { attributes: true, attributeFilter: ['class'] });
})();