/* ===== 龙江投资模块 ===== */
(function () {
  'use strict';

  // ---- 常量 ----
  const LEVEL_COLORS = {
    high:    { bg: '#dcfce7', text: '#166534', label: '高景气' },
    medium:  { bg: '#f0fdf4', text: '#166534', label: '景气中' },
    weak:    { bg: '#fefce8', text: '#854d0e', label: '景气弱' },
    low:     { bg: '#fff1f2', text: '#9f1239', label: '低景气' },
    unknown: { bg: '#f9fafb', text: '#9ca3af', label: '—' },
  };

  const POOL_TYPE_LABELS = { quality: '质量优选', tech_vc: '科技风投' };
  const STATUS_LABELS = { watching: '观察中', holding: '持仓中', exited: '已离场' };

  // ---- 模块初始化 ----
  function init() {
    initTabs();
    initProsperity();
    initHeatmap();
    initPool();
    initValuation();
    initPoolModal();
  }

  // ===== Sub-tabs =====
  function initTabs() {
    document.querySelectorAll('.invest-tab').forEach(btn => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.invest-tab').forEach(b => b.classList.remove('active'));
        document.querySelectorAll('.invest-panel').forEach(p => p.classList.remove('active'));
        btn.classList.add('active');
        const panelId = btn.dataset.panel;
        document.getElementById(panelId)?.classList.add('active');
      });
    });
  }

  // ===== 景气度扫描 =====
  function initProsperity() {
    const queryBtn = document.getElementById('prosperityQueryBtn');
    const input = document.getElementById('prosperityInput');
    if (!queryBtn || !input) return;

    queryBtn.addEventListener('click', () => fetchProsperity(input.value.trim(), 8));
    input.addEventListener('keydown', e => { if (e.key === 'Enter') queryBtn.click(); });
  }

  async function fetchProsperity(keywords, quarters) {
    if (!keywords) return;
    const resultEl = document.getElementById('prosperityResult');
    resultEl.innerHTML = '<div style="text-align:center;padding:32px;color:#9ca3af">加载中...</div>';

    try {
      const res = await fetch(`/api/invest/prosperity?keywords=${encodeURIComponent(keywords)}&quarters=${quarters}`);
      const data = await res.json();
      renderProsperity(data);
    } catch (e) {
      resultEl.innerHTML = `<div style="color:#dc2626;padding:16px">请求失败：${e.message}</div>`;
    }
  }

  function renderProsperity(data) {
    const el = document.getElementById('prosperityResult');
    if (!data.stocks || data.stocks.length === 0) {
      el.innerHTML = '<div class="pool-empty">未找到相关股票数据</div>';
      return;
    }

    const axis = data.quarterAxis || [];
    let html = '';

    // 板块综合判断
    html += `<div class="sector-summary ${data.sectorLevel}">${data.sectorSummary}</div>`;

    // 未找到提示
    if (data.notFound?.length) {
      html += `<div style="font-size:13px;color:#f59e0b;margin-bottom:12px">未找到：${data.notFound.join('、')}</div>`;
    }

    // 多公司对比表（每列一个季度）
    html += '<div class="prosperity-table-wrap"><table class="prosperity-table">';

    // 表头
    html += '<thead><tr><th class="name-col">公司</th><th>指标</th>';
    axis.forEach(q => { html += `<th>${q}</th>`; });
    html += '</tr></thead><tbody>';

    data.stocks.forEach(stock => {
      // 建立季度索引
      const qMap = {};
      stock.quarters.forEach(q => { qMap[q.quarter] = q; });

      // 营收同比行
      html += `<tr>
        <td class="row-label" rowspan="2" style="font-weight:700;color:#111">${stock.stockName}<br><span style="font-size:11px;color:#9ca3af">${stock.stockCode}</span></td>
        <td class="row-label">营收同比</td>`;
      axis.forEach(q => {
        const cell = qMap[q];
        if (!cell) { html += '<td class="lvl-unknown">—</td>'; return; }
        const ry = cell.revenueYoy;
        const lvl = cell.revenueLevel || 'unknown';
        const turn = cell.revenueTurnaround;
        const valText = ry != null ? formatPct(ry) : '—';
        html += `<td class="lvl-${lvl}${turn ? ' turnaround-cell' : ''}">${valText}${turn ? '<span class="turnaround-star">★</span>' : ''}</td>`;
      });
      html += '</tr>';

      // 扣非同比行
      html += `<tr><td class="row-label">扣非同比</td>`;
      axis.forEach(q => {
        const cell = qMap[q];
        if (!cell) { html += '<td class="lvl-unknown">—</td>'; return; }
        const py = cell.deductedNetProfitYoy;
        const lvl = cell.profitLevel || 'unknown';
        const turn = cell.profitTurnaround;
        const valText = py != null ? formatPct(py) : '—';
        html += `<td class="lvl-${lvl}${turn ? ' turnaround-cell' : ''}">${valText}${turn ? '<span class="turnaround-star">★</span>' : ''}</td>`;
      });
      html += '</tr>';
    });

    html += '</tbody></table></div>';

    // 图例
    html += `<div style="display:flex;gap:16px;flex-wrap:wrap;font-size:12px;color:#6b7280;margin-top:8px;">
      <span><span style="display:inline-block;width:12px;height:12px;background:#dcfce7;border:1px solid #6ee7b7;border-radius:2px;margin-right:4px;vertical-align:middle"></span>高景气 ≥30%</span>
      <span><span style="display:inline-block;width:12px;height:12px;background:#f0fdf4;border:1px solid #bbf7d0;border-radius:2px;margin-right:4px;vertical-align:middle"></span>景气中 5%~30%</span>
      <span><span style="display:inline-block;width:12px;height:12px;background:#fefce8;border:1px solid #fde68a;border-radius:2px;margin-right:4px;vertical-align:middle"></span>景气弱 0~5%</span>
      <span><span style="display:inline-block;width:12px;height:12px;background:#fff1f2;border:1px solid #fecaca;border-radius:2px;margin-right:4px;vertical-align:middle"></span>低景气 &lt;0%</span>
      <span style="color:#f59e0b">★ 由负转正转折点</span>
    </div>`;

    el.innerHTML = html;
  }

  // ===== 16季度热力表 =====
  function initHeatmap() {
    const btn = document.getElementById('heatmapQueryBtn');
    const input = document.getElementById('heatmapInput');
    if (!btn || !input) return;

    btn.addEventListener('click', () => fetchHeatmap(input.value.trim()));
    input.addEventListener('keydown', e => { if (e.key === 'Enter') btn.click(); });
  }

  async function fetchHeatmap(keywords) {
    if (!keywords) return;
    const resultEl = document.getElementById('heatmapResult');
    resultEl.innerHTML = '<div style="text-align:center;padding:32px;color:#9ca3af">加载中...</div>';

    try {
      const res = await fetch(`/api/invest/prosperity?keywords=${encodeURIComponent(keywords)}&quarters=16`);
      const data = await res.json();
      renderHeatmap(data);
    } catch (e) {
      resultEl.innerHTML = `<div style="color:#dc2626;padding:16px">请求失败：${e.message}</div>`;
    }
  }

  function renderHeatmap(data) {
    const el = document.getElementById('heatmapResult');
    if (!data.stocks || data.stocks.length === 0) {
      el.innerHTML = '<div class="pool-empty">未找到相关股票数据</div>';
      return;
    }

    let html = '';
    if (data.notFound?.length) {
      html += `<div style="font-size:13px;color:#f59e0b;margin-bottom:12px">未找到：${data.notFound.join('、')}</div>`;
    }

    data.stocks.forEach(stock => {
      html += `<div class="heatmap-stock-title">${stock.stockName} (${stock.stockCode})</div>`;
      html += '<div class="prosperity-table-wrap"><table class="prosperity-table">';
      html += '<thead><tr><th style="min-width:80px">指标</th>';
      stock.quarters.forEach(q => { html += `<th>${q.quarter}</th>`; });
      html += '</tr></thead><tbody>';

      // 营收同比
      html += '<tr><td class="row-label">营收同比</td>';
      stock.quarters.forEach(q => {
        const lvl = q.revenueLevel || 'unknown';
        const turn = q.revenueTurnaround;
        const val = q.revenueYoy != null ? formatPct(q.revenueYoy) : '—';
        html += `<td class="lvl-${lvl}${turn ? ' turnaround-cell' : ''}">${val}${turn ? '<span class="turnaround-star">★</span>' : ''}</td>`;
      });
      html += '</tr>';

      // 扣非同比
      html += '<tr><td class="row-label">扣非同比</td>';
      stock.quarters.forEach(q => {
        const lvl = q.profitLevel || 'unknown';
        const turn = q.profitTurnaround;
        const val = q.deductedNetProfitYoy != null ? formatPct(q.deductedNetProfitYoy) : '—';
        html += `<td class="lvl-${lvl}${turn ? ' turnaround-cell' : ''}">${val}${turn ? '<span class="turnaround-star">★</span>' : ''}</td>`;
      });
      html += '</tr>';

      html += '</tbody></table></div>';
    });

    // 图例
    html += `<div style="display:flex;gap:16px;flex-wrap:wrap;font-size:12px;color:#6b7280;margin-top:8px;">
      <span><span style="display:inline-block;width:12px;height:12px;background:#dcfce7;border-radius:2px;margin-right:4px;vertical-align:middle"></span>高景气 ≥30%</span>
      <span><span style="display:inline-block;width:12px;height:12px;background:#f0fdf4;border-radius:2px;margin-right:4px;vertical-align:middle"></span>景气中 5%~30%</span>
      <span><span style="display:inline-block;width:12px;height:12px;background:#fefce8;border-radius:2px;margin-right:4px;vertical-align:middle"></span>景气弱 0~5%</span>
      <span><span style="display:inline-block;width:12px;height:12px;background:#fff1f2;border-radius:2px;margin-right:4px;vertical-align:middle"></span>低景气 &lt;0%</span>
      <span style="color:#f59e0b">★ 由负转正转折点</span>
    </div>`;

    el.innerHTML = html;
  }

  // ===== 股票池 =====
  let poolData = [];
  let poolFilter = 'all';

  function initPool() {
    loadPool();
    document.querySelectorAll('.pool-filter-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.pool-filter-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        poolFilter = btn.dataset.filter;
        renderPool();
      });
    });
  }

  async function loadPool() {
    try {
      const res = await fetch('/api/invest/pool');
      poolData = await res.json();
      renderPool();
    } catch (e) {
      document.getElementById('poolGrid').innerHTML =
        `<div class="pool-empty">加载失败：${e.message}</div>`;
    }
  }

  function renderPool() {
    const grid = document.getElementById('poolGrid');
    let items = poolData;
    if (poolFilter === 'quality') items = poolData.filter(i => i.poolType === 'quality');
    else if (poolFilter === 'tech_vc') items = poolData.filter(i => i.poolType === 'tech_vc');
    else if (poolFilter === 'holding') items = poolData.filter(i => i.status === 'holding');

    if (items.length === 0) {
      grid.innerHTML = '<div class="pool-empty">暂无股票，点击「加入股票池」添加你关注的标的</div>';
      return;
    }

    grid.innerHTML = items.map(item => {
      const lvlConf = LEVEL_COLORS[item.latestLevel] || LEVEL_COLORS.unknown;
      const ry = item.latestRevenueYoy != null ? formatPct(item.latestRevenueYoy) : '—';
      const py = item.latestProfitYoy != null ? formatPct(item.latestProfitYoy) : '—';

      return `<div class="pool-card">
        <div class="pool-card-header">
          <div>
            <div class="pool-stock-name">${item.stockName}</div>
            <div class="pool-stock-code">${item.stockCode}</div>
          </div>
          <div class="pool-badges">
            <span class="badge badge-${item.poolType === 'quality' ? 'quality' : 'tech'}">${item.poolTypeLabel}</span>
            <span class="badge badge-${item.status}">${item.statusLabel}</span>
          </div>
        </div>
        <div class="pool-prosperity">
          <span class="prosperity-dot ${item.latestLevel}"></span>
          <span>景气：${lvlConf.label}</span>
          <span style="margin-left:8px;color:#d1d5db">|</span>
          <span>营收同比 <b>${ry}</b></span>
          <span style="margin-left:6px">扣非同比 <b>${py}</b></span>
        </div>
        ${item.memo ? `<div class="pool-memo">${escHtml(item.memo)}</div>` : ''}
        <div class="pool-card-footer">
          <div class="pool-target">
            ${item.targetPrice != null ? `目标价：<span>¥${item.targetPrice}</span>` : '<span style="color:#d1d5db">未设目标价</span>'}
          </div>
          <div class="pool-actions">
            <button class="pool-action-btn" onclick="Invest.openEditModal(${item.id})">编辑</button>
            <button class="pool-action-btn danger" onclick="Invest.removePool(${item.id}, '${escHtml(item.stockName)}')">移除</button>
          </div>
        </div>
      </div>`;
    }).join('');
  }

  async function removePool(id, name) {
    if (!confirm(`确认从股票池移除「${name}」？`)) return;
    try {
      await fetch(`/api/invest/pool/${id}`, { method: 'DELETE' });
      await loadPool();
    } catch (e) {
      alert('移除失败：' + e.message);
    }
  }

  // ===== 加入/编辑股票池弹窗 =====
  let editingPoolId = null;

  function initPoolModal() {
    document.getElementById('addPoolBtn')?.addEventListener('click', openAddModal);
    document.getElementById('investModalClose')?.addEventListener('click', closeModal);
    document.getElementById('investModalCancel')?.addEventListener('click', closeModal);
    document.getElementById('investModalSave')?.addEventListener('click', savePool);
    document.getElementById('investModalMask')?.addEventListener('click', e => {
      if (e.target === e.currentTarget) closeModal();
    });
  }

  function openAddModal() {
    editingPoolId = null;
    document.getElementById('investModalTitle').textContent = '加入股票池';
    document.getElementById('modalKeyword').value = '';
    document.getElementById('modalKeyword').disabled = false;
    document.getElementById('modalPoolType').value = 'quality';
    document.getElementById('modalStatus').value = 'watching';
    document.getElementById('modalTargetPrice').value = '';
    document.getElementById('modalMemo').value = '';
    document.getElementById('investModalMask').classList.remove('hidden');
  }

  function openEditModal(id) {
    const item = poolData.find(i => i.id === id);
    if (!item) return;
    editingPoolId = id;
    document.getElementById('investModalTitle').textContent = '编辑股票池条目';
    document.getElementById('modalKeyword').value = item.stockName;
    document.getElementById('modalKeyword').disabled = true;
    document.getElementById('modalPoolType').value = item.poolType;
    document.getElementById('modalStatus').value = item.status;
    document.getElementById('modalTargetPrice').value = item.targetPrice != null ? item.targetPrice : '';
    document.getElementById('modalMemo').value = item.memo || '';
    document.getElementById('investModalMask').classList.remove('hidden');
  }

  function closeModal() {
    document.getElementById('investModalMask').classList.add('hidden');
  }

  async function savePool() {
    const keyword = document.getElementById('modalKeyword').value.trim();
    const poolType = document.getElementById('modalPoolType').value;
    const status = document.getElementById('modalStatus').value;
    const targetPriceVal = document.getElementById('modalTargetPrice').value.trim();
    const memo = document.getElementById('modalMemo').value.trim();

    if (!editingPoolId && !keyword) {
      alert('请输入股票名称或代码');
      return;
    }

    const body = {
      keyword,
      poolType,
      status,
      memo: memo || null,
      targetPrice: targetPriceVal ? parseFloat(targetPriceVal) : null,
    };

    const saveBtn = document.getElementById('investModalSave');
    saveBtn.disabled = true;
    saveBtn.textContent = '保存中...';

    try {
      if (editingPoolId) {
        await fetch(`/api/invest/pool/${editingPoolId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
      } else {
        const res = await fetch('/api/invest/pool', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
        if (!res.ok) {
          const err = await res.json();
          throw new Error(err.message || '添加失败');
        }
      }
      closeModal();
      await loadPool();
    } catch (e) {
      alert(e.message);
    } finally {
      saveBtn.disabled = false;
      saveBtn.textContent = '保存';
    }
  }

  // ===== 估值计算器 =====
  function initValuation() {
    // PE匹配法
    const peBtn = document.getElementById('peCalcBtn');
    if (peBtn) {
      peBtn.addEventListener('click', calcPE);
      ['peInput', 'growthInput'].forEach(id => {
        document.getElementById(id)?.addEventListener('input', calcPE);
      });
    }

    // 10倍PS法
    const psBtn = document.getElementById('psCalcBtn');
    if (psBtn) {
      psBtn.addEventListener('click', calcPS);
      ['psMarketCap', 'psRevY0', 'psRevY1', 'psRevY2'].forEach(id => {
        document.getElementById(id)?.addEventListener('input', calcPS);
      });
    }
  }

  function calcPE() {
    const pe = parseFloat(document.getElementById('peInput')?.value);
    const growth = parseFloat(document.getElementById('growthInput')?.value);
    const result = document.getElementById('peResult');
    if (!result) return;
    if (isNaN(pe) || isNaN(growth) || pe <= 0 || growth <= 0) {
      result.className = 'valuation-result empty';
      result.textContent = '请输入有效的 PE 和预期增长率';
      return;
    }
    const fairPE = growth * 2;
    if (pe <= fairPE) {
      result.className = 'valuation-result ok';
      result.innerHTML = `✓ PE 合理 — 当前 PE ${pe.toFixed(1)} ≤ 合理 PE ${fairPE.toFixed(1)}（增长率×2），可考虑布局`;
    } else if (pe <= growth * 3) {
      result.className = 'valuation-result warn';
      result.innerHTML = `⚠ PE 偏高 — 当前 PE ${pe.toFixed(1)} 超出合理区间（${fairPE.toFixed(1)}），需确认增长确定性`;
    } else {
      result.className = 'valuation-result bad';
      result.innerHTML = `✗ PE 过高，谨慎 — 当前 PE ${pe.toFixed(1)} 远超合理 PE ${fairPE.toFixed(1)}，存在估值泡沫`;
    }
  }

  function calcPS() {
    const mc = parseFloat(document.getElementById('psMarketCap')?.value);
    const r0 = parseFloat(document.getElementById('psRevY0')?.value);
    const r1 = parseFloat(document.getElementById('psRevY1')?.value);
    const r2 = parseFloat(document.getElementById('psRevY2')?.value);
    const result = document.getElementById('psResult');
    if (!result) return;

    if (isNaN(mc) || isNaN(r0) || mc <= 0 || r0 <= 0) {
      result.innerHTML = '<div style="color:#9ca3af;font-size:13px;padding:8px 0">请输入有效的市值和营收数据</div>';
      return;
    }

    const rows = [
      { label: '今年', rev: r0 },
      ...(isNaN(r1) || r1 <= 0 ? [] : [{ label: '明年', rev: r1 }]),
      ...(isNaN(r2) || r2 <= 0 ? [] : [{ label: '后年', rev: r2 }]),
    ];

    let html = '<table class="ps-table"><thead><tr><th>对应营收年份</th><th>营收（亿）</th><th>对应PS倍数</th><th>合理市值（亿）</th><th>判断</th></tr></thead><tbody>';
    rows.forEach(row => {
      const ps = mc / row.rev;
      const fairMc = row.rev * 10;
      let cls, label;
      if (ps < 5) { cls = 'ps-ok'; label = '✓ 低估'; }
      else if (ps <= 10) { cls = 'ps-warn'; label = '⚠ 合理'; }
      else { cls = 'ps-bad'; label = '✗ 高估'; }
      html += `<tr>
        <td>${row.label}</td>
        <td>${row.rev.toFixed(2)}</td>
        <td class="${cls}" style="font-weight:700">${ps.toFixed(1)} 倍</td>
        <td>${fairMc.toFixed(1)}</td>
        <td class="${cls}" style="font-weight:700">${label}</td>
      </tr>`;
    });
    html += '</tbody></table>';
    result.innerHTML = html;
  }

  // ===== 工具函数 =====
  function formatPct(val) {
    if (val == null) return '—';
    const n = parseFloat(val);
    return (n >= 0 ? '+' : '') + n.toFixed(1) + '%';
  }

  function escHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  // ===== 公开接口（供 HTML 内联事件调用）=====
  window.Invest = {
    openEditModal,
    removePool,
  };

  // ---- 等 DOM 就绪后初始化 ----
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
