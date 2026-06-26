(function () {
  'use strict';

  const API_BASE = '/gp/api/stock-analysis';
  const API_KEY = 'wmq-gp-secret-2026';
  const PAGE_SIZE = 12;

  const $ = (id) => document.getElementById(id);
  const els = {
    keyword: $('ppKeyword'),
    method: $('ppMethod'),
    analyzeBtn: $('ppAnalyzeBtn'),
    refreshBtn: $('ppRefreshBtn'),
    loading: $('ppLoading'),
    error: $('ppError'),
    result: $('ppResult'),
    empty: $('ppEmpty'),
    profile: $('ppProfile'),
    overview: $('ppOverview'),
    reportTime: $('ppReportTime'),
    industry: $('ppIndustry'),
    company: $('ppCompany'),
    finance: $('ppFinanceCards'),
    dbFinancial: $('ppDbFinancial'),
    valuation: $('ppValuation'),
    market: $('ppMarket'),
    summary: $('ppSummary'),
    pdfBtn: $('ppPdfBtn'),
    shareBtn: $('ppShareBtn'),
    historyMeta: $('ppHistoryMeta'),
    historyList: $('ppHistoryList'),
    pagination: $('ppPagination'),
    filterKw: $('ppFilterKw'),
    filterStatus: $('ppFilterStatus'),
    finCanvas: $('ppFinCanvas'),
    finChartWrap: $('ppFinChartWrap'),
    anchorNav: $('ppAnchorNav'),
    researchSection: $('pp-section-research'),
    consensus: $('ppConsensus'),
    researchList: $('ppResearchList')
  };

  let currentPage = 0;
  let currentRecordId = null;
  let currentReport = null;
  let pollingTimers = new Map();
  let stepTimer = null;
  let finChart = null;
  let currentMethod = 'full';

  // ============================================================
  // 4 套分析方法的页面结构 (anchor + section 标题 + 渲染权重)
  // 是否启用独立 "Wind 研报" 段: purple/gaojingqi 是, full/五维 折进现有段
  // ============================================================
  const METHOD_HAS_RESEARCH_SECTION = {
    full: false,
    purple_perilla: true,
    gaojingqi: true,
    five_dimension: false
  };
  const METHOD_TEMPLATES = {
    full: {
      label: '全量分析',
      anchors: [
        { id: 'pp-section-overview', no: '①', label: '总览' },
        { id: 'pp-section-industry',  no: '②', label: '行业' },
        { id: 'pp-section-company',   no: '③', label: '公司' },
        { id: 'pp-section-finance',   no: '④', label: '财务' },
        { id: 'pp-section-market',    no: '⑤', label: '技术资金' },
        { id: 'pp-section-summary',   no: '⑥', label: '结论' }
      ],
      sections: {
        'pp-section-industry': '行业景气',
        'pp-section-company':  '公司质地',
        'pp-section-finance':  '财务趋势',
        'pp-section-market':   '技术与资金',
        'pp-section-summary':  '结论 · 一句话'
      },
      weights: { industry: 1, company: 1, finance: 1, market: 1, valuation: 1 }
    },
    purple_perilla: {
      label: '产业分析',
      anchors: [
        { id: 'pp-section-overview', no: '①', label: '总览' },
        { id: 'pp-section-industry',  no: '②', label: '产业链定位' },
        { id: 'pp-section-company',   no: '③', label: '竞争格局' },
        { id: 'pp-section-finance',   no: '④', label: '护城河' },
        { id: 'pp-section-market',    no: '⑤', label: '下单三问' },
        { id: 'pp-section-summary',   no: '⑥', label: '结论' },
        { id: 'pp-section-research',  no: '⑦', label: 'Wind 研报' }
      ],
      sections: {
        'pp-section-industry': '产业链定位 · 全球玩家',
        'pp-section-company':  '竞争格局 · 地缘优势',
        'pp-section-finance':  '护城河 · 业务结构',
        'pp-section-market':   '下单三问 · 不可替代/玩家数/需求',
        'pp-section-summary':  '结论 · 一句话',
        'pp-section-research': 'Wind 研报 · 一致预期 + 卖方研报'
      },
      weights: { industry: 2, company: 2, finance: 2, market: 2, valuation: 0 }
    },
    gaojingqi: {
      label: '景气度分析',
      anchors: [
        { id: 'pp-section-overview', no: '①', label: '总览' },
        { id: 'pp-section-industry',  no: '②', label: '行业景气' },
        { id: 'pp-section-company',   no: '③', label: '拐点信号' },
        { id: 'pp-section-finance',   no: '④', label: '政策共振' },
        { id: 'pp-section-market',    no: '⑤', label: '技术资金' },
        { id: 'pp-section-summary',   no: '⑥', label: '结论' },
        { id: 'pp-section-research',  no: '⑦', label: 'Wind 研报' }
      ],
      sections: {
        'pp-section-industry': '行业景气 · 周期位置 · 上轮复盘',
        'pp-section-company':  '未来12月拐点 · 12季度业绩',
        'pp-section-finance':  '全球共振 · 政策契合 · 业务结构',
        'pp-section-market':   '技术 · 资金 · 行情区间',
        'pp-section-summary':  '结论 · 一句话',
        'pp-section-research': 'Wind 研报 · 一致预期 + 卖方研报'
      },
      weights: { industry: 3, company: 2, finance: 1, market: 2, valuation: 1 }
    },
    five_dimension: {
      label: '增长五维分析',
      anchors: [
        { id: 'pp-section-overview', no: '①', label: '总览' },
        { id: 'pp-section-industry',  no: '②', label: '稀缺卡位' },
        { id: 'pp-section-company',   no: '③', label: '成长动力' },
        { id: 'pp-section-finance',   no: '④', label: '业绩兑现' },
        { id: 'pp-section-market',    no: '⑤', label: '瓶颈壁垒' },
        { id: 'pp-section-summary',   no: '⑥', label: '估值·结论' }
      ],
      sections: {
        'pp-section-industry': '① 稀缺卡位',
        'pp-section-company':  '② 成长动力 · 第一/第二曲线',
        'pp-section-finance':  '③ 业绩兑现 · 历史/当期/远期',
        'pp-section-market':   '④ 瓶颈与壁垒 · 护城河/约束',
        'pp-section-summary':  '⑤ 估值阶梯 · 风险 · 结论'
      },
      weights: { industry: 2, company: 2, finance: 2, market: 2, valuation: 3 }
    }
  };

  function getTemplate(method) {
    return METHOD_TEMPLATES[method] || METHOD_TEMPLATES.full;
  }

  function setAnchorNav(template) {
    if (!els.anchorNav) return;
    els.anchorNav.innerHTML = template.anchors.map((a, i) => `
      <button type="button" class="pp-anchor ${i === 0 ? 'active' : ''}" data-target="${a.id}">
        <span>${a.no}</span> ${a.label}
      </button>
    `).join('');
  }

  function setAllSectionTitles(template) {
    Object.entries(template.sections).forEach(([sectionId, label]) => {
      const sec = document.getElementById(sectionId);
      if (!sec) return;
      const title = sec.querySelector('.pp-section-title');
      if (!title) return;
      const noEl = title.querySelector('.pp-section-no');
      // 保留 pp-overview-head 里的复制分享链接按钮
      const extraBtns = Array.from(title.querySelectorAll('button')).filter((b) => !b.classList.contains('pp-section-no'));
      // 清空标题再重建
      title.innerHTML = '';
      if (noEl) {
        const clone = noEl.cloneNode(true);
        title.appendChild(clone);
      } else {
        const num = template.anchors.find((a) => a.id === sectionId)?.no || '';
        if (num) {
          const span = document.createElement('span');
          span.className = 'pp-section-no';
          span.textContent = num;
          title.appendChild(span);
        }
      }
      title.appendChild(document.createTextNode(' ' + label));
      extraBtns.forEach((b) => title.appendChild(b));
    });
  }

  function setMethod(value) {
    if (!els.method) return;
    currentMethod = value || 'full';
    const buttons = els.method.querySelectorAll('.pp-method-btn');
    buttons.forEach((btn) => {
      const isActive = btn.dataset.value === currentMethod;
      btn.classList.toggle('is-active', isActive);
      btn.setAttribute('aria-selected', isActive ? 'true' : 'false');
    });
  }

  function getSelectedMethod() {
    return currentMethod || 'full';
  }

  if (els.method) {
    els.method.addEventListener('click', (e) => {
      const btn = e.target.closest('.pp-method-btn');
      if (!btn || !els.method.contains(btn)) return;
      const value = btn.dataset.value;
      if (value) setMethod(value);
    });
  }

  els.analyzeBtn.addEventListener('click', submitAnalyze);
  els.refreshBtn.addEventListener('click', () => loadHistory(currentPage));
  els.keyword.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitAnalyze();
  });
  els.filterKw.addEventListener('input', debounce(() => loadHistory(0), 300));
  els.filterStatus.addEventListener('change', () => loadHistory(0));
  els.pdfBtn.addEventListener('click', downloadPdf);
  if (els.shareBtn) els.shareBtn.addEventListener('click', copyShareLink);
  els.anchorNav.addEventListener('click', onAnchorClick);

  initFromQuery();
  loadHistory(0);

  function initFromQuery() {
    const params = new URLSearchParams(window.location.search);
    const keyword = params.get('keyword');
    const recordId = params.get('record');
    const method = params.get('method');
    if (method) setMethod(method);
    if (recordId) {
      const id = Number(recordId);
      if (Number.isFinite(id) && id > 0) {
        openRecord(id);
      }
    } else if (keyword) {
      els.keyword.value = keyword;
      submitAnalyze();
    }
  }

  async function submitAnalyze() {
    const code = els.keyword.value.trim();
    if (!code) {
      showError('请输入股票代码或名称');
      return;
    }
    clearError();
    showLoading();
    els.analyzeBtn.disabled = true;
    try {
      const response = await fetch(`${API_BASE}/submit?api_key=${encodeURIComponent(API_KEY)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          code,
          method: getSelectedMethod(),
          years: 2,
          lite: true,
          quoteDays: 60
        })
      });
      const json = await response.json();
      if (!json.ok) throw new Error(json.message || '提交失败');
      currentRecordId = json.recordId;
      await loadHistory(0);
      startPolling(currentRecordId);
    } catch (error) {
      hideLoading();
      showError(error.message || '提交失败');
    } finally {
      els.analyzeBtn.disabled = false;
    }
  }

  async function loadHistory(page) {
    const kw = els.filterKw.value.trim();
    const status = els.filterStatus.value;
    try {
      const response = await fetch(`${API_BASE}/list?page=${page}&size=${PAGE_SIZE}&kw=${encodeURIComponent(kw)}&status=${encodeURIComponent(status)}`);
      const json = await response.json();
      if (!json.ok) throw new Error(json.message || '加载失败');
      currentPage = json.page;
      renderHistory(json.records || []);
      renderPagination(json.total || 0, json.page || 0, json.size || PAGE_SIZE);
      els.historyMeta.textContent = `共 ${json.total || 0} 条 · 第 ${(json.page || 0) + 1} 页`;
      autoResumePolling(json.records || []);
    } catch (error) {
      els.historyMeta.textContent = '历史记录加载失败';
      els.historyList.innerHTML = `<div class="pp-history-item">${escapeHtml(error.message || '加载失败')}</div>`;
    }
  }

  function autoResumePolling(records) {
    records.forEach((record) => {
      if ((record.status === 'PENDING' || record.status === 'RUNNING') && !pollingTimers.has(record.id)) {
        startPolling(record.id);
      }
    });
  }

  function renderHistory(records) {
    if (!records.length) {
      els.historyList.innerHTML = '<div class="pp-history-item">暂无分析记录</div>';
      return;
    }
    els.historyList.innerHTML = records.map((record) => {
      const price = record.currentPrice == null ? '-' : `${Number(record.currentPrice).toFixed(2)} 元`;
      const tags = [];
      if (record.moatScore != null) tags.push(`<span class="pp-history-tag">护城河 ${record.moatScore}/10</span>`);
      if (record.sourceCoverage != null) tags.push(`<span class="pp-history-tag">来源 ${record.sourceCoverage}</span>`);
      if (record.hasReport) tags.push('<span class="pp-history-tag">富报告</span>');
      const isFailed = record.status === 'FAILED';
      const deleteBtn = isFailed
        ? `<button class="pp-history-delete" type="button" data-action="delete" data-id="${record.id}" title="删除这条失败记录">删除</button>`
        : '';
      return `
        <div class="pp-history-item${isFailed ? ' is-failed' : ''}" data-id="${record.id}">
          <div class="pp-history-top">
            <div>
              <div class="pp-history-name">${escapeHtml(record.stockName || record.stockCodeRaw || record.stockCode || '-')}</div>
              <div class="pp-history-code">${escapeHtml(record.stockCodeRaw || record.stockCode || '-')} · ${price}</div>
            </div>
            <div class="pp-history-top-right">
              <div class="pp-history-status ${statusClass(record.status)}">${escapeHtml(record.status || '-')}</div>
              ${deleteBtn}
            </div>
          </div>
          <div class="pp-history-summary">${escapeHtml(record.summaryOneLiner || record.verdict || '等待统一富报告生成')}</div>
          <div class="pp-history-tags">${tags.join('')}</div>
          <div class="pp-history-bottom" style="margin-top:8px;">
            <div class="pp-history-time">${formatTime(record.submittedAt)}</div>
            <div class="pp-history-time">${record.elapsedMs != null ? `耗时 ${(record.elapsedMs / 1000).toFixed(1)}s` : ''}</div>
          </div>
        </div>
      `;
    }).join('');
    els.historyList.querySelectorAll('.pp-history-item').forEach((item) => {
      item.addEventListener('click', (event) => {
        if (event.target.closest('[data-action="delete"]')) return;
        openRecord(Number(item.dataset.id));
      });
    });
    els.historyList.querySelectorAll('[data-action="delete"]').forEach((btn) => {
      btn.addEventListener('click', (event) => {
        event.stopPropagation();
        const id = Number(btn.dataset.id);
        deleteRecord(id, btn);
      });
    });
  }

  async function deleteRecord(id, btn) {
    if (!confirm(`确认删除这条失败的个股分析记录 (#${id})？\n关联 PDF 会一起清理，此操作不可恢复。`)) {
      return;
    }
    if (btn) {
      btn.disabled = true;
      btn.dataset.originalLabel = btn.dataset.originalLabel || btn.textContent;
      btn.textContent = '删除中…';
    }
    try {
      const response = await fetch(`${API_BASE}/record/${id}?api_key=${encodeURIComponent(API_KEY)}`, {
        method: 'DELETE'
      });
      const json = await response.json().catch(() => ({}));
      if (!response.ok || json.ok === false) {
        throw new Error(json.message || `删除失败: ${response.status}`);
      }
      // 重新加载当前页; 如果当前页空了且不是第 0 页, 退回上一页
      await loadHistory(currentPage);
      if (!els.historyList.querySelector('.pp-history-item[data-id]')) {
        const prevPage = Math.max(0, currentPage - 1);
        if (prevPage !== currentPage) await loadHistory(prevPage);
      }
      showShareToast('已删除', 'success');
    } catch (error) {
      if (btn) {
        btn.disabled = false;
        btn.textContent = btn.dataset.originalLabel || '删除';
      }
      showError(error.message || '删除失败');
    }
  }

  function renderPagination(total, page, size) {
    const pages = Math.ceil(total / size);
    if (pages <= 1) {
      els.pagination.innerHTML = '';
      return;
    }
    const html = [];
    if (page > 0) html.push(`<button class="pp-page-btn" data-page="${page - 1}">上一页</button>`);
    html.push(`<span class="pp-history-meta">${page + 1} / ${pages}</span>`);
    if (page < pages - 1) html.push(`<button class="pp-page-btn" data-page="${page + 1}">下一页</button>`);
    els.pagination.innerHTML = html.join('');
    els.pagination.querySelectorAll('.pp-page-btn').forEach((btn) => {
      btn.addEventListener('click', () => loadHistory(Number(btn.dataset.page)));
    });
  }

  async function openRecord(id) {
    currentRecordId = id;
    try {
      const response = await fetch(`${API_BASE}/record/${id}`);
      const json = await response.json();
      if (!json.ok) throw new Error(json.message || '读取报告失败');
      if (json.status === 'PENDING' || json.status === 'RUNNING') {
        showLoading();
        startPolling(id);
        return;
      }
      if (json.status === 'FAILED') {
        hideLoading();
        showError('该分析任务失败：' + escapeHtml(json.report?.errorMessage || '请重新提交'));
        return;
      }
      hideLoading();
      // 同步 currentMethod 到打开记录的实际方法, 让模板切换正确
      currentMethod = json.method || 'full';
      const template = getTemplate(currentMethod);
      setAnchorNav(template);
      setAllSectionTitles(template);
      // 同步顶部 4 个方法按钮高亮
      if (els.method) {
        els.method.querySelectorAll('.pp-method-btn').forEach((btn) => {
          const isActive = btn.dataset.value === currentMethod;
          btn.classList.toggle('is-active', isActive);
          btn.setAttribute('aria-selected', isActive ? 'true' : 'false');
        });
      }
      if (json.method === 'five_dimension') {
        renderFiveDimReport(json);
      } else {
        renderUnifiedReport(json, template);
      }
    } catch (error) {
      showError(error.message || '读取报告失败');
    }
  }

  function startPolling(id) {
    if (pollingTimers.has(id)) return;
    showLoading();
    const timer = setInterval(async () => {
      try {
        const response = await fetch(`${API_BASE}/status/${id}`);
        const json = await response.json();
        if (!json.ok) return;
        if (json.status === 'SUCCESS' || json.status === 'FAILED') {
          clearInterval(timer);
          pollingTimers.delete(id);
          await loadHistory(currentPage);
          if (json.status === 'SUCCESS') {
            await openRecord(id);
          } else {
            hideLoading();
            showError(json.errorMessage || '分析失败');
          }
        }
      } catch (error) {
        console.warn('polling failed', error);
      }
    }, 2000);
    pollingTimers.set(id, timer);
  }

  function showLoading() {
    els.loading.classList.remove('hidden');
    startStepAnimation();
  }

  function hideLoading() {
    els.loading.classList.add('hidden');
    stopStepAnimation();
  }

  function startStepAnimation() {
    if (stepTimer) return;
    const steps = document.querySelectorAll('.pp-step');
    let index = 0;
    steps.forEach((step) => step.classList.remove('active', 'done'));
    if (steps[0]) steps[0].classList.add('active');
    stepTimer = setInterval(() => {
      if (steps[index]) {
        steps[index].classList.remove('active');
        steps[index].classList.add('done');
      }
      index = (index + 1) % steps.length;
      if (steps[index]) steps[index].classList.add('active');
    }, 4000);
  }

  function stopStepAnimation() {
    if (stepTimer) {
      clearInterval(stepTimer);
      stepTimer = null;
    }
    document.querySelectorAll('.pp-step').forEach((step) => step.classList.remove('active', 'done'));
  }

  function renderUnifiedReport(payload, template) {
    const report = payload.report || {};
    const analysis = report.analysis || {};
    const financialSummary = report.financialSummary || {};
    const t = template || getTemplate(currentMethod);
    const w = t.weights || {};
    currentRecordId = payload.id;
    currentReport = report;

    els.empty.classList.add('hidden');
    els.result.classList.remove('hidden');

    renderProfile(payload, report);
    renderOverview(payload, report);
    renderReportTime(payload);

    // ============ ② industry 段 ============
    // full/gaojingqi 偏周期景气；purple_perilla 偏产业链定位
    const industryItems = [];
    if (w.industry >= 3) {
      // gaojingqi: 周期 / 拐点 / 景气 重点
      industryItems.push(
        ['周期位置', analysis.industry?.cyclePosition],
        ['上轮周期复盘', analysis.industry?.lastCycleReview],
        ['未来12个月拐点', analysis.industry?.next12mForecast],
        ['行业生命周期', analysis.industry?.lifeStage]
      );
    } else if (w.industry >= 2) {
      // purple_perilla: 产业链定位
      industryItems.push(
        ['产业链位置', report.chainPosition?.layer],
        ['产业链路径', report.chainPosition?.chainPath],
        ['行业生命周期', analysis.industry?.lifeStage],
        ['行业竞争格局', analysis.industry?.competition]
      );
    } else {
      // full: 综合
      industryItems.push(
        ['周期位置', analysis.industry?.cyclePosition],
        ['上轮周期复盘', analysis.industry?.lastCycleReview],
        ['未来12个月拐点', analysis.industry?.next12mForecast],
        ['进入壁垒', analysis.industry?.entryBarrier],
        ['竞争格局', analysis.industry?.competition],
        ['全球共振', analysis.industry?.globalResonance]
      );
    }
    renderCards(els.industry, industryItems);

    // ============ ③ company 段 ============
    const companyItems = [];
    if (w.company >= 3) {
      // gaojingqi: 拐点信号 = 12季度业绩 + 未来驱动 + 政策
      companyItems.push(
        ['12季度业绩', analysis.company?.quarterly12],
        ['未来2年驱动', analysis.company?.next2yDriver],
        ['政策契合度', analysis.company?.policyFit]
      );
    } else if (w.company >= 2) {
      // purple_perilla: 竞争格局 + 玩家位置
      companyItems.push(
        ['全球玩家', report.competition?.globalPlayers],
        ['中国位置', report.competition?.chinesePosition],
        ['地缘优势', report.competition?.geographicAdvantage],
        ['业务结构', analysis.company?.businessMix]
      );
    } else {
      // full: 全展开
      companyItems.push(
        ['业务结构', analysis.company?.businessMix],
        ['12季度业绩', analysis.company?.quarterly12],
        ['未来2年驱动', analysis.company?.next2yDriver],
        ['护城河', analysis.company?.moat],
        ['政策契合度', analysis.company?.policyFit],
        ['董事长画像', analysis.company?.chairman],
        ['产业链位置', report.chainPosition?.layer],
        ['产业链路径', report.chainPosition?.chainPath],
        ['全球玩家', report.competition?.globalPlayers],
        ['中国位置', report.competition?.chinesePosition],
        ['地缘优势', report.competition?.geographicAdvantage],
        ['下单前三问', joinLines([
          report.threeQuestions?.Q1_irreplaceable,
          report.threeQuestions?.Q2_competitorCount,
          report.threeQuestions?.Q3_demandTrend
        ])]
      );
    }
    renderCards(els.company, companyItems);

    // ============ ④ finance 段 ============
    if (w.finance >= 1) {
      renderFinancialChart(financialSummary);
      renderDbFinancialTable(report.dbFinancials || []);
    } else {
      // purple_perilla: finance 段不强调财务表, 改成护城河+三问
      if (els.finChartWrap) els.finChartWrap.style.display = 'none';
      renderDbFinancialTable([]);
      // 财务段塞入"下单三问"
      renderCards(els.finance, [
        ['下单三问', joinLines([
          report.threeQuestions?.Q1_irreplaceable,
          report.threeQuestions?.Q2_competitorCount,
          report.threeQuestions?.Q3_demandTrend
        ])],
        ['核心护城河', analysis.company?.moat],
        ['业务结构', analysis.company?.businessMix]
      ]);
    }

    // ============ ⑤ market 段 ============
    const marketItems = [];
    marketItems.push(['技术面', joinLines([
      analysis.technical?.trendLine,
      analysis.technical?.ma,
      analysis.technical?.volume,
      analysis.technical?.macd,
      analysis.technical?.verdict
    ])]);
    marketItems.push(['资金面', joinLines([
      analysis.capital?.mainNetIn,
      analysis.capital?.northbound,
      analysis.capital?.dragonTiger,
      analysis.capital?.verdict
    ])]);
    marketItems.push(['行情区间', joinLines([
      `区间最高: ${safeText(report.nineDimension?.market?.periodHigh)}`,
      `区间最低: ${safeText(report.nineDimension?.market?.periodLow)}`,
      `区间涨跌幅: ${safeText(report.nineDimension?.market?.periodChangePct)}`
    ])]);
    // purple_perilla / gaojingqi 在 market 段加产业链/景气补充
    if (currentMethod === 'purple_perilla') {
      marketItems.push(['护城河类型', report.chainPosition?.moatType]);
      marketItems.push(['产业链拆解', report.chainPosition?.chainPath]);
    } else if (currentMethod === 'gaojingqi') {
      marketItems.push(['全球共振', analysis.industry?.globalResonance]);
      marketItems.push(['政策支持', analysis.company?.policyFit]);
    }
    renderCards(els.market, marketItems);

    // ============ ⑥ valuation 段 ============
    // full/gaojingqi 强调估值；purple_perilla/five_dimension 弱化
    // 但所有方法的 valuation 段都加 "Wind 一致预期" 卡片 (强制 AI 引用, 必须展示)
    if (w.valuation >= 1) {
      renderCards(els.valuation, [
        ['公司类型', analysis.valuation?.type],
        ['综合估值结论', analysis.valuation?.verdict],
        ['2026目标价', analysis.valuation?.target2026],
        ['2027目标价', analysis.valuation?.target2027],
        ['估值依据', analysis.valuation?.reasoning],
        ['forecast 摘要', formatForecast(report.forecastSummary)],
        ['外部预期摘要', report.externalExpectation?.summary],
        ...formatConsensusCards(report.windResearch)
      ]);
    } else {
      // purple_perilla: valuation 段为空, 但仍塞进一致预期作为方法补充
      renderCards(els.valuation, formatConsensusCards(report.windResearch));
    }

    renderSummary(report, analysis.summary || {});

    // ============ ⑦ Wind 研报段 (仅 purple/gaojingqi 显式展示) ============
    renderWindResearch(report, currentMethod);
  }

  function renderFiveDimReport(payload) {
    const report = payload.report || {};
    const analysis = report.analysis || {};
    currentRecordId = payload.id;
    currentReport = report;

    els.empty.classList.add('hidden');
    els.result.classList.remove('hidden');

    // anchor + section 标题已在 openRecord 里按 template 设过, 此处不重复

    renderProfile(payload, report);
    renderOverview(payload, report);
    renderReportTime(payload);

    // ① 稀缺卡位
    const scarce = analysis['稀缺卡位'] || {};
    const scarceGlobal = scarce['全球技术稀缺性'] || {};
    const scarceDual = scarce['双赛道卡位'] || {};
    renderCards(els.industry, [
      ['综合评级', scarce.rating],
      ['评级逻辑', scarce.ratingLogic],
      ['【全球技术稀缺性】全球可量产玩家数', scarceGlobal['全球可量产玩家数']],
      ['【全球技术稀缺性】公司在A股的稀缺性', scarceGlobal['公司在A股的稀缺性']],
      ['【全球技术稀缺性】关键技术指标', scarceGlobal['关键技术指标']],
      ['【全球技术稀缺性】国内同业技术代差', scarceGlobal['国内同业技术代差']],
      ['【全球技术稀缺性】研发投入', scarceGlobal['研发投入']],
      ['【全球技术稀缺性】卡位赛道', scarceGlobal['卡位赛道']],
      ['【双赛道卡位】主业', scarceDual['主业']],
      ['【双赛道卡位】第二曲线', scarceDual['第二曲线']],
      ['【双赛道卡位】跨行业意义', scarceDual['跨行业意义']],
      ['【双赛道卡位】业务结构演变', scarceDual['业务结构演变']]
    ]);

    // ② 成长动力
    const growth = analysis['成长动力'] || {};
    const firstCurve = growth['第一曲线'] || {};
    const firstFc = firstCurve['未来3年量化预测'] || {};
    const secondCurve = growth['第二曲线'] || {};
    const secondFc = secondCurve['未来3年量化预测'] || {};
    renderCards(els.company, [
      ['综合评级', growth.rating],
      ['评级逻辑', growth.ratingLogic],
      ['【第一曲线】业务名', firstCurve['业务名']],
      ['【第一曲线】行业逻辑', firstCurve['行业逻辑']],
      ['【第一曲线】年化复合增速', firstCurve['年化复合增速']],
      ['【第一曲线】稳态年度营收区间', firstCurve['稳态年度营收区间']],
      ['【第一曲线】2026E 营收', firstFc['2026']],
      ['【第一曲线】2027E 营收', firstFc['2027']],
      ['【第一曲线】2028E 营收', firstFc['2028']],
      ['【第一曲线】角色定位', firstCurve['角色定位']],
      ['【第二曲线】业务名', secondCurve['业务名']],
      ['【第二曲线】行业需求端', secondCurve['行业需求端']],
      ['【第二曲线】产能端', secondCurve['产能端']],
      ['【第二曲线】客户端', secondCurve['客户端']],
      ['【第二曲线】2026E 预测', secondFc['2026']],
      ['【第二曲线】2027E 预测', secondFc['2027']],
      ['【第二曲线】2028E 预测', secondFc['2028']],
      ['【第二曲线】关键里程碑', secondCurve['关键里程碑']]
    ]);

    // ③ 业绩兑现度
    const deliver = analysis['业绩兑现度'] || {};
    const histFin = deliver['历史财报验证'] || {};
    const curFin = deliver['当期财报验证'] || {};
    const forwardFin = Array.isArray(deliver['远期利润与毛利率预判']) ? deliver['远期利润与毛利率预判'] : [];
    renderFinancialChart(report.financialSummary || {});
    renderDbFinancialTable(report.dbFinancials || []);
    const finCards = [
      ['综合评级', deliver.rating],
      ['评级逻辑', deliver.ratingLogic],
      [`【${histFin['年份'] || '历史'}】总营收`, histFin['总营收']],
      [`【${histFin['年份'] || '历史'}】归母净利润`, histFin['归母净利润']],
      [`【${histFin['年份'] || '历史'}】经营性现金流净额`, histFin['经营活动现金流净额']],
      [`【${histFin['年份'] || '历史'}】业务毛利率结构`, histFin['业务毛利率结构']],
      [`【${curFin['季度'] || '当期'}】营收`, curFin['营收']],
      [`【${curFin['季度'] || '当期'}】归母净利润`, curFin['归母净利润']],
      [`【${curFin['季度'] || '当期'}】扣非净利润`, curFin['扣非净利润']],
      [`【${curFin['季度'] || '当期'}】核心信号`, curFin['核心信号']],
      ['业绩兑现确定性', deliver['业绩兑现确定性']],
      ['唯一变量', deliver['唯一变量']]
    ];
    finCards.push(...forwardFin.map((r, i) => [`【远期 ${r['年份'] || ('项' + (i+1))}】归母 ${r['归母净利润'] || '-'} / 毛利 ${r['综合毛利率'] || '-'}`, r['核心兑现逻辑']]));
    renderCards(els.finance, finCards);

    // ④ 瓶颈与壁垒 + ⑤ 估值阶梯 + 风险 + 结论
    const barrier = analysis['瓶颈与壁垒'] || {};
    const valuation = analysis['估值阶梯'] || {};
    const summaryObj = analysis.summary || {};
    const moats = Array.isArray(barrier['核心护城河壁垒']) ? barrier['核心护城河壁垒'] : [];
    const bottlenecks = Array.isArray(barrier['当前成长约束瓶颈']) ? barrier['当前成长约束瓶颈'] : [];
    const risks = Array.isArray(valuation['风险提示']) ? valuation['风险提示'] : [];
    const marketCards = [
      ['综合评级', barrier.rating],
      ['评级逻辑', barrier.ratingLogic],
      ['【估值】底层逻辑', valuation['估值底层逻辑']],
      ['【估值】估值体系', valuation['估值体系']],
      ...staircaseCards('第一阶梯（短期）', valuation['第一阶梯']),
      ...staircaseCards('第二阶梯（中期）', valuation['第二阶梯']),
      ...staircaseCards('第三阶梯（远期）', valuation['第三阶梯'])
    ];
    moats.forEach((m, i) => marketCards.push([`【护城河 ${i+1}】${m['类型'] || ''}`, m['数据']]));
    bottlenecks.forEach((m, i) => marketCards.push([`【瓶颈 ${i+1}】${m['类型'] || ''}`, m['数据']]));
    renderCards(els.market, marketCards);

    // ⑤+结论：估值阶梯 + 风险 + 一句话
    const summaryCards = [
      ['综合结论', summaryObj.oneLiner || report.verdict],
      ['核心驱动', Array.isArray(summaryObj.coreDrivers) ? summaryObj.coreDrivers.join('；') : null]
    ];
    if (risks.length) {
      summaryCards.push(['配套风险提示', risks.map((r, i) => `${i+1}. ${r}`).join('\n')]);
    }
    // 五维把一致预期折进结论段 (作为估值锚点)
    summaryCards.push(...formatConsensusCards(report.windResearch));
    renderCards(els.valuation, []);
    renderFiveDimSummary(summaryObj, report);
  }

  function renderFiveDimSummary(summaryObj, report) {
    const drivers = Array.isArray(summaryObj.coreDrivers) ? summaryObj.coreDrivers : [];
    els.summary.innerHTML = `
      ${drivers.length ? `<div class="pp-summary-box"><strong>核心驱动：</strong><ul class="pp-summary-bullets">${drivers.map((d) => `<li>${escapeHtml(d)}</li>`).join('')}</ul></div>` : ''}
      <div class="pp-summary-box">${escapeHtml(summaryObj.oneLiner || report.verdict || '暂无可用结构化数据')}</div>
    `;
  }

  function setSectionTitle(sectionId, no, label) {
    const sec = document.getElementById(sectionId);
    if (!sec) return;
    const title = sec.querySelector('.pp-section-title');
    if (!title) return;
    const noEl = title.querySelector('.pp-section-no');
    if (noEl) noEl.textContent = no;
    // 删除原有文字节点（保留 no 元素）
    Array.from(title.childNodes).forEach((n) => {
      if (n.nodeType === Node.TEXT_NODE) n.remove();
    });
    title.appendChild(document.createTextNode(' ' + label));
  }

  function staircaseCards(prefix, stair) {
    if (!stair || typeof stair !== 'object') return [];
    const cards = [[`【${prefix}】时间窗口`, stair['时间窗口']]];
    ['预期归母净利润', '预期总营收', '第二曲线营收占比', '第二曲线地位',
     '估值中枢', '稳态PE/PS', 'PE测算稳态市值', 'PS测算稳态市值',
     '目标市值区间', '每股目标价', '核心上涨催化', '核心逻辑'].forEach((k) => {
      if (stair[k]) cards.push([`【${prefix}】${k}`, stair[k]]);
    });
    return cards;
  }

  function renderProfile(payload, report) {
    const sourceCoverage = countSources(report.sourceMetadata || {});
    els.profile.innerHTML = `
      <div>
        <div class="pp-profile-name">
          ${escapeHtml(payload.stockName || report.name || '-')}
          <span style="font-size:14px;color:#64748b;font-weight:500;">${escapeHtml(payload.stockCode || report.code || '-')}</span>
          ${report.verdict ? `<span class="pp-badge">${escapeHtml(report.verdict)}</span>` : ''}
          ${report.moatScore != null ? `<span class="pp-badge">护城河 ${report.moatScore}/10</span>` : ''}
          <span class="pp-badge">来源覆盖 ${sourceCoverage}</span>
        </div>
        <div class="pp-profile-meta">
          ${safeText(report.name ? `报告标的：${report.name}` : '')}
          ${report.currentPrice != null ? ` · 现价 ${Number(report.currentPrice).toFixed(2)} 元` : ''}
          ${payload.elapsedMs != null ? ` · 耗时 ${(payload.elapsedMs / 1000).toFixed(1)} 秒` : ''}
        </div>
      </div>
      <div class="pp-profile-actions">
        <span class="pp-cached-tag">${escapeHtml(payload.status || '-')}</span>
        <span class="pp-cached-tag">${formatTime(payload.finishedAt || payload.submittedAt)}</span>
      </div>
    `;
  }

  function renderOverview(payload, report) {
    const sourceCoverage = countSources(report.sourceMetadata || {});
    const conclusion = safeText(report.analysis?.summary?.oneLiner, report.verdict, '-');
    const metrics = [
      ['现价', report.currentPrice == null ? '-' : `${Number(report.currentPrice).toFixed(2)} 元`],
      ['护城河', report.moatScore == null ? '-' : `${report.moatScore}/10`],
      ['来源覆盖', `${sourceCoverage}/5`],
      ['分析方法', payload.method || report.method || '-']
    ];
    els.overview.innerHTML = `
      <div class="pp-overview-hero">
        <div class="pp-overview-hero-label">综合结论</div>
        <div class="pp-overview-hero-value">${escapeHtml(conclusion)}</div>
      </div>
      <div class="pp-overview-metrics">
        ${metrics.map(([label, value]) => `
          <div class="pp-overview-metric">
            <div class="pp-overview-metric-label">${escapeHtml(label)}</div>
            <div class="pp-overview-metric-value">${escapeHtml(value)}</div>
          </div>
        `).join('')}
      </div>
    `;
  }

  function renderReportTime(payload) {
    if (!els.reportTime) return;
    const time = formatTime(payload.finishedAt || payload.submittedAt);
    const elapsed = payload.elapsedMs != null ? `· 耗时 ${(payload.elapsedMs / 1000).toFixed(1)} 秒` : '';
    els.reportTime.textContent = `报告时间：${time} ${elapsed}`;
  }

  function renderCards(container, items) {
    container.innerHTML = items
      .filter(([, value]) => value && String(value).trim())
      .map(([label, value]) => renderCard(label, value))
      .join('') || '<div class="pp-card"><div class="pp-card-value">暂无可用结构化数据</div></div>';
  }

  function renderCard(label, value) {
    // 处理嵌套对象：AI 在某些字段（如"主业"）输出了 {"客户/份额/认证周期": "..."} 这种结构
    let text;
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      text = Object.entries(value)
        .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`)
        .join('\n');
    } else if (Array.isArray(value)) {
      text = value.map((v) => typeof v === 'object' ? JSON.stringify(v) : v).join('\n');
    } else {
      text = String(value).replace(/\r\n/g, '\n').trim();
    }
    const { conclusion, detail } = splitConclusionAndDetail(text);
    return `
      <div class="pp-card">
        <div class="pp-card-head">
          <span class="pp-card-label">${escapeHtml(label)}</span>
        </div>
        <div class="pp-card-conclusion">${escapeHtml(conclusion)}</div>
        ${detail ? `<div class="pp-card-body">${escapeHtml(detail)}</div>` : ''}
      </div>
    `;
  }

  /**
   * 把长文本启发式拆成"结论"（加粗显示的短语）+ "详情"（下面的多行说明）。
   * 规则：按优先级找第一个合理分隔符；找不到则短文整段作结论，长文按字宽硬切。
   */
  function splitConclusionAndDetail(text) {
    if (!text) return { conclusion: '', detail: '' };
    // 纯数字/纯分数/纯符号（如 5/10、12.34元）→ 整段作结论
    if (/^[0-9./%\-+]+(\s*[元股%万千]?)?$/.test(text)) {
      return { conclusion: text, detail: '' };
    }
    const seps = [
      { re: /[。；]\s*\n?/, len: 1 },
      { re: /\n+/, len: 1 },
      { re: /[：:]\s*/, len: 1 },
      { re: /——+/, len: 2 },
      { re: /[，,]\s*/, len: 1 },
      { re: /[；;]\s*/, len: 1 },
      { re: /[、]\s*/, len: 1 }
    ];
    for (const { re, len } of seps) {
      const m = text.match(re);
      if (m && m.index > 0 && m.index <= 32) {
        const c = text.substring(0, m.index).trim();
        const d = text.substring(m.index + m[0].length).trim();
        if (c && d) return { conclusion: c, detail: d };
      }
    }
    if (text.length <= 22) {
      return { conclusion: text, detail: '' };
    }
    // 找最后一个空格/标点，把前面更精炼的部分作结论
    const cutAt = text.search(/[\s,，:：;；。、]/);
    if (cutAt > 4 && cutAt <= 28) {
      return { conclusion: text.substring(0, cutAt).trim(), detail: text.substring(cutAt + 1).trim() };
    }
    return { conclusion: text.substring(0, 20).trim() + '…', detail: text.substring(20).trim() };
  }

  function renderFinancialChart(financialSummary) {
    if (finChart) {
      finChart.destroy();
      finChart = null;
    }
    const hasData = financialSummary.periodLabels && financialSummary.periodLabels.length;
    if (els.finChartWrap) els.finChartWrap.style.display = hasData ? '' : 'none';
    if (!hasData) return;
    const toPct = (arr) => (arr || []).map((value) => value == null ? null : +(value * 100).toFixed(2));
    finChart = new Chart(els.finCanvas.getContext('2d'), {
      type: 'line',
      data: {
        labels: financialSummary.periodLabels,
        datasets: [
          { label: 'ROE %', data: toPct(financialSummary.roeList), borderColor: '#1e88ff', tension: 0.3 },
          { label: '毛利率 %', data: toPct(financialSummary.grossMarginList), borderColor: '#00bcd4', tension: 0.3 },
          { label: '净利率 %', data: toPct(financialSummary.netMarginList), borderColor: '#7c3aed', tension: 0.3 },
          { label: '净利 YoY %', data: toPct(financialSummary.yoyNetProfitList), borderColor: '#ef4444', borderDash: [5, 5], tension: 0.3 }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'bottom',
            labels: { font: { size: 10 } }
          }
        }
      }
    });
  }

  function renderDbFinancialTable(rows) {
    if (!rows.length) {
      els.dbFinancial.innerHTML = '<div class="pp-card"><div class="pp-card-value">暂无可用结构化数据</div></div>';
      return;
    }
    els.dbFinancial.innerHTML = `
      <table class="pp-fin-table">
        <thead>
          <tr>
            <th>报告期</th>
            <th>营收(亿)</th>
            <th>净利润(亿)</th>
            <th>EPS</th>
            <th>ROE</th>
            <th>毛利率</th>
            <th>净利率</th>
            <th>营收YoY</th>
            <th>扣非YoY</th>
          </tr>
        </thead>
        <tbody>
          ${rows.map((row) => `
            <tr>
              <td>${escapeHtml(row.reportDate)}</td>
              <td>${escapeHtml(row.revenue)}</td>
              <td>${escapeHtml(row.netProfit)}</td>
              <td>${escapeHtml(row.eps)}</td>
              <td>${escapeHtml(row.roe)}</td>
              <td>${escapeHtml(row.grossMargin)}</td>
              <td>${escapeHtml(row.netMargin)}</td>
              <td>${escapeHtml(row.revenueYoy)}</td>
              <td>${escapeHtml(row.deductedNetProfitYoy)}</td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    `;
  }

  function renderSummary(report, summary) {
    const bullets = Array.isArray(summary.bullets) ? summary.bullets : [];
    const catalysts = Array.isArray(report.catalysts) ? report.catalysts : [];
    const risks = Array.isArray(report.risks) ? report.risks : [];
    els.summary.innerHTML = `
      ${bullets.length ? `<div class="pp-summary-box"><ul class="pp-summary-bullets">${bullets.map((item) => `<li>${escapeHtml(item)}</li>`).join('')}</ul></div>` : ''}
      <div class="pp-cards">
        <div class="pp-card">
          <div class="pp-card-label">催化剂</div>
          <div class="pp-card-value">${catalysts.length ? escapeHtml(catalysts.join('\n')) : '暂无可用结构化数据'}</div>
        </div>
        <div class="pp-card">
          <div class="pp-card-label">风险</div>
          <div class="pp-card-value">${risks.length ? escapeHtml(risks.join('\n')) : '暂无可用结构化数据'}</div>
        </div>
      </div>
      <div class="pp-summary-box">${escapeHtml(summary.oneLiner || report.verdict || '暂无可用结构化数据')}</div>
    `;
  }

  async function downloadPdf() {
    if (!currentRecordId) {
      showError('请先打开一份成功完成的分析记录');
      return;
    }
    try {
      const response = await fetch(`${API_BASE}/pdf/${currentRecordId}`);
      if (!response.ok) {
        const json = await response.json().catch(() => ({}));
        throw new Error(json.message || `下载失败: ${response.status}`);
      }
      const blob = await response.blob();
      let fileName = 'stock-analysis-report.pdf';
      const cd = response.headers.get('Content-Disposition') || '';
      const utf8 = cd.match(/filename\*=UTF-8''([^;]+)/);
      const ascii = cd.match(/filename="?([^";]+)"?/);
      if (utf8) fileName = decodeURIComponent(utf8[1]);
      else if (ascii) fileName = ascii[1];
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      showError(error.message || 'PDF 下载失败');
    }
  }

  async function copyShareLink() {
    if (!currentRecordId) {
      showError('请先打开一份成功完成的分析记录');
      return;
    }
    const url = `${window.location.origin}${window.location.pathname}?record=${currentRecordId}`;
    // 直接复制链接（不再走 navigator.share，避免移动端只唤起系统面板导致"没复制"）
    const copied = await copyToClipboard(url);
    if (copied) {
      flashShareBtn('已复制');
      showShareToast('链接已复制，去发给好友吧 👌', 'success');
    } else {
      flashShareBtn('复制失败');
      showShareToast('复制失败，请手动复制地址栏 URL', 'error');
    }
  }

  function buildShareText(url) {
    const report = currentReport || {};
    const name = report.name || els.keyword?.value?.trim() || '个股研究';
    const verdict = report.verdict ? ` · ${report.verdict}` : '';
    return `${name}${verdict} — 来自「景气度选股」\n${url}`;
  }

  async function copyToClipboard(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch (e) {
      // fall through to legacy
    }
    return legacyCopy(text);
  }

  function legacyCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try {
      ok = document.execCommand('copy');
    } catch (e) {
      ok = false;
    }
    document.body.removeChild(ta);
    return ok;
  }

  function flashShareBtn(label) {
    if (!els.shareBtn) return;
    const original = els.shareBtn.dataset.label || els.shareBtn.textContent;
    els.shareBtn.dataset.label = original;
    els.shareBtn.textContent = label;
    els.shareBtn.disabled = true;
    setTimeout(() => {
      els.shareBtn.textContent = original;
      els.shareBtn.disabled = false;
    }, 1600);
  }

  function showShareToast(message, tone) {
    let toast = document.getElementById('ppShareToast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'ppShareToast';
      toast.className = 'pp-share-toast';
      document.body.appendChild(toast);
    }
    toast.textContent = message;
    toast.className = 'pp-share-toast show' + (tone ? ' ' + tone : '');
    if (toast._timer) clearTimeout(toast._timer);
    toast._timer = setTimeout(() => {
      toast.className = 'pp-share-toast';
    }, 1800);
  }

  function onAnchorClick(event) {
    const anchor = event.target.closest('.pp-anchor');
    if (!anchor) return;
    const target = document.getElementById(anchor.dataset.target);
    if (!target) return;
    els.anchorNav.querySelectorAll('.pp-anchor').forEach((node) => node.classList.remove('active'));
    anchor.classList.add('active');
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function countSources(sourceMetadata) {
    return Object.values(sourceMetadata).filter((meta) => meta && meta.available === true).length;
  }

  function statusClass(status) {
    const normalized = String(status || '').toLowerCase();
    return `pp-status-${normalized}`;
  }

  function formatForecast(forecastSummary) {
    if (!forecastSummary || !Array.isArray(forecastSummary.items) || !forecastSummary.items.length) {
      return '暂无可用结构化数据';
    }
    return forecastSummary.items.map((item) => `${safeText(item.title)}: ${safeText(item.content)}`).join('\n');
  }

  function showError(message) {
    els.error.textContent = message;
    els.error.classList.remove('hidden');
  }

  function clearError() {
    els.error.textContent = '';
    els.error.classList.add('hidden');
  }

  function formatTime(value) {
    if (!value) return '-';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN');
  }

  function joinLines(lines) {
    return (lines || []).map((line) => safeText(line)).filter(Boolean).join('\n');
  }

  function safeText(value, fallback = '') {
    if (value == null) return fallback;
    const text = String(value).trim();
    return text || fallback;
  }

  function escapeHtml(value) {
    if (value == null) return '-';
    return String(value).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;'
    }[ch]));
  }

  function debounce(fn, ms) {
    let timer = null;
    return function (...args) {
      clearTimeout(timer);
      timer = setTimeout(() => fn.apply(this, args), ms);
    };
  }

  // ============================================================
  // Wind 研报渲染
  //   - formatConsensusCards: 把 windResearch.consensus 折成 valuation 段的卡片
  //   - renderWindResearch: 渲染独立 ⑦ 段 (purple/gaojingqi)
  // ============================================================
  function formatConsensusCards(windResearch) {
    if (!windResearch || !windResearch.available || !windResearch.consensus) return [];
    const c = windResearch.consensus;
    if (!c.sourceRowCount) return [];
    const cards = [];
    if (c.rating) cards.push(['Wind 一致预期 · 评级', c.rating]);
    if (c.targetPrice != null) cards.push(['Wind 一致预期 · 目标价', `${c.targetPrice} ${c.currency || '元'}`]);
    if (c.eps2026 != null) cards.push(['Wind 一致预期 · 2026E EPS', `${c.eps2026} 元`]);
    if (c.eps2027 != null) cards.push(['Wind 一致预期 · 2027E EPS', `${c.eps2027} 元`]);
    if (c.netProfitGrowth2026 != null) cards.push(['Wind 一致预期 · 2026 净利同比', `${c.netProfitGrowth2026}%`]);
    if (c.netProfitGrowth2027 != null) cards.push(['Wind 一致预期 · 2027 净利同比', `${c.netProfitGrowth2027}%`]);
    return cards.map(([label, value]) => [label, value]);
  }

  function renderWindResearch(report, method) {
    if (!els.researchSection) return;
    const hasSection = METHOD_HAS_RESEARCH_SECTION[method];
    if (!hasSection) {
      els.researchSection.classList.add('hidden');
      return;
    }
    const wind = report.windResearch;
    if (!wind || !wind.available) {
      // 即使方法支持, 也要展示 "未启用" 占位, 不藏起来
      els.researchSection.classList.remove('hidden');
      if (els.consensus) {
        renderCards(els.consensus, []);
      }
      if (els.researchList) {
        const reason = !wind
            ? '本次未拉取'
            : !wind.windInstalled
              ? 'Wind skill 未安装'
              : !wind.windHasKey
                ? 'Wind 未配置 API Key'
                : '本次拉取无可用数据';
        els.researchList.innerHTML = `<div class="pp-research-empty">Wind 研报：${escapeHtml(reason)}</div>`;
      }
      return;
    }
    els.researchSection.classList.remove('hidden');

    // 一致预期卡片
    const consensusCards = formatConsensusCards(wind);
    if (els.consensus) renderCards(els.consensus, consensusCards);

    // 研报片段列表
    if (els.researchList) {
      const reports = Array.isArray(wind.reports) ? wind.reports : [];
      if (reports.length === 0) {
        els.researchList.innerHTML = '<div class="pp-research-empty">本次未检索到研报片段</div>';
      } else {
        els.researchList.innerHTML = reports.map((r, i) => `
          <div class="pp-research-item">
            <div class="pp-research-head">
              <span class="pp-research-no">#${i + 1}</span>
              <span class="pp-research-title">${escapeHtml(r.title || '')}</span>
            </div>
            <div class="pp-research-meta">
              ${r.source ? `<span class="pp-research-tag">${escapeHtml(r.source)}</span>` : ''}
              ${r.date ? `<span class="pp-research-tag">${escapeHtml(r.date)}</span>` : ''}
              ${r.docType ? `<span class="pp-research-tag">${escapeHtml(r.docType)}</span>` : ''}
              ${r.relevance != null ? `<span class="pp-research-tag">相关度 ${(r.relevance * 100).toFixed(0)}%</span>` : ''}
            </div>
            <div class="pp-research-body">${escapeHtml(r.content || '')}</div>
          </div>
        `).join('');
      }
    }
  }
}());
