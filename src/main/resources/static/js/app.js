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
  const EXPORT_TABLE_HEAD = '#a9001f';

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
    els.metricChipsMain.innerHTML = '';
    METRIC_CONFIG.forEach(function (m) {
      els.metricChipsMain.appendChild(renderChip(m));
    });

    els.metricCount.textContent = '已选 ' + selectedKeys.size + ' 项';
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
    const resp = await fetch('api/stock/financial?keywords=' + encodeURIComponent(keywords) + '&quarters=16');
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

  /* ===== Stock info bar ===== */
  function buildInfoBar(s) {
    var info = s.basicInfo || {};
    var head = '';

    head += '<span class="info-bar-title">' + esc(s.stockName) + '&nbsp;&nbsp;' + esc(s.stockCode) + '</span>';
    head += '<span class="info-badges">';
    if (info.board) head += '<span class="info-badge info-badge-board">' + esc(info.board) + '</span>';
    if (info.industry) {
      var indLabel = info.industry + (info.extraIndustryCount > 0 ? '+' + info.extraIndustryCount : '');
      head += '<span class="info-badge info-badge-industry" title="' + esc(info.industry) + '">' + esc(indLabel) + '</span>';
    }
    if (info.valuationLevel) {
      var vClass = { '高': 'high', '中': 'mid', '低': 'low' }[info.valuationLevel] || 'mid';
      head += '<span class="info-badge info-badge-valuation-' + vClass + '">估值水平: ' + esc(info.valuationLevel) + '</span>';
    }
    head += '</span>';

    var kvs = '';
    if (info.listDate) {
      var yearsLabel = info.listYears > 0 ? ' / ' + info.listYears + '年' : '';
      kvs += infoKV('上市', info.listDate + yearsLabel);
    }
    kvs += infoKV('PE-TTM', fmtDecimal(info.peTtm));
    kvs += infoKV('PB', fmtDecimal(info.pb));
    kvs += infoKV('PS-TTM', fmtDecimal(info.psTtm));
    kvs += infoKV('当前市值', fmtYi(info.currentMarketCapYi));
    if (info.latestNetMargin != null) {
      kvs += infoKV('净利率', pctFmt(info.latestNetMargin));
    }
    if (info.tenPsCandidate != null) {
      kvs += infoKV('10PS标的', info.tenPsCandidate ? '是' : '否', info.tenPsCandidate ? 'strong' : 'muted');
    }
    if (info.tenPsCurrentToY1 != null) {
      kvs += infoKV('明年PS', fmtDecimal(info.tenPsCurrentToY1) + '倍');
    }
    if (info.tenPsFairMarketCapYi != null) {
      kvs += infoKV('合理市值', fmtYi(info.tenPsFairMarketCapYi));
    }
    if (info.tenPsValuationVerdict) {
      var verdictClass = info.tenPsValuationVerdict === '合理/低估' ? 'ok'
        : info.tenPsValuationVerdict === '不适用' ? 'muted' : 'warn';
      kvs += infoKV('估值', info.tenPsValuationVerdict, verdictClass, info.tenPsValuationDetail);
    }

    var foot = info.updatedAt
      ? '<div class="info-bar-foot">' + esc(info.updatedAt) + '</div>'
      : '';

    return '<div class="stock-info-bar">' +
      '<div class="info-bar-line"><div class="info-bar-head">' + head + '</div>' +
      '<div class="info-kv-row">' + kvs + '</div>' +
      '</div>' +
      foot +
      '</div>';
  }

  function infoKV(label, value, tone, title) {
    var cls = (value === '--') ? ' muted' : '';
    if (tone) cls += ' ' + tone;
    var titleAttr = title ? ' title="' + esc(title) + '"' : '';
    return '<div class="info-kv">' +
      '<span class="info-kv-label">' + esc(label) + '</span>' +
      '<span class="info-kv-value' + cls + '"' + titleAttr + '>' + esc(value) + '</span>' +
      '</div>';
  }

  function fmtDecimal(v) {
    if (v == null) return '--';
    var n = Number(v);
    return isFinite(n) ? n.toFixed(2) : '--';
  }

  function fmtYi(v) {
    if (v == null) return '--';
    var n = Number(v);
    return isFinite(n) ? n.toFixed(2) + '亿' : '--';
  }

  function renderTables(stocks) {
    els.tablesWrap.innerHTML = '';
    const keys = METRIC_CONFIG.filter(function (m) { return selectedKeys.has(m.key); }).map(function (m) { return m.key; });
    if (!keys.length) {
      els.tablesWrap.innerHTML = '<p style="text-align:center;color:var(--text-muted);padding:16px">请至少选择一个指标</p>';
      return;
    }
    const showInfoBars = stocks.length === 1;
    stocks.forEach(function (s) {
      const headerCols = s.quarters.map(function (q) { return '<th>' + esc(q.quarter) + '</th>'; }).join('');
      const rows = keys.map(function (key) { return rowHtml(METRIC_MAP[key].label, s.quarters, key); }).join('');
      var hasInfo = showInfoBars && !!s.basicInfo;
      els.tablesWrap.insertAdjacentHTML('beforeend',
        (hasInfo ? buildInfoBar(s) : '') +
        '<div class="table-scroll' + (hasInfo ? ' has-info-bar' : '') + '">' +
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

  function getSelectedMetricKeys() {
    const keys = METRIC_CONFIG.filter(function (m) { return selectedKeys.has(m.key); }).map(function (m) { return m.key; });
    return keys.length ? keys : DEFAULT_KEYS.slice();
  }

  function buildExportAxisDesc(stocks) {
    const map = new Map();
    stocks.forEach(function (s) {
      (s.quarters || []).forEach(function (q) { map.set(q.reportDate, q.quarter); });
    });
    return Array.from(map.keys()).sort().reverse().map(function (d) { return { date: d, quarter: map.get(d) }; });
  }

  function downloadCsv(stocks) {
    const axis = buildExportAxisDesc(stocks);
    const keys = getSelectedMetricKeys();
    const rows = [['股票名称', '股票代码', '指标'].concat(axis.map(function (a) { return a.quarter; }))];

    stocks.forEach(function (s) {
      const byDate = {};
      (s.quarters || []).forEach(function (q) { byDate[q.reportDate] = q; });
      keys.forEach(function (key) {
        const cfg = METRIC_MAP[key];
        rows.push([s.stockName, s.stockCode, cfg.label].concat(axis.map(function (a) {
          const q = byDate[a.date];
          return q ? csvValue(cfg, q[key]) : '';
        })));
      });
    });

    const csv = '\ufeff' + rows.map(function (row) {
      return row.map(csvCell).join(',');
    }).join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    downloadBlob(blob, exportBaseName() + '_财务数据.csv');
  }

  function csvValue(cfg, value) {
    if (value == null) return '';
    const n = Number(value);
    if (!isFinite(n)) return '';
    if (cfg.unit === '亿') return (n / 1e8).toFixed(4);
    return n.toFixed(4);
  }

  function csvCell(value) {
    const s = value == null ? '' : String(value);
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }

  function downloadBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 800);
  }

  function exportBaseName() {
    const names = currentData && currentData.stocks
      ? currentData.stocks.map(function (s) { return s.stockName || s.stockCode; }).join('_')
      : '财务分析';
    return (names || '财务分析').replace(/[\\/:*?"<>|]/g, '_').slice(0, 80);
  }

  function loadImage(src) {
    return new Promise(function (resolve, reject) {
      const img = new Image();
      img.onload = function () { resolve(img); };
      img.onerror = reject;
      img.src = src;
    });
  }

  async function downloadCombinedPng(stocks) {
    if (!chart) return;
    const axis = buildExportAxisDesc(stocks);
    const keys = getSelectedMetricKeys();
    const chartImg = await loadImage(chart.toBase64Image('image/png', 1));
    const colW = 112;
    const firstColW = 178;
    const left = 40;
    const right = 40;
    const tableW = firstColW + axis.length * colW;
    const width = Math.max(1280, tableW + left + right);
    const titleH = 98;
    const chartH = 430;
    const gap = 30;
    const headH = 74;
    const rowH = 52;
    const tableGap = 30;
    const tableH = headH + keys.length * rowH;
    const height = titleH + chartH + gap + stocks.length * tableH + Math.max(0, stocks.length - 1) * tableGap + 42;
    const scale = 2;
    const canvas = document.createElement('canvas');
    canvas.width = width * scale;
    canvas.height = height * scale;
    canvas.style.width = width + 'px';
    canvas.style.height = height + 'px';
    const ctx = canvas.getContext('2d');
    ctx.scale(scale, scale);

    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, width, height);
    ctx.textAlign = 'center';
    ctx.fillStyle = '#2f3745';
    ctx.font = '700 40px "PingFang SC", "Microsoft YaHei", sans-serif';
    ctx.fillText(stocks.length > 1 ? '核心财务指标对比分析' : stocks[0].stockName + ' 财务趋势分析', width / 2, 54);
    ctx.fillStyle = '#a1a1aa';
    ctx.font = '500 22px "PingFang SC", "Microsoft YaHei", sans-serif';
    ctx.fillText('www.gupiaochaxun.top', width / 2, 88);

    drawContainImage(ctx, chartImg, left, titleH, width - left - right, chartH);

    let y = titleH + chartH + gap;
    stocks.forEach(function (s) {
      drawExportTable(ctx, s, axis, keys, left, y, firstColW, colW, headH, rowH);
      y += tableH + tableGap;
    });

    await new Promise(function (resolve, reject) {
      canvas.toBlob(function (blob) {
        if (!blob) {
          reject(new Error('图片生成失败'));
          return;
        }
        downloadBlob(blob, exportBaseName() + '_' + METRIC_MAP[currentChartKey].label + '_图表.png');
        resolve();
      }, 'image/png', 0.96);
    });
  }

  function drawContainImage(ctx, img, x, y, w, h) {
    const ratio = Math.min(w / img.width, h / img.height);
    const dw = img.width * ratio;
    const dh = img.height * ratio;
    ctx.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh);
  }

  function drawExportTable(ctx, stock, axis, keys, x, y, firstColW, colW, headH, rowH) {
    const byDate = {};
    (stock.quarters || []).forEach(function (q) { byDate[q.reportDate] = q; });

    drawCell(ctx, x, y, firstColW, headH, EXPORT_TABLE_HEAD, '#dce4ee');
    ctx.fillStyle = '#ffffff';
    ctx.font = '700 21px "PingFang SC", "Microsoft YaHei", sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(stock.stockName || '', x + firstColW / 2, y + headH / 2 - 12);
    ctx.fillText('(' + (stock.stockCode || '') + ')', x + firstColW / 2, y + headH / 2 + 14);

    axis.forEach(function (a, idx) {
      drawCell(ctx, x + firstColW + idx * colW, y, colW, headH, EXPORT_TABLE_HEAD, '#dce4ee');
      ctx.fillStyle = '#ffffff';
      ctx.font = '700 20px "PingFang SC", "Microsoft YaHei", sans-serif';
      ctx.fillText(a.quarter, x + firstColW + idx * colW + colW / 2, y + headH / 2);
    });

    keys.forEach(function (key, rowIdx) {
      const cfg = METRIC_MAP[key];
      const rowY = y + headH + rowIdx * rowH;
      drawCell(ctx, x, rowY, firstColW, rowH, '#f6f8fa', '#dce4ee');
      ctx.fillStyle = '#111827';
      ctx.font = '700 20px "PingFang SC", "Microsoft YaHei", sans-serif';
      ctx.fillText(cfg.label, x + firstColW / 2, rowY + rowH / 2);
      axis.forEach(function (a, idx) {
        const q = byDate[a.date];
        const v = q ? q[key] : null;
        drawCell(ctx, x + firstColW + idx * colW, rowY, colW, rowH, '#ffffff', '#dce4ee');
        ctx.fillStyle = exportValueColor(cfg, v);
        ctx.font = '700 19px "PingFang SC", "Microsoft YaHei", sans-serif';
        ctx.fillText(cfg.formatter(v), x + firstColW + idx * colW + colW / 2, rowY + rowH / 2);
      });
    });
  }

  function drawCell(ctx, x, y, w, h, fill, stroke) {
    ctx.fillStyle = fill;
    ctx.fillRect(x, y, w, h);
    ctx.strokeStyle = stroke;
    ctx.lineWidth = 2;
    ctx.strokeRect(x, y, w, h);
  }

  function exportValueColor(cfg, v) {
    if (!cfg.color || v == null) return '#111827';
    const n = Number(v);
    if (!isFinite(n) || n === 0) return '#111827';
    return n > 0 ? '#e11d48' : '#2f9e44';
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
  els.searchInput.addEventListener('focus', function () {
    if (els.searchInput.value === els.searchInput.getAttribute('placeholder')) {
      els.searchInput.value = '';
    }
  });
  els.downloadBtn.addEventListener('click', async function () {
    if (!chart || !currentData || !currentData.stocks || !currentData.stocks.length) return;
    els.downloadBtn.disabled = true;
    const orig = els.downloadBtn.textContent;
    els.downloadBtn.textContent = '生成中...';
    try {
      await downloadCombinedPng(currentData.stocks);
      downloadCsv(currentData.stocks);
    } catch (e) {
      alert('下载失败: ' + (e.message || e));
    } finally {
      els.downloadBtn.disabled = false;
      els.downloadBtn.textContent = orig;
    }
  });

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
  });
})();
