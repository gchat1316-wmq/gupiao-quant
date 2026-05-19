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

  let flashIndex = 0;
  let flashCards = [];
  let nodeDataCache = null;
  let cidCache = null;

  async function load() {
    const { cid, nid } = getParams();
    if (!nid) {
      document.getElementById('nodeTitle').textContent = '缺少参数';
      return;
    }
    cidCache = cid;
    try {
      const resp = await fetch('api/study/nodes/' + nid);
      if (!resp.ok) throw new Error('加载失败 ' + resp.status);
      nodeDataCache = await resp.json();
      render(nodeDataCache, cid);
    } catch (e) {
      document.getElementById('nodeTitle').textContent = e.message || '加载失败';
    }
  }

  function render(data, cid) {
    document.getElementById('crumb').innerHTML =
      '<b>' + esc(data.courseTitle || '') + '</b> · ' + esc(data.node.title);
    document.getElementById('nodeTitle').textContent = data.node.title;

    const overview = (data.aiDetailCards.find(c => c.title === '内容概述') || {}).body
                  || data.node.summary || '';
    document.getElementById('overview').innerHTML = nl2br(overview);

    renderAi(data.aiDetailCards.filter(c => c.title !== '内容概述'));
    flashCards = data.flashCards || [];
    document.getElementById('flashTotal').textContent =
      flashCards.length ? ('共' + flashCards.length + '张') : '';
    renderFlash();

    bindTabs();
    document.getElementById('quizBtn').addEventListener('click', function () {
      window.location.href = 'quiz.html?cid=' + cid + '&nid=' + data.node.id;
    });
  }

  function renderAi(cards) {
    const el = document.getElementById('aiPane');
    if (!cards || cards.length === 0) {
      el.innerHTML = '<div style="color:#9ca3af;padding:24px;">暂无内容</div>';
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
    el.innerHTML =
      parts +
      '<div class="next-step-bar"><button class="next-step-btn" onclick="document.getElementById(\'quizBtn\').click()">下一步 ›</button></div>' +
      '<div class="card-section" style="margin-top:24px;">' +
        '<h4>思考一下</h4>' +
        '<div>问题:如果输入的经验数据中包含大量被错误标记的"坏瓜",模型会发生什么变化?</div>' +
        '<div style="margin-top:6px;color:#92400e;">提示:思考算法在寻找"最佳边界"时,是否会被错误数据点拉偏,从而导致正常瓜被误判。</div>' +
      '</div>';
  }

  function renderFlash() {
    const el = document.getElementById('flashPane');
    if (!flashCards.length) {
      el.innerHTML =
        '<div style="color:#9ca3af;padding:24px;text-align:center;">' +
          '暂无闪卡<br/><br/>' +
          '<button class="btn-primary-dark" id="genFlashBtn">🎨 生成知识闪卡</button>' +
        '</div>';
      var genBtn = document.getElementById('genFlashBtn');
      if (genBtn) {
        genBtn.addEventListener('click', function () {
          doGenerateCards();
        });
      }
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

  async function doGenerateCards() {
    if (!nodeDataCache) return;
    const nid = nodeDataCache.node.id;
    var genBtn = document.getElementById('genFlashBtn');
    if (genBtn) {
      genBtn.textContent = '生成中,请稍候...';
      genBtn.disabled = true;
    }
    try {
      var resp = await fetch('api/study/nodes/' + nid + '/generate-card', { method: 'POST' });
      if (!resp.ok) {
        var text = await resp.text();
        throw new Error('生成失败: ' + text);
      }
      var fresh = await resp.json();
      nodeDataCache = fresh;
      flashCards = fresh.flashCards || [];
      document.getElementById('flashTotal').textContent =
        flashCards.length ? ('共' + flashCards.length + '张') : '';
      renderFlash();
    } catch (e) {
      alert(e.message || '卡片生成失败');
      if (genBtn) {
        genBtn.textContent = '🎨 生成知识闪卡';
        genBtn.disabled = false;
      }
    }
  }

  window.addEventListener('DOMContentLoaded', load);
})();
