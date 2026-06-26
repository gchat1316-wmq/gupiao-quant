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
  pool: [],
  poolCodes: new Set(),
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
  if (!r.ok) throw new Error(await formatApiError(r));
  return r.json();
}
async function apiPost(url) {
  const r = await fetch(url, { method: 'POST' });
  if (!r.ok) throw new Error(await formatApiError(r));
  return r.json();
}

async function formatApiError(response) {
  const text = await response.text();
  const contentType = response.headers.get('content-type') || '';
  const isHtml = contentType.includes('text/html') || /^\s*<!doctype html/i.test(text) || /^\s*<html/i.test(text);

  if (response.status === 501 && isHtml) {
    return 'HTTP 501: 当前静态预览服务不支持接口请求。请使用 Spring Boot 后端地址访问页面后再触发流水线。';
  }
  if (response.status === 404 && isHtml) {
    return 'HTTP 404: 未找到后端接口。请确认页面运行在 Spring Boot 服务下。';
  }
  if (isHtml) {
    return `HTTP ${response.status}: 后端返回了 HTML 错误页，请检查服务地址或接口路径。`;
  }

  try {
    const data = JSON.parse(text);
    return `HTTP ${response.status}: ${data.message || data.error || text}`;
  } catch (e) {
    return `HTTP ${response.status}: ${text || response.statusText || '请求失败'}`;
  }
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
    setStatus('状态加载失败: ' + e.message, 'err');
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

  // 第一段: 横向对比表
  // 行为指标,列为板块
  const metricRows = [
    {
      key: 'score',
      label: '板块评分',
      hint: '0.4×涨幅 + 0.4×资金流 + 0.2×持续性',
      render: (s) => `<span class="ps-compare-score">${fmtNum(s.score)}</span>`,
    },
    {
      key: 'change1d',
      label: '当日涨幅',
      render: (s) => changeCell(s.change1d),
    },
    {
      key: 'change5d',
      label: '5日涨幅',
      render: (s) => changeCell(s.change5d),
    },
    {
      key: 'change20d',
      label: '20日涨幅',
      render: (s) => changeCell(s.change20d),
    },
    {
      key: 'capital',
      label: '5日资金流',
      render: (s) => `<span class="ps-compare-up">${fmtYi(s.capitalInflow5d)}</span>`,
    },
    {
      key: 'updown',
      label: '涨/跌',
      render: (s) => `<span class="ps-compare-up">${s.upCount ?? '--'}</span> / <span class="ps-compare-down">${s.downCount ?? '--'}</span>`,
    },
    {
      key: 'lead',
      label: '领涨股',
      render: (s) => `${escapeHtml(s.leadStock || '--')}${s.leadStockChange != null ? ' <span class="ps-compare-' + (Number(s.leadStockChange) >= 0 ? 'up' : 'down') + '">' + fmtSignedPct(s.leadStockChange) + '</span>' : ''}`,
    },
    {
      key: 'funnel',
      label: '漏斗',
      hint: '龙头筛 / 财务筛 / 主线筛 / 通过',
      render: (s, stats) => {
        const total = stats.total;
        if (!total) return '--';
        return `
          <div>
            <span class="ps-compare-funnel-mini">
              <i class="l" style="width:${pctOf(total, stats.leaderFiltered)}"></i>
              <i class="f" style="width:${pctOf(total, stats.financeFiltered)}"></i>
              <i class="m" style="width:${pctOf(total, stats.mainlineFiltered)}"></i>
              <i class="p" style="width:${pctOf(total, stats.passed)}"></i>
            </span>
            <span class="ps-compare-funnel-num">${stats.leaderFiltered} / ${stats.financeFiltered} / ${stats.mainlineFiltered} / <b>${stats.passed}</b></span>
          </div>
        `;
      },
    },
    {
      key: 'actions',
      label: '操作',
      render: (s, stats) => {
        if (!stats.total) return '<span class="ps-compare-funnel-num">无龙头池</span>';
        return `<button type="button" data-sector-idx="${s.__idx}">查看成分股过滤明细 (${stats.total} 只)</button>`;
      },
    },
  ];

  // 预先计算每个板块的 stats
  list.forEach((s, i) => {
    s.__idx = i;
    s.__stats = computeSectorStats(s);
  });

  const sectorHead = list.map(s => `
    <th>
      <span class="ps-compare-rank">#${s.rankNo}</span>
      <span class="ps-compare-name">${escapeHtml(s.sectorName)}</span>
    </th>
  `).join('');

  const sectorRows = metricRows.map(row => `
    <tr>
      <th scope="row">
        ${row.label}
        ${row.hint ? `<span class="ps-row-hint">${row.hint}</span>` : ''}
      </th>
      ${list.map(s => `<td class="${row.key === 'actions' ? 'ps-compare-actions' : ''}">${row.render(s, s.__stats)}</td>`).join('')}
    </tr>
  `).join('');

  const compareTable = `
    <div class="ps-compare-wrap">
      <table class="ps-compare-table">
        <thead><tr>
          <th scope="col" style="min-width:160px">板块</th>
          ${sectorHead}
        </tr></thead>
        <tbody>${sectorRows}</tbody>
      </table>
    </div>
  `;

  // 第二段: 每个板块的折叠详情(叙述 / 诊断 / 大漏斗)
  const details = list.map(s => {
    const stats = s.__stats;
    const hasLeaders = stats.total > 0;
    return `
    <details class="ps-sector-detail" data-sector-idx="${s.__idx}">
      <summary>#${s.rankNo} ${escapeHtml(s.sectorName)} · ${hasLeaders
        ? `入龙头池 ${stats.total} 只,通过 <b>${stats.passed}</b>`
        : `匹配 ${stats.matched} → 有行情 ${stats.quoted} → 入龙头池 0`}</summary>
      <div class="ps-sector-detail-body">
        <div class="ps-sector-detail-grid">
          <div>
            ${s.aiNarrative ? `<div class="ps-sector-narrative">${escapeHtml(s.aiNarrative)}</div>` : '<div class="ps-sector-narrative" style="border-left-color:#ccc;color:#999">暂无 AI 板块描述</div>'}
            ${s.diagnosticMessage ? `
              <div class="ps-sector-diagnostic">
                <b>过滤诊断:</b> ${escapeHtml(s.diagnosticMessage)}
              </div>
            ` : ''}
          </div>
          <div>
            ${hasLeaders ? renderFunnelBar(stats) : renderFunnelBarNoLeaders(stats)}
            ${hasLeaders ? `
              <div style="margin-top:10px;text-align:right">
                <button type="button" class="ps-btn-mini" data-sector-idx="${s.__idx}">查看成分股过滤明细 (${stats.total} 只)</button>
              </div>
            ` : ''}
          </div>
        </div>
      </div>
    </details>
    `;
  }).join('');

  wrap.innerHTML = compareTable + details;

  // 绑定查看成分股明细按钮
  wrap.querySelectorAll('[data-sector-idx]').forEach(btn => {
    btn.addEventListener('click', (ev) => {
      ev.stopPropagation();
      const idx = Number(btn.dataset.sectorIdx);
      const sector = list[idx];
      if (sector) openLeaderModal(sector);
    });
  });
}

function computeSectorStats(s) {
  const leaders = s.leaders || [];
  const passed = leaders.filter(l => l.finalStage === 'passed').length;
  const total = leaders.length;
  return {
    leaders,
    passed,
    total,
    leaderFiltered: leaders.filter(l => l.finalStage === 'leader_filter').length,
    financeFiltered: leaders.filter(l => l.finalStage === 'finance_filter').length,
    mainlineFiltered: leaders.filter(l => l.finalStage === 'mainline_filter').length,
    matched: Number(s.matchedMemberCount || 0),
    quoted: Number(s.quotedMemberCount || 0),
  };
}

function changeCell(v) {
  if (v == null) return '--';
  const n = Number(v);
  if (Number.isNaN(n)) return v;
  const cls = n >= 0 ? 'ps-compare-up' : 'ps-compare-down';
  return `<span class="${cls}">${fmtSignedPct(n)}</span>`;
}

function renderFunnelBar(stats) {
  const total = stats.total;
  return `
    <div class="ps-funnel">
      <div class="ps-funnel-bar">
        <div class="ps-funnel-seg ps-funnel-leader" style="width:${pctOf(total, stats.leaderFiltered)}" title="龙头筛剔除 ${stats.leaderFiltered} 只"></div>
        <div class="ps-funnel-seg ps-funnel-finance" style="width:${pctOf(total, stats.financeFiltered)}" title="财务筛剔除 ${stats.financeFiltered} 只"></div>
        <div class="ps-funnel-seg ps-funnel-mainline" style="width:${pctOf(total, stats.mainlineFiltered)}" title="主线筛剔除 ${stats.mainlineFiltered} 只"></div>
        <div class="ps-funnel-seg ps-funnel-passed" style="width:${pctOf(total, stats.passed)}" title="通过 ${stats.passed} 只"></div>
      </div>
      <div class="ps-funnel-legend">
        <span class="ps-legend ps-legend-leader">龙头筛剔除 ${stats.leaderFiltered}</span>
        <span class="ps-legend ps-legend-finance">财务筛剔除 ${stats.financeFiltered}</span>
        <span class="ps-legend ps-legend-mainline">主线筛剔除 ${stats.mainlineFiltered}</span>
        <span class="ps-legend ps-legend-passed">通过 ${stats.passed}</span>
      </div>
    </div>
  `;
}

function renderFunnelBarNoLeaders(stats) {
  const pipelineTotal = stats.quoted || stats.matched;
  if (!pipelineTotal) return '<div class="ps-compare-funnel-num">无成分股数据</div>';
  const missQuoted = Math.max(0, stats.matched - stats.quoted);
  return `
    <div class="ps-funnel">
      <div class="ps-funnel-bar">
        <div class="ps-funnel-seg ps-funnel-passed" style="width:${pctOf(pipelineTotal, stats.matched)}" title="匹配成分股 ${stats.matched} 只"></div>
        <div class="ps-funnel-seg ps-funnel-finance" style="width:${pctOf(pipelineTotal, missQuoted)}" title="缺日线行情 ${missQuoted} 只"></div>
      </div>
      <div class="ps-funnel-legend">
        <span class="ps-legend ps-legend-passed">匹配成分股 ${stats.matched}</span>
        <span class="ps-legend ps-legend-finance">缺日线行情 ${missQuoted}</span>
        <span class="ps-legend ps-legend-leader">入龙头池 0</span>
      </div>
    </div>
  `;
}

// ====== 成分股过滤明细 modal ======
function openLeaderModal(sector) {
  const modal = $('#psLeaderModal');
  if (!modal) return;
  const stats = computeSectorStats(sector);
  const leaders = stats.leaders;

  $('#psLeaderModalSectorName').textContent = `#${sector.rankNo} ${sector.sectorName}`;
  $('#psLeaderModalSectorMeta').textContent = `龙头池 ${stats.total} 只 · 通过 ${stats.passed} · 龙头/财务/主线 筛剔除 ${stats.leaderFiltered}/${stats.financeFiltered}/${stats.mainlineFiltered}`;

  const summary = $('#psLeaderModalSummary');
  summary.innerHTML = `
    <span class="ps-pill l">龙头筛剔除 <b>${stats.leaderFiltered}</b></span>
    <span class="ps-pill f">财务筛剔除 <b>${stats.financeFiltered}</b></span>
    <span class="ps-pill m">主线筛剔除 <b>${stats.mainlineFiltered}</b></span>
    <span class="ps-pill p">通过 <b>${stats.passed}</b></span>
  `;

  const wrap = $('#psLeaderModalTableWrap');
  if (!leaders.length) {
    wrap.innerHTML = '<div class="ps-empty">本板块无龙头池股票</div>';
  } else {
    wrap.innerHTML = renderLeaderModalTable(leaders);
  }
  modal.classList.add('open');
  modal.setAttribute('aria-hidden', 'false');
  document.body.style.overflow = 'hidden';
}

function closeLeaderModal() {
  const modal = $('#psLeaderModal');
  if (!modal) return;
  modal.classList.remove('open');
  modal.setAttribute('aria-hidden', 'true');
  document.body.style.overflow = '';
}

function renderLeaderModalTable(leaders) {
  const rows = leaders.map(l => {
    // 找最早失败的阶段 + reason
    let failedStage = null;
    let failedReason = '';
    if (l.filterPassed === false) {
      failedStage = 'leader';
      failedReason = l.filterReason || '未通过龙头快筛';
    } else if (l.financePassed === false) {
      failedStage = 'finance';
      failedReason = l.financeReason || '未通过财务硬筛';
    } else if (l.mainlinePassed === false) {
      failedStage = 'mainline';
      failedReason = l.mainlineReason || '未通过主线判定';
    }
    const rowCls = `row-${failedStage || 'passed'}`;
    const stagePillCls = failedStage || 'passed';
    const stagePillLabel = failedStage
      ? ({ leader: '龙头筛', finance: '财务筛', mainline: '主线筛' }[failedStage] + ' 剔除')
      : '全部通过';

    // Step2/3/4 各自结果
    const s2 = l.filterPassed === true
      ? `<span class="ps-check">✓</span> 龙头分 <b>${fmtNum(l.leaderScore)}</b>`
      : `<span class="ps-cross">✗</span> 龙头分 <b>${fmtNum(l.leaderScore)}</b><br><span class="ps-reason">${escapeHtml(l.filterReason || '未通过龙头快筛')}</span>`;
    const s3Score = fmtNum(l.financeScore);
    const s3 = l.financePassed === true
      ? `<span class="ps-check">✓</span> <b class="ps-score">${s3Score}</b>`
      : (l.financePassed === false
        ? `<span class="ps-cross">✗</span> <b class="ps-score">${s3Score}</b><br><span class="ps-reason">${escapeHtml(l.financeReason || '未通过财务硬筛')}</span>`
        : '--');
    const s4Score = fmtNum(l.mainlineScore);
    const s4 = l.mainlinePassed === true
      ? `<span class="ps-check">✓</span> <b class="ps-score">${s4Score}</b>`
      : (l.mainlinePassed === false
        ? `<span class="ps-cross">✗</span> <b class="ps-score">${s4Score}</b><br><span class="ps-reason">${escapeHtml(l.mainlineReason || '未通过主线判定')}</span>`
        : '--');

    // "过滤原因(完整)"列: 财务筛时展示6项指标明细,其他阶段显示原始 reason
    const reasonCell = (() => {
      if (failedStage === 'finance') {
        return renderFinanceDetailTable(l);
      }
      return `<span class="ps-reason ${failedStage ? '' : 'pass'}">${failedStage ? escapeHtml(failedReason) : '通过所有阶段,纳入候选'}</span>`;
    })();

    return `
      <tr class="${rowCls}">
        <td>${l.stockCode}</td>
        <td>${escapeHtml(l.stockName || '--')}</td>
        <td>${s2}</td>
        <td>${s3}</td>
        <td>${s4}</td>
        <td><span class="ps-pill-mini ${stagePillCls}">${stagePillLabel}</span></td>
        <td class="ps-reason-cell">${reasonCell}</td>
      </tr>
    `;
  }).join('');

  return `
    <table class="ps-leader-modal-table">
      <thead>
        <tr>
          <th>代码</th><th>名称</th>
          <th>Step2 龙头快筛</th>
          <th>Step3 财务硬筛</th>
          <th>Step4 主线判定</th>
          <th>过滤环节</th>
          <th>过滤原因(完整)</th>
        </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

/**
 * 渲染财务硬筛6项指标明细表(营收同比/扣非同比/毛利率/资产负债率/经营现金流/ROE)
 */
function renderFinanceDetailTable(l) {
  const fmt = v => (v == null || v === '') ? '<span style="color:#999">--</span>' : parseFloat(v).toFixed(2) + '%';
  const fmtY = v => {
    if (v == null || v === '') return '<span style="color:#999">--</span>';
    const y = parseFloat(v) / 1e8;
    return y.toFixed(2) + '亿';
  };
  const thresholds = {
    revenueYoyMin4q: { label: '营收同比近4季', thresh: '≥20%', fail: v => v != null && parseFloat(v) < 20 },
    deductedNetProfitYoyMin4q: { label: '扣非同比近4季', thresh: '≥0%', fail: v => v != null && parseFloat(v) < 0 },
    grossMarginAvg4q: { label: '毛利率均值', thresh: '≥25%', fail: v => v != null && parseFloat(v) < 25 },
    debtRatioLatest: { label: '资产负债率', thresh: '≤70%', fail: v => v != null && parseFloat(v) > 70 },
    operatingCashflowSum4q: { label: '近4季经营现金流', thresh: '>0', fail: v => v != null && parseFloat(v) <= 0, isYuan: true },
    roeLatest: { label: '最新ROE', thresh: '≥10%', fail: v => v != null && parseFloat(v) < 10 }
  };
  const rows = Object.entries(thresholds).map(([key, cfg]) => {
    const rawVal = l[key];
    const failed = cfg.fail(rawVal);
    const valStr = cfg.isYuan ? fmtY(rawVal) : fmt(rawVal);
    const cls = failed ? 'fin-row fail' : 'fin-row ok';
    const icon = failed ? '<span style="color:#e74c3c">✗</span>' : '<span style="color:#2ecc71">✓</span>';
    return `<tr class="${cls}"><td>${icon}</td><td>${cfg.label}</td><td>${valStr}</td><td>${cfg.thresh}</td></tr>`;
  }).join('');
  return `<table class="fin-detail-table" cellpadding="0" cellspacing="0">${rows}</table>`;
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
  const rows = list.map(p => {
    const inPool = state.poolCodes.has(p.stockCode);
    const action = inPool
      ? `<span class="ps-pool-badge" title="已加入热点股票池">已入池</span>
         <button class="ps-btn-mini" onclick="psShowDetail('${p.stockCode}')">详情</button>`
      : `<button class="ps-btn-mini" onclick="psShowDetail('${p.stockCode}')">详情</button>
         <button class="ps-btn-mini" onclick="psPromote('${p.stockCode}')">入池</button>`;
    return `
    <tr class="${inPool ? 'ps-pool-in-row' : ''}">
      <td><a href="javascript:psShowDetail('${p.stockCode}')">${p.stockCode}</a></td>
      <td>${p.stockName || '--'}</td>
      <td>${p.sectorName || '--'}</td>
      <td>${fmtMoney(p.latestPrice)}</td>
      <td>${fmtNum(p.financeScore)}</td>
      <td>${fmtNum(p.mainlineScore)}</td>
      <td class="score-cell">${fmtNum(p.combinedScore)}</td>
      <td>${fmtPct(p.revenueYoyMin3q)}</td>
      <td>${fmtMoney(p.buyLeftPrice)}</td>
      <td>${fmtMoney(p.sellTarget1)}</td>
      <td>${fmtMoney(p.stopLossPrice)}</td>
      <td>${fmtPct(p.corePositionPct)}</td>
      <td><span class="ps-signal ${p.actionSignal || ''}">${signalLabel(p.actionSignal)}</span></td>
      <td>${action}</td>
    </tr>`;
  }).join('');
  wrap.innerHTML = `
    <table class="ps-table">
      <thead><tr>
        <th>代码</th><th>名称</th><th>板块</th><th>现价</th>
        <th>财务分</th><th>主线分</th><th>综合分</th>
        <th>近3季度营收增速</th>
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
            近3季度营收增速 <b>${fmtPct(p.revenueYoyMin3q)}</b>
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
  if (!confirm(`确认将 ${code} 加入热点股票池?\n(独立于龙江投资股票池, 同一只票重复入池会自动累加计数)`)) return;
  try {
    const r = await apiPost(`${BASE}/promote/${encodeURIComponent(code)}?date=${state.date}`);
    alert(r.message || '已入池');
    // 刷新池子 set + 候选表格的"已入池"标记
    await loadPool();
    renderCandidates(state.candidates);
  } catch (e) {
    alert('入池失败: ' + e.message);
  }
}
window.psPromote = psPromote;

// ===== 热点股票池 =====
async function loadPool() {
  try {
    const list = await apiGet(`${BASE}/pool`);
    state.pool = list || [];
    state.poolCodes = new Set(state.pool.map(p => p.stockCode));
    renderPool();
  } catch (e) {
    state.pool = [];
    state.poolCodes = new Set();
    const wrap = $('#psPoolTable');
    if (wrap) wrap.innerHTML = '<div class="ps-empty">加载失败: ' + escapeHtml(e.message) + '</div>';
  }
}

function renderPool() {
  const wrap = $('#psPoolTable');
  if (!wrap) return;
  if (!state.pool.length) {
    wrap.innerHTML = '<div class="ps-empty">热点股票池为空, 去"龙头候选"标签页点"入池"吧。</div>';
    return;
  }
  const rows = state.pool.map(p => {
    const lastAdd = p.lastAddedAt ? new Date(p.lastAddedAt).toLocaleString('zh-CN') : '--';
    const memo = p.memo ? p.memo.replace(/\n/g, '<br/>') : '--';
    return `<tr>
      <td><a href="javascript:psShowDetail('${p.stockCode}')">${p.stockCode}</a></td>
      <td>${escapeHtml(p.stockName || '--')}</td>
      <td><span class="ps-signal ${(p.status || 'watching')}">${p.status || 'watching'}</span></td>
      <td class="score-cell">${p.poolCount ?? 1}</td>
      <td>${escapeHtml(p.sectorName || '--')}</td>
      <td>${fmtNum(p.combinedScore)}</td>
      <td>${fmtMoney(p.latestPrice)}</td>
      <td>${fmtMoney(p.buyLeftPrice)}</td>
      <td>${fmtMoney(p.sellTarget1)}</td>
      <td>${fmtMoney(p.stopLossPrice)}</td>
      <td>${p.lastSnapDate || '--'}</td>
      <td>${lastAdd}</td>
      <td><button class="ps-btn-mini" onclick="psTogglePoolMemo('${p.stockCode}')">查看 memo</button></td>
    </tr>
    <tr class="ps-pool-memo-row hidden" id="psPoolMemo_${p.stockCode}">
      <td colspan="13" class="ps-pool-memo">${memo}</td>
    </tr>`;
  }).join('');
  wrap.innerHTML = `
    <table class="ps-table">
      <thead><tr>
        <th>代码</th><th>名称</th><th>状态</th><th>入池次数</th><th>板块</th>
        <th>综合分</th><th>现价</th><th>建仓价</th><th>目标价</th><th>止损价</th>
        <th>最近入池</th><th>更新时间</th><th>memo</th>
      </tr></thead>
      <tbody>${rows}</tbody>
    </table>
    <div class="ps-pool-hint">共 ${state.pool.length} 只票,按最近入池时间倒序。点击代码跳转个股深度。</div>
  `;
}

function psTogglePoolMemo(code) {
  const row = document.getElementById('psPoolMemo_' + code);
  if (row) row.classList.toggle('hidden');
}
window.psTogglePoolMemo = psTogglePoolMemo;

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
  if (name === 'runhistory') {
    loadRunHistory();
  }
  if (name === 'pool') {
    loadPool();
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

// 历史执行
async function loadRunHistory() {
  const from = $('#psRunHistFrom').value;
  const to = $('#psRunHistTo').value;
  if (!from || !to) return;
  const wrap = $('#psRunHistoryTable');
  wrap.innerHTML = '<div class="ps-empty">加载中…</div>';
  try {
    const list = await apiGet(`${BASE}/runs?from=${from}&to=${to}`);
    if (!list.length) { wrap.innerHTML = '<div class="ps-empty">暂无执行记录</div>'; return; }
    const rows = list.map(r => {
      const dur = r.durationMs != null ? (r.durationMs / 1000).toFixed(0) + 's' : '--';
      const statusCls = { SUCCESS: 'success', PARTIAL: 'partial', FAILED: 'failed', BUSY: 'busy' }[r.status] || '';
      const statusLabel = { SUCCESS: '✓ 成功', PARTIAL: '⚠ 部分', FAILED: '✗ 失败', BUSY: '⚡ 进行中' }[r.status] || r.status || '--';
      const dateStr = r.startedAt ? new Date(r.startedAt).toLocaleString('zh-CN') : '--';
      return `<tr>
        <td>${r.snapDate || '--'}</td>
        <td>${dateStr}</td>
        <td><span class="ps-signal ${statusCls}">${statusLabel}</span></td>
        <td>${dur}</td>
        <td>${r.sectorCount ?? '--'}</td>
        <td>${r.leaderCount ?? '--'}</td>
        <td>${r.candidateCount ?? '--'}</td>
        <td>${escapeHtml(r.message || '--')}</td>
        <td><button class="invest-btn-outline ps-del-btn" data-snap="${r.snapDate || ''}">删除</button></td>
      </tr>`;
    }).join('');
    wrap.innerHTML = `<table class="ps-table">
      <thead><tr>
        <th>日期</th><th>时间</th><th>状态</th><th>耗时</th>
        <th>板块</th><th>龙头</th><th>候选</th><th>详情</th><th>操作</th>
      </tr></thead>
      <tbody>${rows}</tbody>
    </table>`;
    wrap.querySelectorAll('.ps-del-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        if (!confirm('确认删除该日期的执行数据？\n（板块+龙头候选+候选记录将被清除）')) return;
        try {
          await apiDelete(`${BASE}/runs/${btn.dataset.snap}`);
          loadRunHistory();
        } catch (e) { alert('删除失败: ' + e.message); }
      });
    });
  } catch (e) { wrap.innerHTML = '<div class="ps-empty">加载失败: ' + escapeHtml(e.message) + '</div>'; }
}

async function apiDelete(url) {
  const r = await fetch(url, { method: 'DELETE' });
  if (!r.ok) throw new Error(await formatApiError(r));
  return r.json();
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

  // 历史候选: 默认最近7天
  const histTo = todayLocalDate();
  const histFrom = (() => {
    const d = new Date(); d.setDate(d.getDate() - 6);
    return d.toISOString().slice(0, 10);
  })();
  if ($('#psHistFrom')) $('#psHistFrom').value = histFrom;
  if ($('#psHistTo'))   $('#psHistTo').value   = histTo;

  // 历史执行: 默认最近7天
  if ($('#psRunHistFrom')) $('#psRunHistFrom').value = histFrom;
  if ($('#psRunHistTo'))   $('#psRunHistTo').value   = histTo;

  $('#psRunBtn').addEventListener('click', runPipeline);
  $('#psProvider').addEventListener('change', (e) => setProvider(e.target.value));
  $('#psRefreshBtn').addEventListener('click', async () => {
    state.date = $('#psDate').value || state.date;
    state.provider = $('#psProvider').value || state.provider;
    await Promise.all([loadSectors(), loadCandidates(), loadPool()]);
    renderCandidates(state.candidates);
  });
  $('#psDate').addEventListener('change', (e) => {
    state.date = e.target.value;
  });

  $$('.ps-tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
  });

  // 成分股明细弹窗
  const modal = $('#psLeaderModal');
  const closeBtn = $('#psLeaderModalClose');
  if (closeBtn) closeBtn.addEventListener('click', closeLeaderModal);
  if (modal) {
    modal.addEventListener('click', (ev) => {
      if (ev.target === modal) closeLeaderModal();
    });
  }
  document.addEventListener('keydown', (ev) => {
    if (ev.key === 'Escape') closeLeaderModal();
  });

  await loadStatus();
  await loadProviders();
  await loadSectors().catch(()=>{});
  await loadCandidates().catch(()=>{});
  // 加载热点股票池, 让"已入池"徽标在候选表格立即可见
  await loadPool().catch(()=>{});
  renderCandidates(state.candidates);

  $('#psDetailLoadBtn').addEventListener('click', () => {
    const code = $('#psDetailCode').value.trim();
    if (!code) return;
    loadDetail(code);
  });

  $('#psHistLoadBtn').addEventListener('click', loadHistory);
  $('#psRunHistLoadBtn').addEventListener('click', loadRunHistory);
});
