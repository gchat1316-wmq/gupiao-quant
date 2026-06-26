/* ===== 龙江投资模块 ===== */
(function () {
  'use strict';

  // ---- 常量 ----
  const POOL_TYPE_LABELS = { quality: '质量优选', tech_vc: '科技风投' };
  const STATUS_LABELS = { watching: '观察中', holding: '持仓中', exited: '已离场' };

  // ---- 模块初始化 ----
  function init() {
    initTabs();
    initSop();
    initBigYang();
    initPool();
    initValuation();
    initPoolModal();
  }

  // ===== 实战选股 SOP =====
  const SOP_5A_DIMS = [
    { key: 'a1', title: 'A1 · 行业地位',  hint: '5分=赛道唯一龙头；3分=前三；1分=跟随者' },
    { key: 'a2', title: 'A2 · 业务唯一性', hint: '5分=不可替代/独家牌照；3分=有差异；1分=同质化' },
    { key: 'a3', title: 'A3 · 客户粘性',   hint: '5分=长合同/高切换成本；3分=中等；1分=低粘性' },
    { key: 'a4', title: 'A4 · 护城河',     hint: '5分=专利/工艺/品牌强壁垒；3分=部分壁垒；1分=易复制' },
    { key: 'a5', title: 'A5 · 替代难度',   hint: '5分=对手追赶需 3 年+；3分=1-2 年；1分=随时被替代' },
  ];

  let sop5aScores = { a1: 0, a2: 0, a3: 0, a4: 0, a5: 0 };

  function initSop() {
    initSop5a();
    initSopCheckup();
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
      const res = await fetch('api/invest/pool', {
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
      const res = await fetch(`api/invest/sop/checkup?keyword=${encodeURIComponent(keyword)}`);
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
    `;
    el.innerHTML = html;
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
      <path d="${linePath}" fill="none" stroke="#e1062c" stroke-width="1.5" />
      ${pathPoints.map(p => `<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="2" fill="#e1062c" />`).join('')}
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

  // ===== 股票池（列表化） =====
  let poolData = [];
  let poolFilter = 'all';
  let poolKeyword = '';
  let poolSearchCache = new Map();   // id -> { name, nameLow, initials, codeLow, searchKey }

  // 把股票名转成「拼音首字母缩写」。pinyinPro 未加载时降级为空字符串。
  function pinyinInitials(name) {
    if (!name) return '';
    if (typeof window.pinyinPro === 'undefined' || !window.pinyinPro.pinyin) return '';
    try {
      const out = window.pinyinPro.pinyin(name, { pattern: 'first', toneType: 'none' });
      return String(out || '').replace(/\s+/g, '').toLowerCase();
    } catch (e) {
      return '';
    }
  }

  function buildPoolSearchItem(item) {
    const name = item.stockName || '';
    const code = item.stockCode || '';
    const nameLow = name.toLowerCase();
    const codeLow = code.toLowerCase();
    const initials = pinyinInitials(name);
    return {
      name,
      code,
      nameLow,
      codeLow,
      initials,
      searchKey: `${nameLow} ${initials} ${codeLow}`,
    };
  }

  function ensurePoolSearchCache() {
    poolSearchCache.clear();
    poolData.forEach(item => {
      if (item && item.id != null) poolSearchCache.set(item.id, buildPoolSearchItem(item));
    });
  }

  function matchesPoolKeyword(item) {
    const kw = poolKeyword.trim().toLowerCase().replace(/\s+/g, '');
    if (!kw) return true;
    const cached = poolSearchCache.get(item.id);
    if (cached) return cached.searchKey.includes(kw);
    // 缓存丢失（极少见）时回退到简易匹配
    const name = (item.stockName || '').toLowerCase();
    const code = (item.stockCode || '').toLowerCase();
    return name.includes(kw) || code.includes(kw);
  }

  // ===== 大阳线战法 =====
  const BIG_YANG_API = 'api/invest/big-yang';
  let bigYangState = {
    loaded: false,
    summary: null,
    signals: [],
    alerts: []
  };

  function initBigYang() {
    loadBigYangSummary();
    document.querySelector('.invest-tab[data-panel="panel-big-yang"]')?.addEventListener('click', () => {
      if (!bigYangState.loaded) {
        loadBigYangPanel();
      }
    });
    document.getElementById('bigYangRunBtn')?.addEventListener('click', runBigYangScan);
    document.getElementById('bigYangAlertList')?.addEventListener('click', async (event) => {
      const btn = event.target.closest('[data-action="read-alert"]');
      if (!btn) return;
      await markBigYangAlertRead(btn.dataset.id);
    });
  }

  async function loadBigYangSummary() {
    try {
      bigYangState.summary = await fetchBigYangJson(`${BIG_YANG_API}/summary`);
      renderBigYangBadges();
      renderBigYangSummary();
    } catch (e) {
      // 静默，不影响主页面
    }
  }

  async function loadBigYangPanel() {
    const signalList = document.getElementById('bigYangSignalList');
    const alertList = document.getElementById('bigYangAlertList');
    if (signalList) signalList.innerHTML = '<div class="bigyang-empty">加载中...</div>';
    if (alertList) alertList.innerHTML = '<div class="bigyang-empty">加载中...</div>';
    try {
      const [summary, signals, alerts] = await Promise.all([
        fetchBigYangJson(`${BIG_YANG_API}/summary`),
        fetchBigYangJson(`${BIG_YANG_API}/signals`),
        fetchBigYangJson(`${BIG_YANG_API}/alerts`)
      ]);
      bigYangState.summary = summary;
      bigYangState.signals = signals || [];
      bigYangState.alerts = alerts || [];
      bigYangState.loaded = true;
      renderBigYang();
    } catch (e) {
      if (signalList) signalList.innerHTML = `<div class="bigyang-empty error">加载失败：${escHtml(e.message)}</div>`;
      if (alertList) alertList.innerHTML = `<div class="bigyang-empty error">加载失败：${escHtml(e.message)}</div>`;
    }
  }

  async function runBigYangScan() {
    const btn = document.getElementById('bigYangRunBtn');
    if (!btn) return;
    btn.disabled = true;
    btn.textContent = '扫描中...';
    try {
      const result = await fetchBigYangJson(`${BIG_YANG_API}/run`, { method: 'POST' });
      setBigYangRunStatus(result.message || '扫描完成', 'success');
      await loadBigYangPanel();
    } catch (e) {
      setBigYangRunStatus(`扫描失败：${e.message}`, 'error');
    } finally {
      btn.disabled = false;
      btn.textContent = '立即扫描';
    }
  }

  async function markBigYangAlertRead(id) {
    if (!id) return;
    try {
      await fetchBigYangJson(`${BIG_YANG_API}/alerts/${id}/read`, { method: 'POST' });
      bigYangState.alerts = bigYangState.alerts.map(alert => {
        if (String(alert.id) === String(id)) {
          return { ...alert, read: true };
        }
        return alert;
      });
      if (bigYangState.summary) {
        bigYangState.summary.unreadAlertCount = Math.max(0, (bigYangState.summary.unreadAlertCount || 0) - 1);
      }
      renderBigYangBadges();
      renderBigYangSummary();
      renderBigYangAlerts();
    } catch (e) {
      setBigYangRunStatus(`标记已读失败：${e.message}`, 'error');
    }
  }

  async function fetchBigYangJson(url, options) {
    const res = await fetch(url, options);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.message || `请求失败：${res.status}`);
    }
    return data;
  }

  function renderBigYang() {
    renderBigYangBadges();
    renderBigYangSummary();
    renderBigYangSignals();
    renderBigYangAlerts();
  }

  function renderBigYangBadges() {
    const unread = bigYangState.summary?.unreadAlertCount || 0;
    const hero = document.getElementById('bigYangHeroAlert');
    const heroCount = document.getElementById('bigYangHeroCount');
    const tabBadge = document.getElementById('bigYangTabBadge');
    if (hero && heroCount) {
      hero.classList.toggle('hidden', unread <= 0);
      heroCount.textContent = unread;
    }
    if (tabBadge) {
      tabBadge.classList.toggle('hidden', unread <= 0);
      tabBadge.textContent = unread;
    }
  }

  function renderBigYangSummary() {
    const wrap = document.getElementById('bigYangSummary');
    if (!wrap || !bigYangState.summary) return;
    const s = bigYangState.summary;
    wrap.innerHTML = `
      <div class="bigyang-summary-card danger">
        <div class="bigyang-summary-label">未读提示</div>
        <div class="bigyang-summary-value">${s.unreadAlertCount || 0}</div>
      </div>
      <div class="bigyang-summary-card">
        <div class="bigyang-summary-label">观察中</div>
        <div class="bigyang-summary-value">${s.watchingCount || 0}</div>
      </div>
      <div class="bigyang-summary-card">
        <div class="bigyang-summary-label">今日新入池</div>
        <div class="bigyang-summary-value">${s.todayNewWatchingCount || 0}</div>
      </div>
      <div class="bigyang-summary-card success">
        <div class="bigyang-summary-label">今日触发</div>
        <div class="bigyang-summary-value">${s.todayTriggeredCount || 0}</div>
      </div>
      <div class="bigyang-summary-card muted">
        <div class="bigyang-summary-label">已失效</div>
        <div class="bigyang-summary-value">${s.expiredCount || 0}</div>
      </div>
    `;
  }

  function renderBigYangSignals() {
    const wrap = document.getElementById('bigYangSignalList');
    const count = document.getElementById('bigYangSignalCount');
    if (!wrap) return;
    const signals = bigYangState.signals || [];
    if (count) count.textContent = signals.length;
    if (!signals.length) {
      wrap.innerHTML = '<div class="bigyang-empty">暂无大阳线候选</div>';
      return;
    }
    wrap.innerHTML = signals.map(signal => {
      const distance = signal.distanceToBasePct == null
        ? '—'
        : `${signal.distanceToBasePct > 0 ? '+' : ''}${Number(signal.distanceToBasePct).toFixed(2)}%`;
      const currentPrice = signal.currentPrice == null ? '—' : Number(signal.currentPrice).toFixed(2);
      const basePrice = signal.baseStartPrice == null ? '—' : Number(signal.baseStartPrice).toFixed(2);
      const priceDateLabel = signal.currentPriceDate ? `收盘 ${signal.currentPriceDate}` : '收盘日期未知';
      return `
        <article class="bigyang-item ${signal.signalStatus}">
          <div class="bigyang-item-head">
            <div>
              <div class="bigyang-stock">${escHtml(signal.stockName)} <span>${escHtml(signal.stockCode)}</span></div>
              <div class="bigyang-meta">${escHtml(signal.sourcePoolTypeLabel || '')} · ${signal.limitUpStreak} 连板 · ${signal.firstLimitUpDate || ''} ~ ${signal.lastLimitUpDate || ''}</div>
            </div>
            <span class="bigyang-status ${signal.signalStatus}">${bigYangStatusLabel(signal.signalStatus)}</span>
          </div>
          <div class="bigyang-kvs">
            <div><label>起涨点</label><b>${basePrice}</b></div>
            <div><label>当前价</label><b>${currentPrice}</b><small>${escHtml(priceDateLabel)}</small></div>
            <div><label>偏离</label><b>${distance}</b></div>
            <div><label>首板开盘</label><b>${signal.firstLimitUpOpenPrice == null ? '—' : Number(signal.firstLimitUpOpenPrice).toFixed(2)}</b></div>
          </div>
          <div class="bigyang-reason">${escHtml(signal.statusReason || '')}</div>
        </article>
      `;
    }).join('');
  }

  function renderBigYangAlerts() {
    const wrap = document.getElementById('bigYangAlertList');
    const count = document.getElementById('bigYangAlertCount');
    if (!wrap) return;
    const alerts = bigYangState.alerts || [];
    if (count) count.textContent = alerts.length;
    if (!alerts.length) {
      wrap.innerHTML = '<div class="bigyang-empty">暂无买入提示消息</div>';
      return;
    }
    wrap.innerHTML = alerts.map(alert => `
      <article class="bigyang-alert ${alert.read ? 'read' : 'unread'}">
        <div class="bigyang-alert-head">
          <div class="bigyang-alert-title">${escHtml(alert.title || '')}</div>
          <div class="bigyang-alert-time">${formatDateTime(alert.triggerAt)}</div>
        </div>
        <div class="bigyang-alert-meta">${escHtml(alert.stockName || '')} · ${escHtml(alert.stockCode || '')} · 触发价 ${alert.triggerPrice == null ? '—' : Number(alert.triggerPrice).toFixed(2)}</div>
        <div class="bigyang-alert-content">${escHtml(alert.content || '')}</div>
        <div class="bigyang-alert-actions">
          ${alert.read ? '<span class="bigyang-read-tag">已读</span>' : `<button class="invest-btn-outline" data-action="read-alert" data-id="${alert.id}">标记已读</button>`}
        </div>
      </article>
    `).join('');
  }

  function setBigYangRunStatus(message, tone) {
    const el = document.getElementById('bigYangRunStatus');
    if (!el) return;
    el.textContent = message || '';
    el.className = `bigyang-run-status ${tone || ''}`;
    el.classList.remove('hidden');
  }

  function bigYangStatusLabel(status) {
    if (status === 'watching') return '观察中';
    if (status === 'triggered') return '已触发';
    if (status === 'expired') return '已失效';
    return status || '—';
  }

  // 10倍PS股票池看板列定义。inline 决定是否可内联编辑。
  const POOL_COLUMNS = [
    { key: 'stockName',         label: '公司简称', cls: 'pool-col-stock', render: renderStockCell },
    { key: 'revenue2023',       label: '2023<br>营收<br>(亿)', cls: 'pool-col-num', inline: 'number' },
    { key: 'revenue2024',       label: '2024<br>营收<br>(亿)', cls: 'pool-col-num', inline: 'number' },
    { key: 'revenue2025',       label: '2025<br>营收<br>(亿)', cls: 'pool-col-num', inline: 'number' },
    { key: 'revenueForecastY0', label: '2026<br>预测<br>(亿)', cls: 'pool-col-num', inline: 'number', hot: true },
    { key: 'revenueForecastY1', label: '2027<br>预测<br>(亿)', cls: 'pool-col-num', inline: 'number' },
    { key: 'revenueForecastY2', label: '2028<br>预测<br>(亿)', cls: 'pool-col-num', inline: 'number' },
    { key: 'q1GrossMargin',     label: '2026Q1<br>毛利率<br>(%)', cls: 'pool-col-rate', inline: 'number' },
    { key: 'q1NetMargin',       label: '2026Q1<br>净利率<br>(%)', cls: 'pool-col-rate', inline: 'number' },
    { key: 'q1RevenueGrowth',   label: '2026Q1<br>营收<br>增速(%)', cls: 'pool-col-rate', inline: 'number', render: renderGrowthEditCell },
    { key: 'minPs5y',           label: '近5年<br>最低<br>PS(倍)', cls: 'pool-col-ps', inline: 'number', render: renderPsCell },
    { key: 'currentMarketCap',  label: '当前<br>市值<br>(亿)', cls: 'pool-col-num', readonly: true, hot: true, render: renderMarketCapCell },
    { key: 'ytdGainPct',        label: '今年<br>涨幅<br>(%)', cls: 'pool-col-rate', readonly: true, render: renderPctCell },
    { key: 'valuationRange',    label: '估值<br>情况', cls: 'pool-col-tag', readonly: true, render: renderValuationRangeCell },
  ];

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
    initPoolSearch();
  }

  function initPoolSearch() {
    const input = document.getElementById('poolSearchInput');
    const clearBtn = document.getElementById('poolSearchClear');
    if (!input) return;
    input.addEventListener('input', () => {
      poolKeyword = input.value || '';
      if (clearBtn) clearBtn.classList.toggle('hidden', !poolKeyword);
      renderPool();
    });
    input.addEventListener('keydown', e => {
      if (e.key === 'Escape') { input.value = ''; poolKeyword = ''; clearBtn?.classList.add('hidden'); renderPool(); }
    });
    clearBtn?.addEventListener('click', () => {
      input.value = '';
      poolKeyword = '';
      clearBtn.classList.add('hidden');
      renderPool();
      input.focus();
    });
  }

  async function loadPool() {
    const wrap = document.getElementById('poolListWrap');
    if (!wrap) return;
    try {
      const res = await fetch('api/invest/pool');
      poolData = await res.json();
      ensurePoolSearchCache();
      renderPool();
    } catch (e) {
      wrap.innerHTML = `<div class="pool-empty">加载失败：${e.message}</div>`;
    }
  }

  function renderPool() {
    const wrap = document.getElementById('poolListWrap');
    if (!wrap) return;
    let items = poolData;
    if (poolFilter === 'fair') items = poolData.filter(isFairZone);
    else if (poolFilter === 'low') items = poolData.filter(isLowZone);
    else if (poolFilter === 'high') items = poolData.filter(isBubbleZone);

    // 搜索过滤
    if (poolKeyword.trim()) {
      items = items.filter(matchesPoolKeyword);
    }

    if (items.length === 0) {
      const emptyMsg = poolKeyword.trim()
        ? `没有匹配「<b>${escHtml(poolKeyword)}</b>」的股票。试试别的关键字，或清空搜索查看全部。`
        : '暂无股票，点击「+ 加入股票池」或「📷 截图批量导入」添加';
      wrap.innerHTML = `<div class="pool-empty">${emptyMsg}</div>`;
      return;
    }

    let head = '<thead><tr>';
    POOL_COLUMNS.forEach(c => {
      head += `<th class="${c.cls || ''}">${c.label}</th>`;
    });
    head += '<th class="pool-col-actions">操作</th></tr></thead>';

    let body = '<tbody>';
    items.forEach(item => {
      let rowCls = '';
      if (item.alertState === 'buy_alerted') rowCls = 'alert-buy';
      else if (item.alertState === 'sell_alerted') rowCls = 'alert-sell';
      body += `<tr class="${rowCls}" data-id="${item.id}">`;
      POOL_COLUMNS.forEach(c => {
        body += `<td class="${c.cls || ''} ${c.hot ? 'pool-col-hot' : ''}" data-field="${c.key}">${renderCell(c, item)}</td>`;
      });
      body += `<td class="pool-cell-actions pool-col-actions">
        <button class="pool-row-btn" data-action="edit" data-id="${item.id}">详情</button>
        <button class="pool-row-btn danger" data-action="delete" data-id="${item.id}">移除</button>
      </td>`;
      body += '</tr>';
    });
    body += '</tbody>';

    wrap.innerHTML = `${renderPoolBoardSummary(items)}
      <div class="pool-table-scroll">
        <table class="pool-table pool-ps-table">${head}${body}</table>
      </div>
      <div class="pool-list-foot">
        <span>共 ${poolData.length} 只，当前显示 ${items.length} 只</span>
        <span>数据来源：invest_stock_pool + a-stock-data（腾讯行情 / 复权日K）+ trade_stock_basic</span>
      </div>
      ${renderPoolCharts(items)}`;

    bindPoolEvents();
  }

  function renderCell(col, item) {
    if (col.render) return col.render(item, col);
    const val = item[col.key];
    if (col.inline === 'number') {
      const display = val != null ? Number(val) : '';
      return `<input type="number" step="0.01" class="pool-cell-input" data-field="${col.key}" value="${display}" />`;
    }
    if (col.inline === 'text') {
      return `<input type="text" class="pool-cell-input" data-field="${col.key}" value="${escHtml(val || '')}" />`;
    }
    if (col.inline === 'select') {
      const opts = col.options.map(o =>
        `<option value="${o.v}" ${val === o.v ? 'selected' : ''}>${o.l}</option>`).join('');
      return `<select class="pool-cell-select" data-field="${col.key}">${opts}</select>`;
    }
    return val != null ? escHtml(String(val)) : '';
  }

  function renderStockCell(item) {
    let alertTag = '';
    if (item.alertState === 'buy_alerted') alertTag = '<span class="alert-tag buy">⬇ 触发买入</span>';
    else if (item.alertState === 'sell_alerted') alertTag = '<span class="alert-tag sell">⬆ 触发卖出</span>';
    return `<div class="pool-cell-name">
      <span class="name">${escHtml(item.stockName)}</span>
      <span class="code">${escHtml(item.stockCode)}</span>
      ${alertTag}
    </div>`;
  }

  function renderPctCell(item, col) {
    const v = col.key === 'ytdGainPct'
      ? (item.ytdGain != null ? item.ytdGain : item.ytdGainPct)
      : item[col.key];
    if (v == null) return '<span style="color:#d1d5db">—</span>';
    const n = parseFloat(v);
    const cls = n > 0 ? 'up' : (n < 0 ? 'down' : '');
    const sign = n >= 0 ? '+' : '';
    return `<span class="pool-cell-pct ${cls}">${sign}${n.toFixed(2)}</span>`;
  }

  function renderGrowthEditCell(item, col) {
    const v = item[col.key];
    const n = asNum(v);
    const cls = n >= 100 ? 'surge' : (n >= 70 ? 'strong' : (n >= 30 ? 'warm' : 'calm'));
    const display = v != null ? Number(v) : '';
    return `<label class="pool-edit-wrap pool-growth-edit ${cls}">
      <input type="number" step="0.01" class="pool-cell-input" data-field="${col.key}" value="${display}" />
    </label>`;
  }

  function renderPsCell(item, col) {
    const v = item[col.key];
    const display = v != null ? Number(v) : '';
    return `<label class="pool-edit-wrap pool-ps-edit">
      <input type="number" step="0.01" class="pool-cell-input" data-field="${col.key}" value="${display}" />
    </label>`;
  }

  function renderValuationRangeCell(item, col) {
    const val = inferValuationRange(item);
    const cls = val === '低估' ? 'low' : (val === '泡沫' ? 'bubble' : (val === '合理' ? 'fair' : 'empty'));
    if (!val) return '<span style="color:#d1d5db">—</span>';
    return `<span class="pool-tag-input valuation-${cls}">${escHtml(val)}</span>`;
  }

  function renderPoolBoardSummary(items) {
    const fairCount = poolData.filter(isFairZone).length;
    const lowCount = poolData.filter(isLowZone).length;
    const bubbleCount = poolData.filter(isBubbleZone).length;
    const avgGrowth = avg(poolData.map(i => i.q1RevenueGrowth));
    return `<div class="pool-board-head">
      <div>
        <div class="pool-board-title">适合用10倍PS来简单估测和跟踪的高科技成长股 <span>${new Date().toISOString().slice(0, 10)}</span></div>
        <div class="pool-board-note">判断依据：合理市值 = 明年预测营收 × 10；当前市值低于 Y1×10 为低估，超过 Y2×10 为泡沫（需警惕）。</div>
      </div>
      <div class="pool-board-stats">
        <span class="pool-stat green">合理 ${fairCount}</span>
        <span class="pool-stat blue">低估 ${lowCount}</span>
        <span class="pool-stat red">泡沫 ${bubbleCount}</span>
        <span class="pool-stat orange">均值 ${isFinite(avgGrowth) ? avgGrowth.toFixed(1) + '%' : '—'}</span>
      </div>
    </div>`;
  }

  function renderPoolCharts(items) {
    const rows = [...items].filter(i => i.q1RevenueGrowth != null || i.q1NetMargin != null).slice(0, 16);
    if (rows.length === 0) return '';
    return `<div class="pool-chart-grid">
      ${renderPoolBarChart('2026 Q1 营收增速分布', rows, 'q1RevenueGrowth', '%', 'revenue')}
      ${renderPoolBarChart('2026 Q1 净利率分布', rows, 'q1NetMargin', '%', 'margin')}
    </div>`;
  }

  function renderPoolBarChart(title, rows, key, suffix, tone) {
    const nums = rows.map(i => Math.max(0, asNum(i[key]))).filter(Number.isFinite);
    const max = Math.max(10, ...nums);
    const bars = rows.map(item => {
      const val = asNum(item[key]);
      const height = Number.isFinite(val) ? Math.max(8, Math.min(100, val / max * 100)) : 0;
      return `<div class="pool-bar-item">
        <div class="pool-bar-value" style="--bar-height:${height}%">${Number.isFinite(val) ? val.toFixed(1) + suffix : '—'}</div>
        <div class="pool-bar-track"><div class="pool-bar ${tone}" style="height:${height}%"></div></div>
        <div class="pool-bar-name" title="${escHtml(item.stockName || item.stockCode)}">${escHtml(item.stockName || item.stockCode)}</div>
      </div>`;
    }).join('');
    return `<section class="pool-chart-section">
      <h3>${title}</h3>
      <div class="pool-bars">${bars}</div>
    </section>`;
  }

  function renderMarketCapCell(item) {
    const v = item.marketCap != null ? item.marketCap : item.currentMarketCap;
    if (v == null) return '<span style="color:#d1d5db">—</span>';
    return `<span class="pool-cell-price">${parseFloat(v).toFixed(1)}</span>`;
  }

  function renderLatestPriceCell(item) {
    const p = item.latestPrice;
    if (p == null) return '<span style="color:#d1d5db">—</span>';
    const price = parseFloat(p);
    let cls = 'pool-cell-price';
    if (item.targetBuyPrice != null && price <= parseFloat(item.targetBuyPrice)) cls += ' below-buy';
    else if (item.targetSellPrice != null && price >= parseFloat(item.targetSellPrice)) cls += ' above-sell';
    return `<span class="${cls}">${price.toFixed(2)}</span>`;
  }

  function renderMemoCell(item) {
    const memo = item.memo;
    if (!memo || !memo.trim()) {
      return `<div class="pool-cell-memo empty" data-action="memo" data-id="${item.id}">点击编辑...</div>`;
    }
    const short = memo.length > 30 ? memo.slice(0, 30) + '…' : memo;
    return `<div class="pool-cell-memo" data-action="memo" data-id="${item.id}" title="${escHtml(memo)}">${escHtml(short)}</div>`;
  }

  function asNum(v) {
    if (v == null || v === '') return NaN;
    const n = Number(v);
    return Number.isFinite(n) ? n : NaN;
  }

  function sortNum(v) {
    const n = asNum(v);
    return Number.isFinite(n) ? n : -Infinity;
  }

  function avg(values) {
    const nums = values.map(asNum).filter(Number.isFinite);
    if (nums.length === 0) return NaN;
    return nums.reduce((sum, n) => sum + n, 0) / nums.length;
  }

  function getCurrentMarketCap(item) {
    return asNum(item.marketCap != null ? item.marketCap : item.currentMarketCap);
  }

  // 合理市值 = 未来一年预测营收 × 10
  // 当前市值 < Y1×10 → 低估；当前市值 > Y2×10 → 泡沫；其余 → 合理
  function inferValuationRange(item) {
    const marketCap = getCurrentMarketCap(item);
    if (!Number.isFinite(marketCap)) return '';
    const y1 = asNum(item.revenueForecastY1);
    const y2 = asNum(item.revenueForecastY2);
    if (!Number.isFinite(y1) && !Number.isFinite(y2)) return '';
    if (Number.isFinite(y1) && marketCap < y1 * 10) return '低估';
    if (Number.isFinite(y2) && marketCap > y2 * 10) return '泡沫';
    return '合理';
  }

  function isFairZone(item) { return inferValuationRange(item) === '合理'; }
  function isLowZone(item)  { return inferValuationRange(item) === '低估'; }
  function isBubbleZone(item){ return inferValuationRange(item) === '泡沫'; }

  function bindPoolEvents() {
    const wrap = document.getElementById('poolListWrap');
    if (!wrap) return;

    // 内联输入框：blur 时保存
    wrap.querySelectorAll('.pool-cell-input').forEach(inp => {
      inp.addEventListener('blur', () => onCellEdit(inp));
      inp.addEventListener('keydown', e => {
        if (e.key === 'Enter') { e.preventDefault(); inp.blur(); }
        if (e.key === 'Escape') {
          const tr = inp.closest('tr');
          const item = poolData.find(i => i.id == tr.dataset.id);
          inp.value = item && item[inp.dataset.field] != null ? item[inp.dataset.field] : '';
          inp.blur();
        }
      });
    });
    wrap.querySelectorAll('.pool-cell-select').forEach(sel => {
      sel.addEventListener('change', () => onCellEdit(sel));
    });

    // 操作按钮（编辑、删除、备注）使用事件委托
    wrap.addEventListener('click', e => {
      const btn = e.target.closest('[data-action]');
      if (!btn) return;
      const action = btn.dataset.action;
      const id = parseInt(btn.dataset.id, 10);
      if (action === 'edit') openEditModal(id);
      else if (action === 'delete') {
        const item = poolData.find(i => i.id === id);
        if (item) removePool(id, item.stockName);
      } else if (action === 'memo') {
        openMemoModal(id);
      }
    });
  }

  async function onCellEdit(el) {
    const tr = el.closest('tr');
    const id = parseInt(tr.dataset.id, 10);
    const field = el.dataset.field;
    const value = el.value;
    const item = poolData.find(i => i.id === id);
    const oldVal = item ? item[field] : null;
    if (String(oldVal == null ? '' : oldVal) === String(value)) return;

    el.classList.remove('error', 'saved');
    el.classList.add('saving');

    try {
      const res = await fetch(`api/invest/pool/${id}/field`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ field, value: value === '' ? null : value }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || '保存失败');
      }
      const updated = await res.json();
      const idx = poolData.findIndex(i => i.id === id);
      if (idx >= 0) {
        poolData[idx] = updated;
        poolSearchCache.set(updated.id, buildPoolSearchItem(updated));
      }
      el.classList.remove('saving');
      el.classList.add('saved');
      setTimeout(() => el.classList.remove('saved'), 800);
      // 如果改了 select（持仓状态/分类），重新渲染整行（filter 可能改变）
      if (field === 'status' || field === 'poolType') renderPool();
      if (['q1RevenueGrowth', 'q1NetMargin', 'q1GrossMargin', 'revenueForecastY1', 'revenueForecastY2'].includes(field)) renderPool();
    } catch (e) {
      el.classList.remove('saving');
      el.classList.add('error');
      alert(e.message);
    }
  }

  async function removePool(id, name) {
    if (!confirm(`确认从股票池移除「${name}」？`)) return;
    try {
      await fetch(`api/invest/pool/${id}`, { method: 'DELETE' });
      await loadPool();
    } catch (e) {
      alert('移除失败：' + e.message);
    }
  }

  // ===== 加入/编辑股票池弹窗 =====
  let editingPoolId = null;

  const MODAL_FIELDS = [
    ['modalUndervaluedPrice', 'undervaluedPrice'],
    ['modalFairPrice',        'fairPrice'],
    ['modalOvervaluedPrice',  'overvaluedPrice'],
    ['modalTargetBuyPrice',   'targetBuyPrice'],
    ['modalTargetSellPrice',  'targetSellPrice'],
    ['modalRevY0',            'revenueForecastY0'],
    ['modalRevY1',            'revenueForecastY1'],
    ['modalRevY2',            'revenueForecastY2'],
  ];

  function initPoolModal() {
    document.getElementById('addPoolBtn')?.addEventListener('click', openAddModal);
    document.getElementById('investModalClose')?.addEventListener('click', closeModal);
    document.getElementById('investModalCancel')?.addEventListener('click', closeModal);
    document.getElementById('investModalSave')?.addEventListener('click', savePool);
    document.getElementById('investModalMask')?.addEventListener('click', e => {
      if (e.target === e.currentTarget) closeModal();
    });

    // 备注弹窗
    document.getElementById('memoModalClose')?.addEventListener('click', closeMemoModal);
    document.getElementById('memoModalCancel')?.addEventListener('click', closeMemoModal);
    document.getElementById('memoModalSave')?.addEventListener('click', saveMemoModal);
    document.getElementById('memoModalMask')?.addEventListener('click', e => {
      if (e.target === e.currentTarget) closeMemoModal();
    });

    // 截图导入
    initImportModal();
  }

  function openAddModal() {
    editingPoolId = null;
    document.getElementById('investModalTitle').textContent = '加入股票池';
    document.getElementById('modalKeyword').value = '';
    document.getElementById('modalKeyword').disabled = false;
    document.getElementById('modalPoolType').value = 'quality';
    document.getElementById('modalStatus').value = 'watching';
    document.getElementById('modalMemo').value = '';
    MODAL_FIELDS.forEach(([id]) => { document.getElementById(id).value = ''; });
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
    document.getElementById('modalMemo').value = item.memo || '';
    MODAL_FIELDS.forEach(([id, key]) => {
      document.getElementById(id).value = item[key] != null ? item[key] : '';
    });
    document.getElementById('investModalMask').classList.remove('hidden');
  }

  function closeModal() {
    document.getElementById('investModalMask').classList.add('hidden');
  }

  async function savePool() {
    const keyword = document.getElementById('modalKeyword').value.trim();
    const poolType = document.getElementById('modalPoolType').value;
    const status = document.getElementById('modalStatus').value;
    const memo = document.getElementById('modalMemo').value.trim();

    if (!editingPoolId && !keyword) {
      alert('请输入股票名称或代码');
      return;
    }

    const body = { keyword, poolType, status, memo: memo || null };
    MODAL_FIELDS.forEach(([id, key]) => {
      const v = document.getElementById(id).value.trim();
      body[key] = v ? parseFloat(v) : null;
    });

    const saveBtn = document.getElementById('investModalSave');
    saveBtn.disabled = true;
    saveBtn.textContent = '保存中...';

    try {
      if (editingPoolId) {
        const res = await fetch(`api/invest/pool/${editingPoolId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.message || '保存失败');
        }
      } else {
        const res = await fetch('api/invest/pool', {
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

  // ===== 备注弹窗 =====
  let memoEditingId = null;
  function openMemoModal(id) {
    const item = poolData.find(i => i.id === id);
    if (!item) return;
    memoEditingId = id;
    document.getElementById('memoModalTitle').textContent = `投资逻辑 — ${item.stockName} (${item.stockCode})`;
    document.getElementById('memoModalText').value = item.memo || '';
    document.getElementById('memoModalMask').classList.remove('hidden');
    setTimeout(() => document.getElementById('memoModalText').focus(), 50);
  }

  function closeMemoModal() {
    memoEditingId = null;
    document.getElementById('memoModalMask').classList.add('hidden');
  }

  async function saveMemoModal() {
    if (!memoEditingId) return;
    const memo = document.getElementById('memoModalText').value;
    const btn = document.getElementById('memoModalSave');
    btn.disabled = true;
    btn.textContent = '保存中...';
    try {
      const res = await fetch(`api/invest/pool/${memoEditingId}/field`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ field: 'memo', value: memo || null }),
      });
      if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || '保存失败');
      const updated = await res.json();
      const idx = poolData.findIndex(i => i.id === memoEditingId);
      if (idx >= 0) poolData[idx] = updated;
      closeMemoModal();
      renderPool();
    } catch (e) {
      alert(e.message);
    } finally {
      btn.disabled = false;
      btn.textContent = '保存';
    }
  }

  // ===== 截图批量导入弹窗 =====
  let importParsedItems = [];
  let importImageBase64 = null;

  function initImportModal() {
    const importBtn = document.getElementById('importPoolBtn');
    if (!importBtn) return;
    importBtn.addEventListener('click', openImportModal);

    document.getElementById('importModalClose')?.addEventListener('click', closeImportModal);
    document.getElementById('importModalMask')?.addEventListener('click', e => {
      if (e.target === e.currentTarget) closeImportModal();
    });

    const uploadArea = document.getElementById('importUploadArea');
    const fileInput = document.getElementById('importFileInput');
    uploadArea?.addEventListener('click', () => fileInput?.click());
    uploadArea?.addEventListener('dragover', e => { e.preventDefault(); uploadArea.classList.add('dragover'); });
    uploadArea?.addEventListener('dragleave', () => uploadArea.classList.remove('dragover'));
    uploadArea?.addEventListener('drop', e => {
      e.preventDefault();
      uploadArea.classList.remove('dragover');
      if (e.dataTransfer.files[0]) handleImportFile(e.dataTransfer.files[0]);
    });
    fileInput?.addEventListener('change', e => {
      if (e.target.files[0]) handleImportFile(e.target.files[0]);
    });

    document.getElementById('importParseBtn')?.addEventListener('click', parseImportImage);
    document.getElementById('importBackBtn')?.addEventListener('click', resetImportToStep1);
    document.getElementById('importConfirmBtn')?.addEventListener('click', confirmImport);
  }

  function openImportModal() {
    document.getElementById('importModalMask').classList.remove('hidden');
    resetImportToStep1();
  }

  function closeImportModal() {
    document.getElementById('importModalMask').classList.add('hidden');
    importParsedItems = [];
    importImageBase64 = null;
    document.getElementById('importFileInput').value = '';
  }

  function resetImportToStep1() {
    document.querySelector('.import-step-1').classList.remove('hidden');
    document.getElementById('importStep2').classList.add('hidden');
    document.getElementById('importPreviewWrap').classList.add('hidden');
    importImageBase64 = null;
    importParsedItems = [];
    const ip = document.getElementById('importPreviewImg');
    if (ip) ip.src = '';
  }

  function handleImportFile(file) {
    if (!file.type.startsWith('image/')) {
      alert('请选择图片文件');
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      alert('图片大小不能超过 10MB');
      return;
    }
    const reader = new FileReader();
    reader.onload = ev => {
      importImageBase64 = ev.target.result;
      document.getElementById('importPreviewImg').src = importImageBase64;
      document.getElementById('importPreviewWrap').classList.remove('hidden');
    };
    reader.readAsDataURL(file);
  }

  async function parseImportImage() {
    if (!importImageBase64) return;
    const defaultPoolType = document.getElementById('importDefaultPoolType').value;
    const body = document.getElementById('importModalBody');
    const originalHtml = body.innerHTML;
    body.innerHTML = `<div class="import-loading">
      <div class="import-loading-spinner"></div>
      <div>AI 正在识别截图，请稍候 (5-30 秒)...</div>
    </div>`;
    try {
      const res = await fetch('api/invest/pool/import-image', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ imageBase64: importImageBase64, defaultPoolType }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || '识别失败');
      }
      const data = await res.json();
      importParsedItems = data.items || [];
      body.innerHTML = originalHtml;
      // 重新绑定事件（因为 innerHTML 重置了）
      initImportModal();
      // 切到 step2
      document.querySelector('.import-step-1').classList.add('hidden');
      document.getElementById('importStep2').classList.remove('hidden');
      renderImportTable(data);
    } catch (e) {
      body.innerHTML = `<div class="import-error">识别失败：${escHtml(e.message)}</div>
        <div style="margin-top:12px;text-align:center">
          <button class="invest-btn-outline" onclick="Invest.resetImport()">← 重试</button>
        </div>`;
    }
  }

  function renderImportTable(data) {
    const summary = document.getElementById('importParseSummary');
    const matched = importParsedItems.filter(it => it.matched).length;
    summary.innerHTML = `共识别 <b>${importParsedItems.length}</b> 只股票，匹配代码 <b>${matched}</b> 只${matched < importParsedItems.length ? `，<span style="color:#b45309">${importParsedItems.length - matched} 只未匹配（黄色高亮，可手动补充代码）</span>` : ''}`;

    const tbl = document.getElementById('importResultTable');
    let html = `<thead><tr>
      <th><input type="checkbox" id="importSelectAll" checked /></th>
      <th>名称</th>
      <th>代码</th>
      <th>分类</th>
      <th>状态</th>
      <th>低估价</th>
      <th>合理价</th>
      <th>高估价</th>
      <th>买入价</th>
      <th>卖出价</th>
      <th>2023营收</th>
      <th>2024营收</th>
      <th>2025营收</th>
      <th>2026预测</th>
      <th>2027预测</th>
      <th>2028预测</th>
      <th>Q1毛利率</th>
      <th>Q1净利率</th>
      <th>Q1营收增速</th>
      <th>最低PS</th>
    </tr></thead><tbody>`;
    importParsedItems.forEach((it, idx) => {
      const cls = it.matched ? '' : 'match-failed';
      const status = it.matched
        ? '<span class="match-status ok">✓</span>'
        : '<span class="match-status fail">⚠ 未匹配</span>';
      html += `<tr class="${cls}" data-idx="${idx}">
        <td><input type="checkbox" class="import-select" checked /></td>
        <td><input type="text" data-field="stockName" value="${escHtml(it.stockName || '')}" /> ${status}</td>
        <td><input type="text" data-field="stockCode" value="${escHtml(it.stockCode || '')}" /></td>
        <td>
          <select data-field="poolType">
            <option value="quality" ${it.poolType==='quality'?'selected':''}>质量</option>
            <option value="tech_vc" ${it.poolType==='tech_vc'?'selected':''}>科技</option>
          </select>
        </td>
        <td>
          <select data-field="status">
            <option value="watching">观察</option>
            <option value="holding">持仓</option>
            <option value="exited">已出</option>
          </select>
        </td>
        <td><input type="number" step="0.01" data-field="undervaluedPrice" value="${it.undervaluedPrice ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="fairPrice" value="${it.fairPrice ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="overvaluedPrice" value="${it.overvaluedPrice ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="targetBuyPrice" value="${it.targetBuyPrice ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="targetSellPrice" value="${it.targetSellPrice ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="revenue2023" value="${it.revenue2023 ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="revenue2024" value="${it.revenue2024 ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="revenue2025" value="${it.revenue2025 ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="revenueForecastY0" value="${it.revenueForecastY0 ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="revenueForecastY1" value="${it.revenueForecastY1 ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="revenueForecastY2" value="${it.revenueForecastY2 ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="q1GrossMargin" value="${it.q1GrossMargin ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="q1NetMargin" value="${it.q1NetMargin ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="q1RevenueGrowth" value="${it.q1RevenueGrowth ?? ''}" /></td>
        <td><input type="number" step="0.01" data-field="minPs5y" value="${it.minPs5y ?? ''}" /></td>
      </tr>`;
    });
    html += '</tbody>';
    tbl.innerHTML = html;

    document.getElementById('importSelectAll').addEventListener('change', e => {
      tbl.querySelectorAll('.import-select').forEach(cb => { cb.checked = e.target.checked; });
    });
  }

  async function confirmImport() {
    const tbl = document.getElementById('importResultTable');
    const rows = Array.from(tbl.querySelectorAll('tbody tr'));
    const items = [];
    rows.forEach(row => {
      const sel = row.querySelector('.import-select');
      if (!sel.checked) return;
      const item = {};
      row.querySelectorAll('input[data-field], select[data-field]').forEach(el => {
        const f = el.dataset.field;
        const v = el.value.trim();
        if (el.type === 'number') item[f] = v ? parseFloat(v) : null;
        else item[f] = v || null;
      });
      if (item.stockCode || item.stockName) items.push(item);
    });
    if (items.length === 0) {
      alert('请至少选择一只股票');
      return;
    }
    const btn = document.getElementById('importConfirmBtn');
    btn.disabled = true;
    btn.textContent = '导入中...';
    try {
      const res = await fetch('api/invest/pool/batch-import', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items }),
      });
      if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || '导入失败');
      const result = await res.json();
      alert(`导入完成：成功 ${result.imported} 只，跳过 ${result.skipped} 只${result.failed > 0 ? '，失败 ' + result.failed + ' 只' : ''}`);
      closeImportModal();
      await loadPool();
    } catch (e) {
      alert(e.message);
    } finally {
      btn.disabled = false;
      btn.textContent = '确认导入选中项';
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
  function escHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function formatDateTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value).replace('T', ' ');
    return date.toLocaleString('zh-CN', { hour12: false });
  }

  // ===== 公开接口（供 HTML 内联事件调用）=====
  window.Invest = {
    openEditModal,
    removePool,
    resetImport: resetImportToStep1,
  };

  // ---- 等 DOM 就绪后初始化 ----
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
