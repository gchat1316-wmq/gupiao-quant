(function () {
  'use strict';

  const STORAGE_KEY = 'quant.selectedMetrics';

  /* ===== Metric config ===== */
  const METRIC_CONFIG = [
    { key: 'revenueYoy',           label: '营收同比',    unit: '%',  formatter: pctFmt,  group: '增长',   color: true,  defaultOn: true,  primary: true  },
    { key: 'deductedNetProfitYoy', label: '扣非同比',    unit: '%',  formatter: pctFmt,  group: '增长',   color: true,  defaultOn: true,  primary: true  },
    { key: 'grossMargin',          label: '毛利率',      unit: '%',  formatter: pctFmt,  group: '盈利',   color: false, defaultOn: true,  primary: true  },
    { key: 'netMargin',            label: '净利率',      unit: '%',  formatter: pctFmt,  group: '盈利',   color: false, defaultOn: false, primary: true  },
    { key: 'roe',                  label: 'ROE',         unit: '%',  formatter: pctFmt,  group: '盈利',   color: true,  defaultOn: false, primary: true  },
    { key: 'roa',                  label: 'ROA',         unit: '%',  formatter: pctFmt,  group: '盈利',   color: true,  defaultOn: false, primary: false },
    { key: 'eps',                  label: '每股收益',    unit: '元', formatter: numFmt2, group: '盈利',   color: true,  defaultOn: false, primary: false },
    { key: 'revenue',              label: '营业收入',    unit: '亿', formatter: yiFmt,   group: '规模',   color: false, defaultOn: false, primary: true  },
    { key: 'netProfit',            label: '净利润',      unit: '亿', formatter: yiFmt,   group: '规模',   color: true,  defaultOn: false, primary: true  },
    { key: 'deductedNetProfitTtm', label: '扣非TTM',     unit: '亿', formatter: yiFmt,   group: '规模',   color: false, defaultOn: false, primary: false },
    { key: 'totalAssets',          label: '总资产',      unit: '亿', formatter: yiFmt,   group: '规模',   color: false, defaultOn: false, primary: false },
    { key: 'totalEquity',          label: '净资产',      unit: '亿', formatter: yiFmt,   group: '规模',   color: false, defaultOn: false, primary: false },
    { key: 'operatingCashflow',    label: '经营现金流',  unit: '亿', formatter: yiFmt,   group: '现金流', color: true,  defaultOn: false, primary: true  },
    { key: 'debtRatio',            label: '资产负债率',  unit: '%',  formatter: pctFmt,  group: '风险',   color: false, defaultOn: false, primary: false },
    { key: 'currentRatio',         label: '流动比率',    unit: '',   formatter: numFmt2, group: '风险',   color: false, defaultOn: false, primary: false },
  ];

  const METRIC_MAP = {};
  METRIC_CONFIG.forEach(function (m) { METRIC_MAP[m.key] = m; });
  const DEFAULT_KEYS = METRIC_CONFIG.filter(function (m) { return m.defaultOn; }).map(function (m) { return m.key; });
  const CHART_COLORS = ['#4c6ef5', '#82c91e', '#fab005', '#fa5252', '#15aabf', '#be4bdb'];

  /* ===== Formatters ===== */
  function pctFmt(v) {
    if (v == null) return '--';
    const n = Number(v);
    return isFinite(n) ? n.toFixed(2) + '%' : '--';
  }

  function yiFmt(v) {
    if (v == null) return '--';
    const n = Number(v);
    return isFinite(n) ? (n / 1e8).toFixed(2) + '亿' : '--';
  }

  function numFmt2(v) {
    if (v == null) return '--';
    const n = Number(v);
    return isFinite(n) ? n.toFixed(2) : '--';
  }

  function colorClass(cfg, v) {
    if (!cfg.color || v == null) return '';
    const n = Number(v);
    if (!isFinite(n)) return '';
    return n > 0 ? 'pos' : n < 0 ? 'neg' : '';
  }

  /* ===== DOM refs ===== */
  const els = {
    searchInput:      document.getElementById('searchInput'),
    clearBtn:         document.getElementById('clearBtn'),
    queryBtn:         document.getElementById('queryBtn'),
    resultSection:    document.getElementById('resultSection'),
    emptyHint:        document.getElementById('emptyHint'),
    subtitle:         document.getElementById('subtitle'),
    tablesWrap:       document.getElementById('tablesWrap'),
    chartTitle:       document.getElementById('chartTitle'),
    chartTabs:        document.getElementById('chartTabs'),
    downloadBtn:      document.getElementById('downloadBtn'),
    canvas:           document.getElementById('metricChart'),
    metricChipsMain:  document.getElementById('metricChipsMain'),
    metricMoreBtn:    document.getElementById('metricMoreBtn'),
    metricMorePopover:document.getElementById('metricMorePopover'),
    metricCount:      document.getElementById('metricCount'),
    metricDefaultBtn: document.getElementById('metricDefaultBtn'),
    metricAllBtn:     document.getElementById('metricAllBtn'),
    metricClearBtn:   document.getElementById('metricClearBtn'),
  };

  /* ===== State ===== */
  let currentData = null;
  let selectedKeys = (function () {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
      if (Array.isArray(saved) && saved.length > 0) return new Set(saved);
    } catch (e) { /* ignore */ }
    return new Set(DEFAULT_KEYS);
  }());
  let currentChartKey = Array.from(selectedKeys)[0] || DEFAULT_KEYS[0];
  let chart = null;
  let popoverOpen = false;

  /* ===== Metric chip bar ===== */
  function persistSelection() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(selectedKeys))); } catch (e) { /* ignore */ }
  }

  function renderChip(m) {
    const on = selectedKeys.has(m.key);
    const chip = document.createElement('button');
    chip.className = 'metric-chip' + (on ? ' on' : '');
    chip.dataset.key = m.key;
    chip.textContent = m.label;
    chip.addEventListener('click', function () {
      if (selectedKeys.has(m.key)) selectedKeys.delete(m.key);
      else selectedKeys.add(m.key);
      persistSelection();
      renderMetricBar();
      onSelectionChange();
    });
    return chip;
  }

  function renderMetricBar() {
    // primary chips
    els.metricChipsMain.innerHTML = '';
    METRIC_CONFIG.filter(function (m) { return m.primary; }).forEach(function (m) {
      els.metricChipsMain.appendChild(renderChip(m));
    });

    // count
    els.metricCount.textContent = '已选 ' + selectedKeys.size + ' 项';

    // more btn arrow
    const caret = els.metricMoreBtn.querySelector('.caret');
    if (caret) caret.style.transform = popoverOpen ? 'rotate(180deg)' : '';

    // popover chips (non-primary)
    if (popoverOpen) {
      const nonPrimary = METRIC_CONFIG.filter(function (m) { return !m.primary; });
      const groupOrder = [];
      nonPrimary.forEach(function (m) { if (!groupOrder.includes(m.group)) groupOrder.push(m.group); });
      els.metricMorePopover.innerHTML = '';
      groupOrder.forEach(function (g) {
        const grpDiv = document.createElement('div');
        grpDiv.className = 'metric-pop-group';
        const label = document.createElement('span');
        label.className = 'metric-pop-group-label';
        label.textContent = g;
        grpDiv.appendChild(label);
        nonPrimary.filter(function (m) { return m.group === g; }).forEach(function (m) {
          grpDiv.appendChild(renderChip(m));
        });
        els.metricMorePopover.appendChild(grpDiv);
      });
    }
  }

  function togglePopover() {
    popoverOpen = !popoverOpen;
    els.metricMorePopover.classList.toggle('hidden', !popoverOpen);
    renderMetricBar();
  }

  function closePopover() {
    if (!popoverOpen) return;
    popoverOpen = false;
    els.metricMorePopover.classList.add('hidden');
    const caret = els.metricMoreBtn.querySelector('.caret');
    if (caret) caret.style.transform = '';
  }

  function onSelectionChange() {
    if (!selectedKeys.has(currentChartKey)) {
      currentChartKey = (Array.from(selectedKeys)[0]) || DEFAULT_KEYS[0];
    }
    if (currentData && currentData.stocks) {
      renderTables(currentData.stocks);
      updateChartTabs();
      renderChart(currentData.stocks, currentChartKey);
    }
  }

  /* ===== Chart tabs ===== */
  function updateChartTabs() {
    els.chartTabs.innerHTML = '';
    const keys = METRIC_CONFIG
      .filter(function (m) { return selectedKeys.has(m.key); })
      .map(function (m) { return m.key; });
    if (!keys.length) keys.push(DEFAULT_KEYS[0]);

    keys.forEach(function (key) {
      const btn = document.createElement('button');
      btn.className = 'tab' + (key === currentChartKey ? ' active' : '');
      btn.dataset.key = key;
      btn.textContent = METRIC_MAP[key].label;
      btn.addEventListener('click', function () {
        currentChartKey = key;
        els.chartTabs.querySelectorAll('.tab').forEach(function (b) {
          b.classList.toggle('active', b.dataset.key === key);
        });
        if (currentData && currentData.stocks) renderChart(currentData.stocks, key);
      });
      els.chartTabs.appendChild(btn);
    });
  }

  /* ===== Data fetch ===== */
  async function fetchData(keywords) {
    const resp = await fetch('/api/stock/financial?keywords=' + encodeURIComponent(keywords));
    if (!resp.ok) throw new Error('请求失败: ' + resp.status);
    return resp.json();
  }

  /* ===== Render ===== */
  function render(data) {
    currentData = data;
    if (!data || !data.stocks || !data.stocks.length) {
      els.resultSection.classList.add('hidden');
      els.emptyHint.classList.remove('hidden');
      els.emptyHint.innerHTML = '<p>未查询到相关股票数据。请确认输入的股票名称或代码，并确保数据库中存在对应记录。</p>';
      return;
    }
    els.emptyHint.classList.add('hidden');
    els.resultSection.classList.remove('hidden');
    els.subtitle.innerHTML =
      '输入<span class="hl">' + data.requested + '</span>只股票，已查询到<span class="hl">' +
      data.matched + '</span>只股票' +
      (data.notFound && data.notFound.length ? '（未找到: ' + data.notFound.join('、') + '）' : '');

    if (!selectedKeys.has(currentChartKey)) currentChartKey = DEFAULT_KEYS[0];
    renderTables(data.stocks);
    updateChartTabs();
    renderChart(data.stocks, currentChartKey);
  }

  function renderTables(stocks) {
    els.tablesWrap.innerHTML = '';
    const keys = METRIC_CONFIG.filter(function (m) { return selectedKeys.has(m.key); }).map(function (m) { return m.key; });
    if (!keys.length) {
      els.tablesWrap.innerHTML = '<p style="text-align:center;color:var(--text-muted);padding:16px">请至少选择一个指标</p>';
      return;
    }
    stocks.forEach(function (s) {
      const headerCols = s.quarters.map(function (q) { return '<th>' + esc(q.quarter) + '</th>'; }).join('');
      const rows = keys.map(function (key) { return rowHtml(METRIC_MAP[key].label, s.quarters, key); }).join('');
      els.tablesWrap.insertAdjacentHTML('beforeend',
        '<div class="table-scroll">' +
          '<table class="stock-table">' +
            '<thead><tr>' +
              '<th class="first-col">' + esc(s.stockName) + '<br/>(' + esc(s.stockCode) + ')</th>' +
              headerCols +
            '</tr></thead>' +
            '<tbody>' + rows + '</tbody>' +
          '</table>' +
        '</div>');
    });
  }

  function rowHtml(label, quarters, key) {
    const cfg = METRIC_MAP[key];
    const cells = quarters.map(function (q) {
      const v = q[key];
      return '<td class="' + colorClass(cfg, v) + '">' + esc(cfg.formatter(v)) + '</td>';
    }).join('');
    return '<tr><td class="metric-name">' + esc(label) + '</td>' + cells + '</tr>';
  }

  function buildUnifiedAxis(stocks) {
    const map = new Map();
    stocks.forEach(function (s) {
      s.quarters.forEach(function (q) { map.set(q.reportDate, q.quarter); });
    });
    return Array.from(map.keys()).sort().map(function (d) { return { date: d, quarter: map.get(d) }; });
  }

  function renderChart(stocks, metricKey) {
    const cfg = METRIC_MAP[metricKey] || METRIC_MAP[DEFAULT_KEYS[0]];
    els.chartTitle.textContent = cfg.label + '  多股对比图';
    const axis = buildUnifiedAxis(stocks);
    const labels = axis.map(function (a) { return a.quarter; });
    const isYi = cfg.unit === '亿';

    const datasets = stocks.map(function (s, idx) {
      const byDate = {};
      s.quarters.forEach(function (q) { byDate[q.reportDate] = q[metricKey]; });
      const data = axis.map(function (a) {
        const v = byDate[a.date];
        if (v == null) return null;
        return isYi ? Number(v) / 1e8 : Number(v);
      });
      const color = CHART_COLORS[idx % CHART_COLORS.length];
      return { label: s.stockName, data, borderColor: color, backgroundColor: color + '33',
               tension: 0.3, spanGaps: true, pointRadius: 3, pointHoverRadius: 5, borderWidth: 2 };
    });

    if (chart) chart.destroy();
    chart = new Chart(els.canvas.getContext('2d'), {
      type: 'line',
      data: { labels, datasets },
      options: {
        responsive: true, maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { position: 'top', labels: { usePointStyle: true } },
          tooltip: {
            callbacks: {
              label: function (ctx) {
                const v = ctx.parsed.y;
                if (v == null) return ctx.dataset.label + ': --';
                return ctx.dataset.label + ': ' + v.toFixed(2) + (cfg.unit || '');
              }
            }
          }
        },
        scales: {
          y: { ticks: { callback: function (val) { return val + (cfg.unit || ''); } }, grid: { color: '#eef1f5' } },
          x: { grid: { display: false } }
        }
      }
    });
  }

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /* ===== Events ===== */
  async function onQuery() {
    const keywords = els.searchInput.value.trim();
    if (!keywords) { els.searchInput.focus(); return; }
    els.queryBtn.disabled = true;
    const orig = els.queryBtn.textContent;
    els.queryBtn.textContent = '查询中...';
    try {
      render(await fetchData(keywords));
    } catch (e) {
      alert(e.message || '查询失败');
    } finally {
      els.queryBtn.disabled = false;
      els.queryBtn.textContent = orig;
    }
  }

  els.queryBtn.addEventListener('click', onQuery);
  els.searchInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') onQuery(); });
  els.clearBtn.addEventListener('click', function () { els.searchInput.value = ''; els.searchInput.focus(); });
  els.downloadBtn.addEventListener('click', function () {
    if (!chart) return;
    const a = document.createElement('a');
    a.href = chart.toBase64Image('image/png', 1);
    a.download = METRIC_MAP[currentChartKey].label + '_对比图.png';
    document.body.appendChild(a); a.click(); a.remove();
  });

  els.metricMoreBtn.addEventListener('click', function (e) {
    e.stopPropagation();
    togglePopover();
  });

  document.addEventListener('click', function (e) {
    if (!els.metricMorePopover.contains(e.target) && e.target !== els.metricMoreBtn) closePopover();
  });

  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closePopover(); });

  els.metricDefaultBtn.addEventListener('click', function () {
    selectedKeys = new Set(DEFAULT_KEYS);
    currentChartKey = DEFAULT_KEYS[0];
    persistSelection();
    renderMetricBar();
    onSelectionChange();
  });

  els.metricAllBtn.addEventListener('click', function () {
    METRIC_CONFIG.forEach(function (m) { selectedKeys.add(m.key); });
    persistSelection();
    renderMetricBar();
    onSelectionChange();
  });

  els.metricClearBtn.addEventListener('click', function () {
    selectedKeys = new Set();
    persistSelection();
    renderMetricBar();
    onSelectionChange();
  });

  window.addEventListener('DOMContentLoaded', function () {
    renderMetricBar();
    onQuery();
  });
})();
