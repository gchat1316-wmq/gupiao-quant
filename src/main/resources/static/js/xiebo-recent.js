(function () {
  'use strict';

  const API_LIST = '/api/xiebo/recent';
  const API_NOTE = (c) => `/api/xiebo/recent/${c}/note`;
  const API_SUB = (c) => `/api/me/recent/subscriptions/${c}`;
  const API_RESET = (c) => `/api/me/recent/subscriptions/${c}/reset-alerts`;

  let watchRows = [];
  let mySubs = {};

  function authHeaders() {
    const t = localStorage.getItem('token');
    return t ? { 'Authorization': 'Bearer ' + t } : {};
  }

  function fmt(n) {
    if (n == null) return '—';
    return Number(n).toFixed(2);
  }

  function statusOptions(current) {
    const opts = ['关注', '建仓', '减仓', '清仓'];
    return opts.map(o => `<option value="${o}" ${o === current ? 'selected' : ''}>${o}</option>`).join('');
  }

  async function fetchList() {
    const r = await fetch(API_LIST);
    if (!r.ok) throw new Error('加载股票列表失败');
    watchRows = await r.json();
  }

  async function fetchMySubs() {
    if (!localStorage.getItem('token')) { mySubs = {}; return; }
    try {
      const r = await fetch('/api/me/recent/subscriptions', { headers: authHeaders() });
      if (r.ok) {
        const arr = await r.json();
        mySubs = Object.fromEntries(arr.map(s => [s.stockCode, s]));
      }
    } catch (e) {
      console.warn('load my subs failed', e);
      mySubs = {};
    }
  }

  function renderTable() {
    const tbody = document.querySelector('#xiebo-recent-table tbody');
    if (!tbody) return;
    tbody.innerHTML = watchRows.map(w => {
      const sub = mySubs[w.stockCode] || {};
      const isLoggedIn = !!localStorage.getItem('token');
      const ro = isLoggedIn ? '' : 'readonly';
      const ph = isLoggedIn ? '' : '登录后可设置';
      const enabled = sub.enabled === true;
      return `<tr data-code="${w.stockCode}">
        <td><strong>${w.stockName}</strong><br><small>${w.stockCode}</small></td>
        <td>${w.type || ''}</td>
        <td><span class="cur-price" data-code="${w.stockCode}">${fmt(w.currentPrice)}</span>
            <button class="refresh-btn" data-code="${w.stockCode}" title="刷新现价">🔄</button></td>
        <td><input type="number" step="0.01" class="price-buy"  ${ro} placeholder="${ph}" value="${fmt(sub.priceBuy)}"></td>
        <td><input type="number" step="0.01" class="price-sl"   ${ro} placeholder="${ph}" value="${fmt(sub.priceStopLoss)}"></td>
        <td><input type="number" step="0.01" class="price-add"  ${ro} placeholder="${ph}" value="${fmt(sub.priceAddPosition)}"></td>
        <td><input type="number" step="0.01" class="price-red"  ${ro} placeholder="${ph}" value="${fmt(sub.priceReducePosition)}"></td>
        <td><input type="number" step="0.01" class="price-clr"  ${ro} placeholder="${ph}" value="${fmt(sub.priceClearPosition)}"></td>
        <td><select class="status-sel" ${isLoggedIn ? '' : 'disabled'}>${statusOptions(sub.status || '关注')}</select></td>
        <td><input type="checkbox" class="sub-chk" ${enabled ? 'checked' : ''} ${isLoggedIn ? '' : 'disabled'}></td>
        <td><button class="note-btn" title="展开笔记">📝</button></td>
        <td><button class="reset-btn" title="重置已触发的提醒">🔄</button></td>
      </tr>`;
    }).join('');
  }

  async function upsertSub(code, partial) {
    const tr = document.querySelector(`tr[data-code="${code}"]`);
    const cur = mySubs[code] || {};
    const body = {
      enabled: tr.querySelector('.sub-chk').checked,
      status: tr.querySelector('.status-sel').value,
      priceBuy: parseFloat(tr.querySelector('.price-buy').value) || null,
      priceStopLoss: parseFloat(tr.querySelector('.price-sl').value) || null,
      priceAddPosition: parseFloat(tr.querySelector('.price-add').value) || null,
      priceReducePosition: parseFloat(tr.querySelector('.price-red').value) || null,
      priceClearPosition: parseFloat(tr.querySelector('.price-clr').value) || null,
      serverchanSendKey: cur.serverchanSendKey || null,
      ...partial
    };
    const r = await fetch(API_SUB(code), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify(body)
    });
    if (!r.ok) {
      const t = await r.text();
      throw new Error('保存失败: ' + t);
    }
    const j = await r.json();
    mySubs[code] = { ...body, id: j.subscriptionId };
    return j;
  }

  function attachHandlers() {
    const tbody = document.querySelector('#xiebo-recent-table tbody');
    if (!tbody) return;

    tbody.addEventListener('change', async (ev) => {
      const tr = ev.target.closest('tr');
      if (!tr) return;
      const code = tr.dataset.code;
      try {
        await upsertSub(code);
      } catch (e) {
        alert(e.message || String(e));
      }
    });

    tbody.addEventListener('click', async (ev) => {
      const tr = ev.target.closest('tr');
      if (!tr) return;
      const code = tr.dataset.code;

      if (ev.target.matches('.refresh-btn')) {
        ev.target.disabled = true;
        try {
          const r = await fetch(API_LIST);
          const arr = await r.json();
          const cur = arr.find(x => x.stockCode === code);
          if (cur) tr.querySelector('.cur-price').textContent = fmt(cur.currentPrice);
        } catch (e) {
          alert('刷新失败');
        } finally {
          ev.target.disabled = false;
        }
        return;
      }

      if (ev.target.matches('.note-btn')) {
        try {
          const r = await fetch(API_NOTE(code));
          if (!r.ok) return alert('笔记加载失败');
          const dto = await r.json();
          if (!dto || !dto.noteHtml) return alert('暂无笔记');
          let row = tr.nextElementSibling;
          if (!row || !row.matches('.note-row')) {
            row = document.createElement('tr');
            row.className = 'note-row';
            row.innerHTML = `<td colspan="12" class="note-cell" style="background:#f8f9fa;padding:12px;">${dto.noteHtml}</td>`;
            tr.after(row);
          } else {
            row.remove();
          }
        } catch (e) {
          alert('笔记加载失败');
        }
        return;
      }

      if (ev.target.matches('.reset-btn')) {
        if (!confirm('重置该股票的所有已触发提醒?')) return;
        try {
          const r = await fetch(API_RESET(code), { method: 'POST', headers: authHeaders() });
          if (!r.ok) throw new Error(await r.text());
          alert('已重置');
        } catch (e) {
          alert('重置失败: ' + (e.message || e));
        }
      }
    });
  }

  async function init() {
    const panel = document.getElementById('panel-recent');
    if (!panel) return;

    // 首次进入页面时如果该 panel 已激活,立即加载
    if (!panel.hidden) {
      try {
        await fetchList();
        await fetchMySubs();
        renderTable();
      } catch (e) {
        console.error('init load failed', e);
      }
    }

    // tab 切换时重新加载
    const tabBtn = document.querySelector('.invest-tab[data-panel="panel-recent"]');
    if (tabBtn) {
      tabBtn.addEventListener('click', async () => {
        try {
          await fetchList();
          await fetchMySubs();
          renderTable();
        } catch (e) {
          console.error('reload failed', e);
        }
      });
    }

    attachHandlers();
  }

  document.addEventListener('DOMContentLoaded', init);
})();
