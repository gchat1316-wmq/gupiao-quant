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
    initSop();
    initProsperity();
    initHeatmap();
    initPool();
    initValuation();
    initPoolModal();
  }

  // ===== 实战选股 SOP =====
  // stocks 字段用 6 位代码（供 API），names 与 stocks 顺序对应（供展示）
  const SOP_TRACKS = [
    { name: 'AI 算力',    stocks: '688256,603659,688041,601138', names: '寒武纪,海光信息,中科曙光,工业富联' },
    { name: '半导体设备', stocks: '002371,688012,300604,688236', names: '北方华创,中微公司,长川科技,拓荆科技' },
    { name: '光伏储能',   stocks: '300274,300750,300014,605117', names: '阳光电源,宁德时代,亿纬锂能,德业股份' },
    { name: '创新药',     stocks: '600276,688180,688069,603259', names: '恒瑞医药,君实生物,热景生物,药明康德' },
    { name: '机器人',     stocks: '300124,002747,301300,603442', names: '汇川技术,埃斯顿,绿的谐波,鸣志电器' },
    { name: '新能源车',   stocks: '002594,300750,601127,600519', names: '比亚迪,宁德时代,赛力斯,贵州茅台' },
    { name: '军工',       stocks: '600760,688122,002179,600893', names: '中航沈飞,汉光科技,中航光电,航发动力' },
    { name: '消费白马',   stocks: '600519,000858,000568,603288', names: '贵州茅台,五粮液,泸州老窖,海天味业' },
  ];

  const SOP_5A_DIMS = [
    { key: 'a1', title: 'A1 · 行业地位',  hint: '5分=赛道唯一龙头；3分=前三；1分=跟随者' },
    { key: 'a2', title: 'A2 · 业务唯一性', hint: '5分=不可替代/独家牌照；3分=有差异；1分=同质化' },
    { key: 'a3', title: 'A3 · 客户粘性',   hint: '5分=长合同/高切换成本；3分=中等；1分=低粘性' },
    { key: 'a4', title: 'A4 · 护城河',     hint: '5分=专利/工艺/品牌强壁垒；3分=部分壁垒；1分=易复制' },
    { key: 'a5', title: 'A5 · 替代难度',   hint: '5分=对手追赶需 3 年+；3分=1-2 年；1分=随时被替代' },
  ];

  let sop5aScores = { a1: 0, a2: 0, a3: 0, a4: 0, a5: 0 };

  function initSop() {
    initSopTracks();
    initSop5a();
    initSopCheckup();
  }

  function initSopTracks() {
    const chipBox = document.getElementById('sopTrackChips');
    if (!chipBox) return;
    chipBox.innerHTML = SOP_TRACKS.map(t => {
      const codes = t.stocks.split(',');
      const names = t.names.split(',');
      const labels = codes.map((c, i) => `${c}(${names[i] || ''})`).join('、');
      return `<button class="sop-track-chip" data-stocks="${escHtml(t.stocks)}">
         <span class="sop-track-name">${t.name}</span>
         <span class="sop-track-stocks">${labels}</span>
         <span class="sop-track-arrow">→ 扫描</span>
       </button>`;
    }).join('');
    chipBox.querySelectorAll('.sop-track-chip').forEach(btn => {
      btn.addEventListener('click', () => {
        const stocks = btn.dataset.stocks;
        switchToProsperity(stocks);
      });
    });

    const customBtn = document.getElementById('sopCustomTrackBtn');
    const customInput = document.getElementById('sopCustomTrackInput');
    if (customBtn && customInput) {
      const run = () => {
        const v = customInput.value.trim();
        if (!v) return;
        switchToProsperity(v);
      };
      customBtn.addEventListener('click', run);
      customInput.addEventListener('keydown', e => { if (e.key === 'Enter') run(); });
    }
  }

  function switchToProsperity(keywords) {
    document.querySelectorAll('.invest-tab').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.invest-panel').forEach(p => p.classList.remove('active'));
    document.querySelector('.invest-tab[data-panel="panel-prosperity"]')?.classList.add('active');
    document.getElementById('panel-prosperity')?.classList.add('active');
    const input = document.getElementById('prosperityInput');
    if (input) input.value = keywords;
    fetchProsperity(keywords, 8);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function initSop5a() {
    const box = document.getElementById('sop5aDimensions');
    if (!box) return;
    box.innerHTML = SOP_5A_DIMS.map(dim => `
      <div class="sop-5a-dim">
        <div class="sop-5a-dim-head">
          <span class="sop-5a-dim-title">${dim.title}</span>
          <span class="sop-5a-dim-score" id="score-${dim.key}">0</span>
        </div>
        <div class="sop-5a-dim-hint">${dim.hint}</div>
        <div class="sop-5a-dots" data-key="${dim.key}">
          ${[1,2,3,4,5].map(n => `<button class="sop-5a-dot" data-val="${n}" type="button">${n}</button>`).join('')}
        </div>
      </div>
    `).join('');

    box.querySelectorAll('.sop-5a-dots').forEach(row => {
      const key = row.dataset.key;
      row.querySelectorAll('.sop-5a-dot').forEach(dot => {
        dot.addEventListener('click', () => {
          const val = parseInt(dot.dataset.val, 10);
          sop5aScores[key] = val;
          row.querySelectorAll('.sop-5a-dot').forEach(d => {
            d.classList.toggle('active', parseInt(d.dataset.val, 10) <= val);
          });
          document.getElementById(`score-${key}`).textContent = val;
          updateSop5aSummary();
        });
      });
    });
    updateSop5aSummary();

    document.getElementById('sop5aSaveBtn')?.addEventListener('click', saveSop5aToPool);
  }

  function updateSop5aSummary() {
    const total = Object.values(sop5aScores).reduce((a, b) => a + b, 0);
    document.getElementById('sop5aTotal').textContent = total;
    let stars, label, cls;
    if (total >= 22)      { stars = '★★★★★'; label = '极为稀缺，重点跟踪'; cls = 'pass'; }
    else if (total >= 17) { stars = '★★★★☆'; label = '稀缺，可纳入候选';   cls = 'pass'; }
    else if (total >= 12) { stars = '★★★☆☆'; label = '一般，需对比同行';   cls = 'warn'; }
    else if (total > 0)   { stars = '★★☆☆☆'; label = '稀缺性不足，建议放弃'; cls = 'fail'; }
    else                  { stars = '☆☆☆☆☆'; label = '请先为各维度打分';     cls = '';     }
    document.getElementById('sop5aStars').textContent = stars;
    const verdict = document.getElementById('sop5aVerdict');
    verdict.textContent = label;
    verdict.className = 'sop-5a-verdict' + (cls ? ' ' + cls : '');
  }

  async function saveSop5aToPool() {
    const kw = document.getElementById('sop5aKeyword').value.trim();
    const total = Object.values(sop5aScores).reduce((a, b) => a + b, 0);
    if (!kw) { alert('请输入股票名称或代码'); return; }
    if (total === 0) { alert('请先为 5 个维度打分'); return; }

    const memo = `5A 稀缺度评分：${total}/25\n`
      + SOP_5A_DIMS.map(d => `${d.title}：${sop5aScores[d.key]}/5`).join('\n');

    const btn = document.getElementById('sop5aSaveBtn');
    btn.disabled = true;
    btn.textContent = '保存中...';
    try {
      const res = await fetch('/api/invest/pool', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          keyword: kw,
          poolType: total >= 17 ? 'tech_vc' : 'quality',
          status: 'watching',
          memo,
          targetPrice: null,
        }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || '保存失败');
      }
      alert(`已加入股票池：${kw}（${total}/25）`);
      await loadPool();
    } catch (e) {
      alert(e.message);
    } finally {
      btn.disabled = false;
      btn.textContent = '加入股票池';
    }
  }

  function initSopCheckup() {
    const btn = document.getElementById('sopCheckupBtn');
    const input = document.getElementById('sopCheckupInput');
    if (!btn || !input) return;
    btn.addEventListener('click', () => fetchSopCheckup(input.value.trim()));
    input.addEventListener('keydown', e => { if (e.key === 'Enter') btn.click(); });
  }

  async function fetchSopCheckup(keyword) {
    if (!keyword) return;
    const el = document.getElementById('sopCheckupResult');
    el.innerHTML = '<div style="text-align:center;padding:24px;color:#9ca3af">体检中...</div>';
    try {
      const res = await fetch(`/api/invest/sop/checkup?keyword=${encodeURIComponent(keyword)}`);
      const data = await res.json();
      renderSopCheckup(data);
    } catch (e) {
      el.innerHTML = `<div style="color:#dc2626;padding:16px">请求失败：${e.message}</div>`;
    }
  }

  function renderSopCheckup(data) {
    const el = document.getElementById('sopCheckupResult');
    if (!data.matched) {
      el.innerHTML = `<div class="sop-checkup-empty">${data.message || '未找到该股票'}</div>`;
      return;
    }
    const overallCls = `sop-verdict-${data.overallVerdict}`;
    let html = `
      <div class="sop-checkup-header">
        <div class="sop-checkup-stock">${data.stockName} <span class="sop-checkup-code">${data.stockCode}</span></div>
        <div class="sop-checkup-overall ${overallCls}">${verdictLabel(data.overallVerdict)} · ${data.overallSummary}</div>
      </div>
      <div class="sop-checkup-grid">
        ${renderCheckupCard(data.grossMargin)}
        ${renderCheckupCard(data.revenueYoy)}
        ${renderCheckupCard(data.profitYoy)}
      </div>
      <div class="sop-checkup-jump">
        <button class="invest-btn-outline" id="sopJumpHeatmap">跳到 16 季度热力表查看完整</button>
      </div>
    `;
    el.innerHTML = html;
    document.getElementById('sopJumpHeatmap')?.addEventListener('click', () => {
      document.querySelectorAll('.invest-tab').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.invest-panel').forEach(p => p.classList.remove('active'));
      document.querySelector('.invest-tab[data-panel="panel-heatmap"]')?.classList.add('active');
      document.getElementById('panel-heatmap')?.classList.add('active');
      const input = document.getElementById('heatmapInput');
      if (input) input.value = data.stockName;
      fetchHeatmap(data.stockName);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  function renderCheckupCard(metric) {
    if (!metric) return '';
    const cls = `sop-verdict-${metric.verdict}`;
    const latest = metric.latest != null
      ? (parseFloat(metric.latest) >= 0 ? '+' : '') + parseFloat(metric.latest).toFixed(1) + metric.unit
      : '—';
    return `
      <div class="sop-checkup-card">
        <div class="sop-checkup-card-head">
          <span class="sop-checkup-label">${metric.label}</span>
          <span class="sop-checkup-verdict ${cls}">${verdictLabel(metric.verdict)}</span>
        </div>
        <div class="sop-checkup-latest">最新：<b>${latest}</b></div>
        ${renderSparkline(metric.series)}
        <div class="sop-checkup-tip">${escHtml(metric.tip || '')}</div>
      </div>
    `;
  }

  function renderSparkline(series) {
    if (!series || series.length === 0) {
      return '<div class="sop-spark-empty">无数据</div>';
    }
    const vals = series.map(s => s.value != null ? parseFloat(s.value) : null);
    const validVals = vals.filter(v => v != null);
    if (validVals.length === 0) return '<div class="sop-spark-empty">无数据</div>';
    const min = Math.min(0, ...validVals);
    const max = Math.max(0, ...validVals);
    const range = max - min || 1;
    const w = 240, h = 60, pad = 4;
    const stepX = (w - pad * 2) / Math.max(series.length - 1, 1);
    const zeroY = h - pad - (0 - min) / range * (h - pad * 2);

    const points = vals.map((v, i) => {
      if (v == null) return null;
      const x = pad + i * stepX;
      const y = h - pad - (v - min) / range * (h - pad * 2);
      return { x, y, v };
    });
    const pathPoints = points.filter(p => p);
    const linePath = pathPoints.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');

    const bars = points.map(p => {
      if (!p) return '';
      const positive = p.v >= 0;
      const top = positive ? p.y : zeroY;
      const height = Math.max(1, Math.abs(p.y - zeroY));
      const color = positive ? '#52c41a' : '#ff4d4f';
      return `<rect x="${(p.x - 5).toFixed(1)}" y="${top.toFixed(1)}" width="10" height="${height.toFixed(1)}" fill="${color}" opacity="0.25" />`;
    }).join('');

    const labels = series.map((s, i) => {
      const x = pad + i * stepX;
      return `<text x="${x.toFixed(1)}" y="${h - 0.5}" text-anchor="middle" font-size="8" fill="#9ca3af">${s.quarter}</text>`;
    }).join('');

    return `<svg class="sop-spark" viewBox="0 0 ${w} ${h + 10}" preserveAspectRatio="none">
      <line x1="${pad}" y1="${zeroY.toFixed(1)}" x2="${w - pad}" y2="${zeroY.toFixed(1)}" stroke="#e5e7eb" stroke-dasharray="2,2" />
      ${bars}
      <path d="${linePath}" fill="none" stroke="#d4a017" stroke-width="1.5" />
      ${pathPoints.map(p => `<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="2" fill="#d4a017" />`).join('')}
      ${labels}
    </svg>`;
  }

  function verdictLabel(v) {
    if (v === 'pass') return '✓ PASS';
    if (v === 'warn') return '⚠ WARN';
    if (v === 'fail') return '✗ FAIL';
    return '—';
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
