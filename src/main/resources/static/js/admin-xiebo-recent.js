/**
 * admin-xiebo-recent.js
 *
 * 设计要点(避免之前"点击保存没反应"的坑):
 * 1. 立即在 DOMContentLoaded 时就绑定事件,不等侧边栏点击
 * 2. 同时在 document.body 上做 click 事件代理 — 即使面板不在当前活动状态,
 *    save 按钮的点击也能被捕获
 * 3. 全程 try/catch,任何错误显示在 status span 而非静默
 */
(function () {
  'use strict';

  var API_LIST = '/api/xiebo/recent';
  var API_ADMIN = '/api/admin/xiebo/recent';
  function API_ADMIN_STOCK(code) {
    return code ? API_ADMIN + '/' + encodeURIComponent(code) : API_ADMIN;
  }
  function API_ADMIN_NOTE(code) { return API_ADMIN + '/' + encodeURIComponent(code) + '/note'; }

  function $(id) { return document.getElementById(id); }

  function authHeaders() {
    var t = localStorage.getItem('gp_auth_token');
    var h = { 'Content-Type': 'application/json' };
    if (t) h['Authorization'] = 'Bearer ' + t;
    return h;
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function setStatus(msg, ok) {
    var el = $('xieboRecentStatus');
    if (!el) return;
    el.textContent = msg || '';
    el.style.color = ok ? '#16a34a' : '#dc2626';
    if (msg) {
      setTimeout(function () { if (el.textContent === msg) el.textContent = ''; }, 5000);
    }
  }

  function parseErr(text) {
    if (!text) return '未知错误';
    try {
      var j = JSON.parse(text);
      if (j && j.message) return j.message;
      if (j && j.errorMessage) return j.errorMessage;
      if (j && j.error) return j.error;
    } catch (e) { /* not JSON */ }
    return text.length > 200 ? text.slice(0, 200) + '…' : text;
  }

  function getFormData() {
    return {
      stockCode: ($('xieboRecentStockCode') || {}).value ? $('xieboRecentStockCode').value.trim() : '',
      stockName: ($('xieboRecentStockName') || {}).value ? $('xieboRecentStockName').value.trim() : '',
      type: ($('xieboRecentStockType') || {}).value || '科技AI',
      noteHtml: ($('xieboRecentNoteHtml') || {}).value || ''
    };
  }

  function setFormData(d) {
    var codeEl = $('xieboRecentStockCode');
    var nameEl = $('xieboRecentStockName');
    var typeEl = $('xieboRecentStockType');
    var noteEl = $('xieboRecentNoteHtml');
    if (codeEl) { codeEl.value = d.stockCode || ''; codeEl.disabled = !!d.locked; }
    if (nameEl) { nameEl.value = d.stockName || ''; }
    if (typeEl) { typeEl.value = d.type || '科技AI'; }
    if (noteEl) { noteEl.value = d.noteHtml || ''; }
  }

  function clearForm() {
    setFormData({ locked: false });
    var el = $('xieboRecentStockCode');
    if (el) el.focus();
  }

  async function upsertAll() {
    var data = getFormData();
    if (!data.stockCode) { setStatus('请填写股票代码', false); return; }
    if (!data.stockName) { setStatus('请填写股票名称', false); return; }

    setStatus('保存中…', true);

    // 检查股票是否已存在
    var exists = false;
    try {
      var probe = await fetch(API_LIST, { headers: authHeaders() });
      if (probe.ok) {
        var rows = await probe.json();
        exists = (rows || []).some(function (r) { return r.stockCode === data.stockCode; });
      }
    } catch (e) { /* ignore — fall through to POST, will surface DUPLICATE */ }

    // POST 或 PUT 股票
    var stockResp;
    try {
      var stockUrl = API_ADMIN_STOCK(exists ? data.stockCode : null);
      stockResp = await fetch(stockUrl, {
        method: exists ? 'PUT' : 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          stockCode: data.stockCode,
          stockName: data.stockName,
          type: data.type
        })
      });
    } catch (e) {
      setStatus('网络错误: ' + e.message, false);
      return;
    }

    if (!stockResp.ok) {
      var errText = await stockResp.text();
      // DUPLICATE race condition: GET 没看到但 POST 撞上 — 改 PUT 重试一次
      try {
        var j = JSON.parse(errText);
        if (j && j.errorCode === 'DUPLICATE') {
          stockResp = await fetch(API_ADMIN_STOCK(data.stockCode), {
            method: 'PUT', headers: authHeaders(),
            body: JSON.stringify({ stockCode: data.stockCode, stockName: data.stockName, type: data.type })
          });
          if (!stockResp.ok) {
            setStatus('股票保存失败: ' + parseErr(await stockResp.text()), false);
            return;
          }
        } else {
          setStatus('股票保存失败: ' + parseErr(errText), false);
          return;
        }
      } catch (e) {
        setStatus('股票保存失败: ' + parseErr(errText), false);
        return;
      }
    }

    // 保存笔记
    try {
      var noteResp = await fetch(API_ADMIN_NOTE(data.stockCode), {
        method: 'PUT', headers: authHeaders(),
        body: JSON.stringify({ noteHtml: data.noteHtml || '' })
      });
      if (!noteResp.ok) {
        setStatus('股票已存,但笔记保存失败: ' + parseErr(await noteResp.text()), false);
        return;
      }
    } catch (e) {
      setStatus('股票已存,但笔记保存失败: ' + e.message, false);
      return;
    }

    setStatus('已保存 ' + data.stockCode, true);
    var codeEl = $('xieboRecentStockCode');
    if (codeEl) codeEl.disabled = true;
    await loadList();
  }

  async function loadList() {
    var tbody = $('adminXieboRecentTableBody');
    if (!tbody) return;
    try {
      var r = await fetch(API_LIST, { headers: authHeaders() });
      if (!r.ok) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px">加载失败 (' + r.status + ')</td></tr>';
        return;
      }
      var rows = await r.json();
      if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px">暂无数据 — 在上方表单填股票代码即可新增</td></tr>';
        return;
      }
      tbody.innerHTML = rows.map(function (w) {
        return '<tr data-code="' + esc(w.stockCode) + '">'
          + '<td><code>' + esc(w.stockCode) + '</code></td>'
          + '<td>' + esc(w.stockName || '') + '</td>'
          + '<td>' + esc(w.type || '') + '</td>'
          + '<td>' + (w.createdAt ? new Date(w.createdAt).toLocaleString() : '') + '</td>'
          + '<td>'
            + '<button type="button" class="btn-sm" data-act="load">编辑</button> '
            + '<button type="button" class="btn-sm" data-act="delete" style="color:#ef4444">删除</button>'
          + '</td>'
          + '</tr>';
      }).join('');
    } catch (e) {
      tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:#dc2626;padding:20px">加载异常: ' + esc(e.message) + '</td></tr>';
    }
  }

  async function loadIntoForm(code) {
    try {
      var list = await (await fetch(API_LIST, { headers: authHeaders() })).json();
      var row = (list || []).find(function (r) { return r.stockCode === code; });
      var noteHtml = '';
      try {
        var nr = await fetch('/api/xiebo/recent/' + encodeURIComponent(code) + '/note');
        if (nr.ok) {
          var nd = await nr.json();
          noteHtml = (nd && nd.noteHtml) || '';
        }
      } catch (e) { /* ignore */ }

      setFormData({
        stockCode: code,
        stockName: row ? row.stockName : '',
        type: row ? row.type : '科技AI',
        noteHtml: noteHtml,
        locked: true
      });
      setStatus('已载入 ' + code + ' — 修改后点保存', true);
      var noteEl = $('xieboRecentNoteHtml');
      if (noteEl) noteEl.focus();
    } catch (e) {
      setStatus('载入失败: ' + e.message, false);
    }
  }

  async function deleteStock(code) {
    if (!confirm('确认删除 ' + code + ' ?\n关联的笔记会一并级联删除。')) return;
    try {
      var r = await fetch(API_ADMIN_STOCK(code), { method: 'DELETE', headers: authHeaders() });
      if (!r.ok) {
        setStatus('删除失败: ' + parseErr(await r.text()), false);
        return;
      }
      setStatus('已删除 ' + code, true);
      clearForm();
      await loadList();
    } catch (e) {
      setStatus('删除失败: ' + e.message, false);
    }
  }

  // 事件绑定 — 同时支持直接绑定和 document 级代理
  function bindDirectHandlers() {
    var saveBtn = $('xieboRecentSaveBtn');
    if (saveBtn && !saveBtn._xieboBound) {
      saveBtn.addEventListener('click', function (e) { e.preventDefault(); upsertAll(); });
      saveBtn._xieboBound = true;
    }

    var clearBtn = $('xieboRecentClearBtn');
    if (clearBtn && !clearBtn._xieboBound) {
      clearBtn.addEventListener('click', function (e) { e.preventDefault(); clearForm(); });
      clearBtn._xieboBound = true;
    }

    var tbody = $('adminXieboRecentTableBody');
    if (tbody && !tbody._xieboBound) {
      tbody.addEventListener('click', function (e) {
        var btn = e.target.closest('button[data-act]');
        if (!btn) return;
        var tr = btn.closest('tr');
        if (!tr) return;
        var code = tr.dataset.code;
        if (btn.dataset.act === 'load') loadIntoForm(code);
        else if (btn.dataset.act === 'delete') deleteStock(code);
      });
      tbody._xieboBound = true;
    }
  }

  // document 级代理 — 即使直接绑定失败,点击也能兜底触发
  function bindDocumentDelegation() {
    if (document._xieboDelegated) return;
    document._xieboDelegated = true;

    document.addEventListener('click', function (e) {
      // 找到点击的元素或其祖先中带特定 data-act / id 的按钮
      var target = e.target;

      // 保存按钮
      var saveBtn = target.closest && target.closest('#xieboRecentSaveBtn');
      if (saveBtn) { e.preventDefault(); upsertAll(); return; }

      // 清空按钮
      var clearBtn = target.closest && target.closest('#xieboRecentClearBtn');
      if (clearBtn) { e.preventDefault(); clearForm(); return; }

      // 表格行的编辑/删除
      var rowBtn = target.closest && target.closest('button[data-act]');
      if (rowBtn) {
        var tr = rowBtn.closest('tr');
        if (!tr) return;
        var code = tr.dataset.code;
        if (rowBtn.dataset.act === 'load') loadIntoForm(code);
        else if (rowBtn.dataset.act === 'delete') deleteStock(code);
      }
    });
  }

  // 兼容旧调用(被 admin-users.html 的 sidebar 点击调用)
  window.initXieboRecentPanel = function () {
    if (window._xieboRecentLoaded) return;
    window._xieboRecentLoaded = true;
    try {
      bindDirectHandlers();
    } catch (e) {
      setStatus('初始化失败: ' + e.message, false);
    }
    loadList();
  };

  // 立即初始化 — 不等 sidebar 点击
  function initImmediately() {
    if (window._xieboRecentLoaded) return;
    if (!$('xieboRecentSaveBtn')) return;  // 页面没有这个表单,跳过
    window._xieboRecentLoaded = true;
    bindDocumentDelegation();
    bindDirectHandlers();
    loadList();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initImmediately);
  } else {
    initImmediately();
  }
})();