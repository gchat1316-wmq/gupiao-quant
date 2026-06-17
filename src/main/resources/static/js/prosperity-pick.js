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
    anchorNav: $('ppAnchorNav')
  };

  let currentPage = 0;
  let currentRecordId = null;
  let currentReport = null;
  let pollingTimers = new Map();
  let stepTimer = null;
  let finChart = null;

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
          method: els.method.value || 'full',
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
      return `
        <div class="pp-history-item" data-id="${record.id}">
          <div class="pp-history-top">
            <div>
              <div class="pp-history-name">${escapeHtml(record.stockName || record.stockCodeRaw || record.stockCode || '-')}</div>
              <div class="pp-history-code">${escapeHtml(record.stockCodeRaw || record.stockCode || '-')} · ${price}</div>
            </div>
            <div class="pp-history-status ${statusClass(record.status)}">${escapeHtml(record.status || '-')}</div>
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
      item.addEventListener('click', () => openRecord(Number(item.dataset.id)));
    });
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
      renderUnifiedReport(json);
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

  function renderUnifiedReport(payload) {
    const report = payload.report || {};
    const analysis = report.analysis || {};
    const financialSummary = report.financialSummary || {};
    currentRecordId = payload.id;
    currentReport = report;

    els.empty.classList.add('hidden');
    els.result.classList.remove('hidden');

    renderProfile(payload, report);
    renderOverview(payload, report);
    renderReportTime(payload);
    renderCards(els.industry, [
      ['周期位置', analysis.industry?.cyclePosition],
      ['上轮周期复盘', analysis.industry?.lastCycleReview],
      ['未来12个月拐点', analysis.industry?.next12mForecast],
      ['进入壁垒', analysis.industry?.entryBarrier],
      ['竞争格局', analysis.industry?.competition],
      ['全球共振', analysis.industry?.globalResonance]
    ]);
    renderCards(els.company, [
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
    ]);
    renderFinancialChart(financialSummary);
    renderDbFinancialTable(report.dbFinancials || []);
    renderCards(els.valuation, [
      ['公司类型', analysis.valuation?.type],
      ['综合估值结论', analysis.valuation?.verdict],
      ['2026目标价', analysis.valuation?.target2026],
      ['2027目标价', analysis.valuation?.target2027],
      ['估值依据', analysis.valuation?.reasoning],
      ['forecast 摘要', formatForecast(report.forecastSummary)],
      ['外部预期摘要', report.externalExpectation?.summary]
    ]);
    renderCards(els.market, [
      ['技术面', joinLines([
        analysis.technical?.trendLine,
        analysis.technical?.ma,
        analysis.technical?.volume,
        analysis.technical?.macd,
        analysis.technical?.verdict
      ])],
      ['资金面', joinLines([
        analysis.capital?.mainNetIn,
        analysis.capital?.northbound,
        analysis.capital?.dragonTiger,
        analysis.capital?.verdict
      ])],
      ['行情区间', joinLines([
        `区间最高: ${safeText(report.nineDimension?.market?.periodHigh)}`,
        `区间最低: ${safeText(report.nineDimension?.market?.periodLow)}`,
        `区间涨跌幅: ${safeText(report.nineDimension?.market?.periodChangePct)}`
      ])]
    ]);
    renderSummary(report, analysis.summary || {});
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
    const text = String(value).replace(/\r\n/g, '\n').trim();
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
    const text = buildShareText(url);
    // 优先走 navigator.share（移动端原生面板）
    try {
      if (typeof navigator !== 'undefined' && typeof navigator.share === 'function') {
        await navigator.share({ title: '景气度选股 · 个股研究', text, url });
        showShareToast('分享已唤起', 'success');
        return;
      }
    } catch (err) {
      if (err && err.name === 'AbortError') return; // 用户取消
      // 其它异常继续走复制兜底
    }
    // fallback：复制链接
    const copied = await copyToClipboard(url);
    if (copied) {
      flashShareBtn('链接已复制');
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
}());
