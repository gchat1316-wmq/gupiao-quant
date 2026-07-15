/**
 * admin-xiebo-recent.js
 * 暴露 window.initXieboRecentPanel() 由 admin-users.html 第一次进 tab 时调用。
 * 单行表单 upsert: 填股票代码 + 名称 + 分类 + 笔记,点保存一次性 upsert。
 * 已存在则自动改为 PUT 更新,不会 400。
 */
(function () {
  'use strict';

  var API_LIST = '/api/xiebo/recent';
  var API_ADMIN = '/api/admin/xiebo/recent';
  var API_ADMIN_NOTE = function (code) { return API_ADMIN + '/' + encodeURIComponent(code) + '/note'; };

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
      setTimeout(function () { if (el.textContent === msg) { el.textContent = ''; } }, 4000);
    }
  }

  // 解析服务端响应 body,尝试把 {"code":..,"message":".."} 的 message 抽出来
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
      stockCode: $('xieboRecentStockCode').value.trim(),
      stockName: $('xieboRecentStockName').value.trim(),
      type: $('xieboRecentStockType').value,
      noteHtml: $('xieboRecentNoteHtml').value
    };
  }

  function setFormData(d) {
    $('xieboRecentStockCode').value = d.stockCode || '';
    $('xieboRecentStockCode').disabled = !!d.locked;
    $('xieboRecentStockName').value = d.stockName || '';
    $('xieboRecentStockType').value = d.type || '科技AI';
    $('xieboRecentNoteHtml').value = d.noteHtml || '';
  }

  function clearForm() {
    setFormData({ locked: false });
    $('xieboRecentStockCode').focus();
  }

  // 把股票 + 笔记一起保存;股票已存在则改 PUT 更新
  async function upsertAll() {
    var data = getFormData();
    if (!data.stockCode) { setStatus('请填写股票代码', false); return; }
    if (!data.stockName) { setStatus('请填写股票名称', false); return; }

    setStatus('保存中…', true);

    // 1. 先 GET 看股票是否存在
    var exists = false;
    try {
      var probe = await fetch(API_LIST, { headers: authHeaders() });
      if (probe.ok) {
        var rows = await probe.json();
        exists = rows.some(function (r) { return r.stockCode === data.stockCode; });
      }
    } catch (e) { /* ignore */ }

    // 2. POST 或 PUT 股票
    var stockResp;
    try {
      if (exists) {
        stockResp = await fetch(API_ADMIN + '/' + encodeURIComponent(data.stockCode), {
          method: 'PUT', headers: authHeaders(),
          body: JSON.stringify({ stockCode: data.stockCode, stockName: data.stockName, type: data.type })
        });
      } else {
        stockResp = await fetch(API_ADMIN, {
          method: 'POST', headers: authHeaders(),
          body: JSON.stringify({ stockCode: data.stockCode, stockName: data.stockName, type: data.type })
        });
      }
    } catch (e) {
      setStatus('网络错误: ' + e.message, false);
      return;
    }

    if (!stockResp.ok) {
      var errText = await stockResp.text();
      setStatus('股票保存失败: ' + parseErr(errText), false);
      return;
    }

    // 3. 保存笔记(无论是否为空,都 PUT 一遍确保 DB 反映当前 textarea 内容)
    try {
      var noteResp = await fetch(API_ADMIN_NOTE(data.stockCode), {
        method: 'PUT', headers: authHeaders(),
        body: JSON.stringify({ noteHtml: data.noteHtml || '' })
      });
      if (!noteResp.ok) {
        var noteErr = await noteResp.text();
        setStatus('股票已存,但笔记保存失败: ' + parseErr(noteErr), false);
        return;
      }
    } catch (e) {
      setStatus('股票已存,但笔记保存失败: ' + e.message, false);
      return;
    }

    setStatus('已保存 ' + data.stockCode, true);
    $('xieboRecentStockCode').disabled = true;  // 已存在,锁定代码
    await loadList();
  }

  async function loadList() {
    var tbody = $('adminXieboRecentTableBody');
    if (!tbody) return;
    try {
      var r = await fetch(API_LIST, { headers: authHeaders() });
      if (!r.ok) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px">加载失败</td></tr>';
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

  // 把列表行加载进表单
  async function loadIntoForm(code) {
    try {
      // 股票基本信息
      var list = await (await fetch(API_LIST, { headers: authHeaders() })).json();
      var row = (list || []).find(function (r) { return r.stockCode === code; });
      // 笔记
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
      $('xieboRecentNoteHtml').focus();
    } catch (e) {
      setStatus('载入失败: ' + e.message, false);
    }
  }

  async function deleteStock(code) {
    if (!confirm('确认删除 ' + code + ' ?\n关联的笔记会一并级联删除。')) return;
    try {
      var r = await fetch(API_ADMIN + '/' + encodeURIComponent(code), {
        method: 'DELETE', headers: authHeaders()
      });
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

  function bindHandlers() {
    var saveBtn = $('xieboRecentSaveBtn');
    if (saveBtn) saveBtn.addEventListener('click', upsertAll);

    var clearBtn = $('xieboRecentClearBtn');
    if (clearBtn) clearBtn.addEventListener('click', clearForm);

    var tbody = $('adminXieboRecentTableBody');
    if (tbody) {
      tbody.addEventListener('click', function (e) {
        var btn = e.target.closest('button[data-act]');
        if (!btn) return;
        var tr = btn.closest('tr');
        if (!tr) return;
        var code = tr.dataset.code;
        if (btn.dataset.act === 'load') loadIntoForm(code);
        else if (btn.dataset.act === 'delete') deleteStock(code);
      });
    }
  }

  window.initXieboRecentPanel = function () {
    if (window._xieboRecentLoaded) return;
    window._xieboRecentLoaded = true;
    bindHandlers();
    loadList();
  };
})();