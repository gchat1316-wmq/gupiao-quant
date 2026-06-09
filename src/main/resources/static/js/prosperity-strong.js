/**
 * 强势股选股前端 - 含过滤漏斗可视化 + 自动触发
 *
 * API base: /gp/api/prosperity-strong/*
 */
const BASE = '/gp/api/prosperity-strong';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

const state = {
  date: '',
  provider: 'local',
  providers: [],
  sectors: [],
  candidates: [],
};

function fmtNum(v, scale) {
  if (v == null || v === '') return '--';
  const n = Number(v);
  if (Number.isNaN(n)) return v;
  return scale != null ? n.toFixed(scale) : n.toFixed(2);
}
function fmtPct(v) { return v == null ? '--' : Number(v).toFixed(2) + '%'; }
function fmtYi(v) {
  if (v == null) return '--';
  const yi = Number(v) / 1e8;
  return yi.toFixed(2) + ' 亿';
}
function fmtMoney(v) { return v == null ? '--' : '¥' + Number(v).toFixed(2); }
function fmtProfit(v) {
  if (v == null) return '--';
  const n = Number(v);
  if (Number.isNaN(n)) return '--';
  const abs = Math.abs(n);
  if (abs >= 1e8) return (n / 1e8).toFixed(2) + ' 亿';
  if (abs >= 1e4) return (n / 1e4).toFixed(2) + ' 万';
  return n.toFixed(0);
}
function fmtSignedPct(v) {
  if (v == null) return '--';
  const n = Number(v);
  if (Number.isNaN(n)) return '--';
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%';
}

function todayLocalDate() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function setCurrentDate(value) {
  const date = value || todayLocalDate();
  const input = $('#psDate');
  if (input) input.value = date;
  state.date = date;
  return date;
}

async function apiGet(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`);
  return r.json();
}
async function apiPost(url) {
  const r = await fetch(url, { method: 'POST' });
  if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`);
  return r.json();
}

function setStatus(text, kind) {
  const pill = $('#psStatusPill');
  pill.textContent = kind === 'busy' ? '运行中' : (kind === 'err' ? '失败' : '就绪');
  pill.className = 'ps-status-pill' + (kind ? ' ' + kind : '');
  $('#psStatusText').textContent = text;
}

async function loadStatus() {
  try {
    const s = await apiGet(`${BASE}/status`);
    state.provider = s.defaultProvider || state.provider || 'local';
    const providerSelect = $('#psProvider');
    if (providerSelect) providerSelect.value = state.provider;
    if (s.latestSnapDate) {
      if (!state.date) setCurrentDate(s.now || todayLocalDate());
      setStatus(`上次执行: ${s.latestSnapDate}`);
    } else {
      setCurrentDate(state.date || s.now || todayLocalDate());
      setStatus('尚无数据,自动触发中...');
      // 自动触发: 无数据时首次自动跑一次
      try {
        const r = await apiPost(`${BASE}/run?date=${state.date}&provider=${encodeURIComponent(state.provider)}`);
        setStatus(r.message || '完成');
        state.date = s.now;
      } catch (e) {
        setStatus('自动触发失败: ' + e.message, 'err');
      }
    }
  } catch (e) {
    setStatus('状态加载失败', 'err');
  }
}

async function loadProviders() {
  try {
    const list = await apiGet(`${BASE}/providers`);
    state.providers = list || [];
    renderProviders(state.providers);
  } catch (e) {
    const panel = $('#psProviderPanel');
    if (panel) panel.innerHTML = `<div class="ps-provider-error">链路诊断加载失败: ${escapeHtml(e.message)}</div>`;
  }
}

function renderProviders(list) {
  const panel = $('#psProviderPanel');
  if (!panel) return;
  if (!list || !list.length) {
    panel.innerHTML = '';
    return;
  }
  panel.innerHTML = list.map(p => `
    <button class="ps-provider-card ${state.provider === p.code ? 'active' : ''}" data-provider="${p.code}">
      <div class="ps-provider-card-head">
        <span>${escapeHtml(p.label || p.code)}</span>
        <span class="ps-provider-badge ${p.verified ? 'ok' : (p.available ? 'warn' : 'off')}">
          ${p.verified ? '已验证' : (p.available ? '可用' : '不可用')}
        </span>
      </div>
      <div class="ps-provider-role">${escapeHtml(p.role || '')}</div>
      <div class="ps-provider-message">${escapeHtml(p.message || '')}</div>
    </button>
  `).join('');
  panel.querySelectorAll('.ps-provider-card').forEach(card => {
    card.addEventListener('click', () => setProvider(card.dataset.provider));
  });
}

function setProvider(provider) {
  state.provider = provider || 'local';
  const select = $('#psProvider');
  if (select) select.value = state.provider;
  renderProviders(state.providers);
}

async function loadSectors() {
  const list = await apiGet(`${BASE}/sectors?date=${state.date}`);
  state.sectors = list;
  renderSectors(list);
}

function stageLabel(stage) {
  switch (stage) {
    case 'leader_filter': return '龙头筛选未通过';
    case 'finance_filter': return '财务硬筛未通过';
    case 'mainline_filter': return '主线判定未通过';
    case 'passed': return '✓ 全部通过';
    default: return '--';
  }
}

function stageClass(stage) {
  switch (stage) {
    case 'passed': return 'ps-stage-passed';
    case 'leader_filter': return 'ps-stage-leader';
    case 'finance_filter': return 'ps-stage-finance';
    case 'mainline_filter': return 'ps-stage-mainline';
    default: return '';
  }
}

function renderSectors(list) {
  const wrap = $('#psSectorList');
  if (!list || !list.length) {
    wrap.innerHTML = '<div class="ps-empty">当日无板块数据,请先手动触发。</div>';
    return;
  }
  wrap.innerHTML = list.map(s => {
    const leaders = s.leaders || [];
    const passed = leaders.filter(l => l.finalStage === 'passed').length;
    const total = leaders.length;
    const leaderFiltered = leaders.filter(l => l.finalStage === 'leader_filter').length;
    const financeFiltered = leaders.filter(l => l.finalStage === 'finance_filter').length;
    const mainlineFiltered = leaders.filter(l => l.finalStage === 'mainline_filter').length;
    const matched = Number(s.matchedMemberCount || 0);
    const quoted = Number(s.quotedMemberCount || 0);
    const pipelineTotal = total || quoted || matched;

    return `
    <div class="ps-sector-card">
      <div class="ps-sector-head-row">
        <div>
          <span class="ps-sector-rank">#${s.rankNo}</span>
          <span class="ps-sector-name">${s.sectorName}</span>
        </div>
        <span class="ps-sector-funnel-summary">
          匹配 ${matched} → 有行情 ${quoted} → 入龙头池 ${total} → <b>通过 ${passed}</b>
        </span>
      </div>
      <div class="ps-sector-metrics">
        <span>评分 <strong>${fmtNum(s.score)}</strong></span>
        <span>当日 <strong>${fmtPct(s.change1d)}</strong></span>
        <span>5日 <strong>${fmtPct(s.change5d)}</strong></span>
        <span>5日资金流 <strong>${fmtYi(s.capitalInflow5d)}</strong></span>
      </div>
      ${s.aiNarrative ? `<div class="ps-sector-narrative">${escapeHtml(s.aiNarrative)}</div>` : ''}
      ${s.diagnosticMessage ? `
        <div class="ps-sector-diagnostic">
          <b>过滤诊断:</b> ${escapeHtml(s.diagnosticMessage)}
        </div>
      ` : ''}
      ${total > 0 ? `
        <div class="ps-funnel">
          <div class="ps-funnel-bar">
            <div class="ps-funnel-seg ps-funnel-leader" style="width:${pctOf(total, leaderFiltered)}" title="龙头筛剔除 ${leaderFiltered} 只"></div>
            <div class="ps-funnel-seg ps-funnel-finance" style="width:${pctOf(total, financeFiltered)}" title="财务筛剔除 ${financeFiltered} 只"></div>
            <div class="ps-funnel-seg ps-funnel-mainline" style="width:${pctOf(total, mainlineFiltered)}" title="主线筛剔除 ${mainlineFiltered} 只"></div>
            <div class="ps-funnel-seg ps-funnel-passed" style="width:${pctOf(total, passed)}" title="通过 ${passed} 只"></div>
          </div>
          <div class="ps-funnel-legend">
            <span class="ps-legend ps-legend-leader">龙头筛剔除 ${leaderFiltered}</span>
            <span class="ps-legend ps-legend-finance">财务筛剔除 ${financeFiltered}</span>
            <span class="ps-legend ps-legend-mainline">主线筛剔除 ${mainlineFiltered}</span>
            <span class="ps-legend ps-legend-passed">通过 ${passed}</span>
          </div>
        </div>
        <details class="ps-leader-details">
          <summary>查看成分股过滤明细 (${total} 只)</summary>
          <table class="ps-table ps-leader-table">
            <thead><tr>
              <th>代码</th><th>名称</th><th>龙头分</th>
              <th>Step2 快筛</th><th>Step3 财务分</th><th>Step3 结果</th>
              <th>Step4 主线分</th><th>Step4 结果</th><th>最终</th>
            </tr></thead>
            <tbody>${leaders.map(l => `
              <tr class="${stageClass(l.finalStage)}">
                <td>${l.stockCode}</td>
                <td>${l.stockName || '--'}</td>
                <td>${fmtNum(l.leaderScore)}</td>
                <td>${l.filterPassed ? '<span class="ps-check">✓</span>' : '<span class="ps-cross">✗</span> ' + (l.filterReason||'')}</td>
                <td>${fmtNum(l.financeScore)}</td>
                <td>${l.financePassed === true ? '<span class="ps-check">✓</span>' : (l.financePassed === false ? '<span class="ps-cross">✗</span> ' + (l.financeReason||'') : '--')}</td>
                <td>${fmtNum(l.mainlineScore)}</td>
                <td>${l.mainlinePassed === true ? '<span class="ps-check">✓</span>' : (l.mainlinePassed === false ? '<span class="ps-cross">✗</span>' : '--')}</td>
                <td><span class="ps-stage-badge ${stageClass(l.finalStage)}">${stageLabel(l.finalStage)}</span></td>
              </tr>
            `).join('')}</tbody>
          </table>
        </details>
      ` : pipelineTotal > 0 ? `
        <div class="ps-funnel">
          <div class="ps-funnel-bar">
            <div class="ps-funnel-seg ps-funnel-passed" style="width:${pctOf(pipelineTotal, matched)}" title="匹配成分股 ${matched} 只"></div>
            <div class="ps-funnel-seg ps-funnel-finance" style="width:${pctOf(pipelineTotal, Math.max(0, matched - quoted))}" title="缺少日线行情 ${Math.max(0, matched - quoted)} 只"></div>
          </div>
          <div class="ps-funnel-legend">
            <span class="ps-legend ps-legend-passed">匹配成分股 ${matched}</span>
            <span class="ps-legend ps-legend-finance">缺日线行情 ${Math.max(0, matched - quoted)}</span>
            <span class="ps-legend ps-legend-leader">入龙头池 ${total}</span>
          </div>
        </div>
      ` : ''}
    </div>
  `;
  }).join('');
}

function pctOf(total, count) {
  if (!total) return '0%';
  return (count / total * 100).toFixed(1) + '%';
}

async function loadCandidates() {
  const list = await apiGet(`${BASE}/candidates?date=${state.date}`);
  state.candidates = list;
  renderCandidates(list);
  renderDetailCandidateTags(list);
}

function renderCandidates(list) {
  const wrap = $('#psCandidateTable');
  if (!list || !list.length) {
    wrap.innerHTML = '<div class="ps-empty">当日无候选,请先运行流水线。</div>';
    renderDetailCandidateTags([]);
    return;
  }
  const rows = list.map(p => `
    <tr>
      <td><a href="javascript:psShowDetail('${p.stockCode}')">${p.stockCode}</a></td>
      <td>${p.stockName || '--'}</td>
      <td>${p.sectorName || '--'}</td>
      <td>${fmtMoney(p.latestPrice)}</td>
      <td>${fmtNum(p.financeScore)}</td>
      <td>${fmtNum(p.mainlineScore)}</td>
      <td class="score-cell">${fmtNum(p.combinedScore)}</td>
      <td>${fmtPct(p.netMarginAvg4q)}</td>
      <td>${fmtMoney(p.buyLeftPrice)}</td>
      <td>${fmtMoney(p.sellTarget1)}</td>
      <td>${fmtMoney(p.stopLossPrice)}</td>
      <td>${fmtPct(p.corePositionPct)}</td>
      <td><span class="ps-signal ${p.actionSignal || ''}">${signalLabel(p.actionSignal)}</span></td>
      <td>
        <button class="ps-btn-mini" onclick="psShowDetail('${p.stockCode}')">详情</button>
        <button class="ps-btn-mini" onclick="psPromote('${p.stockCode}')">入池</button>
      </td>
    </tr>
  `).join('');
  wrap.innerHTML = `
    <table class="ps-table">
      <thead><tr>
        <th>代码</th><th>名称</th><th>板块</th><th>现价</th>
        <th>财务分</th><th>主线分</th><th>综合分</th>
        <th>近4季净利率</th>
        <th>建仓价</th><th>目标价</th><th>止损价</th>
        <th>核心仓位</th><th>信号</th><th>操作</th>
      </tr></thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

function renderDetailCandidateTags(list) {
  const wrap = $('#psDetailCandidateTags');
  if (!wrap) return;
  if (!list || !list.length) {
    wrap.innerHTML = '<div class="ps-detail-tag-empty">暂无龙头候选</div>';
    return;
  }
  wrap.innerHTML = list.map(p => `
    <button class="ps-detail-tag" type="button" data-code="${escapeAttr(p.stockCode)}">
      <span class="ps-detail-tag-name">${escapeHtml(p.stockName || p.stockCode)}</span>
      <span class="ps-detail-tag-code">${escapeHtml(p.stockCode)}</span>
      <span class="ps-detail-tag-score">${fmtNum(p.combinedScore)}</span>
    </button>
  `).join('');
  wrap.querySelectorAll('.ps-detail-tag').forEach(btn => {
    btn.addEventListener('click', () => psShowDetail(btn.dataset.code));
  });
  const input = $('#psDetailCode');
  if (input && !input.value && list[0]?.stockCode) {
    input.value = list[0].stockCode;
  }
}

function signalLabel(s) {
  switch (s) {
    case 'add': return '加仓';
    case 'hold': return '持有';
    case 'reduce': return '减仓';
    case 'observe': return '观察';
    default: return '--';
  }
}

async function psShowDetail(code) {
  switchTab('detail');
  $('#psDetailCode').value = code;
  await loadDetail(code);
}
window.psShowDetail = psShowDetail;

async function loadDetail(code) {
  const card = $('#psDetailCard');
  card.innerHTML = '<div class="ps-empty">加载中...</div>';
  try {
    const d = await apiGet(`${BASE}/detail/${encodeURIComponent(code)}?date=${state.date}`);
    card.innerHTML = renderDetail(d);
  } catch (e) {
    card.innerHTML = `<div class="ps-empty">加载失败: ${e.message}</div>`;
  }
}

function renderDetail(p) {
  return `
    <div class="ps-position-card">
      <div class="ps-position-head">
        <div>
          <h3>${p.stockName || ''} <span style="font-size:14px;color:#777">${p.stockCode}</span></h3>
          <div class="ps-position-meta">
            板块: ${p.sectorName || '--'} · 综合评分 <b>${fmtNum(p.combinedScore)}</b> ·
            现价 <b>${fmtMoney(p.latestPrice)}</b> ·
            近4季净利率 <b>${fmtPct(p.netMarginAvg4q)}</b>
          </div>
          ${renderProfitTrend(p.profitQuarters)}
        </div>
        <button class="invest-btn-outline" onclick="psPromote('${p.stockCode}')">一键入池</button>
      </div>
      ${renderPositionVisual(p)}
      <div class="ps-position-tip">
        分批建仓建议: 综合分 ≥85 建仓 50% / +30% / +20%; 70~84 建仓 40%/40%/20%; 60~69 建仓 30%/40%/30%.
        单股最大 10%, 单板块最大 30%, 总仓位 ≤80%.
      </div>
    </div>
    ${p.aiReport ? `
      ${renderAiReport(p.aiReport)}
    ` : '<div class="ps-empty">AI 深度报告将在 Phase 2 接入</div>'}
  `;
}

function renderAiReport(report) {
  const sections = Array.isArray(report.sections) ? report.sections : [];
  if (!sections.length) {
    return `
      <div class="ps-ai-report">
        <div class="ps-ai-head">
          <h3>深度报告</h3>
          <span>${escapeHtml(report.source || 'system')}</span>
        </div>
        <pre class="ps-ai-raw">${escapeHtml(JSON.stringify(report, null, 2))}</pre>
      </div>
    `;
  }
  return `
    <div class="ps-ai-report">
      <div class="ps-ai-head">
        <div>
          <h3>${escapeHtml(report.title || '深度报告')}</h3>
          <p>${escapeHtml(report.summary || '')}</p>
        </div>
        <span>${report.source === 'system_generated' ? '系统生成' : 'AI生成'}</span>
      </div>
      <div class="ps-ai-sections">
        ${sections.map(s => `
          <section class="ps-ai-section">
            <h4>${escapeHtml(s.title || '')}</h4>
            <ul>
              ${(s.points || []).map(point => `<li>${escapeHtml(point)}</li>`).join('')}
            </ul>
          </section>
        `).join('')}
      </div>
    </div>
  `;
}

function renderPositionVisual(p) {
  const points = [
    { key: 'stop', label: '止损', value: p.stopLossPrice, cls: 'danger' },
    { key: 'buyLeft', label: '左侧建仓', value: p.buyLeftPrice, cls: 'buy' },
    { key: 'low', label: '保守估值', value: p.priceLow, cls: 'value' },
    { key: 'buyRight', label: '右侧确认', value: p.buyRightPrice, cls: 'buy' },
    { key: 'now', label: '现价', value: p.latestPrice, cls: 'now' },
    { key: 'mid', label: '中性估值', value: p.priceMid, cls: 'value' },
    { key: 'target1', label: '第一目标', value: p.sellTarget1, cls: 'target' },
    { key: 'target2', label: '第二目标/乐观', value: p.sellTarget2 || p.priceHigh, cls: 'target' }
  ].filter(x => x.value != null && !Number.isNaN(Number(x.value)));
  if (!points.length) return '';

  const values = points.map(x => Number(x.value));
  let min = Math.min(...values);
  let max = Math.max(...values);
  const pad = Math.max((max - min) * 0.08, max * 0.02, 1);
  min -= pad;
  max += pad;
  const pct = (v) => ((Number(v) - min) / (max - min) * 100).toFixed(2);
  const byKey = Object.fromEntries(points.map(x => [x.key, x]));
  const markerHtml = points.map(x => `
    <div class="ps-price-marker ${x.cls}" style="left:${pct(x.value)}%">
      <span class="ps-marker-dot"></span>
      <span class="ps-marker-label">${x.label}</span>
      <strong>${fmtMoney(x.value)}</strong>
    </div>
  `).join('');
  const buyStart = byKey.buyLeft ? pct(byKey.buyLeft.value) : null;
  const buyEnd = byKey.buyRight ? pct(byKey.buyRight.value) : buyStart;
  const targetStart = byKey.target1 ? pct(byKey.target1.value) : null;
  const targetEnd = byKey.target2 ? pct(byKey.target2.value) : targetStart;
  const valueStart = byKey.low ? pct(byKey.low.value) : null;
  const valueEnd = byKey.target2 ? pct(byKey.target2.value) : (byKey.mid ? pct(byKey.mid.value) : valueStart);
  return `
    <div class="ps-position-visual">
      <div class="ps-position-visual-head">
        <span>价格路径</span>
        <b class="ps-action-chip ${p.actionSignal || ''}">${signalLabel(p.actionSignal)}</b>
      </div>
      ${renderKlineDemo(p, min, max)}
      <div class="ps-price-axis">
        ${valueStart != null && valueEnd != null ? `<div class="ps-axis-band value" style="left:${valueStart}%;width:${Math.max(1, Number(valueEnd) - Number(valueStart)).toFixed(2)}%"></div>` : ''}
        ${buyStart != null && buyEnd != null ? `<div class="ps-axis-band buy" style="left:${Math.min(Number(buyStart), Number(buyEnd)).toFixed(2)}%;width:${Math.max(1, Math.abs(Number(buyEnd) - Number(buyStart))).toFixed(2)}%"></div>` : ''}
        ${targetStart != null && targetEnd != null ? `<div class="ps-axis-band target" style="left:${Math.min(Number(targetStart), Number(targetEnd)).toFixed(2)}%;width:${Math.max(1, Math.abs(Number(targetEnd) - Number(targetStart))).toFixed(2)}%"></div>` : ''}
        <div class="ps-axis-line"></div>
        ${markerHtml}
      </div>
      <div class="ps-axis-scale">
        <span>${fmtMoney(min)}</span>
        <span>${fmtMoney(max)}</span>
      </div>
      <div class="ps-position-dashboard">
        <div class="ps-allocation-card">
          <div class="ps-allocation-title">仓位建议</div>
          ${allocationBar('核心仓位', p.corePositionPct, 10)}
          ${allocationBar('战术仓位', p.tacticalPositionPct, 10)}
        </div>
        <div class="ps-mini-metrics">
          ${miniMetric('建仓区间', `${fmtMoney(p.buyLeftPrice)} - ${fmtMoney(p.buyRightPrice)}`)}
          ${miniMetric('目标区间', `${fmtMoney(p.sellTarget1)} - ${fmtMoney(p.sellTarget2)}`)}
          ${miniMetric('估值区间', `${fmtMoney(p.priceLow)} - ${fmtMoney(p.priceHigh)}`)}
          ${miniMetric('风险线', fmtMoney(p.stopLossPrice))}
        </div>
      </div>
    </div>
  `;
}

function renderKlineDemo(p, min, max) {
  const scale = (v) => {
    if (v == null || Number.isNaN(Number(v))) return null;
    return (86 - ((Number(v) - min) / (max - min) * 70));
  };
  const steps = [
    { key: 'buyLeft', label: '左侧建仓', value: p.buyLeftPrice, cls: 'buy', x: 8, addPct: 40 },
    { key: 'low', label: '保守估值', value: p.priceLow, cls: 'value', x: 23, addPct: 0 },
    { key: 'stop', label: '止损线', value: p.stopLossPrice, cls: 'danger', x: 38, addPct: 0 },
    { key: 'buyRight', label: '右侧确认', value: p.buyRightPrice, cls: 'buy', x: 54, addPct: 20 },
    { key: 'now', label: '现价/一目标', value: p.latestPrice, cls: 'now', x: 72, addPct: 0 },
    { key: 'target2', label: '第二目标', value: p.sellTarget2 || p.priceHigh, cls: 'target', x: 92, addPct: 0 }
  ].filter(x => x.value != null && !Number.isNaN(Number(x.value)))
    .map((x, idx, arr) => {
      const prev = idx === 0 ? Number(x.value) : Number(arr[idx - 1].value);
      const delta = Number(x.value) - prev;
      const body = Math.max(16, Math.min(54, Math.abs(delta) / Math.max(1, max - min) * 170));
      return { ...x, y: scale(x.value), delta, body };
    });
  if (steps.length < 2) return '';

  const curvePath = smoothPath(steps.map(s => ({ x: s.x, y: s.y })));
  const nodes = steps.map((s, idx) => `
    <div class="ps-arc-node ${s.cls} ${idx % 2 ? 'below' : 'above'}" style="left:${s.x}%;top:${s.y.toFixed(2)}%">
      <div class="ps-arc-candle ${idx === 0 || s.delta >= 0 ? 'up' : 'down'}" style="height:${s.body.toFixed(1)}px">
        <span></span>
      </div>
      <div class="ps-arc-label">
        <b>${s.label}</b>
        <strong>${fmtMoney(s.value)}</strong>
      </div>
    </div>
  `).join('');
  const deltas = steps.slice(1).map((s, i) => {
    const prev = steps[i];
    const midX = (prev.x + s.x) / 2;
    const midY = Math.max(10, Math.min(88, (prev.y + s.y) / 2 + (i % 2 ? 15 : -18)));
    const risePct = prev.value ? (s.delta / Number(prev.value) * 100) : 0;
    const addText = s.addPct > 0 ? `加仓 ${s.addPct}%` : (s.cls === 'target' ? '目标空间' : '观察');
    return `
      <div class="ps-arc-delta ${s.delta >= 0 ? 'up' : 'down'}" style="left:${midX}%;top:${midY.toFixed(2)}%">
        <b>${s.delta >= 0 ? '+' : ''}${risePct.toFixed(2)}%</b>
        <span>${addText}</span>
      </div>
    `;
  }).join('');

  return `
    <div class="ps-kline-demo">
      <div class="ps-arc-panel">
        <svg class="ps-arc-svg" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
          <path d="${curvePath}" />
        </svg>
        <div class="ps-arc-zone buy"></div>
        <div class="ps-arc-zone target"></div>
        ${deltas}
        ${nodes}
      </div>
      <div class="ps-kline-legend">
        <span><i class="buy"></i>建仓/确认</span>
        <span><i class="value"></i>估值参考</span>
        <span><i class="danger"></i>风险线</span>
        <span><i class="now"></i>现价</span>
        <span><i class="target"></i>目标价</span>
        <span>节点间显示上涨比例/加仓比例</span>
      </div>
    </div>
  `;
}

function smoothPath(points) {
  if (!points.length) return '';
  if (points.length === 1) return `M ${points[0].x} ${points[0].y}`;
  let d = `M ${points[0].x} ${points[0].y}`;
  for (let i = 1; i < points.length - 1; i++) {
    const midX = (points[i].x + points[i + 1].x) / 2;
    const midY = (points[i].y + points[i + 1].y) / 2;
    d += ` Q ${points[i].x} ${points[i].y} ${midX} ${midY}`;
  }
  const last = points[points.length - 1];
  const prev = points[points.length - 2];
  d += ` Q ${prev.x} ${prev.y} ${last.x} ${last.y}`;
  return d;
}

function allocationBar(label, value, max) {
  const n = value == null ? 0 : Number(value);
  const pct = Math.max(0, Math.min(100, n / max * 100));
  return `
    <div class="ps-allocation-row">
      <span>${label}</span>
      <div class="ps-allocation-track"><i style="width:${pct.toFixed(1)}%"></i></div>
      <b>${fmtPct(value)}</b>
    </div>
  `;
}

function miniMetric(label, value) {
  return `
    <div class="ps-mini-metric">
      <span>${label}</span>
      <b>${value}</b>
    </div>
  `;
}

function renderProfitTrend(items) {
  if (!items || !items.length) return '';
  const values = items.map(x => Number(x.netProfit || 0));
  const maxAbs = Math.max(...values.map(v => Math.abs(v)), 1);
  const points = items.map(x => {
    const profit = Number(x.netProfit || 0);
    const pct = Math.max(8, Math.abs(profit) / maxAbs * 100);
    const qoq = Number(x.qoqPct || 0);
    const direction = qoq > 0 ? 'up' : (qoq < 0 ? 'down' : 'flat');
    return `
      <div class="ps-profit-point ${profit < 0 ? 'loss' : ''}">
        <div class="ps-profit-bar-wrap">
          <div class="ps-profit-bar" style="height:${pct}%"></div>
        </div>
        <div class="ps-profit-label">${escapeHtml(x.label || '')}</div>
        <div class="ps-profit-value">${fmtProfit(x.netProfit)}</div>
        <div class="ps-profit-qoq ${direction}">${fmtSignedPct(x.qoqPct)}</div>
      </div>
    `;
  }).join('');
  return `
    <div class="ps-profit-trend">
      <div class="ps-profit-title">
        <span>近4季单季净利润</span>
        <span>环比涨跌</span>
      </div>
      <div class="ps-profit-chart">${points}</div>
    </div>
  `;
}

function posCell(label, value, suffix) {
  let display;
  if (value == null) display = '--';
  else if (suffix === '%') display = Number(value).toFixed(2) + '%';
  else if (suffix === '') display = value;
  else display = '¥' + Number(value).toFixed(2);
  return `
    <div class="ps-position-item">
      <div class="ps-position-label">${label}</div>
      <div class="ps-position-value">${display}</div>
    </div>
  `;
}

async function psPromote(code) {
  if (!confirm(`确认将 ${code} 加入龙江投资股票池?`)) return;
  try {
    const r = await apiPost(`${BASE}/promote/${encodeURIComponent(code)}?date=${state.date}`);
    alert(r.message || '已入池');
  } catch (e) {
    alert('入池失败: ' + e.message);
  }
}
window.psPromote = psPromote;

async function runPipeline() {
  setStatus(`正在执行流水线(${providerLabel(state.provider)})...`, 'busy');
  try {
    const r = await apiPost(`${BASE}/run?date=${state.date}&provider=${encodeURIComponent(state.provider)}`);
    const extra = r.providerMessage ? `；${r.providerMessage}` : '';
    setStatus((r.message || '完成') + extra);
    await Promise.all([loadSectors(), loadCandidates()]);
  } catch (e) {
    setStatus('流水线失败: ' + e.message, 'err');
  }
}

function providerLabel(provider) {
  const p = (state.providers || []).find(x => x.code === provider);
  return p ? p.label : provider;
}

function switchTab(name) {
  $$('.ps-tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
  $$('.ps-panel').forEach(p => p.classList.toggle('hidden', p.id !== `psPanel${cap(name)}`));
  if (name === 'detail') {
    const card = $('#psDetailCard');
    const first = state.candidates && state.candidates[0];
    if (card && first && !card.innerHTML.trim()) {
      $('#psDetailCode').value = first.stockCode;
      loadDetail(first.stockCode);
    }
  }
}
function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }

function escapeHtml(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeAttr(s) {
  return escapeHtml(s).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

async function loadHistory() {
  const from = $('#psHistFrom').value;
  const to = $('#psHistTo').value;
  if (!from || !to) { alert('请先选择起止日期'); return; }
  try {
    const list = await apiGet(`${BASE}/history?from=${from}&to=${to}`);
    const wrap = $('#psHistoryTable');
    if (!list.length) { wrap.innerHTML = '<div class="ps-empty">无数据</div>'; return; }
    wrap.innerHTML = `
      <table class="ps-table">
        <thead><tr><th>日期</th><th>代码</th><th>名称</th><th>板块</th><th>综合分</th><th>信号</th></tr></thead>
        <tbody>${list.map(p => `
          <tr>
            <td>${p.snapDate}</td>
            <td>${p.stockCode}</td>
            <td>${p.stockName || '--'}</td>
            <td>${p.sectorName || '--'}</td>
            <td class="score-cell">${fmtNum(p.combinedScore)}</td>
            <td><span class="ps-signal ${p.actionSignal||''}">${signalLabel(p.actionSignal)}</span></td>
          </tr>`).join('')}</tbody>
      </table>`;
  } catch (e) {
    alert('查询失败: ' + e.message);
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  setCurrentDate(todayLocalDate());
  await loadStatus();
  await loadProviders();
  await loadSectors().catch(()=>{});
  await loadCandidates().catch(()=>{});

  $('#psRunBtn').addEventListener('click', runPipeline);
  $('#psProvider').addEventListener('change', (e) => setProvider(e.target.value));
  $('#psRefreshBtn').addEventListener('click', async () => {
    state.date = $('#psDate').value || state.date;
    state.provider = $('#psProvider').value || state.provider;
    await Promise.all([loadSectors(), loadCandidates()]);
  });
  $('#psDate').addEventListener('change', (e) => {
    state.date = e.target.value;
  });

  $$('.ps-tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
  });

  $('#psDetailLoadBtn').addEventListener('click', () => {
    const code = $('#psDetailCode').value.trim();
    if (!code) return;
    loadDetail(code);
  });

  $('#psHistLoadBtn').addEventListener('click', loadHistory);
});
