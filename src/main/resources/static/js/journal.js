/* =============================================================
 * journal.js — 交易日志前端
 *   1) 新建表单 + 5 条红线勾选 + 客户端预校验
 *   2) 拉价 + 自动填入场价
 *   3) 提交 POST /api/journal/trades
 * ============================================================= */
(function () {
  'use strict';

  var $ = function (s) { return document.querySelector(s); };
  var API = '/gp/api/journal';

  document.addEventListener('DOMContentLoaded', function () {
    var stockInput = $('#jlStockCode');
    var entryInput = $('#jlEntryPrice');
    var stopInput  = $('#jlStopPrice');
    var targetInput= $('#jlTargetPrice');

    // Debounce stock → fetch current price → fill entry
    var stockTimer;
    if (stockInput) stockInput.addEventListener('input', function () {
      clearTimeout(stockTimer);
      var code = stockInput.value.trim();
      if (!code) return;
      stockTimer = setTimeout(function () {
        fetch('/gp/api/xiebo-invest/quote?keyword=' + encodeURIComponent(code))
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(function (d) {
            if (!d) return;
            var p = d.price || d.currentPrice || (d.quote && d.quote.price);
            if (p != null && !entryInput.value) entryInput.value = Number(p).toFixed(2);
          }).catch(function () {});
      }, 280);
    });

    // Enable submit only when all 5 red lines checked
    var submit = $('#jlSubmit');
    var checks = ['#rl1','#rl2','#rl3','#rl4','#rl5'].map(function (s) { return $(s); });
    function refreshSubmit() {
      submit.disabled = checks.some(function (c) { return !c.checked; });
    }
    checks.forEach(function (c) { if (c) c.addEventListener('change', refreshSubmit); });

    // Form submit
    var form = $('#jlNewForm');
    if (form) form.addEventListener('submit', function (e) {
      e.preventDefault();
      var mode = document.querySelector('input[name="mode"]:checked').value;
      var payload = {
        mode: mode,
        stockCode: stockInput.value.trim(),
        stockName: $('#jlStockName').value.trim() || null,
        entryPrice: Number(entryInput.value),
        stopPrice:  Number(stopInput.value),
        targetPrice: targetInput.value ? Number(targetInput.value) : null,
        entryShares: Number($('#jlEntryShares').value),
        accountAtEntry: $('#jlAccount').value ? Number($('#jlAccount').value) : null,
        riskPercent: $('#jlRiskPct').value ? Number($('#jlRiskPct').value) / 100 : null,
        tags: $('#jlTags').value.trim() || null,
        setupNotes: $('#jlSetupNotes').value.trim() || null
      };
      $('#jlFormError').textContent = '';
      submit.disabled = true;
      submit.textContent = '保存中...';
      fetch(API + '/trades', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      }).then(function (r) {
        if (!r.ok) return r.text().then(function (t) { throw new Error(t || ('HTTP ' + r.status)); });
        return r.json();
      }).then(function () {
        form.reset();
        checks.forEach(function (c) { if (c) c.checked = false; });
        refreshSubmit();
        if (window.jlReload) window.jlReload();
      }).catch(function (err) {
        $('#jlFormError').textContent = '保存失败: ' + (err.message || err);
        submit.disabled = false;
        submit.textContent = '保存交易';
      });
    });
  });
})();
