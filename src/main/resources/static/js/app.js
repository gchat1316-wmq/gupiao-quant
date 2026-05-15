(function () {
  'use strict';

  const METRIC_CONFIG = {
    grossMargin: { label: '毛利率', unit: '%', formatter: pctFmt },
    revenueYoy: { label: '营收同比', unit: '%', formatter: pctFmt },
    deductedNetProfitYoy: { label: '扣非同比', unit: '%', formatter: pctFmt },
    deductedNetProfitTtm: { label: '扣非TTM', unit: '亿', formatter: yiFmt }
  };

  const CHART_COLORS = ['#4c6ef5', '#82c91e', '#fab005', '#fa5252', '#15aabf', '#be4bdb'];

  const els = {
    searchInput: document.getElementById('searchInput'),
    clearBtn: document.getElementById('clearBtn'),
    queryBtn: document.getElementById('queryBtn'),
    resultSection: document.getElementById('resultSection'),
    emptyHint: document.getElementById('emptyHint'),
    subtitle: document.getElementById('subtitle'),
    tablesWrap: document.getElementById('tablesWrap'),
    chartTitle: document.getElementById('chartTitle'),
    indicatorTabs: document.getElementById('indicatorTabs'),
    downloadBtn: document.getElementById('downloadBtn'),
    canvas: document.getElementById('metricChart')
  };

  let currentData = null;
  let currentMetric = 'grossMargin';
  let chart = null;

  function pctFmt(v) {
    if (v === null || v === undefined) return '--';
    const n = Number(v);
    if (!isFinite(n)) return '--';
    return n.toFixed(2) + '%';
  }

  function yiFmt(v) {
    if (v === null || v === undefined) return '--';
    const n = Number(v);
    if (!isFinite(n)) return '--';
    return (n / 1e8).toFixed(2) + '亿';
  }

  function colorClass(v) {
    if (v === null || v === undefined) return '';
    const n = Number(v);
    if (!isFinite(n)) return '';
    if (n > 0) return 'pos';
    if (n < 0) return 'neg';
    return '';
  }

  async function fetchData(keywords) {
    const url = '/api/stock/financial?keywords=' + encodeURIComponent(keywords);
    const resp = await fetch(url);
    if (!resp.ok) {
      throw new Error('请求失败: ' + resp.status);
    }
    return resp.json();
  }

  function render(data) {
    currentData = data;
    if (!data || !data.stocks || data.stocks.length === 0) {
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
      (data.notFound && data.notFound.length
        ? '（未找到: ' + data.notFound.join('、') + '）'
        : '');

    renderTables(data.stocks);
    renderChart(data.stocks, currentMetric);
  }

  function renderTables(stocks) {
    els.tablesWrap.innerHTML = '';
    stocks.forEach(function (s) {
      const headerCols = s.quarters.map(function (q) {
        return '<th>' + escapeHtml(q.quarter) + '</th>';
      }).join('');

      const rows = [
        rowHtml('毛利率', s.quarters, 'grossMargin'),
        rowHtml('营收同比', s.quarters, 'revenueYoy'),
        rowHtml('扣非同比', s.quarters, 'deductedNetProfitYoy'),
        rowHtml('扣非TTM', s.quarters, 'deductedNetProfitTtm')
      ].join('');

      const html =
        '<div class="table-scroll">' +
          '<table class="stock-table">' +
            '<thead><tr>' +
              '<th class="first-col">' + escapeHtml(s.stockName) + '<br/>(' + escapeHtml(s.stockCode) + ')</th>' +
              headerCols +
            '</tr></thead>' +
            '<tbody>' + rows + '</tbody>' +
          '</table>' +
        '</div>';
      els.tablesWrap.insertAdjacentHTML('beforeend', html);
    });
  }

  function rowHtml(label, quarters, key) {
    const fmt = METRIC_CONFIG[key].formatter;
    const cells = quarters.map(function (q) {
      const v = q[key];
      const cls = key === 'deductedNetProfitTtm' ? '' : colorClass(v);
      return '<td class="' + cls + '">' + escapeHtml(fmt(v)) + '</td>';
    }).join('');
    return '<tr><td class="metric-name">' + escapeHtml(label) + '</td>' + cells + '</tr>';
  }

  function buildUnifiedAxis(stocks) {
    const map = new Map();
    stocks.forEach(function (s) {
      s.quarters.forEach(function (q) {
        map.set(q.reportDate, q.quarter);
      });
    });
    const sortedDates = Array.from(map.keys()).sort();
    return sortedDates.map(function (d) { return { date: d, quarter: map.get(d) }; });
  }

  function renderChart(stocks, metricKey) {
    const cfg = METRIC_CONFIG[metricKey];
    els.chartTitle.textContent = cfg.label + '多股对比图';

    const axis = buildUnifiedAxis(stocks);
    const labels = axis.map(function (a) { return a.quarter; });

    const datasets = stocks.map(function (s, idx) {
      const byDate = {};
      s.quarters.forEach(function (q) { byDate[q.reportDate] = q[metricKey]; });
      const data = axis.map(function (a) {
        const v = byDate[a.date];
        if (v === null || v === undefined) return null;
        if (metricKey === 'deductedNetProfitTtm') return Number(v) / 1e8;
        return Number(v);
      });
      const color = CHART_COLORS[idx % CHART_COLORS.length];
      return {
        label: s.stockName,
        data: data,
        borderColor: color,
        backgroundColor: color + '33',
        tension: 0.3,
        spanGaps: true,
        pointRadius: 3,
        pointHoverRadius: 5,
        borderWidth: 2
      };
    });

    if (chart) {
      chart.destroy();
    }
    chart = new Chart(els.canvas.getContext('2d'), {
      type: 'line',
      data: { labels: labels, datasets: datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { position: 'top', labels: { usePointStyle: true } },
          tooltip: {
            callbacks: {
              label: function (ctx) {
                const v = ctx.parsed.y;
                if (v === null || v === undefined) return ctx.dataset.label + ': --';
                const text = metricKey === 'deductedNetProfitTtm'
                  ? v.toFixed(2) + '亿'
                  : v.toFixed(2) + '%';
                return ctx.dataset.label + ': ' + text;
              }
            }
          }
        },
        scales: {
          y: {
            ticks: {
              callback: function (val) {
                return metricKey === 'deductedNetProfitTtm'
                  ? val + '亿'
                  : val + '%';
              }
            },
            grid: { color: '#eef1f5' }
          },
          x: {
            grid: { display: false }
          }
        }
      }
    });
  }

  function escapeHtml(s) {
    if (s === null || s === undefined) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  async function onQuery() {
    const keywords = els.searchInput.value.trim();
    if (!keywords) {
      els.searchInput.focus();
      return;
    }
    els.queryBtn.disabled = true;
    const oldText = els.queryBtn.textContent;
    els.queryBtn.textContent = '查询中...';
    try {
      const data = await fetchData(keywords);
      render(data);
    } catch (e) {
      alert(e.message || '查询失败');
    } finally {
      els.queryBtn.disabled = false;
      els.queryBtn.textContent = oldText;
    }
  }

  function onTabClick(e) {
    const btn = e.target.closest('.tab');
    if (!btn) return;
    Array.from(els.indicatorTabs.querySelectorAll('.tab')).forEach(function (b) {
      b.classList.toggle('active', b === btn);
    });
    currentMetric = btn.dataset.key;
    if (currentData && currentData.stocks) {
      renderChart(currentData.stocks, currentMetric);
    }
  }

  function onDownload() {
    if (!chart) return;
    const url = chart.toBase64Image('image/png', 1);
    const a = document.createElement('a');
    a.href = url;
    a.download = (METRIC_CONFIG[currentMetric].label) + '_对比图.png';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  els.queryBtn.addEventListener('click', onQuery);
  els.searchInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') onQuery();
  });
  els.clearBtn.addEventListener('click', function () {
    els.searchInput.value = '';
    els.searchInput.focus();
  });
  els.indicatorTabs.addEventListener('click', onTabClick);
  els.downloadBtn.addEventListener('click', onDownload);

  // 页面加载后默认执行一次查询
  window.addEventListener('DOMContentLoaded', onQuery);
})();
