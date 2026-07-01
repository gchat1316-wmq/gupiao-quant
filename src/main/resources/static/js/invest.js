/* ===== 谢博投资模块 ===== */
(function () {
  'use strict';

  // ---- 常量 ----
  const POOL_TYPE_LABELS = {
    quality: '质量优选',
    tech_vc: '科技AI',
    innovative_drug: '创新药',
  };
  const POOL_TYPE_ORDER = ['tech_vc', 'innovative_drug', 'quality'];
  const POOL_TYPE_DEFAULT = 'tech_vc';
  const STATUS_LABELS = { watching: '观察中', holding: '持仓中', exited: '已离场' };

  // 一级 tab 映射：section id → poolType
  const SECTION_TO_POOL = {
    techai: 'tech_vc',
    biopharma: 'innovative_drug',
    quality: 'quality',
  };
  const POOL_TO_SECTION = {
    tech_vc: 'techai',
    innovative_drug: 'biopharma',
    quality: 'quality',
  };

  // ---- 权限辅助 ----
  // 当前用户是否拥有股票池修改权限（MANAGER 或 ADMIN）。
  // 角色由 layout.js 在调用 /api/auth/me 后回填到 GPAuth。
  function canManageInvest() {
    return !!(window.GPAuth && typeof GPAuth.canManageInvest === 'function' && GPAuth.canManageInvest());
  }

  // 给所有修改类 fetch 拼上 Authorization 头。后端 @PreAuthorize 会按角色拦截。
  function authJsonHeaders() {
    return Object.assign({ 'Content-Type': 'application/json' }, (GPAuth.headers && GPAuth.headers()) || {});
  }

  // 把所有修改类入口隐藏或锁住。2026-07-01 改：股票池 CRUD/OCR 全部迁至 admin 后台，
  // 前端 invest.html 只剩大阳线 runBtn 和 SOP 5A 按钮需要按角色锁。
  // 该函数幂等：被 layout.js 的 gp:role-changed 事件反复触发，每次按当前 role 重新决策。
  function applyReadOnlyMode() {
    const manage = canManageInvest();

    // 大阳线「立即扫描」按钮
    const hideIfReadonly = (id) => {
      const el = document.getElementById(id);
      if (!el) return;
      el.classList.toggle('hidden', !manage);
    };
    hideIfReadonly('bigYangRunBtn');

    // SOP 5A "加入股票池"：只读时禁用按钮 + 改文案
    const sop5aBtn = document.getElementById('sop5aSaveBtn');
    if (sop5aBtn) {
      sop5aBtn.disabled = !manage;
      sop5aBtn.title = manage ? '' : '需要 MANAGER 权限';
      sop5aBtn.textContent = manage ? '加入股票池' : '需要 MANAGER 权限';
    }
  }

  // ---- 模块初始化 ----
  function init() {
    initMainTabs();      // 一级 tab：科技AI / 创新药 / 质量优选（占满整页）
    initTabs();          // 二级 tab（sub-tabs）：按当前 section 切换 panel
    initSop();
    initBigYang();
    initPool();
    initValuation();
    initWeeklyOpportunity();
    applyReadOnlyMode();

    // 监听 layout.js 设角色后的事件，重新跑一次只读决策（修 /api/auth/me 异步早于 init 的竞态）
    document.addEventListener('gp:role-changed', () => {
      applyReadOnlyMode();
    });
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
        headers: authJsonHeaders(),
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

  // ===== 一级 tab：科技AI / 创新药 / 质量优选（占满整页） =====
  function initMainTabs() {
    document.querySelectorAll('.invest-main-tab').forEach(btn => {
      btn.addEventListener('click', () => {
        const sec = btn.dataset.section;
        if (!sec) return;
        activateMainSection(sec, /* updateHash */ true, /* preferredSub */ null);
      });
    });
    // 初始化：从 hash 读取 section + sub，否则用默认 techai
    const initHash = readHashState();
    const initSec = initHash.section || POOL_TO_SECTION[currentPoolType] || 'techai';
    const initSub = initHash.sub || null;
    activateMainSection(initSec, /* updateHash */ false, initSub);
    // 监听 hashchange：前进/后退 / 外部修改 URL 时同步状态
    window.addEventListener('hashchange', () => {
      const h = readHashState();
      const sec = h.section || POOL_TO_SECTION[currentPoolType] || 'techai';
      activateMainSection(sec, /* updateHash */ false, h.sub);
    });
  }

  function readHashState() {
    const h = (window.location.hash || '').replace(/^#/, '');
    if (!h) return { section: null, sub: null };
    const parts = h.split('&').map(kv => {
      const idx = kv.indexOf('=');
      return idx >= 0 ? [kv.slice(0, idx), kv.slice(idx + 1)] : [kv, ''];
    });
    const obj = Object.fromEntries(parts);
    return { section: obj.section || null, sub: obj.sub || null };
  }

  function activateMainSection(sec, updateHash, preferredSub) {
    // 1. 高亮一级 tab
    document.querySelectorAll('.invest-main-tab').forEach(t =>
      t.classList.toggle('active', t.dataset.section === sec)
    );
    // 2. 显示对应 sub-tabs-wrap，隐藏其它
    document.querySelectorAll('.invest-sub-tabs-wrap').forEach(w =>
      w.hidden = (w.dataset.mainSection !== sec)
    );
    // 3. 同步 poolType（决定股票池 + 元信息内容）
    const poolType = SECTION_TO_POOL[sec];
    if (poolType) {
      currentPoolType = poolType;
      // 首次进入页面（poolTypeLoaded=null）或切换到不同 poolType 时加载股票池。
      // 修复：原来 `poolType !== currentPoolType` 会让首次直连 #section=techai（默认值即 tech_vc）跳过 loadPool。
      if (poolType !== poolTypeLoaded) {
        poolTypeLoaded = poolType;
        loadPool();
      }
      // 元信息 + 每周机会相互独立，并发请求避免串行 RTT
      Promise.allSettled([
        loadPoolMeta(currentPoolType),
        loadWeeklyOpportunity(currentPoolType)
      ]);
    }
    // 4. 决定激活哪个 sub-tab：优先用 preferredSub；否则用 HTML 默认 active
    const wrap = document.querySelector(`.invest-sub-tabs-wrap[data-main-section="${sec}"]`);
    let subBtn = null;
    if (preferredSub && wrap) {
      subBtn = wrap.querySelector(`.invest-tab[data-panel="${preferredSub}"]`);
    }
    if (!subBtn && wrap) {
      subBtn = wrap.querySelector('.invest-tab.active') || wrap.querySelector('.invest-tab');
    }
    if (subBtn) {
      activateSubPanel(sec, subBtn.dataset.panel);
    }
    // 5. 写 hash（用 #section=xxx 替代旧的 #pool=xxx，兼容旧 hash 读取）
    if (updateHash) {
      const cur = wrap?.querySelector('.invest-tab.active')?.dataset.panel;
      writeSectionToHash(sec, cur);
    }
  }

  function activateSubPanel(sec, panelId) {
    if (!panelId) return;
    const wrap = document.querySelector(`.invest-sub-tabs-wrap[data-main-section="${sec}"]`);
    if (wrap) {
      wrap.querySelectorAll('.invest-tab').forEach(t =>
        t.classList.toggle('active', t.dataset.panel === panelId)
      );
    }
    // 隐藏所有 panel，显示选中的
    document.querySelectorAll('.invest-panel').forEach(p => {
      p.hidden = (p.id !== panelId);
    });
    // 大阳线面板懒加载
    if (panelId === 'panel-big-yang' && !bigYangState.loaded) {
      loadBigYangPanel();
    }
  }

  // ===== 二级 sub-tab 切换 =====
  function initTabs() {
    document.querySelectorAll('.invest-sub-tabs-wrap .invest-tab').forEach(btn => {
      btn.addEventListener('click', () => {
        const sec = btn.closest('.invest-sub-tabs-wrap')?.dataset.mainSection;
        if (!sec) return;
        activateSubPanel(sec, btn.dataset.panel);
        writeSectionToHash(sec, btn.dataset.panel);
      });
    });
  }

  // ===== 股票池（列表化） =====
  let poolData = [];
  let poolFilter = 'all';
  let poolKeyword = '';
  let poolSearchCache = new Map();   // id -> { name, nameLow, initials, codeLow, searchKey }
  let currentPoolType = POOL_TYPE_DEFAULT;
  let poolTypeLoaded = null;         // 上次加载过的 poolType。null 表示还没加载过任何池子，用于保证「首次直连 #section=techai 也能 loadPool」而不只是切过别的 poolType 再回来时。
  let currentPoolMeta = null;        // 缓存最近一次拉到的 pool-meta，用于编辑弹窗回填

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
      // 阶段 1：基础数据（缓存命中时毫秒级返回）。拿到立刻渲染骨架 + 基础表格
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
      // 阶段 2：实时行情（异步，不阻塞首屏）
      loadBigYangQuotes();
    } catch (e) {
      if (signalList) signalList.innerHTML = `<div class="bigyang-empty error">加载失败：${escHtml(e.message)}</div>`;
      if (alertList) alertList.innerHTML = `<div class="bigyang-empty error">加载失败：${escHtml(e.message)}</div>`;
    }
  }

  /**
   * 异步拉取实时行情，按 stockCode 合并到 signals 上后只重渲染信号列表。
   * 失败时保持"—"，不影响首屏展示。
   */
  async function loadBigYangQuotes() {
    if (!bigYangState.signals || !bigYangState.signals.length) return;
    try {
      const quotes = await fetchBigYangJson(`${BIG_YANG_API}/signals/quotes`);
      if (!Array.isArray(quotes) || quotes.length === 0) return;
      const quoteMap = new Map(quotes.map(q => [normalizeStockCode(q.stockCode), q]));
      bigYangState.signals = bigYangState.signals.map(s => {
        const q = quoteMap.get(normalizeStockCode(s.stockCode));
        if (!q) return s;
        const currentPrice = q.currentPrice == null ? null : Number(q.currentPrice);
        const baseStartPrice = s.baseStartPrice == null ? null : Number(s.baseStartPrice);
        const distanceToBasePct = (currentPrice != null && baseStartPrice && baseStartPrice > 0)
          ? (currentPrice - baseStartPrice) / baseStartPrice * 100
          : null;
        return {
          ...s,
          currentPrice,
          currentPriceDate: q.currentPriceDate || null,
          distanceToBasePct,
        };
      });
      renderBigYangSignals();
    } catch (e) {
      // 静默失败：实时价拉不到就保持"—"，下次刷新再试
      console.warn('加载大阳线实时行情失败:', e);
    }
  }

  /** 把 000001.SZ / 000001.sz 都归一为小写 stockCode，便于 map 查询 */
  function normalizeStockCode(code) {
    if (!code) return '';
    return String(code).trim().toLowerCase();
  }

  async function runBigYangScan() {
    const btn = document.getElementById('bigYangRunBtn');
    if (!btn) return;
    if (!canManageInvest()) { setBigYangRunStatus('当前角色无权限（需 MANAGER/ADMIN）', 'error'); return; }
    btn.disabled = true;
    btn.textContent = '扫描中...';
    try {
      const result = await fetchBigYangJson(`${BIG_YANG_API}/run`, { method: 'POST', headers: (GPAuth.headers && GPAuth.headers()) || {} });
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
      await fetchBigYangJson(`${BIG_YANG_API}/alerts/${id}/read`, { method: 'POST', headers: (GPAuth.headers && GPAuth.headers()) || {} });
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
    const opts = Object.assign({ headers: {} }, options || {});
    // 读接口不强求登录；写接口由调用方传 headers（含 Authorization）
    const res = await fetch(url, opts);
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
    // 注：bigYangHeroAlert / bigYangHeroCount 在新版布局中已被移除（hero 删了），这里安全降级
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
    // 从 URL hash 恢复当前 poolType（注意：loadPool/loadPoolMeta 已由 initMainTabs → activateMainSection 触发）
    currentPoolType = readPoolTypeFromHash() || POOL_TYPE_DEFAULT;
    syncPoolTypeTabsUI();

    document.querySelectorAll('.pool-filter-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.pool-filter-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        poolFilter = btn.dataset.filter;
        renderPool();
      });
    });
    initPoolSearch();

    // 监听 hash 变化（用户前进/后退、手动改 URL）—— 切到一级 tab
    window.addEventListener('hashchange', () => {
      const next = readPoolTypeFromHash();
      if (next && next !== currentPoolType) {
        currentPoolType = next;
        syncPoolTypeTabsUI();
        // 同步 sub-tabs-wrap 显隐 + 激活默认 sub-tab
        const sec = POOL_TO_SECTION[currentPoolType];
        const wrap = document.querySelector(`.invest-sub-tabs-wrap[data-main-section="${sec}"]`);
        if (wrap) {
          const activeSub = wrap.querySelector('.invest-tab.active') || wrap.querySelector('.invest-tab');
          if (activeSub) activateSubPanel(sec, activeSub.dataset.panel);
        }
        loadPool();
        loadPoolMeta(currentPoolType);
      }
    });
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

  // 客户端股票池缓存：poolType -> 股票列表。切回已访问过的 tab 直接复用，不再发请求。
  // 后端 stockPool cache 已经做了 30s 缓存，前端再叠一层 session 内复用，避免来回切 tab 仍要走网络。
  const poolDataCache = new Map();
  let poolInFlight = null; // 同 poolType 并发请求合并：避免快速来回切换触发多个 fetch

  async function loadPool() {
    const wrap = document.getElementById('poolListWrap');
    if (!wrap) return;
    const cacheKey = currentPoolType || '__all__';
    // 命中缓存：直接复用并重渲染
    if (poolDataCache.has(cacheKey)) {
      poolData = poolDataCache.get(cacheKey);
      ensurePoolSearchCache();
      renderPool();
      return;
    }
    // 合并并发请求：同一 poolType 已有 in-flight 请求则复用其 Promise
    if (poolInFlight && poolInFlight.key === cacheKey) {
      await poolInFlight.promise;
      return;
    }
    try {
      const url = currentPoolType
        ? `api/invest/pool?poolType=${encodeURIComponent(currentPoolType)}`
        : 'api/invest/pool';
      const promise = (async () => {
        const res = await fetch(url);
        return res.json();
      })();
      poolInFlight = { key: cacheKey, promise };
      const data = await promise;
      poolData = data;
      poolDataCache.set(cacheKey, data);
      ensurePoolSearchCache();
      renderPool();
    } catch (e) {
      wrap.innerHTML = `<div class="pool-empty">加载失败：${e.message}</div>`;
    } finally {
      poolInFlight = null;
    }
  }

  // ===== 旧 pool-type-tab 兼容：保留函数签名以避免破坏外部调用 =====
// （DOM 上已不再有 .pool-type-tab 按钮，分类切换由一级 tab 接管）
  function initPoolTypeTabs() {
    // no-op: 原 .pool-type-tab 已被一级 tab 吸收
  }

  // 同步 UI 高亮：一级 tab（按 currentPoolType 决定哪个 active）
  function syncPoolTypeTabsUI() {
    const sec = POOL_TO_SECTION[currentPoolType];
    if (!sec) return;
    document.querySelectorAll('.invest-main-tab').forEach(t =>
      t.classList.toggle('active', t.dataset.section === sec)
    );
    document.querySelectorAll('.invest-sub-tabs-wrap').forEach(w =>
      w.hidden = (w.dataset.mainSection !== sec)
    );
  }

  // 兼容两种 hash：#pool=xxx（旧）和 #section=xxx（新）
  function readPoolTypeFromHash() {
    const h = window.location.hash || '';
    let m = /section=([a-z_]+)/.exec(h);
    if (m) {
      const sec = m[1];
      const pool = SECTION_TO_POOL[sec];
      if (pool) return pool;
    }
    m = /pool=([a-z_]+)/.exec(h);
    if (m) {
      const v = m[1];
      return POOL_TYPE_ORDER.includes(v) ? v : null;
    }
    return null;
  }

  function writeSectionToHash(sec, sub) {
    const newHash = '#section=' + sec + (sub ? '&sub=' + sub : '');
    if (window.location.hash !== newHash) {
      const url = window.location.pathname + window.location.search + newHash;
      window.history.replaceState(null, '', url);
    }
  }

  // 兼容旧调用：把 poolType 写成 section=
  function writePoolTypeToHash(type) {
    const sec = POOL_TO_SECTION[type];
    if (sec) writeSectionToHash(sec);
  }

  // ===== 股票池元信息（封面图 / 估值方法 / 每周机会点） =====
  async function loadPoolMeta(poolType) {
    if (!poolType) return;
    const titleEl = document.getElementById('poolMetaTitle');
    const coverEl = document.getElementById('poolMetaCover');
    const valEl = document.getElementById('poolMetaValuationHtml');
    const weekEl = document.getElementById('poolMetaWeeklyHtml');
    if (titleEl) titleEl.textContent = '加载中...';
    if (valEl) valEl.innerHTML = '<div class="pool-meta-loading">加载中...</div>';
    if (weekEl) weekEl.innerHTML = '<div class="pool-meta-loading">加载中...</div>';
    try {
      const res = await fetch(`api/invest/pool-meta/${encodeURIComponent(poolType)}`);
      if (!res.ok) {
        if (res.status === 404) {
          currentPoolMeta = null;
          renderPoolMetaEmpty();
          return;
        }
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `HTTP ${res.status}`);
      }
      const data = await res.json();
      currentPoolMeta = data;
      renderPoolMeta(data);
    } catch (e) {
      if (titleEl) titleEl.textContent = '加载失败';
      if (valEl) valEl.innerHTML = `<div class="pool-meta-empty">加载失败：${escHtml(e.message)}</div>`;
      if (weekEl) weekEl.innerHTML = '';
      currentPoolMeta = null;
    }
  }

  function renderPoolMeta(meta) {
    const titleEl = document.getElementById('poolMetaTitle');
    const coverEl = document.getElementById('poolMetaCover');
    const valEl = document.getElementById('poolMetaValuationHtml');
    const weekEl = document.getElementById('poolMetaWeeklyHtml');
    if (!meta) {
      renderPoolMetaEmpty();
      return;
    }
    if (titleEl) titleEl.textContent = meta.displayName || POOL_TYPE_LABELS[currentPoolType] || currentPoolType;
    if (coverEl) {
      const url = meta.coverImageUrl || `images/pool-covers/${currentPoolType}.svg`;
      coverEl.src = url;
      coverEl.alt = (meta.displayName || currentPoolType) + ' 封面图';
    }
    if (valEl) {
      valEl.innerHTML = meta.valuationMethodHtml
        || '<div class="pool-meta-empty">暂无估值方法说明，点击右上角"编辑"添加。</div>';
    }
    if (weekEl) {
      weekEl.innerHTML = meta.weeklyOpportunityHtml
        || '<div class="pool-meta-empty">本周暂无更新，点击右上角"编辑"添加。</div>';
    }
  }

  function renderPoolMetaEmpty() {
    const titleEl = document.getElementById('poolMetaTitle');
    const coverEl = document.getElementById('poolMetaCover');
    const valEl = document.getElementById('poolMetaValuationHtml');
    const weekEl = document.getElementById('poolMetaWeeklyHtml');
    if (titleEl) titleEl.textContent = POOL_TYPE_LABELS[currentPoolType] || currentPoolType;
    if (coverEl) coverEl.src = `images/pool-covers/${currentPoolType}.svg`;
    if (valEl) valEl.innerHTML = '<div class="pool-meta-empty">暂无元信息，点击右上角"编辑"添加。</div>';
    if (weekEl) weekEl.innerHTML = '';
  }

  // 估值方法编辑弹窗已迁移至 /gp/admin-users.html，前端 invest.html 不再提供入口
  // ===== 每周机会点（3×3 卡片） =====
  let weeklyOppCache = new Map(); // poolType -> SlotDTO[9]

  async function loadWeeklyOpportunity(poolType) {
    if (!poolType) return;
    const grid = document.getElementById('weeklyOppGrid');
    const count = document.getElementById('weeklyOppCount');
    if (grid) grid.innerHTML = '<div class="pool-meta-loading">加载中...</div>';
    if (count) count.textContent = '—/9';
    try {
      const res = await fetch(`api/invest/weekly-opportunity/${encodeURIComponent(poolType)}`);
      if (!res.ok) {
        if (res.status === 404) {
          weeklyOppCache.set(poolType, emptySlots());
          renderWeeklyOpportunity(poolType);
          return;
        }
        throw new Error(`HTTP ${res.status}`);
      }
      const data = await res.json();
      // 补齐到 9 个（后端已保证 9 个，防御一下）
      const slots = (Array.isArray(data) ? data : []).slice(0, 9);
      while (slots.length < 9) slots.push({ poolType, slotIndex: slots.length, stockCode: null, stockName: null, reason: null, updatedAt: null });
      weeklyOppCache.set(poolType, slots);
      renderWeeklyOpportunity(poolType);
    } catch (e) {
      if (grid) grid.innerHTML = `<div class="pool-meta-empty">加载失败：${escHtml(e.message)}</div>`;
      if (count) count.textContent = '0/9';
    }
  }

  function emptySlots() {
    return Array.from({ length: 9 }, (_, i) => ({
      poolType: '', slotIndex: i, stockCode: null, stockName: null, reason: null, updatedAt: null,
    }));
  }

  function renderWeeklyOpportunity(poolType) {
    const grid = document.getElementById('weeklyOppGrid');
    const count = document.getElementById('weeklyOppCount');
    const slots = weeklyOppCache.get(poolType) || emptySlots();
    const filled = slots.filter(s => s.stockCode).length;
    if (count) count.textContent = `${filled}/9`;

    if (!grid) return;
    grid.innerHTML = slots.map((s, i) => {
      if (!s.stockCode) {
        return `<div class="weekly-opp-cell is-empty" data-idx="${i}">·</div>`;
      }
      const stock = poolData.find(p => p.stockCode === s.stockCode);
      // level 直接读后端给的 valuationRange，不再前端再算 10×PS
      const level = stock ? (stock.valuationRange || '') : '';
      const levelLabel = level === '低估' ? '低估' : level === '泡沫' ? '高估' : level === '合理' ? '合理' : '';
      const lvlClass = level === '低估' ? 'low' : level === '泡沫' ? 'high' : level === '合理' ? 'fair' : '';
      const ts = s.updatedAt;
      const freshCls = ts && isFreshUpdate(ts) ? 'is-fresh' : (ts && isStaleUpdate(ts) ? 'is-stale' : '');
      const tsText = ts ? `${formatDateTimeShort(ts)} · ${agoText(parseBackendDate(ts))}` : '尚未更新';
      return `
        <div class="weekly-opp-cell" data-idx="${i}">
          <div class="weekly-opp-cell-name">${escHtml(s.stockName || s.stockCode)}${levelLabel ? ` <span class="lvl ${lvlClass}">(${levelLabel})</span>` : ''}</div>
          <div class="weekly-opp-cell-reason">${escHtml(s.reason || '')}</div>
          <div class="weekly-opp-cell-time ${freshCls}">
            <span class="dot"></span>${tsText}
          </div>
        </div>
      `;
    }).join('');

    // 管理按钮：仅 admin/manager 可见
    const editBtn = document.getElementById('weeklyOppEditBtn');
    if (editBtn) {
      editBtn.classList.toggle('hidden', !canManageInvest());
    }
  }

  // 时间格式化工具
  function formatDateTimeShort(iso) {
    if (!iso) return '';
    // 后端返回形如 "2026-06-29T21:30:00" 或 "2026-06-29 21:30:00"
    return String(iso).replace('T', ' ').slice(5, 16);
  }
  function parseBackendDate(iso) {
    if (!iso) return new Date(0);
    return new Date(String(iso).replace(' ', 'T'));
  }
  function isFreshUpdate(iso) {
    return (Date.now() - parseBackendDate(iso).getTime()) <= 48 * 3600 * 1000;
  }
  function isStaleUpdate(iso) {
    return (Date.now() - parseBackendDate(iso).getTime()) > 168 * 3600 * 1000;
  }
  function agoText(date) {
    const h = (Date.now() - date.getTime()) / 3600000;
    if (h < 1) return '刚刚';
    if (h < 24) return Math.floor(h) + ' 小时前';
    if (h < 168) return Math.floor(h / 24) + ' 天前';
    if (h < 720) return Math.floor(h / 24 / 7) + ' 周前';
    return Math.floor(h / 24 / 30) + ' 月前';
  }

  // 管理弹窗
  function openWeeklyOpportunityModal() {
    const slots = weeklyOppCache.get(currentPoolType) || emptySlots();
    const html = slots.map((s, i) => {
      const opts = poolData
        .filter(p => p.poolType === currentPoolType || !p.poolType)
        .map(p => `<option value="${escHtml(p.stockCode)}" ${p.stockCode === s.stockCode ? 'selected' : ''}>${escHtml(p.stockName || p.stockCode)} (${escHtml(p.stockCode)})</option>`)
        .join('');
      return `
        <div class="slot-row">
          <div class="slot-no">#${i + 1}</div>
          <select data-idx="${i}">
            <option value="">— 空 —</option>
            ${opts}
          </select>
          <input type="text" data-reason="${i}" maxlength="80" placeholder="推荐理由（1-2 句话）" value="${escHtml(s.reason || '')}" />
          <button type="button" class="slot-clear" data-clear="${i}">清空</button>
        </div>
      `;
    }).join('');
    const body = document.getElementById('weeklyOppModalBody');
    if (body) body.innerHTML = html;
    body.querySelectorAll('button[data-clear]').forEach(btn => {
      btn.addEventListener('click', () => {
        const i = btn.dataset.clear;
        body.querySelector(`select[data-idx="${i}"]`).value = '';
        body.querySelector(`input[data-reason="${i}"]`).value = '';
      });
    });
    document.getElementById('weeklyOppModalTitle').textContent = `管理 9 格 · ${POOL_TYPE_LABELS[currentPoolType] || currentPoolType}`;
    document.getElementById('weeklyOppModalMask').classList.remove('hidden');
  }
  function closeWeeklyOpportunityModal() {
    document.getElementById('weeklyOppModalMask').classList.add('hidden');
  }
  async function saveWeeklyOpportunityModal() {
    const body = document.getElementById('weeklyOppModalBody');
    const items = [];
    body.querySelectorAll('select[data-idx]').forEach(sel => {
      const i = sel.dataset.idx;
      const reasonEl = body.querySelector(`input[data-reason="${i}"]`);
      items.push({
        slotIndex: parseInt(i, 10),
        stockCode: sel.value || null,
        reason: reasonEl ? (reasonEl.value || '').trim() || null : null,
      });
    });
    const btn = document.getElementById('weeklyOppModalSave');
    btn.disabled = true; btn.textContent = '保存中...';
    try {
      const res = await fetch(`api/invest/weekly-opportunity/${encodeURIComponent(currentPoolType)}`, {
        method: 'PUT',
        headers: authJsonHeaders(),
        body: JSON.stringify({ slots: items }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `HTTP ${res.status}`);
      }
      // 刷新缓存
      weeklyOppCache.set(currentPoolType, items.map((it, i) => ({
        poolType: currentPoolType, slotIndex: i,
        stockCode: it.stockCode, stockName: null, reason: it.reason, updatedAt: new Date().toISOString(),
      })));
      renderWeeklyOpportunity(currentPoolType);
      closeWeeklyOpportunityModal();
    } catch (e) {
      alert('保存失败：' + e.message);
    } finally {
      btn.disabled = false; btn.textContent = '保存';
    }
  }

  function initWeeklyOpportunity() {
    const editBtn = document.getElementById('weeklyOppEditBtn');
    if (editBtn) editBtn.addEventListener('click', openWeeklyOpportunityModal);
    const closeBtn = document.getElementById('weeklyOppModalClose');
    if (closeBtn) closeBtn.addEventListener('click', closeWeeklyOpportunityModal);
    const cancelBtn = document.getElementById('weeklyOppModalCancel');
    if (cancelBtn) cancelBtn.addEventListener('click', closeWeeklyOpportunityModal);
    const saveBtn = document.getElementById('weeklyOppModalSave');
    if (saveBtn) saveBtn.addEventListener('click', saveWeeklyOpportunityModal);
    const mask = document.getElementById('weeklyOppModalMask');
    if (mask) mask.addEventListener('click', e => { if (e.target === mask) closeWeeklyOpportunityModal(); });
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
    head += '</tr></thead>';

    let body = '<tbody>';
    items.forEach(item => {
      let rowCls = '';
      if (item.alertState === 'buy_alerted') rowCls = 'alert-buy';
      else if (item.alertState === 'sell_alerted') rowCls = 'alert-sell';
      body += `<tr class="${rowCls}" data-id="${item.id}">`;
      POOL_COLUMNS.forEach(c => {
        body += `<td class="${c.cls || ''} ${c.hot ? 'pool-col-hot' : ''}" data-field="${c.key}">${renderCell(c, item)}</td>`;
      });
      body += '</tr>';
    });
    body += '</tbody>';

    wrap.innerHTML = `${renderPoolBoardSummary(items)}
      <div class="pool-table-scroll">
        <table class="pool-table pool-ps-table">${head}${body}</table>
      </div>
      <div class="pool-list-foot">
        <span>共 ${poolData.length} 只，当前显示 ${items.length} 只</span>
      </div>
      ${renderPoolCharts(items)}`;

    bindPoolEvents();
    applyPoolReadOnlyLocks();
  }

  /**
   * 2026-07-01 改：股票池编辑动作全部迁至 admin 后台，前台 invest.html 仅展示。
   * 表格里仍然有 inline `<input>` (POOL_COLUMNS 的 inline: 'number')，但加 readonly/disabled 锁死，
   * 防止误操作也给用户清晰的「只读」视觉反馈。
   */
  function applyPoolReadOnlyLocks() {
    document.querySelectorAll('#poolListWrap .pool-cell-input, #poolListWrap .pool-cell-select').forEach(el => {
      el.setAttribute('readonly', 'readonly');
      el.setAttribute('disabled', 'disabled');
    });
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
    return `<span class="pool-cell-pct ${cls}">${sign}${n.toFixed(2)}%</span>`;
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

  /**
   * 渲染「估值情况」单元格：后端已用 10×PS 算好三档 (valuationRange) + 偏离百分比
   * (valuationDegree) + 参照年 (valuationRefYear)。前端只负责套样式和拼接文本。
   * 单格紧凑展示：「低估 27年 -45%」「泡沫 28年 +85%」「合理」「—」
   */
  function renderValuationRangeCell(item, col) {
    const level = item.valuationRange;
    if (!level) return '<span style="color:#d1d5db">—</span>';
    const cls = level === '低估' ? 'low' : (level === '泡沫' ? 'bubble' : (level === '合理' ? 'fair' : 'empty'));
    const degree = item.valuationDegree;
    const refYear = item.valuationRefYear;
    let degreeHtml = '';
    if (degree != null && refYear != null) {
      const yearShort = String(refYear).slice(-2);  // 2027 → 27
      const sign = degree > 0 ? '+' : '';             // 负数自带 - 号
      const numStr = `${sign}${Number(degree).toFixed(2)}%`;
      degreeHtml = `<span class="valuation-degree" title="相比 ${refYear} 年 10×PS 合理市值的偏离">${yearShort}年 ${numStr}</span>`;
    }
    return `<span class="pool-tag-input valuation-${cls}">${escHtml(level)}</span>${degreeHtml}`;
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

  // 估值三档 + 偏离百分比 都由后端 InvestService.inferValuationRange 计算，
  // 通过 /api/invest/pool 响应的 valuationRange / valuationDegree / valuationRefYear
  // 三个字段带到前端。前端不再重复 10×PS 计算，避免后端/前端算法漂移。
  function isFairZone(item) { return item.valuationRange === '合理'; }
  function isLowZone(item)  { return item.valuationRange === '低估'; }
  function isBubbleZone(item){ return item.valuationRange === '泡沫'; }

  function bindPoolEvents() {
    // 2026-07-01 改：股票池编辑动作全部迁至 admin 后台，前台不再绑任何写操作事件。
    // 表格里的 input/select 由 renderPool → applyPoolReadOnlyLocks 加 readonly/disabled 锁死。
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
      ['psMarketCap', 'psRevY0', 'psRevY1', 'psRevY2', 'psNetMargin'].forEach(id => {
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
    const nm = parseFloat(document.getElementById('psNetMargin')?.value);
    const result = document.getElementById('psResult');
    if (!result) return;

    if (isNaN(mc) || isNaN(r0) || mc <= 0 || r0 <= 0) {
      result.innerHTML = '<div style="color:#9ca3af;font-size:13px;padding:8px 0">请输入有效的市值和营收数据</div>';
      return;
    }

    // 2026-07-01 改：前端只负责收集表单 + 渲染后端返回的表格，不再自己算 PS。
    // 估值逻辑（Y1×10 vs Y2×10、净利率提示）统一在 Ps10ValuationService。
    const payload = {
      marketCap: mc,
      revenueY0: r0,
      revenueY1: isNaN(r1) || r1 <= 0 ? null : r1,
      revenueY2: isNaN(r2) || r2 <= 0 ? null : r2,
      netMarginPct: isNaN(nm) || nm <= 0 ? null : nm,
    };

    result.innerHTML = '<div style="color:#9ca3af;font-size:13px;padding:8px 0">计算中…</div>';

    fetch('/api/valuation/ps10', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
      .then(res => {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(data => renderPsResult(result, data))
      .catch(err => {
        result.innerHTML = `<div style="color:#dc2626;font-size:13px;padding:8px 0">计算失败：${escHtml(err.message)}</div>`;
      });
  }

  /**
   * 渲染后端返回的 PS 表格 + 总体结论 verdict。
   * 样式类复用原有 ps-ok / ps-warn / ps-bad，颜色规则沿用旧前端：< 5 低估，5-10 合理，> 10 高估。
   */
  function renderPsResult(container, data) {
    const verdict = data.verdict || '—';
    const commentary = data.commentary || '';
    const rows = data.rows || [];

    const verdictCls = verdict === '低估' ? 'low'
        : verdict === '泡沫' ? 'high'
        : verdict === '合理' ? 'fair' : 'empty';

    let html = `<div class="valuation-verdict-banner valuation-${verdictCls}" style="margin-bottom:8px">
        <b>结论：${escHtml(verdict)}</b>
        <span class="valuation-verdict-commentary">${escHtml(commentary)}</span>
      </div>`;
    if (rows.length > 0) {
      html += '<table class="ps-table"><thead><tr><th>对应营收年份</th><th>营收（亿）</th><th>对应PS倍数</th><th>合理市值（亿）</th><th>判断</th></tr></thead><tbody>';
      rows.forEach(row => {
        const sub = row.subVerdict || '—';
        const cls = sub === '低估' ? 'ps-ok'
            : sub === '高估' ? 'ps-bad'
            : sub === '合理' ? 'ps-warn' : '';
        const label = sub === '低估' ? '✓ 低估'
            : sub === '高估' ? '✗ 高估'
            : sub === '合理' ? '⚠ 合理' : sub;
        const psStr = row.psMultiple != null ? row.psMultiple.toFixed(1) + ' 倍' : '—';
        html += `<tr>
          <td>${escHtml(row.label)}</td>
          <td>${(row.revenue != null ? Number(row.revenue) : 0).toFixed(2)}</td>
          <td class="${cls}" style="font-weight:700">${psStr}</td>
          <td>${(row.fairCap != null ? Number(row.fairCap) : 0).toFixed(1)}</td>
          <td class="${cls}" style="font-weight:700">${label}</td>
        </tr>`;
      });
      html += '</tbody></table>';
    }
    container.innerHTML = html;
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
  window.Invest = {};

  // ---- 等 DOM 就绪后初始化 ----
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
