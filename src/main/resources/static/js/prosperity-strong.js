/**
 * 强势股选股前端 (Phase 1 MVP)
 *
 * API base: /gp/api/prosperity-strong/*
 */
const BASE = '/gp/api/prosperity-strong';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

const state = {
  date: '',
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
    if (s.latestSnapDate) {
      $('#psDate').value = s.latestSnapDate;
      state.date = s.latestSnapDate;
      setStatus(`上次执行: ${s.latestSnapDate}`);
    } else {
      $('#psDate').value = s.now;
      state.date = s.now;
      setStatus('尚无数据,可点击"手动触发"');
    }
  } catch (e) {
    setStatus('状态加载失败', 'err');
  }
}

async function loadSectors() {
  const list = await apiGet(`${BASE}/sectors?date=${state.date}`);
  state.sectors = list;
  renderSectors(list);
}

function renderSectors(list) {
  const wrap = $('#psSectorList');
  if (!list || !list.length) {
    wrap.innerHTML = '<div class="ps-empty">当日无板块数据,请先手动触发。</div>';
    return;
  }
  wrap.innerHTML = list.map(s => `
    <div class="ps-sector-card">
      <div>
        <span class="ps-sector-rank">#${s.rankNo}</span>
        <span class="ps-sector-name">${s.sectorName}</span>
      </div>
      <div class="ps-sector-metrics">
        <span>评分 <strong>${fmtNum(s.score)}</strong></span>
        <span>当日 <strong>${fmtPct(s.change1d)}</strong></span>
        <span>5日 <strong>${fmtPct(s.change5d)}</strong></span>
        <span>5日资金流 <strong>${fmtYi(s.capitalInflow5d)}</strong></span>
      </div>
      ${s.aiNarrative ? `<div class="ps-sector-narrative">${escapeHtml(s.aiNarrative)}</div>` : ''}
    </div>
  `).join('');
}

async function loadCandidates() {
  const list = await apiGet(`${BASE}/candidates?date=${state.date}`);
  state.candidates = list;
  renderCandidates(list);
}

function renderCandidates(list) {
  const wrap = $('#psCandidateTable');
  if (!list || !list.length) {
    wrap.innerHTML = '<div class="ps-empty">当日无候选,请先运行流水线。</div>';
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
        </div>
        <button class="invest-btn-outline" onclick="psPromote('${p.stockCode}')">一键入池</button>
      </div>
      <div class="ps-position-grid">
        ${posCell('左侧建仓', p.buyLeftPrice)}
        ${posCell('右侧确认', p.buyRightPrice)}
        ${posCell('第一目标价', p.sellTarget1)}
        ${posCell('第二目标价', p.sellTarget2)}
        ${posCell('止损价', p.stopLossPrice)}
        ${posCell('保守估值', p.priceLow)}
        ${posCell('中性估值', p.priceMid)}
        ${posCell('乐观估值', p.priceHigh)}
        ${posCell('核心仓位', p.corePositionPct, '%')}
        ${posCell('战术仓位', p.tacticalPositionPct, '%')}
        ${posCell('操作信号', signalLabel(p.actionSignal), '')}
      </div>
      <div class="ps-position-tip">
        分批建仓建议: 综合分 ≥85 建仓 50% / +30% / +20%; 70~84 建仓 40%/40%/20%; 60~69 建仓 30%/40%/30%.
        单股最大 10%, 单板块最大 30%, 总仓位 ≤80%.
      </div>
    </div>
    ${p.aiReport ? `
      <div class="ps-position-card">
        <h3 style="margin:0 0 8px">AI 深度报告</h3>
        <pre style="white-space:pre-wrap;font-size:12px;color:#444;background:#fafafa;padding:10px;border-radius:6px;max-height:600px;overflow:auto;">${escapeHtml(JSON.stringify(p.aiReport, null, 2))}</pre>
      </div>
    ` : '<div class="ps-empty">AI 深度报告将在 Phase 2 接入</div>'}
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
  if (!confirm('确认手动触发流水线? 可能耗时 1-3 分钟。')) return;
  setStatus('正在执行流水线...', 'busy');
  try {
    const r = await apiPost(`${BASE}/run?date=${state.date}`);
    setStatus(r.message || '完成');
    await Promise.all([loadSectors(), loadCandidates()]);
  } catch (e) {
    setStatus('流水线失败: ' + e.message, 'err');
  }
}

function switchTab(name) {
  $$('.ps-tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
  $$('.ps-panel').forEach(p => p.classList.toggle('hidden', p.id !== `psPanel${cap(name)}`));
}
function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }

function escapeHtml(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
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
  await loadStatus();
  await loadSectors().catch(()=>{});
  await loadCandidates().catch(()=>{});

  $('#psRunBtn').addEventListener('click', runPipeline);
  $('#psRefreshBtn').addEventListener('click', async () => {
    state.date = $('#psDate').value || state.date;
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
