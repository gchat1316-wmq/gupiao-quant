(function () {
  'use strict';

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function nl2br(s) { return esc(s).replace(/\n/g, '<br/>'); }

  function getId() {
    const url = new URL(window.location.href);
    return url.searchParams.get('id');
  }

  let mindmapApi = null;
  let flatList = [];   // DFS pre-order [{ node, path:[...titles] }]
  let studyIdx = 0;
  let courseData = null;

  async function load() {
    const id = getId();
    if (!id) {
      document.getElementById('courseTitle').textContent = '缺少课程 id';
      return;
    }
    try {
      const resp = await fetch('/api/study/courses/' + id);
      if (!resp.ok) throw new Error('加载失败 ' + resp.status);
      courseData = await resp.json();
      render(courseData);
    } catch (e) {
      document.getElementById('courseTitle').textContent = e.message || '加载失败';
    }
  }

  function collectChildrenTitles(tree) {
    const items = [];
    (tree || []).forEach(function (root) {
      (root.children || []).forEach(function (c) {
        items.push(c.title);
      });
    });
    return items;
  }

  function flattenDfs(tree) {
    const out = [];
    function walk(node, path) {
      const newPath = path.concat([node.title]);
      out.push({ node: node, path: newPath });
      (node.children || []).forEach(function (c) { walk(c, newPath); });
    }
    (tree || []).forEach(function (r) { walk(r, []); });
    return out;
  }

  function render(data) {
    const c = data.course;
    const rootNode = (data.tree && data.tree[0]) || null;

    document.getElementById('crumb').innerHTML =
      '<b>' + esc(c.title) + '</b> · 课程详情';
    document.getElementById('courseTitle').textContent =
      (rootNode && rootNode.title) ? rootNode.title : c.title;

    const summary = (rootNode && rootNode.summary) || c.summary || '本课程暂无简介。';
    const definition = rootNode && rootNode.definition;
    const chapterTitles = collectChildrenTitles(data.tree);

    let html =
      '<div style="font-weight:700;margin-bottom:8px;">本节概述</div>' +
      '<p>' + nl2br(summary) + '</p>';
    if (definition && definition.trim()) {
      html +=
        '<div style="font-weight:700;margin:14px 0 6px;">核心定义</div>' +
        '<p style="color:#1b5e20;font-weight:600;">' + nl2br(definition) + '</p>';
    }
    if (chapterTitles.length) {
      html +=
        '<div style="font-weight:700;margin:16px 0 8px;">你将学到</div>' +
        '<ul style="padding-left:18px;line-height:1.8;color:#374151;">' +
          chapterTitles.map(function (t) { return '<li>' + esc(t) + '</li>'; }).join('') +
        '</ul>';
    }
    document.getElementById('courseIntro').innerHTML = html;

    const mats = data.materials || [];
    document.getElementById('materialsBox').innerHTML =
      mats.length === 0
        ? '<div style="color:#9ca3af;">暂无资料</div>'
        : mats.map(function (m) {
            return '<div style="padding:8px 0;border-bottom:1px solid #f1f5f9;">📄 ' + esc(m.fileName) +
              ' <span style="color:#9ca3af;font-size:12px;">(' + esc(m.fileType || '') + ')</span></div>';
          }).join('');

    mindmapApi = window.renderMindmap(document.getElementById('mindmap'), data.tree, {
      onClick: function (node) {
        window.location.href = '/node.html?cid=' + c.id + '&nid=' + node.id;
      }
    });

    flatList = flattenDfs(data.tree);
    bindToolbar();
    bindRightToggle();
    bindStudyMode();
  }

  function bindToolbar() {
    const inEl = document.getElementById('mmZoomIn');
    const outEl = document.getElementById('mmZoomOut');
    const fitEl = document.getElementById('mmFit');
    if (inEl) inEl.onclick = function () { if (mindmapApi) mindmapApi.zoomIn(); };
    if (outEl) outEl.onclick = function () { if (mindmapApi) mindmapApi.zoomOut(); };
    if (fitEl) fitEl.onclick = function () { if (mindmapApi) mindmapApi.fit(); };
  }

  function bindRightToggle() {
    const page = document.getElementById('coursePage');
    const btn = document.getElementById('rightToggle');
    if (!btn) return;
    btn.addEventListener('click', function () {
      const collapsed = page.classList.toggle('right-collapsed');
      btn.textContent = collapsed ? '«' : '»';
      btn.title = collapsed ? '展开' : '折叠';
    });
  }

  function bindStudyMode() {
    const enterBtn = document.getElementById('enterStudyBtn');
    const exitBtn = document.getElementById('studyExitBtn');
    const prevBtn = document.getElementById('studyPrev');
    const nextBtn = document.getElementById('studyNext');
    const introView = document.getElementById('introView');
    const studyView = document.getElementById('studyView');

    enterBtn.addEventListener('click', function () {
      if (!flatList.length) return;
      studyIdx = 0;
      introView.classList.add('hidden');
      studyView.classList.remove('hidden');
      renderStudyItem();
    });

    exitBtn.addEventListener('click', function () {
      studyView.classList.add('hidden');
      introView.classList.remove('hidden');
    });

    prevBtn.addEventListener('click', function () {
      if (studyIdx > 0) { studyIdx--; renderStudyItem(); }
    });

    nextBtn.addEventListener('click', function () {
      if (studyIdx < flatList.length - 1) { studyIdx++; renderStudyItem(); }
      else {
        // 最后一个,询问是否进入卡片
        const item = flatList[studyIdx];
        if (item && item.node && courseData) {
          if (confirm('已完成全部知识点浏览,是否进入"' + item.node.title + '"的知识卡片?')) {
            window.location.href = '/node.html?cid=' + courseData.course.id + '&nid=' + item.node.id;
          }
        }
      }
    });
  }

  function renderStudyItem() {
    const item = flatList[studyIdx];
    if (!item) return;
    const n = item.node;

    document.getElementById('studyIdx').textContent =
      (studyIdx + 1) + ' / ' + flatList.length;

    const crumb = item.path.slice(0, -1).join(' › ');
    document.getElementById('studyCrumb').textContent = crumb || '总览';
    document.getElementById('studyTitle').textContent = n.title;

    let html = '';
    if (n.summary && n.summary.trim()) {
      html +=
        '<div class="study-section-title">内容概述</div>' +
        '<div class="study-paragraph">' + nl2br(n.summary) + '</div>';
    }
    if (n.definition && n.definition.trim()) {
      html +=
        '<div class="study-section-title">核心定义</div>' +
        '<div class="study-paragraph study-paragraph-emph">' + nl2br(n.definition) + '</div>';
    }
    if (n.children && n.children.length) {
      html +=
        '<div class="study-section-title">本节包含</div>' +
        '<ul class="study-children">' +
          n.children.map(function (c) {
            return '<li><b>' + esc(c.title) + '</b>' +
              (c.summary ? '<span class="study-child-sum"> — ' + esc(c.summary) + '</span>' : '') +
            '</li>';
          }).join('') +
        '</ul>';
    }
    if (!html) {
      html = '<div class="study-paragraph" style="color:#9ca3af;">该节点暂无内容。</div>';
    }

    // 同时附上"进入知识卡片"的快捷入口 (叶子节点更有用)
    if (courseData && (!n.children || n.children.length === 0)) {
      html += '<div style="margin-top:16px;">' +
        '<a class="entry-pill" href="/node.html?cid=' + courseData.course.id + '&nid=' + n.id + '">进入此知识点卡片 ›</a>' +
      '</div>';
    }

    document.getElementById('studyContent').innerHTML = html;

    document.getElementById('studyPrev').disabled = (studyIdx === 0);
    const nextBtn = document.getElementById('studyNext');
    nextBtn.textContent = (studyIdx === flatList.length - 1) ? '完成 ›' : '下一个 ›';
  }

  window.addEventListener('DOMContentLoaded', load);
})();
