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

/* =============================================================
 * 续 — 列表 / 平仓 / 统计 / 图表
 * ============================================================= */
(function () {
  'use strict';

  var $ = function (s) { return document.querySelector(s); };
  var $$ = function (s) { return Array.prototype.slice.call(document.querySelectorAll(s)); };
  var API = '/gp/api/journal';

  var currentTab = 'open';
  var currentMode = '';
  var equityChart, distChart;

  document.addEventListener('DOMContentLoaded', function () {
    // Tab switching
    $$('.jl-tabs button[data-tab]').forEach(function (b) {
      b.addEventListener('click', function () {
        $$('.jl-tabs button[data-tab]').forEach(function (x) { x.classList.remove('jl-tab-active'); });
        b.classList.add('jl-tab-active');
        currentTab = b.dataset.tab;
        loadList();
      });
    });

    // Mode filter
    var modeSel = $('#jlStatsMode');
    if (modeSel) modeSel.addEventListener('change', function () {
      currentMode = modeSel.value;
      loadStats();
    });

    // Sync from pool
    var syncBtn = $('#jlSyncBtn');
    if (syncBtn) syncBtn.addEventListener('click', syncFromPool);

    loadList();
    loadStats();
    setInterval(refreshOpenFloating, 30000);
    window.jlReload = function () { loadList(); loadStats(); };
  });

  function loadList() {
    var url = API + '/trades?size=50';
    if (currentTab === 'open') url += '&isOpen=true';
    if (currentTab === 'closed') url += '&isOpen=false';
    fetch(url, { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : { content: [] }; })
      .then(function (page) { renderList(page.content || []); })
      .catch(function () { renderList([]); });
  }

  function authHeaders() {
    var t = localStorage.getItem('token');
    return t ? { 'Authorization': 'Bearer ' + t } : {};
  }

  function fmtMoney(v) {
    if (v == null) return '-';
    return 'CNY ' + Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  function fmtR(v) {
    if (v == null) return '-';
    var n = Number(v);
    var cls = n >= 0 ? 'jl-r-pos' : 'jl-r-neg';
    return '<span class="' + cls + '">' + (n >= 0 ? '+' : '') + n.toFixed(2) + 'R</span>';
  }
  function esc(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
        .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
  }

  function renderList(items) {
    var box = $('#jlTradeList');
    if (!box) return;
    if (!items.length) {
      box.innerHTML = '<div class="jl-trade-card">没有交易记录</div>';
      return;
    }
    box.innerHTML = items.map(function (t) {
      var modeBadge = t.mode === 'REAL'
        ? '<span class="jl-mode-real">REAL</span>'
        : '<span class="jl-mode-paper">PAPER</span>';
      var stateBadge = t.isOpen ? '持仓中' : '已平仓';
      var pnl = t.isOpen ? '浮盈 ' + fmtR(t.rMultiple) : '实盈 ' + fmtR(t.rMultiple);
      var closeBtn = t.isOpen
        ? '<button class="jl-btn-ghost" onclick="window.__jlClose(' + t.id + ')">平仓</button>'
        : '';
      return '<div class="jl-trade-card">' +
        '<div><strong>' + esc(t.stockCode) + '</strong> ' + esc(t.stockName || '') + ' ' + modeBadge + ' ' + stateBadge + '</div>' +
        '<div class="jl-meta">入 ' + t.entryPrice + ' / 损 ' + t.stopPrice +
        (t.targetPrice ? ' / 目标 ' + t.targetPrice : '') + ' · ' +
        t.entryShares + ' 股</div>' +
        '<div class="jl-meta">入场 ' + (t.entryDate || '').substring(0,10) +
        (t.exitDate ? ' → 平仓 ' + t.exitDate.substring(0,10) : '') + '</div>' +
        '<div style="margin-top:6px;">' + pnl + ' · 标签: ' + esc(t.tags || '-') + ' ' + closeBtn + '</div>' +
        '</div>';
    }).join('');
  }

  window.__jlClose = function (id) {
    var p = prompt('输入实际平仓价:');
    if (!p) return;
    var reason = prompt('平仓原因 (manual / stopped_out / target_hit / time_stop):', 'manual');
    var notes  = prompt('复盘笔记:') || '';
    fetch(API + '/trades/' + id, {
      method: 'PUT',
      headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
      body: JSON.stringify({ exitPrice: Number(p), exitReason: reason, reviewNotes: notes })
    }).then(function (r) {
      if (!r.ok) return r.text().then(function (t) { throw new Error(t); });
      window.jlReload();
    }).catch(function (e) { alert('平仓失败: ' + e.message); });
  };

  function loadStats() {
    var url = API + '/stats';
    if (currentMode) url += '?mode=' + encodeURIComponent(currentMode);
    fetch(url, { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(renderStats);
    loadEquity();
    loadDistribution();
  }

  function renderStats(s) {
    if (!s) return;
    $('#jlWinRate').textContent = s.totalTrades === 0 ? '-' :
      (s.winRate * 100).toFixed(1) + '% (' + s.wins + '/' + s.totalTrades + ')';
    $('#jlAvgR').textContent = s.averageR != null ? Number(s.averageR).toFixed(2) + 'R' : '-';
    $('#jlEV').textContent = s.expectedValue != null ? Number(s.expectedValue).toFixed(2) + 'R' : '-';
    $('#jlMaxDD').textContent = s.maxDrawdown != null ? Number(s.maxDrawdown).toFixed(2) + 'R' : '-';
  }

  function loadEquity() {
    var url = API + '/equity-curve';
    if (currentMode) url += '?mode=' + encodeURIComponent(currentMode);
    fetch(url, { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (pts) {
        var ctx = $('#jlEquityCanvas').getContext('2d');
        if (equityChart) equityChart.destroy();
        equityChart = new Chart(ctx, {
          type: 'line',
          data: {
            labels: pts.map(function (p) { return p.tradeIndex; }),
            datasets: [{
              label: '累计 R', data: pts.map(function (p) { return Number(p.cumulativeR); }),
              borderColor: '#1e88ff', backgroundColor: 'rgba(30,136,255,0.1)',
              fill: true, tension: 0.2
            }]
          },
          options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } }
        });
      });
  }

  function loadDistribution() {
    var url = API + '/r-distribution';
    if (currentMode) url += '?mode=' + encodeURIComponent(currentMode);
    fetch(url, { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (b) {
        var ctx = $('#jlDistCanvas').getContext('2d');
        if (distChart) distChart.destroy();
        distChart = new Chart(ctx, {
          type: 'bar',
          data: {
            labels: b.map(function (x) { return x.label; }),
            datasets: [{ label: '数量', data: b.map(function (x) { return x.count; }),
              backgroundColor: '#1e88ff' }]
          },
          options: { plugins: { legend: { display: false } } }
        });
      });
  }

  function refreshOpenFloating() {
    if (currentTab !== 'open') return;
    loadList();
  }

  function syncFromPool() {
    fetch(API + '/pending-fills', { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) {
        if (!list.length) { alert('没有待同步的清仓记录'); return; }
        var msg = list.map(function (f) {
          return f.fillId + ': ' + f.stockCode + ' @ ' + f.price + ' (' + f.lots + '手)';
        }).join('\n');
        var pick = prompt('待同步清仓记录:\n' + msg + '\n\n输入要同步的 fillId:');
        if (!pick) return;
        fetch(API + '/sync-from-fill/' + pick, {
          method: 'POST', headers: authHeaders()
        }).then(function (r) {
          if (!r.ok) return r.text().then(function (t) { throw new Error(t); });
          window.jlReload();
        }).catch(function (e) { alert('同步失败: ' + e.message); });
      });
  }
})();
