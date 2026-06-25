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
        if (c instanceof Node) { e.appendChild(c); return; }
        if (typeof c === 'string' || typeof c === 'number' || typeof c === 'boolean') {
          e.appendChild(document.createTextNode(String(c)));
          return;
        }
        // 数组：扁平化递归
        if (Array.isArray(c)) {
          c.forEach(cc => { if (cc instanceof Node) e.appendChild(cc); else if (typeof cc === 'string' || typeof cc === 'number') e.appendChild(document.createTextNode(String(cc))); });
          return;
        }
        // 未知类型：转 JSON 字符串避免 appendChild 炸
        try { e.appendChild(document.createTextNode(JSON.stringify(c))); } catch (_) { /* 彻底兑底 */ }
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
      // URL 同步更新（便于分享）
      const newUrl = new URL(window.location.href);
      newUrl.searchParams.set('articleId', articleId);
      window.history.replaceState({}, '', newUrl.toString());
      try {
        renderArticle(detail);
      } catch (inner) {
        console.error('renderArticle 炸了:', inner);
        $('#irContent').innerHTML = '';
        $('#irContent').appendChild(el('div', { class: 'ir-empty' }, [
          el('div', { class: 'ir-empty-icon' }, '💥'),
          el('h3', null, 'renderArticle 崩溃'),
          el('p', null, '类型：' + inner.name + ' · ' + inner.message),
          el('pre', { style: 'white-space:pre-wrap;font-size:11px;background:#1a1a1f;color:#f87171;padding:10px;border-radius:6px;overflow:auto;max-height:300px;text-align:left;' }, (inner.stack || '').slice(0, 2000))
        ]));
        return;
      }
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
  // 缓存空状态模板（init 时 #irContent 还未被清空，必须在此刻克隆）
  const EMPTY_TEMPLATE = (() => {
    const t = $('#irEmpty');
    return t ? t.cloneNode(true) : null;
  })();

  function renderEmpty() {
    STATE.currentArticle = null;
    const content = $('#irContent');
    if (!content) return;
    content.innerHTML = '';
    if (EMPTY_TEMPLATE) {
      content.appendChild(EMPTY_TEMPLATE.cloneNode(true));
    } else {
      // 兜底：模板丢失时直接 inline 一份
      content.innerHTML =
        '<div class="ir-empty">' +
        '<div class="ir-empty-icon">🧠</div>' +
        '<h3>产业投研看板</h3>' +
        '<p>左侧选择一个产业查看已发布的深度报告；<br>' +
        '或点击右上角「启动新投研任务」由 AI 流水线自动生成。</p></div>';
    }
  }

  /* ========== 4. 渲染文章详情（含 11 Tab + Hero 大数字 KPI + 分享按钮） ========== */
  function renderArticle(detail) {
    const s = detail.summary;
    const sections = detail.sections || [];
    $('#irContent').innerHTML = '';

    /* --- 文章 Hero：标题 + meta + tags + 分享按钮 --- */
    const hero = el('section', { class: 'ir-article-hero' });
    const heroLeft = el('div', { class: 'ir-article-hero-main' });
    heroLeft.appendChild(el('div', { class: 'ir-kicker' },
      (s.categoryIcon ? (s.categoryIcon + ' ') : '🧠 ') + (s.categoryName || '产业投研')));
    heroLeft.appendChild(el('h2', { class: 'ir-article-title' }, s.title));
    if (s.subtitle) heroLeft.appendChild(el('p', { class: 'ir-article-subtitle' }, s.subtitle));
    const meta = el('div', { class: 'ir-article-meta' });
    if (s.updateDate) meta.appendChild(el('span', null, ['数据时点：', el('b', null, s.updateDate)]));
    if (s.sourceSummary) meta.appendChild(el('span', null, ['来源：', el('b', null, s.sourceSummary)]));
    if (s.version) meta.appendChild(el('span', { class: 'ir-version-badge' }, 'v' + s.version));
    heroLeft.appendChild(meta);
    if (s.tags) {
      const tagWrap = el('div', { class: 'ir-tag-list' });
      s.tags.split(',').filter(Boolean).forEach(t => tagWrap.appendChild(el('span', { class: 'ir-tag' }, t.trim())));
      heroLeft.appendChild(tagWrap);
    }
    hero.appendChild(heroLeft);

    const heroActions = el('div', { class: 'ir-article-hero-actions' });
    heroActions.appendChild(el('button', {
      class: 'ir-btn ir-btn-ghost',
      id: 'irShareBtn',
      onclick: () => openShareModal(s)
    }, ['📤', el('span', null, '分享')]));
    hero.appendChild(heroActions);
    $('#irContent').appendChild(hero);

    /* --- 大数字 KPI 灯塔：从所有 sections 抽 metrics，取前 4 个 --- */
    const heroKpis = collectHeroKpis(detail);
    if (heroKpis.length > 0) {
      const kpiRow = el('div', { class: 'ir-hero-kpis' });
      heroKpis.forEach(k => {
        const card = el('div', { class: 'ir-kpi-big ' + (k.tone || '') }, [
          el('div', { class: 'ir-kpi-big-label' }, [
            document.createTextNode(k.label || ''),
            k.badge ? el('span', { class: 'ir-kpi-badge' }, k.badge) : null
          ]),
          el('div', { class: 'ir-kpi-big-value' }, [
            document.createTextNode(String(k.value || '—')),
            k.unit ? el('span', { class: 'ir-kpi-big-unit' }, ' ' + k.unit) : null
          ]),
          k.desc ? el('div', { class: 'ir-kpi-big-desc' }, k.desc) : null
        ]);
        kpiRow.appendChild(card);
      });
      $('#irContent').appendChild(kpiRow);
    }

    if (sections.length === 0) {
      $('#irContent').appendChild(el('div', { class: 'ir-empty' }, '该文章暂无章节内容'));
      return;
    }

    /* --- Tab 切换 --- */
    const tabs = el('nav', { class: 'ir-tabs' });
    const tabPanels = el('div', { class: 'ir-tab-panels' });
    sections.forEach((sec, idx) => {
      const tabIdx = idx + 1;
      const btn = el('button', {
        class: 'ir-tab' + (idx === 0 ? ' active' : ''),
        onclick: () => switchTab(idx)
      }, [
        el('span', { class: 'ir-tab-num' }, String(tabIdx).padStart(2, '0')),
        el('span', { class: 'ir-tab-name' }, sec.sectionTitle)
      ]);
      tabs.appendChild(btn);

      const panel = el('div', { class: 'ir-tab-panel' + (idx === 0 ? ' active' : '') });
      renderSectionContent(panel, sec, tabIdx);
      tabPanels.appendChild(panel);
    });
    $('#irContent').appendChild(tabs);
    $('#irContent').appendChild(tabPanels);

    function switchTab(idx) {
      $$('.ir-tab').forEach((b, i) => b.classList.toggle('active', i === idx));
      $$('.ir-tab-panel').forEach((p, i) => p.classList.toggle('active', i === idx));
      setTimeout(() => sections.forEach((sec, i) => i === idx && renderChart(sec, i)), 50);
    }

    // 首次激活时渲染图表
    setTimeout(() => renderChart(sections[0], 0), 100);
  }

  /**
   * 从 sections 里抽 metrics 当 Hero KPI 灯塔：优先取第一个有 metrics 的 section，最多 4 个
   * tone: ok / warn / info / 默认
   */
  function collectHeroKpis(detail) {
    const sections = detail.sections || [];
    const metrics = [];
    // 第一遍：找 overview（sectionKey 或 title 含"总览"）的 metrics
    let picked = null;
    for (const sec of sections) {
      const isOverview = (sec.sectionKey || '').toLowerCase() === 'overview'
        || /总览|overview/i.test(sec.sectionTitle || '');
      if (isOverview && sec.content && Array.isArray(sec.content.metrics)) {
        picked = sec.content.metrics;
        break;
      }
    }
    if (!picked) {
      for (const sec of sections) {
        if (sec.content && Array.isArray(sec.content.metrics) && sec.content.metrics.length > 0) {
          picked = sec.content.metrics;
          break;
        }
      }
    }
    if (!picked) return [];
    return picked.slice(0, 4).map(m => ({
      label: m.label, value: m.value, unit: m.unit, badge: m.badge, desc: m.desc,
      tone: (m.badge || '').toUpperCase().includes('OK') ? 'ok'
        : (m.badge || '').toUpperCase().includes('WARN') || (m.badge || '').toUpperCase().includes('RISK') ? 'warn'
        : (m.badge || '').toUpperCase().includes('INFO') ? 'info' : ''
    }));
  }

  function renderSectionContent(panel, sec, tabIdx) {
    const c = sec.content || {};
    // 小节顶部条：编号 + 数据源（更结构化）
    const sectionHead = el('div', { class: 'ir-section-head' });
    if (tabIdx) {
      sectionHead.appendChild(el('span', { class: 'ir-section-num' }, String(tabIdx).padStart(2, '0')));
    }
    sectionHead.appendChild(el('span', { class: 'ir-section-title-sm' }, sec.sectionTitle || ''));
    if (sec.source) sectionHead.appendChild(el('span', { class: 'ir-section-source' }, '📚 ' + sec.source));
    panel.appendChild(sectionHead);

    // 备注（突出）
    if (c.note) panel.appendChild(el('div', { class: 'ir-note' }, '💡 ' + c.note));
    // 子标题
    if (c.subtitle) panel.appendChild(el('p', { class: 'ir-section-subtitle' }, c.subtitle));

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
      'report-read': 'AI 读研报',
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
    modal.style.display = 'flex'; // 双保险
  }
  function closeModal() {
    const modal = $('#irModal');
    if (!modal) return;
    modal.hidden = true;
    modal.style.display = 'none'; // 双保险，避免 CSS 优先级问题
  }

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

  /* ========== 初始化 ==========
   * 注意：先同步绑定所有事件，再异步加载数据。
   * 这样即使 categories/tasks API 失败，弹窗开关等 UI 仍可用。
   */
  document.addEventListener('DOMContentLoaded', () => {
    // 1. 事件绑定（同步，立即生效，与数据加载解耦）
    $('#irNewTaskBtn').addEventListener('click', openModal);
    $('#irModalConfirm').addEventListener('click', confirmNewTask);
    $$('[data-modal-close]').forEach(el => el.addEventListener('click', closeModal));
    $$('[data-share-close]').forEach(el => el.addEventListener('click', closeShareModal));
    $('#irTaskbarClose').addEventListener('click', hideTaskbar);
    $('#irShareCopyUrlBtn').addEventListener('click', copyShareUrl);
    $('#irShareCopySummaryBtn').addEventListener('click', copyShareSummary);
    bindSearch();

    // ESC 关闭所有弹窗
    document.addEventListener('keydown', e => {
      if (e.key !== 'Escape') return;
      if ($('#irShareModal') && !$('#irShareModal').hidden) closeShareModal();
      else if ($('#irModal') && !$('#irModal').hidden) closeModal();
    });

    // 2. 数据加载（异步，失败不阻断 UI）
    // URL 参数 ?articleId=N 直接定位文章（分享入口）
    const requestedArticleId = new URLSearchParams(location.search).get('articleId');
    if (requestedArticleId) {
      // 直接加载指定文章（同时后台加载 categories 让左侧菜单正常）
      loadCategories().catch(e => console.error('loadCategories failed', e));
      loadTasks().catch(e => console.error('loadTasks failed', e));
      selectArticle(parseInt(requestedArticleId, 10)).catch(e => renderError('加载失败：' + e.message));
    } else {
      loadCategories().catch(e => {
        console.error('loadCategories failed', e);
        renderError('加载产业目录失败：' + e.message);
      });
      loadTasks().catch(e => console.error('loadTasks failed', e));
    }
  });

  /* ========== 9. 分享功能 ========== */
  function openShareModal(article) {
    if (!article || !article.id) return;
    const modal = $('#irShareModal');
    if (!modal) return;
    const url = `${location.origin}/gp/industry-research.html?articleId=${article.id}`;
    $('#irShareUrl').value = url;
    const lines = [
      `【${article.title}】`,
      article.subtitle ? article.subtitle : '',
      '',
      `📊 数据时点：${article.updateDate || '—'}`,
      `📚 来源：${article.sourceSummary || '—'}`,
      `🏷️ 标签：${article.tags || '—'}`,
      '',
      `🔗 ${url}`
    ];
    $('#irShareSummary').value = lines.filter(Boolean).join('\n').trim();
    $('#irShareTip').textContent = '';
    modal.hidden = false;
    modal.style.display = 'flex';
    setTimeout(() => $('#irShareUrl').select(), 50);
  }
  function closeShareModal() {
    const modal = $('#irShareModal');
    if (!modal) return;
    modal.hidden = true;
    modal.style.display = 'none';
  }
  function showShareTip(msg) {
    const tip = $('#irShareTip');
    if (!tip) return;
    tip.textContent = msg;
    setTimeout(() => { if (tip.textContent === msg) tip.textContent = ''; }, 2200);
  }
  async function copyShareUrl() {
    const url = $('#irShareUrl').value || '';
    if (!url) return showShareTip('❌ 链接为空');
    const ok = await copyText(url);
    showShareTip(ok ? '✅ 链接已复制' : '❌ 复制失败，请手动选中');
  }
  async function copyShareSummary() {
    const text = $('#irShareSummary').value || '';
    if (!text) return showShareTip('❌ 摘要为空');
    const ok = await copyText(text);
    showShareTip(ok ? '✅ 摘要已复制' : '❌ 复制失败，请手动选中');
  }
  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch (_) { /* fall through */ }
    // 兜底：textarea + execCommand
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (_) { ok = false; }
    document.body.removeChild(ta);
    return ok;
  }
})();