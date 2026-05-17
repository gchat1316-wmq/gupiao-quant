(function () {
  'use strict';

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function getParams() {
    const url = new URL(window.location.href);
    return { cid: url.searchParams.get('cid'), nid: url.searchParams.get('nid') };
  }

  let quizzes = [];
  let idx = 0;
  let picked = null;
  let answered = false;
  let lastAnswer = null;

  async function load() {
    const { nid } = getParams();
    if (!nid) {
      document.getElementById('progress').textContent = '缺少参数';
      return;
    }
    try {
      const resp = await fetch('/api/study/nodes/' + nid + '/quizzes');
      if (!resp.ok) throw new Error('加载失败 ' + resp.status);
      quizzes = await resp.json();
      if (!quizzes.length) {
        document.getElementById('progress').textContent = '该知识点暂无测验';
        return;
      }
      render();
    } catch (e) {
      document.getElementById('progress').textContent = e.message || '加载失败';
    }
  }

  function render() {
    const q = quizzes[idx];
    picked = null;
    answered = false;
    lastAnswer = null;
    document.getElementById('crumb').innerHTML =
      '知识点: <b>' + esc(q.relatedNodeTitle || '') + '</b>';
    document.getElementById('progress').textContent = (idx + 1) + '/' + quizzes.length;
    document.getElementById('stem').textContent = q.stem;
    document.getElementById('related').innerHTML =
      '⊙ 1个相关知识点: <span class="chip">' + esc(q.relatedNodeTitle || '') + '</span>';

    document.getElementById('options').innerHTML = q.options.map(function (o) {
      return (
        '<div class="quiz-option" data-key="' + esc(o.key) + '">' +
          '<span class="key">' + esc(o.key) + '</span>' +
          '<span>' + esc(o.text) + '</span>' +
        '</div>'
      );
    }).join('');
    document.getElementById('feedback').innerHTML = '';

    const checkBtn = document.getElementById('checkBtn');
    checkBtn.disabled = true;
    checkBtn.textContent = '检查答案';

    document.querySelectorAll('.quiz-option').forEach(function (el) {
      el.addEventListener('click', function () {
        if (answered) return;
        picked = el.getAttribute('data-key');
        document.querySelectorAll('.quiz-option').forEach(function (e) {
          e.classList.toggle('selected', e === el);
        });
        checkBtn.disabled = false;
      });
    });

    checkBtn.onclick = function () {
      if (!answered) doCheck();
      else doNext();
    };
  }

  async function doCheck() {
    if (!picked) return;
    const q = quizzes[idx];
    const form = new URLSearchParams();
    form.append('picked', picked);
    try {
      const resp = await fetch('/api/study/quizzes/' + q.id + '/answer', {
        method: 'POST',
        body: form
      });
      if (!resp.ok) throw new Error('提交失败');
      const data = await resp.json();
      lastAnswer = data;
      answered = true;

      document.querySelectorAll('.quiz-option').forEach(function (el) {
        const k = el.getAttribute('data-key');
        el.classList.remove('selected');
        if (k === data.correctAnswer) {
          el.classList.add('correct');
          el.insertAdjacentHTML('beforeend', '<span class="tick">✓</span>');
        } else if (k === picked && !data.correct) {
          el.classList.add('wrong');
        }
      });

      const fb = document.getElementById('feedback');
      fb.innerHTML =
        '<div class="quiz-feedback ' + (data.correct ? '' : 'wrong') + '">' +
          '<strong>' + (data.correct ? '回答正确!' : '回答错误!') + '</strong> 正确答案: ' + esc(data.correctAnswer) + '<br/>' +
          '解析: ' + esc(data.analysis || '') +
        '</div>';

      const btn = document.getElementById('checkBtn');
      btn.textContent = idx < quizzes.length - 1 ? '下一题' : '完成';
    } catch (e) {
      alert(e.message || '提交失败');
    }
  }

  function doNext() {
    if (idx < quizzes.length - 1) {
      idx++;
      render();
    } else {
      alert('测验完成!');
      history.back();
    }
  }

  window.addEventListener('DOMContentLoaded', load);
})();
