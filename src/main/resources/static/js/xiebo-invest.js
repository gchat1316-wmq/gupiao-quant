(function () {
  'use strict';

  const state = {
    watchlist: [],
    quote: null,
    sector: null,
    news: null,
    analysisHistory: [],
    currentAnalysis: null
  };

  function $(id) {
    return document.getElementById(id);
  }

  function esc(v) {
    return String(v == null ? '' : v)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function fmt(v, digits) {
    if (v == null || v === '') return '—';
    const n = Number(v);
    if (Number.isNaN(n)) return esc(v);
    return n.toFixed(digits == null ? 2 : digits);
  }

  function pegClass(rating) {
    if (rating === '极度低估' || rating === '低估') return 'good';
    if (rating === '合理' || rating === '暂不适用') return 'warn';
    return 'bad';
  }

  async function api(path, options) {
    const res = await fetch(path, options);
    const text = await res.text();
    const data = text ? JSON.parse(text) : {};
    if (!res.ok) throw new Error(data.message || data.error || ('HTTP ' + res.status));
    return data;
  }

  async function loadWatchlist() {
    state.watchlist = await api('api/xiebo-invest/watchlist');
    renderWatchlist();
  }

  async function addWatchlist() {
    const keyword = $('watchlistKeyword').value.trim();
    if (!keyword) return;
    try {
      $('watchlistError').classList.add('hidden');
      state.watchlist = await api('api/xiebo-invest/watchlist', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ keyword })
      });
      $('watchlistKeyword').value = '';
      renderWatchlist();
    } catch (err) {
      $('watchlistError').textContent = err.message || '加入失败';
      $('watchlistError').classList.remove('hidden');
    }
  }

  async function removeWatchlist(stockCode) {
    await api('api/xiebo-invest/watchlist/' + encodeURIComponent(stockCode), { method: 'DELETE' });
    await loadWatchlist();
  }

  async function loadQuote() {
    const keyword = $('quoteKeyword').value.trim();
    if (!keyword) return;
    state.quote = await api('api/xiebo-invest/quote?keyword=' + encodeURIComponent(keyword));
    renderQuote();
  }

  async function loadSector() {
    const keyword = $('sectorKeyword').value.trim();
    if (!keyword) return;
    state.sector = await api('api/xiebo-invest/sector-pe?keyword=' + encodeURIComponent(keyword));
    renderSector();
  }

  async function loadNews() {
    const keyword = $('newsKeyword').value.trim();
    const suffix = keyword ? ('?keyword=' + encodeURIComponent(keyword)) : '';
    state.news = await api('api/xiebo-invest/news' + suffix);
    renderNews();
  }

  async function loadAnalysisHistory() {
    state.analysisHistory = await api('api/xiebo-invest/analysis');
    renderAnalysis();
  }

  async function createAnalysis() {
    const keyword = $('analysisKeyword').value.trim();
    if (!keyword) return;
    state.currentAnalysis = await api('api/xiebo-invest/analysis', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ keyword: keyword })
    });
    await loadAnalysisHistory();
    renderAnalysis();
  }

  async function loadAnalysisDetail(id) {
    state.currentAnalysis = await api('api/xiebo-invest/analysis/' + encodeURIComponent(id));
    renderAnalysis();
  }

  function renderWatchlist() {
    const mount = $('lynchWatchlist');
    if (!state.watchlist.length) {
      mount.innerHTML = '<div class="lynch-empty">还没有加入任何监控股票。</div>';
      return;
    }
    mount.innerHTML = '<table class="lynch-table"><thead><tr>' +
      '<th>股票</th><th>现价</th><th>PE</th><th>PB</th><th>CAGR</th><th>PEG</th><th>评级</th><th>操作</th>' +
      '</tr></thead><tbody>' +
      state.watchlist.map(function (item) {
        return '<tr>' +
          '<td><strong>' + esc(item.stockName) + '</strong><div style="color:#6b7280;font-size:12px">' + esc(item.stockCode) + '</div></td>' +
          '<td>' + fmt(item.price, 2) + '</td>' +
          '<td>' + fmt(item.peTtm, 2) + '</td>' +
          '<td>' + fmt(item.pb, 2) + '</td>' +
          '<td>' + fmt(item.cagrPct, 2) + '%</td>' +
          '<td>' + fmt(item.peg, 2) + '</td>' +
          '<td><span class="lynch-chip ' + pegClass(item.pegRating) + '">' + esc(item.pegRating || '—') + '</span></td>' +
          '<td><button class="metric-link-btn lynch-remove-btn" data-code="' + esc(item.stockCode) + '">删除</button></td>' +
          '</tr>';
      }).join('') + '</tbody></table>';
    mount.querySelectorAll('.lynch-remove-btn').forEach(function (btn) {
      btn.addEventListener('click', function () { removeWatchlist(btn.dataset.code); });
    });
  }

  function renderQuote() {
    const q = state.quote;
    if (!q) {
      $('lynchQuote').innerHTML = '<div class="lynch-empty">输入股票后查看 PEG 快照。</div>';
      return;
    }
    $('lynchQuote').innerHTML =
      '<div class="lynch-kv">' +
      kv('股票', esc(q.stockName) + ' · ' + esc(q.stockCode)) +
      kv('行业', esc(q.sectorName || '—')) +
      kv('当前价格', fmt(q.price, 2)) +
      kv('PE(TTM)', fmt(q.peTtm, 2)) +
      kv('PB', fmt(q.pb, 2)) +
      kv('总市值(亿)', fmt(q.marketCap, 2)) +
      kv('3年 CAGR(%)', fmt(q.cagrPct, 2)) +
      kv('PEG', fmt(q.peg, 2) + ' · ' + esc(q.pegRating || '—')) +
      '</div>';
  }

  function renderSector() {
    const s = state.sector;
    if (!s) {
      $('lynchSector').innerHTML = '<div class="lynch-empty">输入股票后查看行业 PE 对比。</div>';
      return;
    }
    const stocks = Array.isArray(s.stocks) ? s.stocks : [];
    $('lynchSector').innerHTML =
      '<div class="lynch-kv" style="margin-bottom:12px">' +
      kv('行业', esc(s.sectorName || '—')) +
      kv('样本数', esc(s.count || 0)) +
      kv('平均 PE', fmt(s.avgPe, 2)) +
      kv('中位 PE', fmt(s.medianPe, 2)) +
      '</div>' +
      '<div class="lynch-table-shell"><table class="lynch-table"><thead><tr><th>股票</th><th>PE</th><th>PB</th><th>市值(亿)</th></tr></thead><tbody>' +
      stocks.map(function (item) {
        return '<tr><td><strong>' + esc(item.stockName || '') + '</strong><div style="color:#6b7280;font-size:12px">' + esc(item.stockCode || '') + '</div></td>' +
          '<td>' + fmt(item.peTtm, 2) + '</td><td>' + fmt(item.pb, 2) + '</td><td>' + fmt(item.marketCap, 2) + '</td></tr>';
      }).join('') +
      '</tbody></table></div>';
  }

  function renderAnalysis() {
    if (!state.currentAnalysis) {
      $('lynchAnalysisCurrent').innerHTML = '<div class="lynch-empty">输入股票后生成 PEG 报告，或点击下方历史记录查看。</div>';
    } else {
      var a = state.currentAnalysis;
      $('lynchAnalysisCurrent').innerHTML =
        '<div class="lynch-kv" style="margin-bottom:12px">' +
        kv('股票', esc(a.stockName) + ' · ' + esc(a.stockCode)) +
        kv('状态', esc(a.status || '—')) +
        kv('PEG', fmt(a.pegValue, 2)) +
        kv('评级', esc(a.pegRating || '—')) +
        '</div>' +
        '<div class="lynch-report">' +
        (a.reportMarkdown ? markdownToHtml(a.reportMarkdown) : '<div class="lynch-empty">暂无报告内容。</div>') +
        '</div>';
    }

    if (!state.analysisHistory.length) {
      $('lynchAnalysisHistory').innerHTML = '<div class="lynch-empty">暂无分析记录。</div>';
      return;
    }
    $('lynchAnalysisHistory').innerHTML = '<div class="lynch-history-list">' +
      state.analysisHistory.map(function (item) {
        return '<button type="button" class="lynch-history-item lynch-history-btn" data-id="' + esc(item.id) + '">' +
          '<strong>' + esc(item.stockName) + '</strong>' +
          '<span style="color:#6b7280"> · ' + esc(item.stockCode) + '</span>' +
          '<div style="margin-top:4px;color:#6b7280;font-size:12px">' + esc(item.conclusion || '') + '</div>' +
          '</button>';
      }).join('') +
      '</div>';
    $('lynchAnalysisHistory').querySelectorAll('.lynch-history-btn').forEach(function (btn) {
      btn.addEventListener('click', function () { loadAnalysisDetail(btn.dataset.id); });
    });
  }

  function renderNews() {
    var news = state.news || {};
    $('lynchNews').innerHTML =
      '<div class="lynch-news-columns">' +
      newsCol('个股新闻', news.stockNews || []) +
      newsCol('公司公告', news.announcements || []) +
      newsCol('市场快讯', news.marketNews || []) +
      '</div>';
  }

  function kv(label, value) {
    return '<div class="lynch-kv-card"><span>' + label + '</span><strong>' + value + '</strong></div>';
  }

  function newsCol(title, items) {
    return '<div class="lynch-news-col"><h3>' + title + '</h3><div class="lynch-news-list">' +
      (items.length ? items.map(function (x) {
        var body = x.url
          ? '<a href="' + esc(x.url) + '" target="_blank" rel="noreferrer noopener">' + esc(x.title || '') + '</a>'
          : esc(x.title || '');
        var meta = [x.ticker, x.source, x.time].filter(Boolean).map(esc).join(' · ');
        var content = x.content ? '<div>' + esc(x.content) + '</div>' : '';
        return '<div class="lynch-news-item">' + body + content + (meta ? '<div class="lynch-news-meta">' + meta + '</div>' : '') + '</div>';
      }).join('') : '<div class="lynch-empty">暂无数据</div>') +
      '</div></div>';
  }

  function markdownToHtml(md) {
    return esc(md)
      .replace(/^# (.+)$/gm, '<h3>$1</h3>')
      .replace(/^## (.+)$/gm, '<h4>$1</h4>')
      .replace(/^- (.+)$/gm, '<li>$1</li>')
      .replace(/(<li>.*<\/li>)/gs, '<ul>$1</ul>')
      .replace(/\n\n/g, '</p><p>')
      .replace(/^(.+)$/gm, function (m) {
        if (/^<h[34]>/.test(m) || /^<ul>/.test(m) || /^<\/ul>/.test(m)) return m;
        if (/^<li>/.test(m)) return m;
        if (/^> /.test(m)) return '<blockquote>' + m.slice(2) + '</blockquote>';
        return '<p>' + m + '</p>';
      })
      .replace(/<p><\/p>/g, '');
  }

  async function init() {
    renderQuote();
    renderSector();
    renderAnalysis();
    renderNews();
    await loadWatchlist().catch(function () {});
    await loadAnalysisHistory().catch(function () {});
    await loadNews().catch(function () {});

    $('watchlistAddBtn').addEventListener('click', addWatchlist);
    $('quoteLoadBtn').addEventListener('click', loadQuote);
    $('sectorLoadBtn').addEventListener('click', loadSector);
    $('analysisCreateBtn').addEventListener('click', createAnalysis);
    $('newsLoadBtn').addEventListener('click', loadNews);

    bindSubnav();
  }

  function bindSubnav() {
    const tabs = document.querySelectorAll('.lynch-subnav-tab');
    const panes = document.querySelectorAll('.lynch-pane');
    if (!tabs.length || !panes.length) return;

    function activate(tabName) {
      tabs.forEach(function (tab) {
        const isActive = tab.dataset.tab === tabName;
        tab.classList.toggle('active', isActive);
        tab.setAttribute('aria-selected', isActive ? 'true' : 'false');
      });
      panes.forEach(function (pane) {
        const isMatch = pane.dataset.pane === tabName;
        pane.classList.toggle('active', isMatch);
        if (isMatch) {
          pane.removeAttribute('hidden');
        } else {
          pane.setAttribute('hidden', '');
        }
      });
    }

    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        if (!tab.dataset.tab) return;
        activate(tab.dataset.tab);
      });
    });
  }

  document.addEventListener('DOMContentLoaded', init);
}());
