/* ===== 实战选股 · 一键综合分析 ===== */
(function () {
  'use strict';

  const API = 'api/practical-select';
  let historyPage = 0;
  const historyPageSize = 20;
  let historyKeyword = '';

  function init() {
    const btn = document.getElementById('psAnalyzeBtn');
    const input = document.getElementById('psKeywordInput');
    if (!btn || !input) return;

    btn.addEventListener('click', () => analyze(input.value.trim()));
    input.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); analyze(input.value.trim()); }
    });

    document.querySelectorAll('.ps-example-link').forEach(a => {
      a.addEventListener('click', e => {
        e.preventDefault();
        const kw = a.dataset.kw;
        input.value = kw;
        analyze(kw);
      });
    });

    // 历史记录
    loadHistory();
    const search = document.getElementById('psHistorySearch');
    if (search) {
      let timer;
      search.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(() => {
          historyKeyword = search.value.trim();
          historyPage = 0;
          loadHistory();
        }, 300);
      });
    }
  }

  // ====== 步骤指示 ======
  const STEPS = [
    { id: 'ps-step-trend', label: '① 解析月线走势' },
    { id: 'ps-step-fin',   label: '② 调取 16 季度财务' },
    { id: 'ps-step-val',   label: '③ 估算 PS 估值' },
    { id: 'ps-step-ai',    label: '④ AI 评级稀缺性 + 成长动力' },
  ];

  function startLoading() {
    document.getElementById('psResultWrap').innerHTML = '';
    document.getElementById('psErrorBox').classList.add('hidden');
    const box = document.getElementById('psLoading');
    box.classList.remove('hidden');
    STEPS.forEach(s => {
      const el = document.getElementById(s.id);
      if (el) {
        el.classList.remove('done', 'active');
      }
    });
    let i = 0;
    const tick = () => {
      STEPS.forEach((s, idx) => {
        const el = document.getElementById(s.id);
        if (!el) return;
        if (idx < i) el.classList.add('done');
        else if (idx === i) el.classList.add('active');
        else el.classList.remove('done', 'active');
      });
      i++;
      if (i <= STEPS.length) {
        setTimeout(tick, 450);
      }
    };
    tick();
  }

  function stopLoading() {
    document.getElementById('psLoading').classList.add('hidden');
  }

  function showError(msg) {
    const box = document.getElementById('psErrorBox');
    box.innerHTML = `<div class="ps-error-title">分析失败</div><div class="ps-error-msg">${escHtml(msg)}</div>`;
    box.classList.remove('hidden');
  }

  async function analyze(keyword) {
    if (!keyword) {
      showError('请先输入股票代码或名称');
      return;
    }
    const btn = document.getElementById('psAnalyzeBtn');
    btn.disabled = true;
    btn.textContent = '分析中…';
    startLoading();
    try {
      const res = await fetch(`${API}/analyze?keyword=${encodeURIComponent(keyword)}`);
      const data = await res.json();
      if (!res.ok || !data.ok === false && !data.matched) {
        throw new Error(data.message || `请求失败：${res.status}`);
      }
      if (!data.matched) {
        throw new Error(data.message || '未匹配到股票');
      }
      // 跑完所有步骤动画
      await wait(STEPS.length * 450 + 100);
      stopLoading();
      renderResult(data);
    } catch (e) {
      stopLoading();
      showError(e.message);
    } finally {
      btn.disabled = false;
      btn.textContent = '立即分析';
    }
  }

  function wait(ms) { return new Promise(r => setTimeout(r, ms)); }

  // ====== 渲染 ======
  function renderResult(d) {
    const wrap = document.getElementById('psResultWrap');
    wrap.innerHTML = `
      ${renderHeadline(d)}
      ${d.recordId ? renderActionBar(d.recordId, d.stockName, d.stockCode) : ''}
      ${d.trend ? renderTrendCard(d.trend) : ''}
      ${d.financials ? renderFinancialCard(d.financials) : ''}
      ${d.rating ? renderRatingCard(d.rating) : ''}
      ${d.valuation ? renderValuationCard(d.valuation) : ''}
      ${d.dataNote ? `<div class="ps-data-note">${escHtml(d.dataNote)}</div>` : ''}
      <div class="ps-foot-note">
        ⚠️ 本分析基于自动计算 + AI 生成，<b>仅供学习研究，不构成投资建议</b>。
      </div>
    `;
    bindActionBar(wrap, d.recordId);
    wrap.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  // share.html 调用的渲染入口（不显示历史侧栏按钮）
  function renderShared(d) {
    const wrap = document.getElementById('shareResultWrap');
    if (!wrap) return;
    wrap.innerHTML = `
      ${renderHeadline(d)}
      <div class="ps-callout ps-callout-success" style="margin-bottom:14px">
        🔗 这是一份<b>只读分享</b>报告 · 由 ${escHtml(d.stockName)} (${escHtml(d.stockCode)}) 分析结果生成
      </div>
      ${d.trend ? renderTrendCard(d.trend) : ''}
      ${d.financials ? renderFinancialCard(d.financials) : ''}
      ${d.rating ? renderRatingCard(d.rating) : ''}
      ${d.valuation ? renderValuationCard(d.valuation) : ''}
      ${d.dataNote ? `<div class="ps-data-note">${escHtml(d.dataNote)}</div>` : ''}
      <div class="ps-foot-note">
        ⚠️ 本报告由 AI + 自动计算生成 · 仅供学习研究，不构成投资建议
      </div>
    `;
  }

  // 操作栏（分享 / PDF / 删除）
  function renderActionBar(recordId, stockName, stockCode) {
    return `
      <div class="ps-action-bar" data-record-id="${recordId}">
        <span class="ps-action-label">记录 #${recordId} · ${escHtml(stockName || '')} (${escHtml(stockCode || '')})</span>
        <div class="ps-action-buttons">
          <button class="ps-btn ps-btn-secondary" data-action="reanalyze" data-kw="${escHtml(stockCode || '')}">🔄 重新分析</button>
          <button class="ps-btn ps-btn-secondary" data-action="pdf">📄 下载 PDF</button>
          <button class="ps-btn ps-btn-primary" data-action="share">🔗 生成分享链接</button>
          <button class="ps-btn ps-btn-danger" data-action="delete">🗑 删除</button>
        </div>
      </div>
    `;
  }

  function bindActionBar(wrap, recordId) {
    if (!recordId) return;
    wrap.querySelectorAll('.ps-action-bar button').forEach(btn => {
      btn.addEventListener('click', async () => {
        const action = btn.dataset.action;
        if (action === 'pdf') {
          downloadPdf(recordId);
        } else if (action === 'share') {
          await enableShare(recordId);
        } else if (action === 'delete') {
          await deleteRecord(recordId);
        } else if (action === 'reanalyze') {
          const kw = btn.dataset.kw;
          const input = document.getElementById('psKeywordInput');
          if (input && kw) {
            input.value = kw;
            analyze(kw);
          }
        }
      });
    });
  }

  async function downloadPdf(id) {
    try {
      window.open(`${API}/record/${id}/pdf`, '_blank');
    } catch (e) {
      alert('PDF 下载失败：' + e.message);
    }
  }

  async function enableShare(id) {
    try {
      const res = await fetch(`${API}/record/${id}/share`, { method: 'POST' });
      const data = await res.json();
      if (!data.ok) throw new Error(data.message || '失败');
      showShareModal(data.shareUrl);
      loadHistory();
    } catch (e) {
      alert('生成分享链接失败：' + e.message);
    }
  }

  function showShareModal(url) {
    const mask = document.createElement('div');
    mask.className = 'ps-share-mask';
    mask.innerHTML = `
      <div class="ps-share-modal">
        <div class="ps-share-modal-head">
          <span>🔗 分享链接已生成</span>
          <button class="ps-share-close">×</button>
        </div>
        <div class="ps-share-modal-body">
          <div class="ps-share-tip">任何人通过这个链接可以查看这份分析报告（只读）：</div>
          <textarea class="ps-share-url" readonly rows="3">${escHtml(url)}</textarea>
          <div class="ps-share-actions">
            <button class="ps-btn ps-btn-primary" data-copy>📋 复制链接</button>
            <button class="ps-btn ps-btn-secondary" data-open>🌐 在新窗口打开</button>
          </div>
        </div>
      </div>
    `;
    document.body.appendChild(mask);
    const close = () => mask.remove();
    mask.querySelector('.ps-share-close').addEventListener('click', close);
    mask.addEventListener('click', e => { if (e.target === mask) close(); });
    mask.querySelector('[data-copy]').addEventListener('click', () => {
      const ta = mask.querySelector('.ps-share-url');
      ta.select();
      try {
        navigator.clipboard.writeText(url).then(() => {
          mask.querySelector('[data-copy]').textContent = '✓ 已复制';
          setTimeout(() => mask.querySelector('[data-copy]').textContent = '📋 复制链接', 2000);
        });
      } catch (e) {
        document.execCommand('copy');
      }
    });
    mask.querySelector('[data-open]').addEventListener('click', () => window.open(url, '_blank'));
  }

  async function deleteRecord(id) {
    if (!confirm(`确认删除记录 #${id}？（含 PDF、分享链接一并作废）`)) return;
    try {
      const res = await fetch(`${API}/record/${id}`, { method: 'DELETE' });
      const data = await res.json();
      if (!data.ok) throw new Error(data.message || '失败');
      document.getElementById('psResultWrap').innerHTML = '';
      loadHistory();
    } catch (e) {
      alert('删除失败：' + e.message);
    }
  }

  // ====== 综合标题 ======
  function renderHeadline(d) {
    return `
      <div class="ps-headline">
        <div class="ps-headline-main">
          <div class="ps-headline-name">${escHtml(d.stockName)} <span class="ps-headline-code">${escHtml(d.stockCode)}</span></div>
          ${d.currentPrice ? `<div class="ps-headline-price">现价 <b>${fmtNum(d.currentPrice, 2)}</b></div>` : ''}
        </div>
        <div class="ps-headline-tags">
          ${valuationTag(d.valuation)}
          ${ratingTag(d.rating)}
          ${trendTag(d.trend)}
        </div>
      </div>
    `;
  }

  function valuationTag(v) {
    if (!v || !v.verdict) return '';
    // 2026-07-01 改：后端 verdict 用"泡沫"（跟投资池一致），不是"高估"
    const cls = v.verdict === '低估' ? 'low' : (v.verdict === '泡沫' ? 'high' : 'fair');
    return `<span class="ps-tag ps-tag-${cls}">估值：${escHtml(v.verdict)}</span>`;
  }

  function ratingTag(r) {
    if (!r) return '';
    const sc = r.scarcityStars != null ? `稀缺 ★${r.scarcityStars.toFixed(1)}` : '';
    const gr = r.growthStars != null ? `成长 ★${r.growthStars.toFixed(1)}` : '';
    if (!sc && !gr) return '';
    const aiTag = r.aiGenerated ? '' : ' <small>(启发式)</small>';
    return `<span class="ps-tag ps-tag-rating">${sc} · ${gr}${aiTag}</span>`;
  }

  function trendTag(t) {
    if (!t) return '';
    if (t.breakoutDetected) return `<span class="ps-tag ps-tag-success">突破平台</span>`;
    if (t.monthToDateReturnPct != null && t.monthToDateReturnPct >= 20) return `<span class="ps-tag ps-tag-success">本月强势 +${t.monthToDateReturnPct.toFixed(1)}%</span>`;
    return '';
  }

  // ====== 走势卡片 ======
  function renderTrendCard(t) {
    const mtdRet = t.monthToDateReturnPct != null ? t.monthToDateReturnPct : 0;
    const lastRet = t.lastMonthReturnPct != null ? t.lastMonthReturnPct : 0;
    const bars = t.monthlyBars || [];
    return `
      <div class="ps-card ps-card-trend">
        <div class="ps-card-head">
          <span class="ps-card-num">1</span>
          <h3>完美的走势</h3>
        </div>
        <div class="ps-card-body">
          <div class="ps-trend-summary">${escHtml(t.summary || '—')}</div>
          <div class="ps-trend-kpis">
            <div class="ps-kpi">
              <label>本月至今</label>
              <b class="${mtdRet >= 0 ? 'up' : 'down'}">${mtdRet >= 0 ? '+' : ''}${mtdRet.toFixed(2)}%</b>
            </div>
            <div class="ps-kpi">
              <label>最近一月</label>
              <b class="${lastRet >= 0 ? 'up' : 'down'}">${lastRet >= 0 ? '+' : ''}${lastRet.toFixed(2)}%</b>
            </div>
            <div class="ps-kpi">
              <label>近 60 日最大涨幅</label>
              <b class="up">${t.sixtyDayMaxGainPct != null ? '+' + t.sixtyDayMaxGainPct.toFixed(2) + '%' : '—'}</b>
            </div>
            <div class="ps-kpi">
              <label>近 60 日最大回撤</label>
              <b class="down">${t.sixtyDayMaxDrawdownPct != null ? t.sixtyDayMaxDrawdownPct.toFixed(2) + '%' : '—'}</b>
            </div>
          </div>
          ${renderMonthlyBars(bars)}
          ${t.breakoutDetected && t.breakoutNote ? `<div class="ps-callout ps-callout-success">🚀 ${escHtml(t.breakoutNote)}</div>` : ''}
          ${renderBigYangList(t.recentBigYang || [])}
          <div class="ps-data-meta">
            数据覆盖：${t.dataDays || 0} 个交易日（${escHtml(t.dataStartDate || '—')} ~ ${escHtml(t.dataEndDate || '—')}）
          </div>
        </div>
      </div>
    `;
  }

  function renderMonthlyBars(bars) {
    if (!bars || bars.length === 0) {
      return '<div class="ps-empty">无月线数据</div>';
    }
    const max = Math.max(10, ...bars.map(b => Math.abs(b.returnPct || 0)));
    const w = 800, rowH = 28, labelW = 70, barAreaW = w - labelW - 80;
    const h = rowH * bars.length + 12;
    let svg = `<svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="xMidYMid meet" class="ps-month-bars">`;
    // 中线 0
    const midX = labelW + barAreaW / 2;
    svg += `<line x1="${midX}" y1="4" x2="${midX}" y2="${h - 8}" stroke="#e5e7eb" stroke-dasharray="3,3"/>`;
    bars.forEach((b, i) => {
      const y = 6 + i * rowH;
      const pct = b.returnPct == null ? 0 : b.returnPct;
      const len = Math.abs(pct) / max * (barAreaW / 2);
      const x = pct >= 0 ? midX : midX - len;
      const color = pct >= 5 ? '#10b981' : (pct >= 0 ? '#34d399' : (pct <= -5 ? '#ef4444' : '#f87171'));
      svg += `<text x="${labelW - 8}" y="${y + 14}" text-anchor="end" font-size="11" fill="#6b7280">${escHtml(b.month)}</text>`;
      svg += `<rect x="${x.toFixed(1)}" y="${y + 4}" width="${Math.max(1, len).toFixed(1)}" height="16" fill="${color}" rx="2"/>`;
      svg += `<text x="${(pct >= 0 ? x + len + 6 : x - 6).toFixed(1)}" y="${y + 16}" text-anchor="${pct >= 0 ? 'start' : 'end'}" font-size="11" fill="${pct >= 0 ? '#059669' : '#dc2626'}">${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%</text>`;
      if (b.close != null) {
        svg += `<text x="${w - 4}" y="${y + 16}" text-anchor="end" font-size="11" fill="#9ca3af">${b.close.toFixed(2)}</text>`;
      }
    });
    svg += '</svg>';
    return `<div class="ps-trend-bars-wrap">${svg}</div>`;
  }

  function renderBigYangList(list) {
    if (!list || list.length === 0) {
      return '<div class="ps-data-meta">最近无大阳线（≥ 9.5% 涨幅）</div>';
    }
    const rows = list.map(b => `
      <tr>
        <td>${escHtml(b.date)}</td>
        <td>${b.openPrice != null ? fmtNum(b.openPrice, 2) : '—'}</td>
        <td>${b.closePrice != null ? fmtNum(b.closePrice, 2) : '—'}</td>
        <td>${b.highPrice != null ? fmtNum(b.highPrice, 2) : '—'}</td>
        <td class="up"><b>+${b.pctChange.toFixed(2)}%</b></td>
        <td>${b.turnoverRate != null ? b.turnoverRate.toFixed(2) + '%' : '—'}</td>
      </tr>
    `).join('');
    return `
      <details class="ps-bigyang-details">
        <summary>📈 最近大阳线（${list.length} 根，可点击展开）</summary>
        <table class="ps-table ps-bigyang-table">
          <thead><tr><th>日期</th><th>开盘</th><th>收盘</th><th>最高</th><th>涨幅</th><th>换手</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </details>
    `;
  }

  // ====== 财务卡片 ======
  function renderFinancialCard(f) {
    const verdict = f.sopVerdict || 'warn';
    const verdictMeta = {
      pass: { tag: '✓ PASS', desc: '三大数字漂亮' },
      warn: { tag: '⚠ WARN', desc: '部分指标偏弱' },
      fail: { tag: '✗ FAIL', desc: '数字不漂亮，建议谨慎' },
    }[verdict] || { tag: verdict.toUpperCase(), desc: '—' };
    const verdictClass = `ps-verdict-${verdict}`;
    return `
      <div class="ps-card ps-card-fin">
        <div class="ps-card-head">
          <span class="ps-card-num">2</span>
          <h3>漂亮的数字 · 16 季度财务</h3>
        </div>
        <div class="ps-card-body">
          <div class="ps-trend-summary">${escHtml(f.summary || '—')}</div>
          <div class="ps-sop-banner ${verdictClass}">
            <span class="ps-sop-banner-tag">${verdictMeta.tag}</span>
            <span class="ps-sop-banner-desc">${verdictMeta.desc}</span>
            <span class="ps-sop-banner-tip">${escHtml(f.sopSummary || '')}</span>
          </div>
          ${renderSopMetrics(f.sopMetrics || [])}
          ${renderFinSeries(f)}
          ${f.turnaroundDetected && f.turnaroundNote ? `<div class="ps-callout ps-callout-success">🌱 ${escHtml(f.turnaroundNote)}</div>` : ''}
          ${renderQuarterTable(f.quarters || [])}
        </div>
      </div>
    `;
  }

  // SOP 三项细分卡片（毛利率 / 营收同比 / 扣非同比）
  function renderSopMetrics(metrics) {
    if (!metrics || metrics.length === 0) return '';
    const cards = metrics.map(m => {
      const v = m.verdict || 'warn';
      const vClass = `ps-sop-card-${v}`;
      const vTag = v === 'pass' ? '✓ PASS' : (v === 'warn' ? '⚠ WARN' : '✗ FAIL');
      return `
        <div class="ps-sop-card ${vClass}">
          <div class="ps-sop-card-head">
            <span class="ps-sop-card-label">${escHtml(m.label || '—')}</span>
            <span class="ps-sop-card-tag">${vTag}</span>
          </div>
          <div class="ps-sop-card-latest">最新值 <b>${escHtml(m.latestText || '—')}</b></div>
          <div class="ps-sop-card-tip">${escHtml(m.tip || '')}</div>
        </div>
      `;
    }).join('');
    return `<div class="ps-sop-metrics">${cards}</div>`;
  }

  function renderFinSeries(f) {
    const rev = (f.revenueYoySeries || []).filter(v => v != null);
    const prof = (f.profitYoySeries || []).filter(v => v != null);
    const gm = (f.grossMarginSeries || []).filter(v => v != null);
    return `
      <div class="ps-fin-stats">
        ${renderSparkBar('最近 8 季度营收同比 %', rev, '#3b82f6', (f.latestRevenueYoy != null ? f.latestRevenueYoy : null))}
        ${renderSparkBar('最近 8 季度扣非同比 %', prof, '#10b981', (f.latestProfitYoy != null ? f.latestProfitYoy : null))}
        ${renderSparkBar('最近 8 季度毛利率 %', gm, '#f59e0b', (f.latestGrossMargin != null ? f.latestGrossMargin : null))}
      </div>
    `;
  }

  function renderSparkBar(title, arr, color, latest) {
    if (!arr || arr.length === 0) {
      return `<div class="ps-fin-stat"><label>${escHtml(title)}</label><div class="ps-empty-inline">无数据</div></div>`;
    }
    const max = Math.max(...arr.map(Math.abs), 10);
    const w = 320, h = 56, pad = 4;
    const bw = (w - pad * 2) / arr.length - 2;
    let svg = `<svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" class="ps-spark-bars">`;
    const zeroY = h - pad - (max / (max * 2) * (h - pad * 2));
    svg += `<line x1="${pad}" y1="${zeroY}" x2="${w - pad}" y2="${zeroY}" stroke="#e5e7eb" stroke-dasharray="2,2"/>`;
    arr.forEach((v, i) => {
      const x = pad + i * (bw + 2);
      const ratio = v / max;
      const barH = Math.abs(ratio) * (h - pad * 2) / 2;
      const y = v >= 0 ? zeroY - barH : zeroY;
      svg += `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${bw.toFixed(1)}" height="${Math.max(1, barH).toFixed(1)}" fill="${color}" rx="1"/>`;
    });
    svg += '</svg>';
    const latestTxt = latest != null
      ? (latest >= 0 ? '+' : '') + latest.toFixed(2) + '%'
      : '—';
    return `
      <div class="ps-fin-stat">
        <label>${escHtml(title)}</label>
        <div class="ps-fin-stat-chart">${svg}</div>
        <div class="ps-fin-stat-latest">最新：<b>${latestTxt}</b></div>
      </div>
    `;
  }

  // 横向 16 季度表格（与财务分析模块一致：表头是季度，左边是指标）
  function renderQuarterTable(quarters) {
    if (!quarters || quarters.length === 0) return '<div class="ps-empty">无季度数据</div>';
    // quarters 后端已按时间升序，最新在最右
    const cols = quarters.slice();
    const headerCols = cols.map(q => `
      <th>
        <div class="ps-q-col-q">${escHtml(q.quarter)}</div>
        <div class="ps-q-col-date">${escHtml((q.reportDate || '').slice(2))}</div>
      </th>
    `).join('');

    function color(v) {
      if (v == null) return '';
      return v >= 0 ? 'up' : 'down';
    }
    function fmt(v, unit, digits = 2) {
      if (v == null) return '—';
      const s = v.toFixed(digits);
      return unit === '%' ? (v >= 0 ? '+' : '') + s + '%' : s;
    }

    const rows = [
      { label: '营收 (亿)', key: 'revenueYi', unit: '', digits: 2 },
      { label: '同比', key: 'revenueYoy', unit: '%', digits: 2, color: true },
      { label: '毛利率', key: 'grossMargin', unit: '%', digits: 2 },
      { label: '净利率', key: 'netMargin', unit: '%', digits: 2 },
      { label: 'EPS', key: 'eps', unit: '', digits: 2 },
      { label: 'ROE', key: 'roe', unit: '%', digits: 2 },
    ];

    const trs = rows.map(r => {
      const cells = cols.map(q => {
        const v = q[r.key];
        const cls = r.color ? color(v) : '';
        return `<td class="${cls}">${fmt(v, r.unit, r.digits)}</td>`;
      }).join('');
      return `<tr><th class="ps-q-metric">${escHtml(r.label)}</th>${cells}</tr>`;
    }).join('');

    return `
      <details class="ps-quarter-details" open>
        <summary>📊 近 16 季度财务数据（与财务分析模块同款排版 · 点击收起）</summary>
        <div class="ps-table-wrap">
          <table class="ps-table ps-quarter-table">
            <thead><tr><th class="ps-q-metric">指标</th>${headerCols}</tr></thead>
            <tbody>${trs}</tbody>
          </table>
        </div>
      </details>
    `;
  }

  // ====== 星级评级卡片 ======
  function renderRatingCard(r) {
    return `
      <div class="ps-card ps-card-rating">
        <div class="ps-card-head">
          <span class="ps-card-num">3</span>
          <h3>稀缺性 + 成长动力 星级评级</h3>
          <span class="ps-ai-badge ${r.aiGenerated ? 'on' : 'off'}">${r.aiGenerated ? '🤖 AI 生成' : '⚙️ 本地启发式'}</span>
        </div>
        <div class="ps-card-body">
          ${renderRatingRow('稀缺性', r.scarcityStars, r.scarcityStarsText, r.scarcitySummary, r.scarcityDimensions)}
          ${renderRatingRow('成长动力', r.growthStars, r.growthStarsText, r.growthSummary, r.growthDimensions, r.growthWeaknesses)}
        </div>
      </div>
    `;
  }

  function renderRatingRow(label, stars, starsText, summary, dims, weaknesses) {
    const starsNum = stars != null ? stars : 0;
    const fullText = starsText || starsToText(starsNum);
    return `
      <div class="ps-rating-block">
        <div class="ps-rating-head">
          <div class="ps-rating-title">${escHtml(label)}</div>
          <div class="ps-rating-stars">
            <span class="ps-stars">${escHtml(fullText)}</span>
            <span class="ps-stars-num">${starsNum.toFixed(1)} / 5.0</span>
          </div>
        </div>
        <div class="ps-rating-bar">
          <div class="ps-rating-bar-fill" style="width:${(starsNum / 5 * 100).toFixed(1)}%"></div>
        </div>
        ${summary ? `<div class="ps-rating-summary">${escHtml(summary)}</div>` : ''}
        ${dims && dims.length > 0 ? `
          <div class="ps-rating-dims">
            ${dims.map(d => `
              <div class="ps-rating-dim">
                <div class="ps-rating-dim-name">${escHtml(d.name || '')}</div>
                <div class="ps-rating-dim-bar">
                  <div class="ps-rating-dim-bar-fill" style="width:${((d.stars || 0) / 5 * 100).toFixed(1)}%"></div>
                </div>
                <div class="ps-rating-dim-stars">${d.stars != null ? d.stars.toFixed(1) : '—'}</div>
                <div class="ps-rating-dim-reason">${escHtml(d.reason || '')}</div>
              </div>
            `).join('')}
          </div>
        ` : ''}
        ${weaknesses && weaknesses.length > 0 ? `
          <div class="ps-rating-weakness">
            <div class="ps-rating-weakness-title">⚠️ 短板（降星原因）</div>
            <ul>${weaknesses.map(w => `<li>${escHtml(w)}</li>`).join('')}</ul>
          </div>
        ` : ''}
      </div>
    `;
  }

  function starsToText(s) {
    if (s == null) return '☆☆☆☆☆';
    const full = Math.floor(s);
    const half = (s - full) >= 0.5;
    let t = '';
    for (let i = 0; i < 5; i++) {
      if (i < full) t += '★';
      else if (i === full && half) t += '☆';
      else t += '☆';
    }
    return t;
  }

  // ====== 估值卡片 ======
  function renderValuationCard(v) {
    const verdict = v.verdict || '—';
    // 2026-07-01 改：后端 verdict 用"泡沫"（跟投资池一致），不是"高估"
    const verdictClass = verdict === '低估' ? 'low' : (verdict === '泡沫' ? 'high' : (verdict === '合理' ? 'fair' : ''));
    return `
      <div class="ps-card ps-card-valuation">
        <div class="ps-card-head">
          <span class="ps-card-num">4</span>
          <h3>成长与估值的匹配 · ${escHtml(v.method || '估值分析')}</h3>
        </div>
        <div class="ps-card-body">
          <div class="ps-verdict-banner ps-verdict-${verdictClass}">估值结论：${escHtml(verdict)}</div>
          <div class="ps-trend-summary">${escHtml(v.methodReason || '')}</div>
          ${renderValuationCalc(v)}
          <div class="ps-valuation-commentary">${escHtml(v.commentary || '')}</div>
          ${v.buildPositionTip ? `<div class="ps-callout ps-callout-tip">💡 ${escHtml(v.buildPositionTip)}</div>` : ''}
        </div>
      </div>
    `;
  }

  function renderValuationCalc(v) {
    const rows = [
      ['当前股价', v.currentPrice != null ? fmtNum(v.currentPrice, 2) + ' 元' : '—'],
      ['总股本', v.totalSharesYi != null ? fmtNum(v.totalSharesYi, 4) + ' 亿股' : '—'],
      ['当前市值', v.currentMarketCapYi != null ? fmtNum(v.currentMarketCapYi, 1) + ' 亿元' : '—'],
      ['最新净利率', v.latestNetMargin != null ? v.latestNetMargin.toFixed(2) + '%' : '—'],
      ['PS 倍数', v.psMultiple != null ? v.psMultiple.toFixed(1) + ' 倍' : '—'],
      ['今年预测营收 (Y0)', v.forecastRevenueY0 != null ? fmtNum(v.forecastRevenueY0, 1) + ' 亿' : '—'],
      ['明年预测营收 (Y1)', v.forecastRevenueY1 != null ? fmtNum(v.forecastRevenueY1, 1) + ' 亿' : '—'],
      ['后年预测营收 (Y2)', v.forecastRevenueY2 != null ? fmtNum(v.forecastRevenueY2, 1) + ' 亿' : '—'],
      ['合理市值 = Y1 × PS', v.fairMarketCapYi != null ? fmtNum(v.fairMarketCapYi, 1) + ' 亿元' : '—'],
    ];
    return `
      <table class="ps-table ps-val-table">
        <tbody>
          ${rows.map(r => `<tr><td>${escHtml(r[0])}</td><td>${r[1]}</td></tr>`).join('')}
        </tbody>
      </table>
    `;
  }

  // ====== 工具 ======
  function escHtml(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
  }

  function fmtNum(n, digits) {
    if (n == null || !isFinite(n)) return '—';
    return Number(n).toFixed(digits);
  }

  // ============ 历史记录 ============

  async function loadHistory() {
    const list = document.getElementById('psHistoryList');
    if (!list) return;
    try {
      const params = new URLSearchParams({
        kw: historyKeyword || '',
        page: String(historyPage),
        size: String(historyPageSize),
      });
      const res = await fetch(`${API}/records?${params}`);
      const data = await res.json();
      if (!data.ok) throw new Error(data.message || '加载失败');
      renderHistory(data);
    } catch (e) {
      list.innerHTML = `<div class="ps-empty">加载失败：${escHtml(e.message)}</div>`;
    }
  }

  function renderHistory(data) {
    const list = document.getElementById('psHistoryList');
    const pager = document.getElementById('psHistoryPager');
    const items = data.records || [];
    if (items.length === 0) {
      list.innerHTML = `<div class="ps-empty">暂无历史记录</div>`;
      pager.innerHTML = '';
      return;
    }
    list.innerHTML = items.map(it => `
      <article class="ps-history-item" data-id="${it.id}">
        <div class="ps-history-item-head">
          <span class="ps-history-name">${escHtml(it.stockName || '—')}</span>
          <span class="ps-history-code">${escHtml(it.stockCode || '')}</span>
        </div>
        <div class="ps-history-headline">${escHtml(it.headline || '—')}</div>
        <div class="ps-history-meta">
          <span class="ps-history-time">${formatDateTime(it.createdAt)}</span>
          ${it.verdict ? `<span class="ps-history-verdict ps-verdict-${verdictClass(it.verdict)}">${escHtml(it.verdict)}</span>` : ''}
          ${it.shareEnabled ? '<span class="ps-history-share-tag">🔗 已分享</span>' : ''}
          ${it.pdfPath ? '<span class="ps-history-pdf-tag">📄</span>' : ''}
        </div>
        <div class="ps-history-actions">
          <button data-action="view" data-id="${it.id}">查看</button>
          <button data-action="pdf" data-id="${it.id}" ${it.pdfPath ? '' : 'disabled'}>PDF</button>
          <button data-action="share" data-id="${it.id}">分享</button>
          <button data-action="delete" data-id="${it.id}" class="danger">删除</button>
        </div>
      </article>
    `).join('');
    // 绑定按钮
    list.querySelectorAll('button').forEach(btn => {
      btn.addEventListener('click', e => {
        e.stopPropagation();
        const action = btn.dataset.action;
        const id = parseInt(btn.dataset.id, 10);
        if (action === 'view') loadRecordIntoPanel(id);
        else if (action === 'pdf') downloadPdf(id);
        else if (action === 'share') enableShare(id);
        else if (action === 'delete') deleteRecord(id);
      });
    });
    // 点击整条 = 查看
    list.querySelectorAll('.ps-history-item').forEach(el => {
      el.addEventListener('click', () => loadRecordIntoPanel(parseInt(el.dataset.id, 10)));
    });
    // 分页
    const total = data.total || 0;
    const totalPages = Math.ceil(total / historyPageSize);
    pager.innerHTML = `
      <button data-page="prev" ${historyPage <= 0 ? 'disabled' : ''}>‹ 上一页</button>
      <span class="ps-pager-info">${historyPage + 1} / ${totalPages || 1}</span>
      <button data-page="next" ${historyPage + 1 >= totalPages ? 'disabled' : ''}>下一页 ›</button>
    `;
    pager.querySelectorAll('button').forEach(b => {
      b.addEventListener('click', () => {
        if (b.dataset.page === 'prev' && historyPage > 0) {
          historyPage--;
          loadHistory();
        } else if (b.dataset.page === 'next' && historyPage + 1 < totalPages) {
          historyPage++;
          loadHistory();
        }
      });
    });
  }

  async function loadRecordIntoPanel(id) {
    try {
      const res = await fetch(`${API}/record/${id}`);
      const data = await res.json();
      if (!data.ok || !data.data) throw new Error(data.message || '加载失败');
      renderResult(data.data);
    } catch (e) {
      alert('查看历史记录失败：' + e.message);
    }
  }

  function verdictClass(v) {
    if (v === '低估') return 'low';
    // 2026-07-01 改：后端 verdict 用"泡沫"（跟投资池一致），不是"高估"
    if (v === '泡沫') return 'high';
    if (v === '合理') return 'fair';
    return '';
  }

  function formatDateTime(s) {
    if (!s) return '';
    // s 形如 "2026-06-22T21:35:00"
    return s.replace('T', ' ').slice(0, 16);
  }

  // expose
  window.PracticalSelect = { init, analyze, renderShared };

  // DOM ready 时自动初始化
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();