(function () {
  'use strict';

  const state = {
    loading: false,
    error: '',
    markets: [],
    selectedMarket: '',
    timeline: [],
    currentDetail: null,
    activeDetailId: null,
    detailLoadingId: null,
  };
  const DEFAULT_MARKETS = ['A股', '美股', '港股'];

  function init() {
    bindGlobalHandlers();
    loadPage(readMarketFromUrl());
  }

  function bindGlobalHandlers() {
    document.getElementById('marketTabs')?.addEventListener('click', function (event) {
      const btn = event.target.closest('.rq-tab, .recap-market-tab');
      if (!btn) return;
      const market = btn.dataset.market || '';
      if (market === state.selectedMarket && !state.error) return;
      writeMarketToUrl(market);
      loadPage(market);
    });

    document.getElementById('recapStatus')?.addEventListener('click', function (event) {
      const btn = event.target.closest('[data-action="retry"]');
      if (!btn) return;
      loadPage(state.selectedMarket || readMarketFromUrl());
    });

    document.getElementById('recapDatePicker')?.addEventListener('change', function (event) {
      const value = event.target.value;
      if (!value) return;
      selectByDate(value);
    });

    document.getElementById('recapDateClear')?.addEventListener('click', function () {
      const items = Array.isArray(state.timeline) ? state.timeline : [];
      if (!items.length) return;
      const latest = items[0];
      if (latest && latest.id && latest.id !== state.activeDetailId) {
        loadDetail(latest.id);
      } else {
        renderDatePicker();
      }
    });

    document.getElementById('recapList')?.addEventListener('click', function (event) {
      const btn = event.target.closest('.rq-list-item');
      if (!btn) return;
      const id = Number(btn.dataset.id);
      if (!id || id === state.activeDetailId || id === state.detailLoadingId) return;
      loadDetail(id);
    });
  }

  function selectByDate(date) {
    const items = Array.isArray(state.timeline) ? state.timeline : [];
    const match = items.find(function (item) { return item.tradeDate === date; });
    if (!match) {
      window.alert('该日期暂无复盘');
      renderDatePicker();
      return;
    }
    if (match.id && match.id !== state.activeDetailId) {
      loadDetail(match.id);
    }
  }

  async function loadPage(market) {
    state.loading = true;
    state.error = '';
    state.detailLoadingId = null;
    render();

    try {
      const query = market ? '?market=' + encodeURIComponent(market) : '';
      const data = await fetchJson('api/market-recaps' + query);

      state.markets = Array.isArray(data.markets) ? data.markets : [];
      state.selectedMarket = data.selectedMarket || market || '';
      state.timeline = Array.isArray(data.timeline) ? data.timeline : [];
      state.currentDetail = data.latest || null;
      state.activeDetailId = state.currentDetail && state.currentDetail.id ? state.currentDetail.id : null;
    } catch (error) {
      state.error = error.message || '加载失败';
      state.markets = [];
      state.timeline = [];
      state.currentDetail = null;
      state.activeDetailId = null;
    } finally {
      state.loading = false;
      render();
    }
  }

  async function loadDetail(id) {
    state.detailLoadingId = id;
    render();
    try {
      const detail = await fetchJson('api/market-recaps/' + id);
      state.currentDetail = detail;
      state.activeDetailId = detail && detail.id ? detail.id : id;
    } catch (error) {
      window.alert(error.message || '加载详情失败');
    } finally {
      state.detailLoadingId = null;
      render();
    }
  }

  async function fetchJson(url) {
    const response = await fetch(url, { headers: { Accept: 'application/json' } });
    const data = await response.json().catch(function () { return {}; });
    if (!response.ok) {
      throw new Error(data.message || '请求失败');
    }
    return data;
  }

  function render() {
    renderMarkets();
    renderDatePicker();
    renderList();
    renderStatus();
    renderContent();
  }

  function renderList() {
    const el = document.getElementById('recapList');
    if (!el) return;

    const items = Array.isArray(state.timeline) ? state.timeline : [];
    if (state.loading || state.error || !items.length) {
      el.hidden = true;
      el.innerHTML = '';
      return;
    }

    el.hidden = false;
    el.innerHTML = items.map(function (item) {
      const active = item.id === state.activeDetailId ? ' active' : '';
      const loading = item.id === state.detailLoadingId ? ' loading' : '';
      return '<button class="rq-list-item' + active + loading + '" data-id="' + escAttr(String(item.id)) + '">' +
        '<span class="rq-list-date">' + escHtml(item.tradeDate || '未知日期') + '</span>' +
        '<span class="rq-list-title">' + escHtml(item.title || '未命名复盘') + '</span>' +
        (item.sentiment ? '<span class="rq-list-tag">' + escHtml(item.sentiment) + '</span>' : '') +
      '</button>';
    }).join('');
  }

  function renderMarkets() {
    const el = document.getElementById('marketTabs');
    if (!el) return;

    const markets = unique(DEFAULT_MARKETS.concat(state.markets || [], state.selectedMarket ? [state.selectedMarket] : []));

    if (!markets.length) {
      el.innerHTML = '';
      return;
    }

    el.innerHTML = markets.map(function (market) {
      const active = market === state.selectedMarket ? ' active' : '';
      return '<button class="rq-tab' + active + '" data-market="' + escAttr(market) + '">' +
        escHtml(market) + '</button>';
    }).join('');
  }

  function renderDatePicker() {
    const input = document.getElementById('recapDatePicker');
    if (!input) return;

    const items = Array.isArray(state.timeline) ? state.timeline : [];
    const dates = items.map(function (item) { return item.tradeDate; }).filter(Boolean).sort();

    if (dates.length) {
      input.min = dates[0];
      input.max = dates[dates.length - 1];
      input.disabled = false;
    } else {
      input.removeAttribute('min');
      input.removeAttribute('max');
      input.disabled = true;
    }

    const currentDate = state.currentDetail && state.currentDetail.tradeDate
      ? state.currentDetail.tradeDate
      : (dates.length ? dates[dates.length - 1] : '');
    input.value = currentDate || '';
  }

  function renderStatus() {
    const statusEl = document.getElementById('recapStatus');
    const contentEl = document.getElementById('recapContent');
    if (!statusEl || !contentEl) return;

    const isEmpty = !state.loading && !state.error && !state.currentDetail;
    const showStatus = state.loading || !!state.error || isEmpty;

    statusEl.classList.toggle('hidden', !showStatus);
    contentEl.classList.toggle('hidden', showStatus);

    if (state.loading) {
      statusEl.className = 'rq-status-card';
      statusEl.textContent = '复盘载入中...';
      return;
    }

    if (state.error) {
      statusEl.className = 'rq-status-card error';
      statusEl.innerHTML = '<div>加载失败：' + escHtml(state.error) + '</div>' +
        '<div class="recap-status-action"><button class="primary-btn" data-action="retry">重试</button></div>';
      return;
    }

    if (isEmpty) {
      statusEl.className = 'rq-status-card';
      statusEl.textContent = '当前市场暂无复盘数据';
    }
  }

  function renderContent() {
    renderArticle();
  }

  function renderHeadline() {
    const el = document.getElementById('recapHeadline');
    const detail = state.currentDetail;
    if (!el || !detail) return;

    const stats = [
      { label: '指数摘要', value: detail.indexesSummary || '—' },
      { label: '涨跌比', value: detail.advanceDecline || '—' },
      { label: '涨停家数', value: detail.limitUp != null ? String(detail.limitUp) : '—' },
      { label: '跌停家数', value: detail.limitDown != null ? String(detail.limitDown) : '—' },
      { label: '情绪阶段', value: detail.sentiment || '—' }
    ];

    el.innerHTML =
      '<section class="recap-headline">' +
        '<div class="recap-headline-top">' +
          '<div>' +
            '<h2 class="recap-headline-title">' + escHtml(detail.title || '每日复盘') + '</h2>' +
            '<div class="recap-headline-meta">' +
              pill(detail.market || '未分类') +
              pill(detail.tradeDate || '未知日期') +
              pill(detail.sentiment || '情绪未标注') +
            '</div>' +
          '</div>' +
        '</div>' +
        '<p class="recap-headline-summary">' + escHtml(detail.summaryExcerpt || '暂无摘要') + '</p>' +
        '<div class="recap-stat-grid">' +
          stats.map(function (item) {
            return '<div class="recap-stat-card"><span class="recap-stat-label">' + escHtml(item.label) +
              '</span><div class="recap-stat-value">' + escHtml(item.value) + '</div></div>';
          }).join('') +
        '</div>' +
      '</section>';
  }

  function renderStructure() {
    const el = document.getElementById('recapStructure');
    const detail = state.currentDetail;
    if (!el || !detail) return;

    el.className = 'recap-structure';
    el.innerHTML =
      structureCard('主线板块', 'span-12', renderSectors(detail.sectors)) +
      structureCard('风险提示', 'span-4', renderBulletList(detail.risks)) +
      structureCard('关键催化', 'span-4', renderBulletList(detail.catalysts)) +
      structureCard('关键数据', 'span-4', renderKeyData(detail.keyData)) +
      structureCard('明日策略', 'span-12', renderStrategy(detail.nextDayStrategy));
  }

  function renderArticle() {
    const el = document.getElementById('recapArticle');
    const detail = state.currentDetail;
    if (!el || !detail) return;

    const articleHtml = detail.contentHtml || '<p class="rq-empty">暂无正文</p>';
    const infoItems = [
      detail.market,
      detail.tradeDate,
      detail.sentiment
    ].filter(Boolean);

    el.innerHTML =
      '<div class="rq-article-info rq-article-info-line">' +
        infoItems.map(function (item, i) {
          return (i > 0 ? '<span class="rq-article-info-dot"></span>' : '') +
            '<span>' + escHtml(item) + '</span>';
        }).join('') +
      '</div>' +
      '<div class="rq-article-body">' + articleHtml + '</div>';
  }

  function renderTimeline() {
    const el = document.getElementById('recapTimeline');
    if (!el) return;

    const items = Array.isArray(state.timeline) ? state.timeline : [];
    el.innerHTML =
      '<section class="recap-timeline-card">' +
        '<div class="recap-timeline-head">' +
          '<h2>历史时间轴</h2>' +
          '<span class="recap-timeline-sub">' + escHtml(state.selectedMarket || '全部市场') + ' · ' + items.length + ' 条</span>' +
        '</div>' +
        (items.length ? '<div class="recap-timeline-list">' + items.map(renderTimelineItem).join('') + '</div>'
          : '<div class="recap-empty-block">暂无历史复盘</div>') +
      '</section>';
  }

  function renderTimelineItem(item) {
    const active = item.id === state.activeDetailId ? ' active' : '';
    const loading = item.id === state.detailLoadingId ? ' · 切换中' : '';
    const tags = [
      item.sentiment ? '<span class="recap-timeline-tag">' + escHtml(item.sentiment) + '</span>' : '',
      item.indexesSummary ? '<span class="recap-timeline-tag">' + escHtml(shorten(item.indexesSummary, 24)) + '</span>' : ''
    ].join('');

    return '<button class="recap-timeline-item' + active + '" data-id="' + escAttr(String(item.id)) + '">' +
      '<span class="recap-timeline-dot"></span>' +
      '<span class="recap-timeline-card-body">' +
        '<div class="recap-timeline-date">' + escHtml(item.tradeDate || '未知日期') + loading + '</div>' +
        '<h3 class="recap-timeline-title">' + escHtml(item.title || '未命名复盘') + '</h3>' +
        '<div class="recap-timeline-tags">' + tags + '</div>' +
        '<p class="recap-timeline-summary">' + escHtml(item.summaryExcerpt || '暂无摘要') + '</p>' +
      '</span>' +
    '</button>';
  }

  function renderSectors(sectors) {
    if (!Array.isArray(sectors) || !sectors.length) {
      return '<div class="recap-empty-block">暂无板块结构数据</div>';
    }
    return '<div class="recap-sector-grid">' + sectors.map(function (sector) {
      return '<article class="recap-sector-card">' +
        '<div class="recap-sector-top">' +
          '<span class="recap-sector-name">' + escHtml(sector.name || '未命名板块') + '</span>' +
          '<span class="recap-sector-strength">' + escHtml(sector.strengthLabel || '—') + '</span>' +
        '</div>' +
        '<div class="recap-sector-block"><strong>代表标的：</strong>' + escHtml(joinList(sector.leaders)) + '</div>' +
        '<div class="recap-sector-block"><strong>催化：</strong>' + escHtml(sector.catalyst || '暂无') + '</div>' +
      '</article>';
    }).join('') + '</div>';
  }

  function renderBulletList(items) {
    if (!Array.isArray(items) || !items.length) {
      return '<div class="recap-empty-block">暂无数据</div>';
    }
    return '<div class="recap-bullet-list">' + items.map(function (item) {
      return '<div class="recap-bullet-item">' + escHtml(item) + '</div>';
    }).join('') + '</div>';
  }

  function renderKeyData(items) {
    if (!Array.isArray(items) || !items.length) {
      return '<div class="recap-empty-block">暂无关键数据</div>';
    }
    return '<div class="recap-keydata-list">' + items.map(function (item) {
      return '<div class="recap-keydata-item"><span class="recap-keydata-label">' + escHtml(item.label || '数据') +
        '</span><div class="recap-keydata-value">' + escHtml(item.value || '—') + '</div></div>';
    }).join('') + '</div>';
  }

  function renderStrategy(items) {
    if (!Array.isArray(items) || !items.length) {
      return '<div class="recap-empty-block">暂无策略数据</div>';
    }
    return '<div class="recap-strategy-list">' + items.map(function (item) {
      return '<div class="recap-strategy-item"><span class="recap-strategy-label">' + escHtml(item.label || '策略') +
        '</span><div class="recap-strategy-value">' + escHtml(item.value || '—') + '</div></div>';
    }).join('') + '</div>';
  }

  function structureCard(title, spanClass, bodyHtml) {
    return '<section class="recap-structure-card ' + spanClass + '">' +
      '<h3 class="recap-card-title">' + escHtml(title) + '</h3>' + bodyHtml +
    '</section>';
  }

  function pill(text) {
    return '<span class="recap-meta-pill">' + escHtml(text) + '</span>';
  }

  function joinList(items) {
    return Array.isArray(items) && items.length ? items.join('、') : '暂无';
  }

  function shorten(text, maxLength) {
    if (!text) return '';
    return text.length <= maxLength ? text : text.slice(0, maxLength) + '...';
  }

  function unique(items) {
    const seen = new Set();
    return items.filter(function (item) {
      if (!item || seen.has(item)) return false;
      seen.add(item);
      return true;
    });
  }

  function readMarketFromUrl() {
    const url = new URL(window.location.href);
    return url.searchParams.get('market') || 'A股';
  }

  function writeMarketToUrl(market) {
    const url = new URL(window.location.href);
    if (market) {
      url.searchParams.set('market', market);
    } else {
      url.searchParams.delete('market');
    }
    window.history.replaceState({}, '', url.toString());
  }

  function escHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function escAttr(value) {
    return escHtml(value).replace(/`/g, '&#96;');
  }

  init();
}());
