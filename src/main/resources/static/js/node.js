(function () {
  'use strict';

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function nl2br(s) { return esc(s).replace(/\n/g, '<br/>'); }

  function getParams() {
    const url = new URL(window.location.href);
    return { cid: url.searchParams.get('cid'), nid: url.searchParams.get('nid') };
  }

  let currentCid = null;
  let currentNid = null;
  let flashIndex = 0;
  let flashCards = [];
  let nodeDataCache = null;

  async function load() {
    const { cid, nid } = getParams();
    if (!cid || !nid) {
      document.getElementById('nodeTitle').textContent = '缺少参数';
      return;
    }
    currentCid = cid;
    currentNid = nid;
    try {
      const [nodeResp, courseResp] = await Promise.all([
        fetch('/api/study/nodes/' + nid),
        fetch('/api/study/courses/' + cid)
      ]);
      if (!nodeResp.ok) throw new Error('节点加载失败 ' + nodeResp.status);
      if (!courseResp.ok) throw new Error('课程加载失败 ' + courseResp.status);
      const nodeData = await nodeResp.json();
      const courseData = await courseResp.json();
      nodeDataCache = nodeData;
      render(nodeData, courseData);
    } catch (e) {
      document.getElementById('nodeTitle').textContent = e.message || '加载失败';
    }
  }

  function render(nodeData, courseData) {
    const n = nodeData.node;
    document.getElementById('crumb').innerHTML =
      '<b>' + esc(nodeData.courseTitle || '') + '</b> · ' + esc(n.title);
    document.getElementById('breadcrumb').textContent = '知识点';
    document.getElementById('nodeTitle').textContent = n.title;
    document.getElementById('summary').textContent = n.summary || '暂无概述';
    document.getElementById('definition').textContent = n.definition || '暂无定义';

    // 知识卡片区域
    const hasCards = (nodeData.aiDetailCards && nodeData.aiDetailCards.length > 0)
                  || (nodeData.flashCards && nodeData.flashCards.length > 0);
    const cardArea = document.getElementById('cardArea');
    const noCardHint = document.getElementById('noCardHint');

    if (hasCards) {
      noCardHint.style.display = 'none';
      const overview = (nodeData.aiDetailCards.find(c => c.title === '内容概述') || {}).body
                    || n.summary || '';
      document.getElementById('overview').innerHTML = nl2br(overview);
      renderAi(nodeData.aiDetailCards.filter(c => c.title !== '内容概述'));
      flashCards = nodeData.flashCards || [];
      document.getElementById('flashTotal').textContent =
        flashCards.length ? ('共' + flashCards.length + '张') : '';
      renderFlash();
      bindTabs();
    } else {
      // 无卡片: 隐藏 tab 内容, 显示生成按钮
      cardArea.querySelectorAll('.card-tabs, .card-pane').forEach(function (el) { el.style.display = 'none'; });
      noCardHint.style.display = '';
      document.getElementById('overview').innerHTML = '';
      document.getElementById('genCardBtn').addEventListener('click', function () {
        doGenerate(n.id, courseData.course.id);
      });
    }

    document.getElementById('quizBtn').addEventListener('click', function () {
      window.location.href = '/quiz.html?cid=' + courseData.course.id + '&nid=' + n.id;
    });

    window.renderMindmap(document.getElementById('mindmap'), courseData.tree, {
      currentId: n.id,
      onClick: function (clicked) {
        if (clicked.id === n.id) return;
        window.location.href = '/node.html?cid=' + courseData.course.id + '&nid=' + clicked.id;
      }
    });
  }

  function renderAi(cards) {
    const el = document.getElementById('aiPane');
    if (!cards || cards.length === 0) {
      el.innerHTML = '<div style="color:#9ca3af;padding:24px;">暂无详解内容</div>';
      return;
    }
    const parts = cards.map(function (c) {
      if (c.stage) {
        return (
          '<div class="stage-block">' +
            '<div class="stage-tag">' + esc(c.stage) + '</div>' +
            '<div class="stage-title">' + esc(c.title || '') + '</div>' +
            '<div class="stage-body">' + nl2br(c.body || '') + '</div>' +
          '</div>'
        );
      }
      const title = c.title ? '<h4>' + esc(c.title) + '</h4>' : '';
      return '<div class="card-section">' + title + nl2br(c.body || '') + '</div>';
    }).join('');
    el.innerHTML = parts;
  }

  function renderFlash() {
    const el = document.getElementById('flashPane');
    if (!flashCards.length) {
      el.innerHTML = '<div style="color:#9ca3af;padding:24px;text-align:center;">暂无闪卡</div>';
      return;
    }
    const c = flashCards[flashIndex];
    el.innerHTML =
      '<div class="flash-stage">' +
        (c.imageUrl ? '<img class="flash-card-img" src="' + esc(c.imageUrl) + '" alt="" />'
                    : '<div class="flash-card-img" style="height:300px;background:#f3f4f6;display:flex;align-items:center;justify-content:center;">' + esc(c.title || '') + '</div>') +
      '</div>' +
      '<div class="flash-card-body"><b>' + esc(c.title || '') + '</b><br/>' + esc(c.body || '') + '</div>' +
      '<div class="flash-nav">' +
        '<button id="flashPrev" ' + (flashIndex === 0 ? 'disabled' : '') + '>‹</button>' +
        '<span>' + (flashIndex + 1) + ' / ' + flashCards.length + '</span>' +
        '<button id="flashNext" ' + (flashIndex >= flashCards.length - 1 ? 'disabled' : '') + '>›</button>' +
      '</div>';
    document.getElementById('flashPrev').addEventListener('click', function () {
      if (flashIndex > 0) { flashIndex--; renderFlash(); }
    });
    document.getElementById('flashNext').addEventListener('click', function () {
      if (flashIndex < flashCards.length - 1) { flashIndex++; renderFlash(); }
    });
  }

  function bindTabs() {
    document.querySelectorAll('.card-tab').forEach(function (t) {
      t.addEventListener('click', function () {
        document.querySelectorAll('.card-tab').forEach(b => b.classList.toggle('active', b === t));
        const which = t.getAttribute('data-tab');
        document.getElementById('aiPane').style.display = which === 'ai' ? '' : 'none';
        document.getElementById('flashPane').style.display = which === 'flash' ? '' : 'none';
      });
    });
  }

  async function doGenerate(nid, cid) {
    const genBtn = document.getElementById('genCardBtn');
    genBtn.textContent = '生成中...';
    genBtn.style.pointerEvents = 'none';
    try {
      const resp = await fetch('/api/study/nodes/' + nid + '/generate-card', {
        method: 'POST'
      });
      if (!resp.ok) {
        const text = await resp.text();
        throw new Error('生成失败: ' + text);
      }
      // 刷新页面以显示生成的卡片
      window.location.reload();
    } catch (e) {
      alert(e.message || '卡片生成失败');
      genBtn.textContent = '生成卡片';
      genBtn.style.pointerEvents = '';
    }
  }

  window.addEventListener('DOMContentLoaded', load);
})();
