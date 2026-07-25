(function () {
  'use strict';

  const API = '/gp/api/swing';
  const $ = (sel) => document.querySelector(sel);

  function authHeaders(json) {
    const h = (window.GPAuth && GPAuth.headers && GPAuth.headers()) || {};
    if (json) h['Content-Type'] = 'application/json';
    return h;
  }

  async function api(path, opts) {
    const res = await fetch(API + path, opts);
    if (res.status === 401) {
      throw new Error('请先登录后再使用趋势波段');
    }
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (_) { data = text; }
    if (!res.ok) {
      const msg = (data && (data.message || data.error)) || text || res.statusText;
      throw new Error(msg);
    }
    return data;
  }

  function fmt(v, digits) {
    if (v == null || v === '') return '—';
    const n = Number(v);
    if (Number.isNaN(n)) return String(v);
    return n.toFixed(digits == null ? 2 : digits);
  }

  function fmtPct(v) {
    if (v == null) return '—';
    const n = Number(v);
    const sign = n > 0 ? '+' : '';
    return sign + n.toFixed(2) + '%';
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function setupLabel(r) {
    if (!r.activeSetupType) return '—';
    if (r.activeSetupType === 'PULLBACK') {
      return `回踩 ${fmt(r.pullbackZoneLow)}~${fmt(r.pullbackZoneHigh)}`;
    }
    if (r.activeSetupType === 'BREAKOUT') {
      return `突破>${fmt(r.platformHigh)}`;
    }
    return r.activeSetupType;
  }

  function renderWatchRow(r) {
    const pnlCls = (r.unrealizedPnlPct || 0) >= 0 ? 'pnl-pos' : 'pnl-neg';
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><strong>${escapeHtml(r.stockName || '')}</strong><br><small>${escapeHtml(r.stockCode)}</small>
        <div class="muted">${escapeHtml(r.sectorTag || '')}</div></td>
      <td><span class="status-pill ${escapeHtml(r.status || '')}">${escapeHtml(r.status || '')}</span></td>
      <td>${fmt(r.latestPrice)}<br><small class="muted">MA20 ${fmt(r.ma20)}</small></td>
      <td class="${r.preconditionsOk ? 'ok-yes' : 'ok-no'}">${r.preconditionsOk ? '✓' : '✗'}</td>
      <td>${escapeHtml(setupLabel(r))}</td>
      <td>${r.shares ? `${r.shares} @ ${fmt(r.avgCost)}` : '—'}</td>
      <td>${fmt(r.stopPrice)}</td>
      <td class="${pnlCls}">${r.unrealizedPnlPct == null ? '—' : fmtPct(r.unrealizedPnlPct)}<br>
        <small>${r.unrealizedPnl == null ? '' : fmt(r.unrealizedPnl)}</small></td>
      <td>
        <button type="button" class="link-btn" data-act="pause" data-id="${r.id}">暂停</button>
        <button type="button" class="link-btn" data-act="resume" data-id="${r.id}">恢复</button>
        <button type="button" class="link-btn" data-act="del" data-id="${r.id}">删除</button>
      </td>`;
    return tr;
  }

  async function loadStats() {
    try {
      const s = await api('/stats', { headers: authHeaders() });
      $('#statWatching').textContent = s.watchingCount;
      $('#statOpen').textContent = s.openPositions;
      $('#statClosed').textContent = s.totalClosed;
      $('#statWinRate').textContent = fmt(s.winRate) + '%';
      const pnl = Number(s.totalPnl || 0);
      const el = $('#statPnl');
      el.textContent = fmt(pnl);
      el.className = pnl >= 0 ? 'pnl-pos' : 'pnl-neg';
      $('#statPf').textContent = fmt(s.profitFactor);
    } catch (e) {
      console.warn(e);
    }
  }

  async function loadWatch() {
    const rows = await api('/watch', { headers: authHeaders() });
    const tbody = $('#watchTbody');
    tbody.innerHTML = '';
    (rows || []).forEach((r) => tbody.appendChild(renderWatchRow(r)));
    $('#listMeta').textContent = `共 ${rows.length} 只 · ${new Date().toLocaleTimeString()}`;
  }

  async function loadSignals() {
    const rows = await api('/signals', { headers: authHeaders() });
    const ul = $('#signalList');
    ul.innerHTML = '';
    if (!rows || !rows.length) {
      ul.innerHTML = '<li class="muted">暂无信号</li>';
      return;
    }
    rows.forEach((s) => {
      const li = document.createElement('li');
      li.innerHTML = `
        <div class="sig-title">${escapeHtml(s.title)}</div>
        <div class="sig-meta">${escapeHtml(s.level)} · ${escapeHtml(s.signalType)} · ${escapeHtml(s.status)}
          · ${escapeHtml(s.createdAt || '')}
          ${s.suggestAction ? ' · 建议 ' + escapeHtml(s.suggestAction) + (s.suggestShares ? ' ' + s.suggestShares + '股' : '') : ''}
        </div>
        <div class="sig-body">${escapeHtml(s.content || '')}</div>
        <div style="margin-top:8px">
          <button type="button" class="btn-secondary" data-ack="${s.id}">已知晓</button>
        </div>`;
      ul.appendChild(li);
    });
  }

  function showMsg(text, ok) {
    const el = $('#formMsg');
    el.hidden = false;
    el.textContent = text;
    el.className = 'msg' + (ok ? ' ok' : '');
  }

  function bindForm() {
    $('#addForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      const body = {
        stockCode: fd.get('stockCode'),
        sectorTag: fd.get('sectorTag'),
        thesis: fd.get('thesis') || null,
        preferredSetup: fd.get('preferredSetup'),
        hardFilterOk: !!fd.get('hardFilterOk'),
        quietPeriod: !!fd.get('quietPeriod'),
      };
      const eq = fd.get('accountEquity');
      if (eq) body.accountEquity = Number(eq);
      try {
        await api('/watch', {
          method: 'POST',
          headers: authHeaders(true),
          body: JSON.stringify(body),
        });
        e.target.reset();
        e.target.hardFilterOk.checked = true;
        showMsg('已加入监控（HYBRID：提醒+自动记账）', true);
        await refreshAll();
      } catch (err) {
        showMsg(err.message || String(err), false);
      }
    });

    $('#btnScan').addEventListener('click', async () => {
      try {
        const r = await api('/scan/run', { method: 'POST', headers: authHeaders(true) });
        showMsg(`扫描完成：${r.scanned} 只 / 信号 ${r.signals} / 成交 ${r.fills}`, true);
        await refreshAll();
      } catch (err) {
        showMsg(err.message || String(err), false);
      }
    });
  }

  function bindTableActions() {
    document.body.addEventListener('click', async (e) => {
      const t = e.target;
      if (!(t instanceof HTMLElement)) return;
      if (t.dataset.ack) {
        try {
          await api('/signals/' + t.dataset.ack + '/ack', {
            method: 'POST',
            headers: authHeaders(true),
          });
          await loadSignals();
        } catch (err) {
          alert(err.message);
        }
        return;
      }
      const act = t.dataset.act;
      const id = t.dataset.id;
      if (!act || !id) return;
      try {
        if (act === 'del') {
          if (!confirm('确认删除该监控？')) return;
          await api('/watch/' + id, { method: 'DELETE', headers: authHeaders() });
        } else if (act === 'pause') {
          await api('/watch/' + id, {
            method: 'PATCH',
            headers: authHeaders(true),
            body: JSON.stringify({ status: 'PAUSED' }),
          });
        } else if (act === 'resume') {
          await api('/watch/' + id, {
            method: 'PATCH',
            headers: authHeaders(true),
            body: JSON.stringify({ status: 'WATCHING' }),
          });
        }
        await refreshAll();
      } catch (err) {
        alert(err.message);
      }
    });
  }

  async function refreshAll() {
    await Promise.all([loadStats(), loadWatch(), loadSignals()]);
  }

  async function boot() {
    bindForm();
    bindTableActions();
    try {
      await refreshAll();
    } catch (e) {
      showMsg(e.message || String(e), false);
      $('#watchTbody').innerHTML =
        '<tr><td colspan="9" class="muted">请先登录后查看私有监控池</td></tr>';
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
