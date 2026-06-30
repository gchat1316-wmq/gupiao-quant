/* =============================================================
 * position-management.js
 * 仓位计算器交互:
 *   1) 监听表单输入,实时计算仓位建议
 *   2) 渲染三段公式结果 + 传奇交易员卡片 + 回撤表
 *   3) 支持预设档位点击自动填充,支持 URL ?stock=600519 预填
 * ============================================================= */
(function () {
  'use strict';

  var API_BASE = '/gp/api/position-management';
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  var form, submitBtn, resetBtn, errorEl;
  var legendGrid, presetRow, drawdownTbody;
  var sizingResult, rrResult, evResult, verdictBox, summaryBox, principlesList;

  function ready(fn) {
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
  }

  ready(function () {
    form = $('#pmForm');
    submitBtn = $('#pmSubmit');
    resetBtn = $('#pmReset');
    errorEl = $('#pmError');
    legendGrid = $('#pmLegendGrid');
    presetRow = $('#pmPresetRow');
    drawdownTbody = $('#pmDrawdownTbody');
    sizingResult = $('#pmSizingResult');
    rrResult = $('#pmRRResult');
    evResult = $('#pmEvResult');
    verdictBox = $('#pmVerdict');
    summaryBox = $('#pmSummary');
    principlesList = $('#pmPrinciples');

    if (form) {
      form.addEventListener('submit', function (e) {
        e.preventDefault();
        calculate();
      });
    }
    if (resetBtn) resetBtn.addEventListener('click', resetForm);

    $$('#pmForm input, #pmForm select').forEach(function (el) {
      el.addEventListener('input', onInputChanged);
    });

    loadLegends();
    loadPresets();

    var params = new URLSearchParams(window.location.search);
    var stock = params.get('stock');
    if (stock) {
      var input = $('#pmStockKeyword');
      if (input) input.value = stock;
      fetchCurrentPrice(stock).then(function (price) {
        if (price != null) {
          var entry = $('#pmEntryPrice');
          if (entry) entry.value = price.toFixed(2);
        }
      });
    }
  });

  function onInputChanged() {
    if (!verdictBox || verdictBox.dataset.calculated !== 'true') return;
    clearTimeout(window.__pmDebounce);
    window.__pmDebounce = setTimeout(calculate, 280);
  }

  function resetForm() {
    if (form) form.reset();
    clearError();
    sizingResult.innerHTML = '';
    rrResult.innerHTML = '';
    evResult.innerHTML = '';
    verdictBox.className = 'pm-verdict';
    verdictBox.textContent = '请填写左侧表单,系统会基于三大数学公式给出仓位建议。';
    verdictBox.dataset.calculated = 'false';
    if (summaryBox) summaryBox.textContent = '';
    if (principlesList) principlesList.innerHTML = '';
  }

  function setError(msg) { if (errorEl) errorEl.textContent = msg; }
  function clearError() { if (errorEl) errorEl.textContent = ''; }

  function readForm() {
    return {
      stockKeyword: ($('#pmStockKeyword') && $('#pmStockKeyword').value) || '',
      accountCapital: parseNumber($('#pmAccountCapital').value),
      entryPrice: parseNumber($('#pmEntryPrice').value),
      stopLossPrice: parseNumber($('#pmStopLossPrice').value),
      targetPrice: parseNumber($('#pmTargetPrice').value),
      riskPercent: parseNumber($('#pmRiskPercent').value),
      winRate: parseNumber($('#pmWinRate').value)
    };
  }

  function parseNumber(s) {
    if (s === null || s === undefined) return null;
    var t = String(s).trim();
    if (!t) return null;
    var n = Number(t);
    return isNaN(n) ? null : n;
  }

  function calculate() {
    clearError();
    var payload = readForm();
    if (!payload.accountCapital || !payload.entryPrice || !payload.stopLossPrice || !payload.targetPrice) {
      setError('账户资金、入场价、止损价、目标价都是必填项。');
      return;
    }
    if (payload.entryPrice <= payload.stopLossPrice) {
      setError('入场价必须大于止损价,否则仓位公式会算出负数或无穷大。');
      return;
    }
    if (payload.targetPrice <= payload.entryPrice) {
      setError('目标价必须大于入场价,否则风险回报比会倒挂。');
      return;
    }

    submitBtn.disabled = true;
    submitBtn.textContent = '计算中...';

    fetch(API_BASE + '/advise', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
      .then(function (resp) {
        if (!resp.ok) return resp.text().then(function (t) { throw new Error(t || ('HTTP ' + resp.status)); });
        return resp.json();
      })
      .then(renderResult)
      .catch(function (err) {
        console.error(err);
        setError('请求失败:' + (err.message || err));
      })
      .finally(function () {
        submitBtn.disabled = false;
        submitBtn.textContent = '计算仓位建议';
      });
  }

  function renderResult(data) {
    if (!data) return;
    verdictBox.dataset.calculated = 'true';

    sizingResult.innerHTML = '';
    sizingResult.appendChild(buildCell('每股风险', fmtMoney(data.sizing.riskPerShare), '入场价 - 止损价'));
    sizingResult.appendChild(buildCell('最大可承受亏损', fmtMoney(data.sizing.maxRiskAmount), '账户 x 风险比例'));
    sizingResult.appendChild(buildCell('建议买入', fmtShares(data.sizing.lotsOf100) + ' 手', '即 ' + fmtShares(data.sizing.shares) + ' 股(A股最小 100 股)'));
    sizingResult.appendChild(buildCell('占用资金', fmtMoney(data.sizing.positionAmount), fmtPct(data.sizing.positionPct) + ' 仓位'));

    rrResult.innerHTML = '';
    rrResult.appendChild(buildCell('风报比', data.riskReward.ratioLabel, '入场 -> 止损 vs 入场 -> 目标'));
    rrResult.appendChild(buildCell('盈亏平衡胜率', fmtPct(data.riskReward.breakEvenWinRate), '1 / (1 + R) 最低要求'));
    rrResult.appendChild(buildCell('每股潜在盈利', fmtMoney(data.riskReward.rewardPerShare), '目标 - 入场'));
    rrResult.appendChild(buildCell('评级', verdictLabel(data.riskReward.verdict), data.riskReward.verdictReason));

    evResult.innerHTML = '';
    evResult.appendChild(buildCell('你的估计胜率', fmtPct(data.expectation.winRate), '0% - 100%'));
    evResult.appendChild(buildCell('单笔期望收益', fmtMoney(data.expectation.expectedValuePerTrade), '胜率 x 盈利 - 败率 x 亏损'));
    evResult.appendChild(buildCell('100 笔累计预期', fmtMoney(data.expectation.expectedValue100Trades), '复利前的粗算'));
    var evLabel = data.expectation.verdict === 'positive' ? '正期望'
      : data.expectation.verdict === 'negative' ? '负期望' : '盈亏平衡';
    evResult.appendChild(buildCell('期望值评级', evLabel, ''));

    verdictBox.textContent = data.verdict || '';
    var cls = 'pm-verdict';
    if (data.riskReward && data.riskReward.verdict === 'excellent' && data.expectation && data.expectation.verdict === 'positive') {
      cls += ' good';
    } else if (data.expectation && data.expectation.verdict === 'negative') {
      cls += ' bad';
    } else if (data.riskReward && (data.riskReward.verdict === 'poor' || data.riskReward.verdict === 'marginal')) {
      cls += ' warn';
    }
    verdictBox.className = cls;
    if (summaryBox) summaryBox.textContent = data.summary || '';
    if (principlesList) {
      principlesList.innerHTML = '';
      (data.principles || []).forEach(function (p) {
        var li = document.createElement('li');
        li.textContent = p;
        principlesList.appendChild(li);
      });
    }

    drawdownTbody.innerHTML = '';
    (data.drawdownTable || []).forEach(function (row) {
      var tr = document.createElement('tr');
      tr.innerHTML =
        '<td>连续 ' + row.consecutiveLosses + ' 次亏损</td>' +
        '<td>' + fmtPct(row.remainingPct) + '</td>' +
        '<td>+' + fmtPct(row.recoverGainRequired) + '</td>';
      drawdownTbody.appendChild(tr);
    });
  }

  function buildCell(label, value, hint) {
    var div = document.createElement('div');
    div.className = 'pm-result-cell';
    div.innerHTML = '<div class="label"></div><div class="value"></div><div class="hint"></div>';
    div.querySelector('.label').textContent = label;
    div.querySelector('.value').textContent = value;
    div.querySelector('.hint').textContent = hint || '';
    return div;
  }

  function verdictLabel(v) {
    if (v === 'excellent') return '优秀 1:3+';
    if (v === 'good') return '良好 1:2+';
    if (v === 'marginal') return '边缘 1:1+';
    if (v === 'poor') return '倒挂 <1:1';
    return v || '-';
  }

  function loadLegends() {
    fetch(API_BASE + '/legends')
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(renderLegends)
      .catch(function () {
        if (legendGrid) legendGrid.innerHTML = '<div class="pm-empty">传奇数据加载失败,可稍后刷新重试。</div>';
      });
  }

  function renderLegends(list) {
    if (!legendGrid) return;
    if (!list || !list.length) {
      legendGrid.innerHTML = '<div class="pm-empty">暂无数据</div>';
      return;
    }
    legendGrid.innerHTML = '';
    list.forEach(function (lg) {
      var card = document.createElement('div');
      card.className = 'pm-legend-card';
      card.innerHTML =
        '<div class="pm-legend-tag">' + esc(lg.nickname) + '</div>' +
        '<div class="pm-legend-name">' + esc(lg.name) + '</div>' +
        '<div class="pm-legend-amount">' + esc(lg.startingCapital) + ' -> ' + esc(lg.finalCapital) + '</div>' +
        '<div class="pm-legend-period">' + esc(lg.period) + ' · ' + esc(lg.achievement) + '</div>' +
        (lg.coreQuote ? '<div class="pm-legend-quote">' + esc(lg.coreQuote) + '</div>' : '') +
        (lg.principle ? '<div class="pm-legend-principle">' + esc(lg.principle) + '</div>' : '');
      legendGrid.appendChild(card);
    });
  }

  function loadPresets() {
    fetch(API_BASE + '/presets')
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(renderPresets)
      .catch(function () {
        if (presetRow) presetRow.innerHTML = '<div class="pm-empty">预设档位加载失败</div>';
      });
  }

  function renderPresets(list) {
    if (!presetRow || !list || !list.length) return;
    presetRow.innerHTML = '';
    list.forEach(function (p) {
      var chip = document.createElement('div');
      chip.className = 'pm-preset-chip';
      chip.innerHTML =
        '<strong>1 : ' + esc(stripZeros(p.ratioValue)) + '</strong>' +
        '<span>平衡胜率 ' + fmtPct(p.breakEvenWinRate) + '</span>' +
        '<span>' + esc(p.label) + ' · ' + esc(p.note) + '</span>';
      chip.addEventListener('click', function () { applyPreset(p); });
      presetRow.appendChild(chip);
    });
  }

  function applyPreset(p) {
    var entry = parseNumber($('#pmEntryPrice').value) || 10;
    var ratio = Number(stripZeros(p.ratioValue));
    if (!isFinite(ratio) || ratio <= 0) return;
    var stopPct = 0.05;
    var stop = round2(entry * (1 - stopPct));
    var target = round2(entry * (1 + stopPct * ratio));
    $('#pmStopLossPrice').value = stop;
    $('#pmTargetPrice').value = target;
    clearError();
    calculate();
  }

  function fetchCurrentPrice(keyword) {
    return fetch('/gp/api/xiebo-invest/quote?keyword=' + encodeURIComponent(keyword))
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d) return null;
        var p = d.price || d.currentPrice || (d.quote && d.quote.price);
        if (p == null) return null;
        var n = Number(p);
        return isNaN(n) ? null : n;
      })
      .catch(function () { return null; });
  }

  function fmtMoney(v) {
    if (v === null || v === undefined) return '-';
    var n = Number(v);
    if (isNaN(n)) return '-';
    return 'CNY ' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  function fmtShares(v) {
    if (v === null || v === undefined) return '-';
    var n = Number(v);
    if (isNaN(n)) return '-';
    return n.toLocaleString('zh-CN');
  }
  function fmtPct(v) {
    if (v === null || v === undefined) return '-';
    var n = Number(v);
    if (isNaN(n)) return '-';
    var pct = n <= 1 ? n * 100 : n;
    return pct.toFixed(2) + '%';
  }
  function stripZeros(v) {
    if (v === null || v === undefined) return '-';
    return String(v).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
  }
  function round2(n) { return Math.round(n * 100) / 100; }
  function esc(s) {
    if (s === null || s === undefined) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }
})();
