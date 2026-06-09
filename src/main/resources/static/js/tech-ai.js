(function () {
  'use strict';

  const API = 'api/tech-ai';

  function esc(v) {
    return String(v == null ? '' : v)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function fmtNum(v, digits) {
    if (v == null || v === '') return '—';
    const n = Number(v);
    if (!Number.isFinite(n)) return '—';
    return n.toFixed(digits);
  }

  function fmtPct(v) {
    if (v == null) return '—';
    const n = Number(v);
    if (!Number.isFinite(n)) return '—';
    const cls = n >= 0 ? 'tech-ai-change-up' : 'tech-ai-change-down';
    const sign = n > 0 ? '+' : '';
    return `<span class="${cls}">${sign}${n.toFixed(2)}%</span>`;
  }

  function fmtTime(v) {
    if (!v) return '—';
    return String(v).replace('T', ' ').slice(0, 19);
  }

  function inputValue(v, fallback) {
    if (v == null || v === '') return fallback;
    const n = Number(v);
    return Number.isFinite(n) ? String(n) : fallback;
  }

  async function fetchJson(url, options) {
    const res = await fetch(url, options);
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || '请求失败');
    }
    return res.json();
  }

  async function loadAll() {
    await Promise.all([loadPool(), loadAlerts()]);
  }

  async function loadPool() {
    const wrap = document.getElementById('poolTableWrap');
    wrap.innerHTML = '<div class="tech-ai-empty">加载股票池...</div>';
    try {
      const rows = await fetchJson(`${API}/pool`);
      document.getElementById('poolCount').textContent = `${rows.length} 只`;
      if (!rows.length) {
        wrap.innerHTML = '<div class="tech-ai-empty">暂无科技AI股票，先加入一个标的。</div>';
        return;
      }
      wrap.innerHTML = `
        <table class="tech-ai-table">
          <thead>
            <tr>
              <th>股票</th>
              <th>QMT代码</th>
              <th>状态</th>
              <th>最新价</th>
              <th>当日涨跌</th>
              <th>成交量</th>
              <th>换手率</th>
              <th>行情时间</th>
              <th>告警阈值</th>
              <th>备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            ${rows.map(renderPoolRow).join('')}
          </tbody>
        </table>`;
    } catch (e) {
      wrap.innerHTML = `<div class="tech-ai-empty">加载失败：${esc(e.message)}</div>`;
    }
  }

  function renderPoolRow(row) {
    return `
      <tr data-id="${row.id}">
        <td>
          <div class="tech-ai-stock-name">${esc(row.stockName)}</div>
          <div class="tech-ai-stock-code">${esc(row.stockCode)}</div>
        </td>
        <td><span class="tech-ai-qmt-code">${esc(row.qmtCode)}</span></td>
        <td>
          <select class="pool-cell-select" data-field="status">
            <option value="watching" ${row.status === 'watching' ? 'selected' : ''}>观察中</option>
            <option value="holding" ${row.status === 'holding' ? 'selected' : ''}>持仓中</option>
            <option value="exited" ${row.status === 'exited' ? 'selected' : ''}>已离场</option>
          </select>
        </td>
        <td>${fmtNum(row.latestPrice, 2)}</td>
        <td>${fmtPct(row.dailyChangePct)}</td>
        <td>${row.volume == null ? '—' : Number(row.volume).toLocaleString()}</td>
        <td>${row.turnoverRate == null ? '—' : fmtNum(row.turnoverRate, 2) + '%'}</td>
        <td>${fmtTime(row.quoteTime)}</td>
        <td>${renderThresholdInputs(row)}</td>
        <td><input class="pool-cell-input" data-field="memo" value="${esc(row.memo || '')}" placeholder="备注" /></td>
        <td>
          <div class="tech-ai-row-actions">
            <button class="tech-ai-mini-btn" data-action="save">保存</button>
            <button class="tech-ai-mini-btn" data-action="delete">删除</button>
          </div>
        </td>
      </tr>`;
  }

  function renderThresholdInputs(row) {
    return `
      <div class="tech-ai-thresholds">
        <label><span>1m</span><input type="number" min="0" step="0.1" data-field="alertMinute1mPct" value="${esc(inputValue(row.alertMinute1mPct, '3'))}" /></label>
        <label><span>5m</span><input type="number" min="0" step="0.1" data-field="alertMinute5mPct" value="${esc(inputValue(row.alertMinute5mPct, '5'))}" /></label>
        <label><span>日</span><input type="number" min="0" step="0.1" data-field="alertDailyPct" value="${esc(inputValue(row.alertDailyPct, '3'))}" /></label>
        <label><span>3日</span><input type="number" min="0" step="0.1" data-field="alertThreeDayPct" value="${esc(inputValue(row.alertThreeDayPct, '10'))}" /></label>
        <label><span>换手</span><input type="number" min="0" step="1" data-field="alertTurnoverRatioPct" value="${esc(inputValue(row.alertTurnoverRatioPct, '150'))}" /></label>
      </div>`;
  }

  async function loadAlerts() {
    const list = document.getElementById('alertList');
    list.innerHTML = '<div class="tech-ai-empty">加载告警...</div>';
    try {
      const rows = await fetchJson(`${API}/alerts`);
      if (!rows.length) {
        list.innerHTML = '<div class="tech-ai-empty">暂无告警记录。</div>';
        return;
      }
      list.innerHTML = rows.map(row => `
        <div class="tech-ai-alert">
          <div class="tech-ai-alert-title">${esc(row.title)}</div>
          <div class="tech-ai-alert-meta">
            <span>${esc(row.signalType)}</span>
            <span>${fmtTime(row.triggerAt)}</span>
          </div>
        </div>`).join('');
    } catch (e) {
      list.innerHTML = `<div class="tech-ai-empty">加载失败：${esc(e.message)}</div>`;
    }
  }

  async function addStock() {
    const input = document.getElementById('stockInput');
    const keyword = input.value.trim();
    if (!keyword) {
      alert('请输入股票代码或名称');
      return;
    }
    await fetchJson(`${API}/pool`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ keyword, status: 'watching' }),
    });
    input.value = '';
    await loadPool();
  }

  async function updateField(id, field, value) {
    await fetchJson(`${API}/pool/${id}/field`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ field, value }),
    });
  }

  async function deleteRow(id) {
    if (!confirm('确定删除这只股票？')) return;
    await fetchJson(`${API}/pool/${id}`, { method: 'DELETE' });
    await Promise.all([loadPool(), loadAlerts()]);
  }

  async function runMonitor() {
    const btn = document.getElementById('runMonitorBtn');
    btn.disabled = true;
    btn.textContent = '扫描中...';
    try {
      const result = await fetchJson(`${API}/monitor/run`, { method: 'POST' });
      alert(`扫描完成，触发 ${result.triggered || 0} 条告警`);
      await Promise.all([loadPool(), loadAlerts()]);
    } catch (e) {
      alert(e.message);
    } finally {
      btn.disabled = false;
      btn.textContent = '手动扫描';
    }
  }

  function bindEvents() {
    document.getElementById('refreshBtn')?.addEventListener('click', loadAll);
    document.getElementById('runMonitorBtn')?.addEventListener('click', runMonitor);
    document.getElementById('addStockBtn')?.addEventListener('click', () => addStock().catch(e => alert(e.message)));
    document.getElementById('stockInput')?.addEventListener('keydown', e => {
      if (e.key === 'Enter') addStock().catch(err => alert(err.message));
    });
    document.getElementById('poolTableWrap')?.addEventListener('click', async e => {
      const btn = e.target.closest('button[data-action]');
      if (!btn) return;
      const tr = btn.closest('tr');
      const id = tr?.dataset.id;
      if (!id) return;
      if (btn.dataset.action === 'delete') {
        await deleteRow(id);
        return;
      }
      const memo = tr.querySelector('[data-field="memo"]').value;
      const status = tr.querySelector('[data-field="status"]').value;
      await updateField(id, 'memo', memo);
      await updateField(id, 'status', status);
      const thresholdFields = [
        'alertMinute1mPct',
        'alertMinute5mPct',
        'alertDailyPct',
        'alertThreeDayPct',
        'alertTurnoverRatioPct',
      ];
      for (const field of thresholdFields) {
        await updateField(id, field, tr.querySelector(`[data-field="${field}"]`).value);
      }
      await loadPool();
    });
  }

  document.addEventListener('DOMContentLoaded', () => {
    bindEvents();
    loadAll();
  });
})();
