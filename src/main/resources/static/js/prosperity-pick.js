(function () {
  'use strict';

  const API_BASE = '/gp/api/invest/prosperity-pick';

  const $ = (id) => document.getElementById(id);
  const els = {
    keyword: $('ppKeyword'),
    analyzeBtn: $('ppAnalyzeBtn'),
    forceBtn: $('ppForceBtn'),
    recent: $('ppRecent'),
    loading: $('ppLoading'),
    error: $('ppError'),
    result: $('ppResult'),
    profile: $('ppProfile'),
    industry: $('ppIndustry'),
    company: $('ppCompany'),
    valuation: $('ppValuation'),
    technical: $('ppTechnical'),
    capital: $('ppCapital'),
    summary: $('ppSummary'),
    infographicBtn: $('ppInfographicBtn'),
    infographicHint: $('ppInfographicHint'),
    infographic: $('ppInfographic'),
    anchorNav: $('ppAnchorNav'),
  };

  let currentResult = null;
  let stepTimer = null;

  // ---- 加载态步骤模拟 ----
  function startStepAnimation() {
    let idx = 0;
    const steps = document.querySelectorAll('.pp-step');
    steps.forEach(s => s.classList.remove('active', 'done'));
    if (steps[0]) steps[0].classList.add('active');
    stepTimer = setInterval(() => {
      if (steps[idx]) {
        steps[idx].classList.remove('active');
        steps[idx].classList.add('done');
      }
      idx++;
      if (idx >= steps.length) {
        clearInterval(stepTimer);
        stepTimer = null;
        return;
      }
      if (steps[idx]) steps[idx].classList.add('active');
    }, 8000);
  }

  function stopStepAnimation() {
    if (stepTimer) {
      clearInterval(stepTimer);
      stepTimer = null;
    }
    document.querySelectorAll('.pp-step').forEach(s => s.classList.remove('active', 'done'));
  }

  function showError(msg) {
    els.error.textContent = msg;
    els.error.classList.remove('hidden');
  }
  function hideError() {
    els.error.classList.add('hidden');
    els.error.textContent = '';
  }

  // ---- API ----
  async function apiAnalyze(keyword, force) {
    const url = `${API_BASE}?keyword=${encodeURIComponent(keyword)}&force=${force ? 'true' : 'false'}`;
    const r = await fetch(url);
    if (!r.ok) {
      const t = await r.text();
      throw new Error(parseErrorMsg(t) || `HTTP ${r.status}`);
    }
    return r.json();
  }
  async function apiInfographic(id) {
    const r = await fetch(`${API_BASE}/${id}/infographic`, { method: 'POST' });
    if (!r.ok) {
      const t = await r.text();
      throw new Error(parseErrorMsg(t) || `HTTP ${r.status}`);
    }
    return r.json();
  }
  async function apiRecent() {
    const r = await fetch(`${API_BASE}/recent`);
    if (!r.ok) return [];
    return r.json();
  }
  async function apiGet(id) {
    const r = await fetch(`${API_BASE}/${id}`);
    if (!r.ok) return null;
    return r.json();
  }
  function parseErrorMsg(t) {
    try {
      const j = JSON.parse(t);
      return j.message || j.error || null;
    } catch { return t; }
  }

  // ---- 渲染 ----
  function renderProfile(p, data) {
    const { stockName, stockCode, exchange, board, industry, currentPrice,
      totalMarketCap, peTtm, pb, psTtm, latestRevenue, latestNetProfit, latestReportDate } = p || {};
    const badges = [];
    if (board) badges.push(`<span class="pp-badge">${escape(board)}</span>`);
    if (data.degraded) badges.push(`<span class="pp-badge pp-badge-warn">演示数据</span>`);
    if (data.cached) badges.push(`<span class="pp-badge">缓存</span>`);
    const meta = [];
    if (currentPrice != null) meta.push(`现价 <b>¥${currentPrice}</b>`);
    if (totalMarketCap != null) meta.push(`总市值 <b>${totalMarketCap} 亿</b>`);
    if (peTtm != null) meta.push(`PE ${peTtm}`);
    if (pb != null) meta.push(`PB ${pb}`);
    if (psTtm != null) meta.push(`PS ${psTtm}`);
    if (industry) meta.push(`行业：${escape(industry)}`);
    const fin = [];
    if (latestReportDate) fin.push(`最新报告期 ${latestReportDate}`);
    if (latestRevenue) fin.push(`营收 ${latestRevenue}`);
    if (latestNetProfit) fin.push(`净利润 ${latestNetProfit}`);

    els.profile.innerHTML = `
      <div>
        <div class="pp-profile-name">
          ${escape(stockName || '')}
          <span style="font-size:14px;color:#6b7280;font-weight:500">${escape(stockCode || '')}</span>
          ${badges.join('')}
        </div>
        <div class="pp-profile-meta">${meta.join(' · ')}</div>
        ${fin.length ? `<div class="pp-profile-meta">${fin.join(' · ')}</div>` : ''}
      </div>
      <div class="pp-profile-actions">
        <span class="pp-cached-tag">分析日期 ${data.analysisDate || ''}</span>
      </div>
    `;
  }

  function pairCard(label, value) {
    if (!value) return '';
    return `<div class="pp-card"><div class="pp-card-label">${escape(label)}</div><div class="pp-card-value">${escape(value)}</div></div>`;
  }

  function renderIndustry(i) {
    if (!i) { els.industry.innerHTML = '<div class="pp-card">无数据</div>'; return; }
    els.industry.innerHTML = [
      pairCard('1. 周期位置', i.cyclePosition),
      pairCard('2. 上一轮周期复盘', i.lastCycleReview),
      pairCard('3. 12 个月拐点预判', i.next12mForecast),
      pairCard('4. 行业进入壁垒', i.entryBarrier),
      pairCard('5. 行业生命周期', i.lifeStage),
      pairCard('6. 竞争格局与公司地位', i.competition),
      pairCard('7. 全球共振程度', i.globalResonance),
    ].join('');
  }

  function renderCompany(c) {
    if (!c) { els.company.innerHTML = '<div class="pp-card">无数据</div>'; return; }
    els.company.innerHTML = [
      pairCard('1. 业务结构与新增长曲线', c.businessMix),
      pairCard('2. 12 季度业绩与驱动因子', c.quarterly12),
      pairCard('3. 未来 2 年业绩驱动', c.next2yDriver),
      pairCard('4. 护城河', c.moat),
      pairCard('5. 政策契合度（十五五）', c.policyFit),
      pairCard('6. 全球化进展', c.globalization),
      pairCard('7. 产品/服务价格趋势', c.priceTrend),
      pairCard('8. 董事长画像', c.chairman),
      pairCard('9. 概念故事与股价催化剂', c.catalysts),
    ].join('');
  }

  function verdictClass(v) {
    if (!v) return '';
    if (/便宜|低估/.test(v)) return 'pp-verdict-cheap';
    if (/合理/.test(v)) return 'pp-verdict-fair';
    if (/略贵|高估/.test(v)) return 'pp-verdict-expensive';
    if (/泡沫/.test(v)) return 'pp-verdict-bubble';
    return '';
  }

  function renderValuation(v) {
    if (!v) { els.valuation.innerHTML = '<div class="pp-card">无数据</div>'; return; }
    const rows = (v.methods || []).map(m => `
      <tr>
        <td>${escape(m.name || '')}</td>
        <td>${escape(m.current || '')}</td>
        <td>${escape(m.reasonable || '')}</td>
        <td class="${verdictClass(m.verdict)}">${escape(m.verdict || '')}</td>
      </tr>
    `).join('');
    els.valuation.innerHTML = `
      <div class="pp-valuation-meta">
        <span>公司类型：<b>${escape(v.type || '-')}</b></span>
        <span>综合判定：<b class="${verdictClass(v.verdict)}">${escape(v.verdict || '-')}</b></span>
      </div>
      <table class="pp-valuation-table">
        <thead><tr><th>估值方法</th><th>当前值</th><th>合理区间</th><th>结论</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="4" style="text-align:center;color:#9ca3af">未提供估值方法明细</td></tr>'}</tbody>
      </table>
      <div class="pp-target-row">
        <div class="pp-target-pill">
          <div class="pp-target-pill-label">2026 目标价</div>
          <div class="pp-target-pill-value">${escape(v.target2026 || '-')}</div>
        </div>
        <div class="pp-target-pill">
          <div class="pp-target-pill-label">2027 目标价</div>
          <div class="pp-target-pill-value">${escape(v.target2027 || '-')}</div>
        </div>
      </div>
      ${v.reasoning ? `<div class="pp-card"><div class="pp-card-label">估值依据</div><div class="pp-card-value">${escape(v.reasoning)}</div></div>` : ''}
    `;
  }

  function techRow(label, value) {
    if (!value) return '';
    return `<div class="pp-tech-row"><div class="pp-tech-row-label">${escape(label)}</div><div class="pp-tech-row-value">${escape(value)}</div></div>`;
  }

  function renderTechnical(t) {
    if (!t) { els.technical.innerHTML = '<div class="pp-tech-row">无数据</div>'; return; }
    els.technical.innerHTML = [
      techRow('趋势线', t.trendLine),
      techRow('均线', t.ma),
      techRow('成交量', t.volume),
      techRow('MACD', t.macd),
      t.verdict ? `<div class="pp-tech-verdict">综合判定：${escape(t.verdict)}</div>` : '',
    ].join('');
  }

  function renderCapital(c) {
    if (!c) { els.capital.innerHTML = '<div class="pp-tech-row">无数据</div>'; return; }
    els.capital.innerHTML = [
      techRow('主力资金', c.mainNetIn),
      techRow('北向资金', c.northbound),
      techRow('龙虎榜', c.dragonTiger),
      c.verdict ? `<div class="pp-tech-verdict">综合判定：${escape(c.verdict)}</div>` : '',
    ].join('');
  }

  function renderSummary(s) {
    if (!s) { els.summary.innerHTML = '<div>无数据</div>'; return; }
    const bullets = (s.bullets || []).map(b => `<li>${escape(b)}</li>`).join('');
    els.summary.innerHTML = `
      ${bullets ? `<ul class="pp-summary-bullets">${bullets}</ul>` : ''}
      ${s.oneLiner ? `<div class="pp-summary-oneliner">${escape(s.oneLiner)}</div>` : ''}
    `;
  }

  function escape(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function renderResult(data) {
    if (data && data.degraded) {
      els.result.classList.add('hidden');
      showError('本次分析未生成真实结论，已停止展示演示数据。请稍后重试或点击“重新分析”。');
      return;
    }
    currentResult = data;
    hideError();
    els.result.classList.remove('hidden');
    renderProfile(data.profile, data);
    const a = data.analysis || {};
    renderIndustry(a.industry);
    renderCompany(a.company);
    renderValuation(a.valuation);
    renderTechnical(a.technical);
    renderCapital(a.capital);
    renderSummary(a.summary);

    // 信息图区
    if (data.imageUrl) {
      els.infographic.innerHTML = `<img src="${escape(data.imageUrl)}" alt="信息图" />`;
      els.infographicBtn.textContent = '重新生成 ↻';
      els.infographicHint.textContent = '已生成，可点击按钮重新生成';
    } else {
      els.infographic.innerHTML = '';
      els.infographicBtn.textContent = '生成信息图 ✨';
      els.infographicHint.textContent = '点击按钮异步生成可爱卡通风格信息图（需 30~60s）';
    }
  }

  async function runAnalyze(keyword, force) {
    if (!keyword || !keyword.trim()) {
      showError('请输入股票名称或代码');
      return;
    }
    hideError();
    els.result.classList.add('hidden');
    els.loading.classList.remove('hidden');
    startStepAnimation();
    els.analyzeBtn.disabled = true;
    els.forceBtn.disabled = true;
    try {
      const data = await apiAnalyze(keyword.trim(), !!force);
      renderResult(data);
      loadRecent();
    } catch (e) {
      showError('分析失败：' + (e.message || e));
    } finally {
      els.loading.classList.add('hidden');
      stopStepAnimation();
      els.analyzeBtn.disabled = false;
      els.forceBtn.disabled = false;
    }
  }

  async function runInfographic() {
    if (!currentResult || currentResult.id == null) {
      showError('请先完成分析再生成信息图');
      return;
    }
    els.infographicBtn.disabled = true;
    els.infographic.innerHTML = '<div class="pp-infographic-loading">信息图生成中，请稍候 30~60s…</div>';
    try {
      const r = await apiInfographic(currentResult.id);
      if (r.imageUrl) {
        currentResult.imageUrl = r.imageUrl;
        els.infographic.innerHTML = `<img src="${escape(r.imageUrl)}" alt="信息图" />`;
        els.infographicBtn.textContent = '重新生成 ↻';
        els.infographicHint.textContent = '已生成，可点击按钮重新生成';
      } else {
        els.infographic.innerHTML = '';
        showError('信息图生成失败：未返回图片地址');
      }
    } catch (e) {
      els.infographic.innerHTML = '';
      showError('信息图生成失败：' + (e.message || e));
    } finally {
      els.infographicBtn.disabled = false;
    }
  }

  async function loadRecent() {
    try {
      const list = await apiRecent();
      const realList = (list || []).filter(r => !r.degraded);
      els.recent.innerHTML = realList.map(renderRecentItem).join('')
        || '<div class="pp-recent-empty">近 3 天暂无真实分析记录</div>';
      els.recent.querySelectorAll('.pp-recent-item').forEach(item => {
        item.addEventListener('click', async () => {
          const id = item.getAttribute('data-id');
          const data = await apiGet(id);
          if (data) {
            if (data.degraded) {
              showError('该缓存记录是演示数据，已停止展示。请重新分析获取真实结论。');
              return;
            }
            els.keyword.value = data.stockName || data.stockCode;
            renderResult(data);
            window.scrollTo({ top: 0, behavior: 'smooth' });
          }
        });
      });
    } catch (e) {
      // 静默
    }
  }

  function renderRecentItem(r) {
    const verdicts = [
      r.valuationVerdict ? `估值：${r.valuationVerdict}` : '',
      r.technicalVerdict ? `技术：${r.technicalVerdict}` : '',
      r.capitalVerdict ? `资金：${r.capitalVerdict}` : '',
    ].filter(Boolean);
    const summary = r.summaryOneLiner || (r.summaryBullets || [])[0] || '查看缓存分析结果';
    return `
      <button type="button" class="pp-recent-item" data-id="${r.id}">
        <span class="pp-recent-main">
          <span class="pp-recent-name">${escape(r.stockName || r.stockCode || '')}</span>
          <span class="pp-recent-code">${escape(r.stockCode || '')}</span>
        </span>
        <span class="pp-recent-one">${escape(summary)}</span>
        ${verdicts.length ? `<span class="pp-recent-verdicts">${verdicts.slice(0, 3).map(v => `<span>${escape(v)}</span>`).join('')}</span>` : ''}
        <span class="pp-recent-date">${escape(r.analysisDate || '')}</span>
      </button>
    `;
  }

  function activateAnchor(targetId) {
    if (!els.anchorNav) return;
    els.anchorNav.querySelectorAll('.pp-anchor').forEach(anchor => {
      anchor.classList.toggle('active', anchor.getAttribute('data-target') === targetId);
    });
  }

  function bindAnchorNav() {
    if (!els.anchorNav) return;
    els.anchorNav.addEventListener('click', (e) => {
      const anchor = e.target.closest('.pp-anchor');
      if (!anchor) return;
      const targetId = anchor.getAttribute('data-target');
      const target = targetId ? document.getElementById(targetId) : null;
      if (!target) return;
      activateAnchor(targetId);
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      if (history.replaceState) {
        history.replaceState(null, '', `${window.location.pathname}${window.location.search}#${targetId}`);
      }
    });
  }

  // ---- 事件绑定 ----
  els.analyzeBtn.addEventListener('click', () => runAnalyze(els.keyword.value, false));
  els.forceBtn.addEventListener('click', () => runAnalyze(els.keyword.value, true));
  els.keyword.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') runAnalyze(els.keyword.value, false);
  });
  els.infographicBtn.addEventListener('click', runInfographic);
  bindAnchorNav();

  // 初始化
  loadRecent();

  // 支持 URL ?keyword= 自动分析
  const params = new URLSearchParams(window.location.search);
  const initKeyword = params.get('keyword');
  if (initKeyword) {
    els.keyword.value = initKeyword;
    runAnalyze(initKeyword, false);
  }
})();
