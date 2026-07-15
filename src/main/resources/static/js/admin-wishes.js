/**
 * 许愿池管理后台 - 列表 / 回复 / 展示开关 / 删除
 * 暴露 window.initWishAdminPanel() 由 admin-users.html 第一次进 panel-wishes 时调用。
 */
(function () {
  'use strict';

  var state = {
    page: 0,
    size: 20,
    status: '',
    keyword: '',
    totalPages: 0,
    total: 0
  };

  var els = {};

  function $(id) { return document.getElementById(id); }

  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function fmtTime(iso) {
    if (!iso) return '-';
    try {
      var d = new Date(iso);
      if (isNaN(d.getTime())) return iso;
      var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
      return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
        + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    } catch (e) { return iso; }
  }

  function setMsg(text, tone) {
    var m = els.listMsg;
    if (!m) return;
    m.textContent = text || '';
    m.className = 'msg' + (tone ? ' ' + tone : '');
  }

  function apiGet(path) {
    var token = localStorage.getItem('gp_auth_token');
    return fetch('/gp' + path, {
      headers: { 'Accept': 'application/json', 'Authorization': 'Bearer ' + (token || '') }
    }).then(function (r) {
      return r.json().then(function (data) { return { ok: r.ok, data: data }; });
    }).then(function (res) {
      if (!res.ok) throw new Error((res.data && res.data.error) || ('HTTP ' + r.status));
      return res.data;
    }).catch(function (e) {
      setMsg('请求失败：' + e.message, 'err');
      throw e;
    });
  }

  function apiPost(path, body) {
    var token = localStorage.getItem('gp_auth_token');
    return fetch('/gp' + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'Authorization': 'Bearer ' + (token || '') },
      body: JSON.stringify(body || {})
    }).then(function (r) {
      return r.json().then(function (data) { return { ok: r.ok, data: data }; });
    }).then(function (res) {
      if (!res.ok) throw new Error((res.data && res.data.error) || ('HTTP ' + r.status));
      return res.data;
    });
  }

  function apiDelete(path) {
    var token = localStorage.getItem('gp_auth_token');
    return fetch('/gp' + path, {
      method: 'DELETE',
      headers: { 'Accept': 'application/json', 'Authorization': 'Bearer ' + (token || '') }
    }).then(function (r) {
      if (r.status === 204) return { ok: true, data: {} };
      return r.json().then(function (data) { return { ok: r.ok, data: data }; });
    }).then(function (res) {
      if (!res.ok) throw new Error((res.data && res.data.error) || ('HTTP ' + r.status));
      return res.data;
    });
  }

  function renderRow(w) {
    var wish = esc(w.wish);
    var reply = esc(w.reply);
    var replyBy = w.replyBy ? esc(w.replyBy) : '';
    var replyAt = w.replyAt ? fmtTime(w.replyAt) : '';
    var display = !!w.display;

    var statusBadge = '';
    if (w.status === 'PENDING') statusBadge = '<span style="color:#f59e0b">⏳ 待回复</span>';
    else if (w.status === 'REPLIED') statusBadge = '<span style="color:#10b981">✅ 已回复</span>';
    else if (w.status === 'ARCHIVED') statusBadge = '<span style="color:#94a3b8">🗄️ 已归档</span>';
    else statusBadge = esc(w.status);

    var replyBlock = reply
      ? ('<div style="white-space:pre-wrap;color:#334155">' + reply + '</div>'
         + '<div style="font-size:11px;color:#94a3b8;margin-top:4px">'
         + '回复人：' + (replyBy || '—') + ' · ' + replyAt + '</div>')
      : '<span style="color:#94a3b8">— 尚未回复 —</span>';

    var displayBtn = '<button type="button" class="btn-sm" data-act="display" data-id="' + w.id + '" data-display="' + (!display) + '">'
      + (display ? '✅ 展示中 · 关闭' : '⚪ 关闭 · 开启') + '</button>';

    return ''
      + '<tr data-id="' + w.id + '">'
      +   '<td><div style="white-space:pre-wrap;word-break:break-word">' + wish + '</div>'
      +     '<div style="font-size:11px;color:#94a3b8;margin-top:4px">来源：' + esc(w.page || '—') + '</div></td>'
      +   '<td style="font-size:12px;color:#475569">'
      +     (w.email ? esc(w.email) : '<span style="color:#94a3b8">—</span>')
      +     '<div style="color:#94a3b8">' + esc(w.ip || '') + '</div></td>'
      +   '<td>' + replyBlock + '</td>'
      +   '<td>' + statusBadge + '</td>'
      +   '<td style="text-align:center">' + (display ? '<span style="color:#10b981;font-weight:600">✓</span>' : '<span style="color:#cbd5e1">—</span>') + '</td>'
      +   '<td style="font-size:11px;color:#94a3b8">' + fmtTime(w.createdAt) + '</td>'
      +   '<td>'
      +     '<button type="button" class="btn-sm" data-act="reply" data-id="' + w.id + '" data-reply="' + esc(w.reply || '') + '">✏️ 回复</button> '
      +     displayBtn + ' '
      +     '<button type="button" class="btn-sm" data-act="delete" data-id="' + w.id + '" style="color:#ef4444">🗑️</button>'
      +   '</td>'
      + '</tr>';
  }

  function renderTable(rows) {
    if (!rows || rows.length === 0) {
      els.body.innerHTML = '<tr><td colspan="7" style="text-align:center;color:var(--text-muted);padding:20px">暂无留言</td></tr>';
    } else {
      els.body.innerHTML = rows.map(renderRow).join('');
    }
    els.pageInfo.textContent = '第 ' + (state.page + 1) + ' / ' + Math.max(state.totalPages, 1) + ' 页 · 共 ' + state.total + ' 条';
    els.prev.disabled = state.page <= 0;
    els.next.disabled = state.page >= state.totalPages - 1;
  }

  function loadList() {
    setMsg('加载中…');
    var qs = 'page=' + state.page + '&size=' + state.size;
    if (state.status) qs += '&status=' + encodeURIComponent(state.status);
    if (state.keyword) qs += '&keyword=' + encodeURIComponent(state.keyword);
    apiGet('/api/admin/wishes?' + qs).then(function (data) {
      state.totalPages = Math.max(Math.ceil((data.total || 0) / state.size), 1);
      state.total = data.total || 0;
      renderTable(data.rows || []);
      setMsg('');
    }).catch(function () { /* setMsg already */ });
  }

  function loadCounts() {
    apiGet('/api/admin/wishes/counts').then(function (data) {
      var parts = [];
      parts.push('待回复 ' + (data.pending || 0));
      parts.push('已回复 ' + (data.replied || 0));
      parts.push('展示中 ' + (data.display || 0));
      if (els.counts) els.counts.textContent = parts.join(' · ');
    }).catch(function () { /* silent */ });
  }

  function openReplyModal(id, existing) {
    var overlay = document.createElement('div');
    overlay.style.position = 'fixed';
    overlay.style.inset = '0';
    overlay.style.background = 'rgba(0,0,0,0.5)';
    overlay.style.display = 'flex';
    overlay.style.alignItems = 'center';
    overlay.style.justifyContent = 'center';
    overlay.style.zIndex = '99999';

    var box = document.createElement('div');
    box.style.background = '#fff';
    box.style.borderRadius = '10px';
    box.style.padding = '24px';
    box.style.width = '420px';
    box.style.maxWidth = '92vw';
    box.style.maxHeight = '80vh';
    box.style.overflow = 'auto';
    box.innerHTML = ''
      + '<h3 style="margin:0 0 12px">回复留言 #' + id + '</h3>'
      + '<label style="display:block;font-size:13px;color:#475569;margin-bottom:6px">回复内容</label>'
      + '<textarea id="replyModalText" rows="6" style="width:100%;box-sizing:border-box;padding:8px;border:1px solid #cbd5e1;border-radius:6px;font-family:inherit;font-size:14px"></textarea>'
      + '<div id="replyModalMsg" style="font-size:12px;margin-top:8px;min-height:18px"></div>'
      + '<div style="display:flex;gap:8px;justify-content:flex-end;margin-top:12px">'
      +   '<button type="button" id="replyModalCancel" class="btn-sm">取消</button>'
      +   '<button type="button" id="replyModalSave" class="primary-btn">保存回复</button>'
      + '</div>';
    overlay.appendChild(box);
    document.body.appendChild(overlay);

    var textarea = box.querySelector('#replyModalText');
    var msg = box.querySelector('#replyModalMsg');
    if (existing) textarea.value = existing;

    function close() {
      if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
    }
    box.querySelector('#replyModalCancel').addEventListener('click', close);
    overlay.addEventListener('click', function (e) { if (e.target === overlay) close(); });

    box.querySelector('#replyModalSave').addEventListener('click', function () {
      var text = textarea.value.trim();
      if (!text) {
        msg.style.color = '#ef4444';
        msg.textContent = '回复不能为空';
        return;
      }
      apiPost('/api/admin/wishes/' + id + '/reply', { reply: text }).then(function () {
        msg.style.color = '#10b981';
        msg.textContent = '已保存';
        setTimeout(function () { close(); loadList(); loadCounts(); }, 400);
      }).catch(function (e) {
        msg.style.color = '#ef4444';
        msg.textContent = '保存失败：' + e.message;
      });
    });
    setTimeout(function () { textarea.focus(); }, 50);
  }

  function bindRowActions() {
    if (!els.body) return;
    els.body.addEventListener('click', function (e) {
      var btn = e.target.closest('button[data-act]');
      if (!btn) return;
      var act = btn.dataset.act;
      var id = btn.dataset.id;
      if (act === 'reply') {
        openReplyModal(id, btn.dataset.reply || '');
      } else if (act === 'display') {
        var next = btn.dataset.display === 'true';
        apiPost('/api/admin/wishes/' + id + '/display?display=' + next, {})
          .then(function () { setMsg(next ? '已开启公开展示' : '已关闭公开展示'); loadList(); loadCounts(); })
          .catch(function (e) { setMsg('操作失败：' + e.message, 'err'); });
      } else if (act === 'delete') {
        if (!confirm('确认删除这条留言吗？此操作不可恢复。')) return;
        apiDelete('/api/admin/wishes/' + id).then(function () {
          setMsg('已删除');
          loadList();
          loadCounts();
        }).catch(function (e) { setMsg('删除失败：' + e.message, 'err'); });
      }
    });
  }

  window.initWishAdminPanel = function () {
    els = {
      body: $('wishAdminTableBody'),
      pageInfo: $('wishAdminPageInfo'),
      prev: $('wishAdminPrevPageBtn'),
      next: $('wishAdminNextPageBtn'),
      searchInput: $('wishAdminSearchInput'),
      searchBtn: $('wishAdminSearchBtn'),
      statusSel: $('wishAdminStatusSel'),
      listMsg: $('wishAdminListMsg'),
      counts: $('wishAdminCounts')
    };

    if (!els.body) return;

    if (els.searchBtn) {
      els.searchBtn.addEventListener('click', function () {
        state.keyword = els.searchInput.value.trim();
        state.status = els.statusSel.value;
        state.page = 0;
        loadList();
      });
    }
    if (els.searchInput) {
      els.searchInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') els.searchBtn.click();
      });
    }
    if (els.statusSel) {
      els.statusSel.addEventListener('change', function () {
        state.status = els.statusSel.value;
        state.page = 0;
        loadList();
      });
    }
    if (els.prev) {
      els.prev.addEventListener('click', function () {
        if (state.page <= 0) return;
        state.page--;
        loadList();
      });
    }
    if (els.next) {
      els.next.addEventListener('click', function () {
        if (state.page >= state.totalPages - 1) return;
        state.page++;
        loadList();
      });
    }

    bindRowActions();
    loadList();
    loadCounts();
  };
})();
