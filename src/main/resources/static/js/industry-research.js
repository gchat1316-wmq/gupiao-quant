/* ============================================================
 * 产业投研 - 主交互
 * ============================================================ */
(function () {
  'use strict';

  const API = '/gp/api/industry-research';
  const STATE = {
    categories: [],
    articlesByCategory: {},
    currentCategoryId: null,
    currentArticle: null,
    charts: {},
    pollTimer: null,
    activeTaskId: null
  };

  /* ========== 工具 ========== */
  function $(sel) { return document.querySelector(sel); }
  function $$(sel) { return Array.from(document.querySelectorAll(sel)); }
  function el(tag, attrs, children) {
    const e = document.createElement(tag);
    if (attrs) Object.entries(attrs).forEach(([k, v]) => {
      if (k === 'class') e.className = v;
      else if (k === 'html') e.innerHTML = v;
      else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.substring(2), v);
      else e.setAttribute(k, v);
    });
    if (children) {
      (Array.isArray(children) ? children : [children]).forEach(c => {
        if (c == null) return;
        e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
      });
    }
    return e;
  }
  async function api(path, opts) {
    const res = await fetch(API + path, Object.assign(
      { headers: { 'Content-Type': 'application/json' } }, opts));
    if (!res.ok) throw new Error('API ' + res.status);
    return res.json();
  }

  /* ========== 1. 加载产业目录 ========== */
  async function loadCategories() {
    STATE.categories = await api('/categories');
    renderSidebar();
    // 默认选中第一个产业（有文章的）
    const firstWithArticle = STATE.categories.find(c => c.articleCount > 0);
    if (firstWithArticle) {
      await selectCategory(firstWithArticle.id);
    }
  }

  function renderSidebar() {
    const list = $('#irCategoryList');
    list.innerHTML = '';
    STATE.categories.forEach(cat => {
      const li = el('li', { class: 'ir-cat-group' });
      const head = el('div', {
        class: 'ir-cat-head' + (STATE.currentCategoryId === cat.id ? ' active' : ''),
        onclick: () => selectCategory(cat.id)
      }, [
        el('span', { class: 'ir-cat-icon' }, cat.icon || '📁'),
        el('span', { class: 'ir-cat-name' }, cat.name),
        el('span', { class: 'ir-cat-count' }, String(cat.articleCount || 0))
      ]);
      li.appendChild(head);

      // 当前选中的分类展开文章
      if (STATE.currentCategoryId === cat.id) {
        const articles = STATE.articlesByCategory[cat.id] || [];
        const ul = el('ul', { class: 'ir-article-list' });
        if (articles.length === 0) {
          ul.appendChild(el('li', { style: 'cursor:default;color:var(--text-soft);' }, '暂无文章，点击上方按钮新建'));
        }
        articles.forEach(a => {
          ul.appendChild(el('li', {
            class: STATE.currentArticle && STATE.currentArticle.id === a.id ? 'active' : '',
            onclick: () => selectArticle(a.id)
          }, [
            el('span', null, a.title),
            el('span', { class: 'ir-article-ver' }, 'v' + (a.version || 1))
          ]));
        });
        li.appendChild(ul);
      }
      list.appendChild(li);
    });
  }

  /* ========== 2. 选中分类 → 加载文章 ========== */
  async function selectCategory(catId) {
    STATE.currentCategoryId = catId;
    STATE.currentArticle = null;
    if (!STATE.articlesByCategory[catId]) {
      STATE.articlesByCategory[catId] = await api('/articles?categoryId=' + catId);
    }
    renderSidebar();
    renderEmpty();
    // 自动选中第一篇
    const articles = STATE.articlesByCategory[catId] || [];
    if (articles.length > 0) {
      await selectArticle(articles[0].id);
    }
  }

  /* ========== 3. 选中文章 → 加载详情 + 渲染 11 Tab ========== */
  async function selectArticle(articleId) {
    renderLoading('正在加载文章…');
    try {
      const detail = await api('/article/' + articleId);
      STATE.currentArticle = detail.summary;
      renderArticle(detail);
      renderSidebar();
    } catch (e) {
      renderError('加载失败：' + e.message);
    }
  }

  function renderLoading(msg) {
    $('#irContent').innerHTML = '';
    $('#irContent').appendChild(el('div', { class: 'ir-empty' }, [
      el('div', { class: 'ir-loading-spinner', style: 'margin: 0 auto 12px;' }),
      el('p', null, msg || '加载中…')
    ]));
  }
  function renderError(msg) {
    $('#irContent').innerHTML = '';
    $('#irContent').appendChild(el('div', { class: 'ir-empty' }, [
      el('div', { class: 'ir-empty-icon' }, '⚠️'),
      el('h3', null, '加载失败'),
      el('p', null, msg)
    ]));
  }
  function renderEmpty() {
    STATE.currentArticle = null;
    $('#irContent').innerHTML = '';
    $('#irContent').appendChild($('#irEmpty').cloneNode(true));
  }

  /* ========== 4. 渲染文章详情（含 11 Tab） ========== */
  function renderArticle(detail) {
    const s = detail.summary;
    const sections = detail.sections || [];
    $('#irContent').innerHTML = '';

    // 文章头
    const head = el('div', { class: 'ir-article-head' });
    head.appendChild(el('h2', { class: 'ir-article-title' }, s.title));
    if (s.subtitle) head.appendChild(el('p', { class: 'ir-article-subtitle' }, s.subtitle));
    const meta = el('div', { class: 'ir-article-meta' });
    if (s.categoryName) meta.appendChild(el('span', null, ['产业：', el('b', null, s.categoryName)]));
    if (s.updateDate) meta.appendChild(el('span', null, ['数据时点：', el('b', null, s.updateDate)]));
    if (s.sourceSummary) meta.appendChild(el('span', null, ['来源：', el('b', null, s.sourceSummary)]));
    if (s.version) meta.appendChild(el('span', null, ['版本：', el('b', null, 'v' + s.version)]));
    head.appendChild(meta);
    if (s.tags) {
      const tagWrap = el('div', { style: 'margin-top:8px;' });
      s.tags.split(',').filter(Boolean).forEach(t => tagWrap.appendChild(el('span', { class: 'ir-tag' }, t.trim())));
      head.appendChild(tagWrap);
    }
    $('#irContent').appendChild(head);

    if (sections.length === 0) {
      $('#irContent').appendChild(el('div', { class: 'ir-empty' }, '该文章暂无章节内容'));
      return;
    }

    // Tab 切换
    const tabs = el('nav', { class: 'ir-tabs' });
    const tabPanels = el('div');
    sections.forEach((sec, idx) => {
      const btn = el('button', {
        class: 'ir-tab' + (idx === 0 ? ' active' : ''),
        onclick: () => switchTab(idx)
      }, sec.sectionTitle);
      tabs.appendChild(btn);

      const panel = el('div', { class: 'ir-tab-panel' + (idx === 0 ? ' active' : '') });
      renderSectionContent(panel, sec);
      tabPanels.appendChild(panel);
    });
    $('#irContent').appendChild(tabs);
    $('#irContent').appendChild(tabPanels);

    function switchTab(idx) {
      $$('.ir-tab').forEach((b, i) => b.classList.toggle('active', i === idx));
      $$('.ir-tab-panel').forEach((p, i) => p.classList.toggle('active', i === idx));
      // 重渲染图表
      setTimeout(() => sections.forEach((sec, i) => i === idx && renderChart(sec, i)), 50);
    }

    // 首次激活时渲染图表
    setTimeout(() => renderChart(sections[0], 0), 100);
  }

  function renderSectionContent(panel, sec) {
    const c = sec.content || {};
    // 子标题
    if (c.subtitle) panel.appendChild(el('p', { style: 'color:var(--text-mid);font-size:12px;margin-bottom:12px;' }, c.subtitle));
    // 备注
    if (c.note) panel.appendChild(el('div', { class: 'ir-note' }, c.note));
    // 数据源
    if (sec.source) panel.appendChild(el('div', { style: 'color:var(--text-soft);font-size:11px;margin-bottom:14px;' }, '📚 ' + sec.source));

    // 指标卡
    if (Array.isArray(c.metrics)) {
      const grid = el('div', { class: 'ir-kpi-grid' });
      c.metrics.forEach(m => {
        grid.appendChild(el('div', { class: 'ir-kpi' }, [
          el('div', { class: 'ir-kpi-label' }, [
            document.createTextNode(m.label || ''),
            m.badge ? el('span', { class: 'ir-kpi-badge' }, m.badge) : null
          ]),
          el('div', { class: 'ir-kpi-value' }, [
            document.createTextNode(String(m.value || '')),
            m.unit ? el('span', { class: 'ir-kpi-unit' }, m.unit) : null
          ]),
          m.desc ? el('div', { class: 'ir-kpi-desc' }, m.desc) : null
        ]));
      });
      panel.appendChild(grid);
    }

    // 结论块
    if (Array.isArray(c.conclusions)) {
      const wrap = el('div', { style: 'margin-bottom:14px;' });
      c.conclusions.forEach(con => {
        wrap.appendChild(el('div', { class: 'ir-conclusion ' + (con.level || 'info') }, [
          el('span', { class: 'ir-tag-inline' }, con.tag || ''),
          document.createTextNode(con.text || '')
        ]));
      });
      panel.appendChild(wrap);
    }

    // BOM 条
    if (Array.isArray(c.bomBars)) {
      const wrap = el('div', { style: 'margin-bottom:14px;' });
      c.bomBars.forEach(b => {
        wrap.appendChild(el('div', { class: 'ir-bom-row' }, [
          el('span', { class: 'ir-bom-lbl' }, b.label || ''),
          el('div', { class: 'ir-bom-bar' }, [
            el('div', { class: 'ir-bom-fill', style: 'width:' + (b.percentage || 0) + '%' },
              (b.percentage || 0) + '%')
          ]),
          el('span', { class: 'ir-bom-val' }, b.value || '')
        ]));
      });
      panel.appendChild(wrap);
    }

    // 表格
    if (Array.isArray(c.tables)) {
      c.tables.forEach(t => {
        const wrap = el('div', { class: 'ir-table-wrap' });
        if (t.name) wrap.appendChild(el('div', { class: 'ir-table-name' }, t.name));
        const table = el('table', { class: 'ir-table' });
        const thead = el('thead');
        const trh = el('tr');
        (t.headers || []).forEach((h, i) => {
          const isNum = h.includes('PE') || h.includes('PB') || h.includes('市值') || h.includes('YoY') || h.includes('%') || h.includes('×') || h.includes('份额');
          trh.appendChild(el('th', { class: isNum ? 'num' : '' }, h));
        });
        thead.appendChild(trh);
        table.appendChild(thead);
        const tbody = el('tbody');
        (t.rows || []).forEach(row => {
          const tr = el('tr');
          row.forEach((cell, i) => {
            const txt = String(cell);
            const h = (t.headers || [])[i] || '';
            const isNum = h.includes('PE') || h.includes('PB') || h.includes('市值') || h.includes('YoY') || h.includes('%') || h.includes('×') || h.includes('份额');
            let cls = isNum ? 'num' : '';
            if (txt.startsWith('+')) cls += ' pos';
            else if (txt.startsWith('-')) cls += ' neg';
            tr.appendChild(el('td', { class: cls }, txt));
          });
          tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        wrap.appendChild(table);
        panel.appendChild(wrap);
      });
    }

    // 股票卡片
    if (Array.isArray(c.stockCards)) {
      const grid = el('div', { class: 'ir-stock-grid' });
      c.stockCards.forEach(s => {
        const card = el('div', { class: 'ir-stock' }, [
          el('div', { class: 'ir-stock-head' }, [
            el('span', { class: 'ir-stock-name' }, [
              document.createTextNode(s.name || ''),
              s.code ? el('span', { class: 'ir-stock-code' }, s.code) : null
            ]),
            el('span', { class: 'ir-stock-pe' }, [
              document.createTextNode('PE '),
              el('b', null, s.pe || '—'),
              s.marketCap ? document.createTextNode(' · 市值 ' + s.marketCap + '亿') : null
            ])
          ]),
          el('div', { class: 'ir-stock-logic' }, s.logic || ''),
          el('div', { class: 'ir-stock-score' }, [
            document.createTextNode('综合评分 ' + (s.score || '—') + ' · 不可替代性 ' + (s.irreplaceablePct || '—') + '%')
          ]),
          el('div', { class: 'ir-stock-score-bar' }, [
            el('div', { class: 'ir-stock-score-fill', style: 'width:' + (s.score || 0) + '%' })
          ])
        ]);
        grid.appendChild(card);
      });
      panel.appendChild(grid);
    }

    // 图表（懒渲染）
    if (c.chart) {
      const wrap = el('div', { class: 'ir-chart-wrap' });
      wrap.appendChild(el('canvas'));
      panel.appendChild(wrap);
      // 保存 chart 数据供后续渲染
      panel._chartData = c.chart;
      panel._chartId = 'chart-' + Math.random().toString(36).substring(7);
    }

    // 新闻列表
    if (Array.isArray(c.news)) {
      const wrap = el('div', { style: 'margin-bottom:12px;' });
      c.news.forEach(n => {
        wrap.appendChild(el('div', { class: 'ir-news-item' }, [
          el('span', { class: 'ir-news-time' }, n.time || ''),
          el('span', { class: 'ir-news-src' }, n.source || ''),
          el('span', { class: 'ir-news-title' }, n.title || '')
        ]));
      });
      panel.appendChild(wrap);
    }

    // 关键词热度
    if (Array.isArray(c.topKeywords)) {
      const wrap = el('div', { style: 'display:flex;flex-wrap:wrap;gap:6px;' });
      c.topKeywords.forEach(k => {
        wrap.appendChild(el('span', { class: 'ir-tag' }, [
          document.createTextNode(k.keyword || ''),
          document.createTextNode(' ' + (k.count || ''))
        ]));
      });
      panel.appendChild(el('div', { style: 'margin-top:14px;' }, [
        el('div', { style: 'font-size:12px;color:var(--text-soft);margin-bottom:6px;font-weight:600;' }, '🔥 关键词热度'),
        wrap
      ]));
    }
  }

  /* ========== 5. 图表渲染 ========== */
  function renderChart(sec, idx) {
    const panel = document.querySelectorAll('.ir-tab-panel')[idx];
    if (!panel) return;
    const canvas = panel.querySelector('canvas');
    if (!canvas || !panel._chartData) return;
    const data = panel._chartData.data || {};
    const type = panel._chartData.chartType || 'bar';

    if (STATE.charts[idx]) STATE.charts[idx].destroy();
    STATE.charts[idx] = new Chart(canvas.getContext('2d'), {
      type: type,
      data: {
        labels: data.labels || [],
        datasets: [{
          label: data.label || '',
          data: data.values || [],
          backgroundColor: type === 'line'
            ? 'rgba(225, 6, 44, 0.15)'
            : ['#e1062c', '#2563eb', '#10b981', '#f59e0b', '#8b5cf6', '#06b6d4'].slice(0, (data.values || []).length),
          borderColor: '#e1062c',
          borderWidth: type === 'line' ? 2 : 0,
          tension: 0.3,
          pointRadius: type === 'line' ? 4 : 0,
          pointBackgroundColor: '#e1062c',
          fill: type === 'line'
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: type === 'doughnut', position: 'right' }
        },
        scales: type === 'doughnut' ? {} : {
          y: { beginAtZero: true, grid: { color: 'rgba(15,15,20,0.05)' } },
          x: { grid: { display: false } }
        }
      }
    });
  }

  /* ========== 6. 任务相关 ========== */
  async function loadTasks() {
    const tasks = await api('/tasks');
    const list = $('#irTaskList');
    list.innerHTML = '';
    if (tasks.length === 0) {
      list.appendChild(el('li', { class: 'ir-loading' }, '暂无任务'));
      return;
    }
    tasks.slice(0, 10).forEach(t => {
      list.appendChild(el('li', { onclick: () => openTaskLog(t) }, [
        el('span', { class: 'ir-task-status ' + t.status }),
        el('span', { style: 'flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }, t.taskName),
        el('span', { style: 'color:var(--text-soft);font-size:10px;' }, (t.progress || 0) + '%')
      ]));
    });
  }

  function openTaskLog(task) {
    if (task.status === 'running') {
      // 重新激活这个任务的轮询
      STATE.activeTaskId = task.id;
      showTaskbar(task);
      pollTask();
    } else if (task.articleId) {
      selectArticle(task.articleId);
    } else if (task.log) {
      alert('任务日志：\n' + task.log);
    }
  }

  function showTaskbar(task) {
    $('#irTaskbar').hidden = false;
    updateTaskbar(task);
  }
  function updateTaskbar(task) {
    $('#irTaskbarStage').textContent = '阶段：' + stageLabel(task.stage) + ' · ' + (task.taskName || '');
    $('#irTaskbarBar').style.width = (task.progress || 0) + '%';
    $('#irTaskbarPct').textContent = (task.progress || 0) + '%';
  }
  function hideTaskbar() {
    $('#irTaskbar').hidden = true;
    if (STATE.pollTimer) { clearInterval(STATE.pollTimer); STATE.pollTimer = null; }
    STATE.activeTaskId = null;
  }
  function stageLabel(stage) {
    return {
      'init': '初始化',
      'data-fetch': 'A-Stock-Data 取数据',
      'report-read': 'Kimi CLI 读研报',
      'news-radar': 'News Radar 抓新闻',
      'assembling': '组装 11 Tab',
      'saving': '写入数据库',
      'done': '已完成'
    }[stage] || stage;
  }

  async function pollTask() {
    if (!STATE.activeTaskId) return;
    try {
      const t = await api('/task/' + STATE.activeTaskId);
      updateTaskbar(t);
      if (t.status === 'success') {
        // 刷新菜单（可能多了文章）+ 自动跳到新文章
        await loadCategories();
        if (t.articleId) selectArticle(t.articleId);
        setTimeout(hideTaskbar, 2500);
      } else if (t.status === 'failed') {
        alert('任务失败：\n' + (t.errorMessage || ''));
        hideTaskbar();
      } else {
        STATE.pollTimer = setTimeout(pollTask, 1500);
      }
    } catch (e) {
      STATE.pollTimer = setTimeout(pollTask, 3000);
    }
  }

  /* ========== 7. 新任务弹窗 ========== */
  function openModal() {
    const modal = $('#irModal');
    const sel = $('#irModalCategory');
    sel.innerHTML = '';
    STATE.categories.forEach(c => {
      sel.appendChild(el('option', { value: c.code }, c.icon + ' ' + c.name));
    });
    $('#irModalKeyword').value = '';
    $('#irModalTaskName').value = '';
    modal.hidden = false;
  }
  function closeModal() { $('#irModal').hidden = true; }

  async function confirmNewTask() {
    const req = {
      categoryCode: $('#irModalCategory').value,
      keyword: $('#irModalKeyword').value || null,
      taskName: $('#irModalTaskName').value || null
    };
    try {
      const task = await api('/pipeline/run', {
        method: 'POST',
        body: JSON.stringify(req)
      });
      STATE.activeTaskId = task.id;
      showTaskbar(task);
      pollTask();
      closeModal();
      loadTasks();
    } catch (e) {
      alert('启动失败：' + e.message);
    }
  }

  /* ========== 8. 搜索 ========== */
  function bindSearch() {
    $('#irSearchInput').addEventListener('input', e => {
      const q = e.target.value.trim().toLowerCase();
      $$('.ir-cat-group').forEach(li => {
        const name = li.querySelector('.ir-cat-name').textContent.toLowerCase();
        li.style.display = (!q || name.includes(q)) ? '' : 'none';
      });
    });
  }

  /* ========== 初始化 ========== */
  document.addEventListener('DOMContentLoaded', async () => {
    try {
      await loadCategories();
      await loadTasks();
      bindSearch();
      $('#irNewTaskBtn').addEventListener('click', openModal);
      $('#irModalConfirm').addEventListener('click', confirmNewTask);
      $$('[data-modal-close]').forEach(el => el.addEventListener('click', closeModal));
      $('#irTaskbarClose').addEventListener('click', hideTaskbar);
    } catch (e) {
      console.error('Init failed', e);
      renderError('初始化失败：' + e.message);
    }
  });
})();