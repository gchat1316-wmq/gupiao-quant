/**
 * 近期关注股票管理后台 - CRUD + wangEditor笔记编辑器
 * 暴露 window.initXieboRecentPanel() 由 admin-users.html 第一次进 tab 时调用。
 */
(function () {
  'use strict';

  var API_LIST = '/gp/api/xiebo/recent';
  var API_ADMIN = '/gp/api/admin/xiebo/recent';
  var API_UPLOAD = '/gp/api/admin/upload/note-image';

  var wangEditorInstance = null;
  var editingStockCode = null;
  var _loaded = false;

  function $(id) { return document.getElementById(id); }

  function authHeaders() {
    var t = localStorage.getItem('gp_auth_token');
    var h = { 'Content-Type': 'application/json' };
    if (t) h['Authorization'] = 'Bearer ' + t;
    return h;
  }

  function authHeadersNoContentType() {
    var t = localStorage.getItem('gp_auth_token');
    var h = {};
    if (t) h['Authorization'] = 'Bearer ' + t;
    return h;
  }

  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function loadList() {
    // Load admin list (shows all stocks regardless of public filter)
    return fetch(API_ADMIN, { headers: authHeaders() })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (rows) {
        renderTable(rows || []);
      })
      .catch(function (e) {
        console.error('load list failed', e);
        renderTable([]);
      });
  }

  function renderTable(rows) {
    var tbody = $('adminXieboRecentTableBody');
    if (!tbody) return;
    if (rows.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px">暂无数据</td></tr>';
      return;
    }
    tbody.innerHTML = rows.map(function (w) {
      return '<tr data-code="' + esc(w.stockCode) + '">'
        + '<td><code>' + esc(w.stockCode) + '</code></td>'
        + '<td>' + esc(w.stockName || '') + '</td>'
        + '<td>' + esc(w.type || '') + '</td>'
        + '<td>' + (w.createdAt ? new Date(w.createdAt).toLocaleString() : '') + '</td>'
        + '<td>'
          + '<button type="button" class="btn-sm" data-act="edit">编辑</button> '
          + '<button type="button" class="btn-sm" data-act="note">笔记</button> '
          + '<button type="button" class="btn-sm" data-act="delete" style="color:#ef4444">删除</button>'
        + '</td>'
        + '</tr>';
    }).join('');
  }

  function showStockModal(stock) {
    editingStockCode = stock ? stock.stockCode : null;
    $('xieboRecentModalTitle').textContent = stock ? '编辑股票' : '新增股票';
    $('xieboRecentStockCode').value = stock ? stock.stockCode : '';
    $('xieboRecentStockCode').disabled = !!stock;
    $('xieboRecentStockName').value = stock ? stock.stockName : '';
    $('xieboRecentStockType').value = stock ? stock.type : '科技AI';
    $('xieboRecentStockModal').hidden = false;
  }

  function hideStockModal() {
    $('xieboRecentStockModal').hidden = true;
    editingStockCode = null;
  }

  function saveStock() {
    var body = {
      stockCode: $('xieboRecentStockCode').value.trim(),
      stockName: $('xieboRecentStockName').value.trim(),
      type: $('xieboRecentStockType').value
    };
    if (!body.stockCode || !body.stockName) {
      alert('股票代码和股票名必填');
      return;
    }
    var url = editingStockCode
      ? API_ADMIN + '/' + encodeURIComponent(editingStockCode)
      : API_ADMIN;
    var method = editingStockCode ? 'PUT' : 'POST';
    fetch(url, { method: method, headers: authHeaders(), body: JSON.stringify(body) })
      .then(function (r) {
        if (!r.ok) throw new Error('保存失败: HTTP ' + r.status);
        return r.text();
      })
      .then(function () {
        hideStockModal();
        loadList();
      })
      .catch(function (e) {
        alert('保存失败: ' + e.message);
      });
  }

  function deleteStock(code) {
    if (!confirm('确认删除 ' + code + '？关联的笔记会一并删除。')) return;
    fetch(API_ADMIN + '/' + encodeURIComponent(code), {
      method: 'DELETE',
      headers: authHeaders()
    })
      .then(function (r) {
        if (!r.ok) throw new Error('删除失败: HTTP ' + r.status);
        return r.text();
      })
      .then(function () {
        loadList();
      })
      .catch(function (e) {
        alert('删除失败: ' + e.message);
      });
  }

  function showNoteModal(code) {
    editingStockCode = code;
    $('xieboRecentNoteModal').hidden = false;

    // Fetch existing note
    var existing = '';
    fetch('/gp/api/xiebo/recent/' + encodeURIComponent(code) + '/note', { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (dto) { if (dto && dto.noteHtml) existing = dto.noteHtml; initEditor(existing); })
      .catch(function () { initEditor(''); });

    function initEditor(initialHtml) {
      if (wangEditorInstance) {
        try { wangEditorInstance.destroy(); } catch (e) { /* ignore */ }
        wangEditorInstance = null;
      }

      // eslint-disable-next-line no-undef
      wangEditorInstance = window.wangEditor.createEditor({
        selector: '#xieboRecentNoteEditor',
        html: initialHtml || '',
        config: {
          placeholder: '写下你的笔记...',
          onUploadImage: function (files, insertCallback) {
            var fd = new FormData();
            fd.append('file', files[0]);
            fetch(API_UPLOAD, {
              method: 'POST',
              body: fd,
              headers: authHeadersNoContentType()
            })
              .then(function (r) { return r.ok ? r.json() : null; })
              .then(function (j) {
                if (j && j.url) {
                  insertCallback({ url: j.url, alt: files[0].name, href: j.url });
                }
              })
              .catch(function () { /* upload failed silently */ });
          }
        }
      });
    }
  }

  function hideNoteModal() {
    $('xieboRecentNoteModal').hidden = true;
    if (wangEditorInstance) {
      try { wangEditorInstance.destroy(); } catch (e) { /* ignore */ }
      wangEditorInstance = null;
    }
    editingStockCode = null;
  }

  function saveNote() {
    if (!wangEditorInstance || !editingStockCode) return;
    var html = wangEditorInstance.getHtml();
    fetch(API_ADMIN + '/' + encodeURIComponent(editingStockCode) + '/note', {
      method: 'PUT',
      headers: authHeaders(),
      body: JSON.stringify({ noteHtml: html })
    })
      .then(function (r) {
        if (!r.ok) throw new Error('保存失败: HTTP ' + r.status);
        return r.text();
      })
      .then(function () {
        hideNoteModal();
        alert('笔记已保存');
      })
      .catch(function (e) {
        alert('保存失败: ' + e.message);
      });
  }

  function bindRowActions() {
    var tbody = $('adminXieboRecentTableBody');
    if (!tbody) return;
    tbody.addEventListener('click', function (e) {
      var btn = e.target.closest('button[data-act]');
      if (!btn) return;
      var act = btn.dataset.act;
      var tr = btn.closest('tr');
      if (!tr) return;
      var code = tr.dataset.code;

      if (act === 'edit') {
        // Prefetch full data and open modal
        fetch(API_ADMIN + '/' + encodeURIComponent(code), { headers: authHeaders() })
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(function (data) {
            showStockModal(data || { stockCode: code, stockName: '', type: '科技AI' });
          })
          .catch(function () {
            showStockModal({ stockCode: code, stockName: '', type: '科技AI' });
          });
      } else if (act === 'delete') {
        deleteStock(code);
      } else if (act === 'note') {
        showNoteModal(code);
      }
    });
  }

  function bindModalHandlers() {
    var addBtn = $('xieboRecentAddBtn');
    if (addBtn) addBtn.addEventListener('click', function () { showStockModal(null); });

    var cancelBtn = $('xieboRecentModalCancel');
    if (cancelBtn) cancelBtn.addEventListener('click', hideStockModal);

    var saveBtn = $('xieboRecentModalSave');
    if (saveBtn) saveBtn.addEventListener('click', saveStock);

    var noteCancelBtn = $('xieboRecentNoteCancel');
    if (noteCancelBtn) noteCancelBtn.addEventListener('click', hideNoteModal);

    var noteSaveBtn = $('xieboRecentNoteSave');
    if (noteSaveBtn) noteSaveBtn.addEventListener('click', saveNote);
  }

  window.initXieboRecentPanel = function () {
    if (_loaded) return;
    _loaded = true;
    bindModalHandlers();
    bindRowActions();
    loadList();
  };
})();
