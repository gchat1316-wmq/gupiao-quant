(function () {
  'use strict';

  const API = '/gp/api/trend-wave';
  const $ = (s) => document.querySelector(s);
  let watches = [];
  let filter = 'ALL';

  function authHeaders() {
    const h = { 'Content-Type': 'application/json' };
    if (window.GPAuth && GPAuth.headers) {
      Object.assign(h, GPAuth.headers());
    }
    return h;
  }

  async function api(path, opts = {}) {
    const r = await fetch(API + path, {
      ...opts,
      headers: { ...authHeaders(), ...(opts.headers || {}) },
    });
    if (!r.ok) {
      const t = await r.text();
      throw new Error(t || r.statusText);
    }
    if (r.status === 204) return null;
    return r.json();
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }
  function fmt(v, d = 2) {
    if (v == null || v === '') return '—';
    const n = Number(v);
    return Number.isFinite(n) ? n.toFixed(d) : String(v);
  }
  function statusBadge(st) {
    const map = {
      SCREENING: ['筛选中', ''],
      WATCH_PULLBACK: ['回踩观察', 'tw-badge-action'],
      WATCH_BREAKOUT: ['突破观察', 'tw-badge-warn'],
      BUY_SIGNAL: ['待买入', 'tw-badge-action'],
      HOLDING: ['持仓中', 'tw-badge-ok'],
      PARTIAL_EXIT: ['半仓中', 'tw-badge-warn'],
      CLOSED: ['已结束', ''],
      INVALID: ['已失效', 'tw-badge-dead'],
    };
    const [label, cls] = map[st] || [st || '—', ''];
    return `<span class="tw-badge ${cls}">${esc(label)}</span>`;
  }

  function matchFilter(w) {
    if (filter === 'ALL') return true;
    if (filter === 'WATCH') {
      return ['SCREENING', 'WATCH_PULLBACK', 'WATCH_BREAKOUT'].includes(w.status);
    }
    if (filter === 'BUY_SIGNAL') return w.status === 'BUY_SIGNAL';
    if (filter === 'HOLDING') return w.status === 'HOLDING' || w.status === 'PARTIAL_EXIT';
    return true;
  }

  function screenSummary(w) {
    const d = w.screenDetail || {};
    const ok = w.screenPassed;
    const bits = [
      d.trend ? '趋势✓' : '趋势✗',
      d.volumeExpand ? '量能✓' : '量能✗',
      d.sectorOk ? '赛道✓' : '赛道✗',
      d.notTooHigh === false ? '高位✗' : null,
    ].filter(Boolean);
    return `<span class="${ok ? 'tw-badge-ok' : 'tw-badge-dead'} tw-badge">${ok ? '通过' : '未过'}</span>
      <div class="tw-muted">${esc(bits.join(' · '))}</div>`;
  }

  function setupSummary(w) {
    const setups = w.setups || [];
    if (!setups.length) return '<span class="tw-muted">—</span>';
    return setups
      .map((s) => {
        if (s.setupType === 'PULLBACK') {
          return `<div>回踩 ${esc(s.status)}<br/><span class="tw-muted">平台 ${fmt(s.platformLow)}~${fmt(s.platformOpen)} · ${s.limitUpCount || 0}板</span></div>`;
        }
        return `<div>突破 ${esc(s.status)}<br/><span class="tw-muted">高点 ${fmt(s.platformHigh)} · ${s.platformDays || 0}日</span></div>`;
      })
      .join('');
  }

  function positionSummary(w) {
    const p = w.position;
    if (!p) {
      if (w.buySignalPrice != null) {
        return `<div class="tw-muted">信号价 ${fmt(w.buySignalPrice)}<br/>${esc(w.buySignalType || '')}</div>`;
      }
      return '—';
    }
    const pnlCls = (p.unrealizedPnlPct || 0) >= 0 ? 'up' : 'down';
    return `<div>
      ${esc(p.buyType)} · ${esc(p.profitTier)} · 仓位${fmt(p.positionPct, 0)}%<br/>
      成本 ${fmt(p.entryPrice)} · <span class="${pnlCls}">${fmt(p.unrealizedPnlPct)}%</span><br/>
      <span class="tw-muted">止损 ${fmt(p.stopPrimary)} / 兜底 ${fmt(p.stopSecondary)} / 移动 ${fmt(p.trailingStop)}</span>
    </div>`;
  }

  function actions(w) {
    const btns = [];
    btns.push(`<button type="button" class="tw-btn tw-btn-sm" data-act="rescreen" data-id="${w.id}">重筛</button>`);
    if (w.status === 'BUY_SIGNAL' || w.status === 'WATCH_PULLBACK' || w.status === 'WATCH_BREAKOUT') {
      btns.push(`<button type="button" class="tw-btn tw-btn-sm tw-btn-primary" data-act="buy" data-id="${w.id}" data-price="${w.latestPrice || w.buySignalPrice || ''}">确认买入</button>`);
    }
    if (w.position && (w.position.status === 'HOLDING' || w.position.status === 'PARTIAL_EXIT')) {
      btns.push(`<button type="button" class="tw-btn tw-btn-sm" data-act="add" data-pid="${w.position.id}" data-price="${w.latestPrice || ''}">加仓</button>`);
      btns.push(`<button type="button" class="tw-btn tw-btn-sm tw-btn-danger" data-act="sell" data-pid="${w.position.id}" data-price="${w.latestPrice || ''}">卖出</button>`);
    }
    if (w.poolId) {
      btns.push(`<button type="button" class="tw-btn tw-btn-sm" data-act="remove" data-pool="${w.poolId}">移出</button>`);
    }
    return btns.join(' ');
  }

  function renderWatches() {
    const tbody = $('#watchTable tbody');
    const rows = watches.filter(matchFilter);
    if (!rows.length) {
      tbody.innerHTML = '<tr><td colspan="8" class="tw-muted">暂无监控，先在上方加入股票池</td></tr>';
      return;
    }
    tbody.innerHTML = rows
      .map((w) => {
        const chgCls = (w.dailyChangePct || 0) >= 0 ? 'up' : 'down';
        return `<tr>
          <td><strong>${esc(w.stockName || w.stockCode)}</strong><br/><small class="tw-muted">${esc(w.stockCode)} · ${esc(w.sectorTag || '')}</small></td>
          <td>${statusBadge(w.status)}${w.paperMode ? '<div class="tw-muted">纸面</div>' : ''}${w.invalidReason ? `<div class="tw-muted">${esc(w.invalidReason)}</div>` : ''}</td>
          <td>${fmt(w.latestPrice)} <span class="${chgCls}">${fmt(w.dailyChangePct)}%</span></td>
          <td class="tw-muted">5/10/20/60<br/>${fmt(w.ma5)} / ${fmt(w.ma10)} / ${fmt(w.ma20)} / ${fmt(w.ma60)}</td>
          <td>${screenSummary(w)}</td>
          <td>${setupSummary(w)}</td>
          <td>${positionSummary(w)}</td>
          <td>${actions(w)}</td>
        </tr>`;
      })
      .join('');
  }

  async function loadWatches() {
    watches = await api('/watches');
    renderWatches();
  }

  async function loadPool() {
    const rows = await api('/pool');
    const el = $('#poolList');
    if (!rows.length) {
      el.innerHTML = '<li class="tw-muted">池为空</li>';
      return;
    }
    el.innerHTML = rows
      .map(
        (p) => `<li>
          <div class="title">${esc(p.stockName || p.stockCode)} <small class="tw-muted">${esc(p.stockCode)}</small></div>
          <div class="meta">${esc(p.source)} · ${esc(p.sectorTag || '未标赛道')} · 监控 ${esc(p.watchStatus || '—')}${p.paperMode ? ' · 纸面' : ''}</div>
        </li>`
      )
      .join('');
  }

  async function loadEvents() {
    const rows = await api('/events?limit=30');
    const el = $('#eventList');
    if (!rows.length) {
      el.innerHTML = '<li class="tw-muted">暂无信号</li>';
      return;
    }
    el.innerHTML = rows
      .map(
        (e) => `<li>
          <div class="title">${esc(e.title || e.eventType)}</div>
          <div class="meta">${esc(e.severity)} · ${esc(e.stockCode)} · ${esc(e.createdAt || '')}${e.pushed ? ' · 已推送' : ''}</div>
          <div class="tw-muted">${esc(e.content || '')}</div>
        </li>`
      )
      .join('');
  }

  async function loadStats() {
    try {
      const s = await api('/stats');
      $('#stWinRate').textContent = fmt(s.winRate) + '%';
      $('#stPf').textContent = fmt(s.profitFactor);
      $('#stExp').textContent = fmt(s.expectancyPct) + '%';
      $('#stTotal').textContent = String(s.totalTrades ?? 0);
      $('#stPnl').textContent = fmt(s.totalRealizedPnl);
    } catch (e) {
      /* ignore */
    }
  }

  function openTradeDialog(mode, opts) {
    const dlg = $('#tradeDialog');
    const form = $('#tradeForm');
    form.mode.value = mode;
    form.watchId.value = opts.watchId || '';
    form.positionId.value = opts.positionId || '';
    form.price.value = opts.price || '';
    form.shares.value = '';
    form.sellPct.value = mode === 'sell' ? '50' : '100';
    form.memo.value = '';
    $('#tradeTitle').textContent =
      mode === 'buy' ? '确认买入' : mode === 'add' ? '确认加仓' : '确认卖出';
    form.querySelector('.sell-only').hidden = mode !== 'sell';
    dlg.showModal();
  }

  function bind() {
    $('#addForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      try {
        await api('/pool', {
          method: 'POST',
          body: JSON.stringify({
            stockCode: fd.get('stockCode'),
            sectorTag: fd.get('sectorTag') || null,
            source: fd.get('source') || 'MANUAL',
            paperMode: !!fd.get('paperMode'),
          }),
        });
        e.target.reset();
        await refreshAll();
      } catch (err) {
        alert('加入失败：' + err.message);
      }
    });

    $('#watchTabs').addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-filter]');
      if (!btn) return;
      filter = btn.dataset.filter;
      $$('#watchTabs button').forEach((b) => b.classList.toggle('active', b === btn));
      renderWatches();
    });

    $('#watchTable').addEventListener('click', async (e) => {
      const btn = e.target.closest('button[data-act]');
      if (!btn) return;
      const act = btn.dataset.act;
      try {
        if (act === 'rescreen') {
          await api(`/watches/${btn.dataset.id}/rescreen`, { method: 'POST' });
          await refreshAll();
        } else if (act === 'buy') {
          openTradeDialog('buy', { watchId: btn.dataset.id, price: btn.dataset.price });
        } else if (act === 'add') {
          openTradeDialog('add', { positionId: btn.dataset.pid, price: btn.dataset.price });
        } else if (act === 'sell') {
          openTradeDialog('sell', { positionId: btn.dataset.pid, price: btn.dataset.price });
        } else if (act === 'remove') {
          if (!confirm('移出股票池？')) return;
          await api(`/pool/${btn.dataset.pool}`, { method: 'DELETE' });
          await refreshAll();
        }
      } catch (err) {
        alert(err.message);
      }
    });

    $('#tradeCancel').addEventListener('click', () => $('#tradeDialog').close());
    $('#tradeForm').addEventListener('submit', async (e) => {
      // method=dialog will close; handle first
      e.preventDefault();
      const form = e.target;
      const mode = form.mode.value;
      const price = Number(form.price.value);
      const shares = form.shares.value ? Number(form.shares.value) : null;
      try {
        if (mode === 'buy') {
          await api('/positions', {
            method: 'POST',
            body: JSON.stringify({
              watchId: Number(form.watchId.value),
              price,
              shares,
              memo: form.memo.value || null,
            }),
          });
        } else {
          await api('/trades', {
            method: 'POST',
            body: JSON.stringify({
              positionId: Number(form.positionId.value),
              legType: mode === 'add' ? 'ADD' : 'SELL',
              price,
              shares,
              sellPct: mode === 'sell' ? Number(form.sellPct.value || 100) : null,
              memo: form.memo.value || null,
            }),
          });
        }
        $('#tradeDialog').close();
        await refreshAll();
      } catch (err) {
        alert('提交失败：' + err.message);
      }
    });

    $('#btnScan').addEventListener('click', () => runScan(false));
    $('#btnScanEod').addEventListener('click', () => runScan(true));
  }

  function $$(s) {
    return Array.from(document.querySelectorAll(s));
  }

  async function runScan(eod) {
    const msg = $('#twScanMsg');
    msg.textContent = '扫描中...';
    try {
      const r = await api('/scan?eod=' + eod, { method: 'POST' });
      msg.textContent = r.message || '完成';
      await refreshAll();
    } catch (err) {
      msg.textContent = '失败：' + err.message;
    }
  }

  async function refreshAll() {
    await Promise.all([loadWatches(), loadPool(), loadEvents(), loadStats()]);
  }

  bind();
  refreshAll();
  setInterval(refreshAll, 60000);
})();
