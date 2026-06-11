(function () {
  'use strict';

  const API = 'api/tech-ai';
  const POOL_COLSPAN = 11;
  const openDetails = new Set();

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

  function fmtMoney(v) {
    if (v == null || v === '') return '—';
    const n = Number(v);
    if (!Number.isFinite(n)) return '—';
    return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  function fmtSignedMoney(v, pct) {
    if (v == null) return '—';
    const n = Number(v);
    if (!Number.isFinite(n)) return '—';
    const cls = n >= 0 ? 'tech-ai-change-up' : 'tech-ai-change-down';
    const sign = n > 0 ? '+' : '';
    const pctText = pct == null ? '' : `（${sign}${Number(pct).toFixed(2)}%）`;
    return `<span class="${cls}">${sign}${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}${pctText}</span>`;
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

  const STATE_LABEL = { none: '空仓', holding: '持仓中', scaled: '已减仓', exited: '已离场' };

  function posStateLabel(state) {
    return STATE_LABEL[state] || '空仓';
  }

  function signalBadge(signal) {
    if (!signal) return '';
    const map = {
      ADD: ['加仓', 'add'],
      STOP: ['清仓', 'stop'],
      TP: ['止盈', 'tp'],
    };
    const item = map[signal];
    if (!item) return '';
    return `<span class="tech-ai-badge tech-ai-badge-${item[1]}">${item[0]}信号</span>`;
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
        wrap.innerHTML = '<div class="tech-ai-empty">暂无AI监控股票，先加入一个标的。</div>';
        return;
      }
      wrap.innerHTML = `
        <table class="tech-ai-table">
          <thead>
            <tr>
              <th>股票</th>
              <th>QMT代码</th>
              <th>状态 / 信号</th>
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
      // 恢复已展开的持仓面板并加载流水
      for (const row of rows) {
        if (openDetails.has(String(row.id))) {
          const detail = wrap.querySelector(`tr.tech-ai-pos-detail[data-id="${row.id}"]`);
          if (detail) {
            detail.style.display = '';
            loadFills(row.id).catch(() => {});
          }
        }
      }
    } catch (e) {
      wrap.innerHTML = `<div class="tech-ai-empty">加载失败：${esc(e.message)}</div>`;
    }
  }

  function renderPoolRow(row) {
    const hasPosition = Number(row.positionLots || 0) > 0;
    const posLine = `
      <div class="tech-ai-pos-state">${posStateLabel(row.positionState)}${hasPosition ? ` · ${fmtNum(row.positionLots, 0)}手` : ''}</div>
      ${signalBadge(row.pendingSignal)}`;
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
          ${posLine}
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
            <button class="tech-ai-mini-btn" data-action="toggle-pos">持仓</button>
            <button class="tech-ai-mini-btn" data-action="save">保存</button>
            <button class="tech-ai-mini-btn" data-action="delete">删除</button>
          </div>
        </td>
      </tr>
      ${renderPositionDetail(row)}`;
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

  function metric(label, valueHtml) {
    return `<div class="tech-ai-pos-metric"><span>${label}</span><strong>${valueHtml}</strong></div>`;
  }

  function paramInput(label, field, value, step, isText) {
    if (isText) {
      return `<label><span>${label}</span><input type="text" data-pfield="${field}" value="${esc(value == null ? '' : value)}" /></label>`;
    }
    return `<label><span>${label}</span><input type="number" min="0" step="${step || '0.01'}" data-pfield="${field}" value="${esc(value == null ? '' : value)}" /></label>`;
  }

  function renderPositionDetail(row) {
    const isOpen = openDetails.has(String(row.id));
    const stopHtml = row.currentStopPrice == null ? '—'
      : `${fmtNum(row.currentStopPrice, 2)}${row.stopBelowCost ? ' <span class="tech-ai-pos-warn">止损低于成本</span>' : ''}`;
    const nextAddHtml = row.nextAddPrice == null ? '—'
      : `${fmtNum(row.nextAddPrice, 2)}${row.nextAddLots != null ? ` · ${fmtNum(row.nextAddLots, 0)}手` : ''}`;
    return `
      <tr class="tech-ai-pos-detail" data-id="${row.id}" style="display:${isOpen ? '' : 'none'}">
        <td colspan="${POOL_COLSPAN}">
          <div class="tech-ai-pos-wrap">
            <div class="tech-ai-pos-metrics">
              ${metric('状态', posStateLabel(row.positionState))}
              ${metric('持仓手数', fmtNum(row.positionLots, 0))}
              ${metric('平均成本', fmtNum(row.avgCost, 2))}
              ${metric('总投入', fmtMoney(row.totalInvested))}
              ${metric('浮动盈亏', fmtSignedMoney(row.floatingPnl, row.floatingPnlPct))}
              ${metric('已实现盈亏', fmtSignedMoney(row.realizedPnl, null))}
              ${metric('建仓后最高价', fmtNum(row.peakPrice, 2))}
              ${metric('当前移动止损', stopHtml)}
              ${metric('下一加仓价', nextAddHtml)}
              ${metric('目标止盈价', fmtNum(row.targetSellPrice, 2))}
              ${metric('首仓价', fmtNum(row.entryPrice, 2))}
              ${row.useAtr ? metric('ATR', fmtNum(row.atrValue, 2)) : ''}
            </div>

            <div class="tech-ai-pos-actions">
              <button class="tech-ai-mini-btn tech-ai-btn-buy" data-action="fill-open">建仓</button>
              <button class="tech-ai-mini-btn tech-ai-btn-buy" data-action="fill-add">加仓</button>
              <button class="tech-ai-mini-btn tech-ai-btn-sell" data-action="fill-reduce">减仓</button>
              <button class="tech-ai-mini-btn tech-ai-btn-sell" data-action="fill-clear">清仓</button>
            </div>

            <div class="tech-ai-pos-params">
              ${paramInput('加仓步长%', 'addStepPct', row.addStepPct, '0.1')}
              ${paramInput('回撤%', 'trailPct', row.trailPct, '0.1')}
              ${paramInput('加仓手数表', 'addSizeSchedule', row.addSizeSchedule, null, true)}
              ${paramInput('最大手数', 'maxLots', row.maxLots, '1')}
              ${paramInput('止盈比例%', 'takeProfitPct', row.takeProfitPct, '1')}
              ${paramInput('目标卖价', 'targetSellPrice', row.targetSellPrice, '0.01')}
              ${paramInput('时间止损天数', 'timeStopDays', row.timeStopDays, '1')}
              <label class="tech-ai-pos-check"><span>启用ATR</span><input type="checkbox" data-pfield="useAtr" ${row.useAtr ? 'checked' : ''} /></label>
              ${paramInput('ATR周期', 'atrPeriod', row.atrPeriod, '1')}
              ${paramInput('ATR加仓倍数', 'atrAddMult', row.atrAddMult, '0.1')}
              ${paramInput('ATR止损倍数', 'atrTrailMult', row.atrTrailMult, '0.1')}
              <button class="tech-ai-mini-btn" data-action="save-params">保存参数</button>
            </div>

            <div class="tech-ai-pos-fills" data-fills-id="${row.id}">
              <div class="tech-ai-empty">展开后加载成交流水...</div>
            </div>
          </div>
        </td>
      </tr>`;
  }

  async function loadFills(id) {
    const box = document.querySelector(`.tech-ai-pos-fills[data-fills-id="${id}"]`);
    if (!box) return;
    try {
      const fills = await fetchJson(`${API}/pool/${id}/fills`);
      if (!fills.length) {
        box.innerHTML = '<div class="tech-ai-empty">暂无成交流水。</div>';
        return;
      }
      const actionLabel = { open: '建仓', add: '加仓', reduce: '减仓', clear: '清仓' };
      box.innerHTML = `
        <table class="tech-ai-fills-table">
          <thead><tr><th>时间</th><th>操作</th><th>价格</th><th>手数</th><th>金额</th><th></th></tr></thead>
          <tbody>
            ${fills.map(f => `
              <tr>
                <td>${fmtTime(f.filledAt)}</td>
                <td>${actionLabel[f.action] || esc(f.action)}</td>
                <td>${fmtNum(f.price, 2)}</td>
                <td>${fmtNum(f.lots, 0)}</td>
                <td>${fmtMoney(f.amount)}</td>
                <td><button class="tech-ai-link-btn" data-action="del-fill" data-fill-id="${f.id}">删除</button></td>
              </tr>`).join('')}
          </tbody>
        </table>`;
    } catch (e) {
      box.innerHTML = `<div class="tech-ai-empty">加载失败：${esc(e.message)}</div>`;
    }
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
    if (!confirm('确定删除这只股票？相关持仓流水也会一并删除。')) return;
    await fetchJson(`${API}/pool/${id}`, { method: 'DELETE' });
    openDetails.delete(String(id));
    await Promise.all([loadPool(), loadAlerts()]);
  }

  async function saveParams(id, tr) {
    const fields = tr.querySelectorAll('[data-pfield]');
    for (const el of fields) {
      const field = el.dataset.pfield;
      const value = el.type === 'checkbox' ? (el.checked ? '1' : '0') : el.value;
      await updateField(id, field, value);
    }
    await loadPool();
  }

  async function recordFill(id, action) {
    const labels = { open: '建仓', add: '加仓', reduce: '减仓', clear: '清仓' };
    const priceRaw = prompt(`${labels[action]} - 成交价（元）`);
    if (priceRaw == null) return;
    const price = Number(priceRaw);
    if (!Number.isFinite(price) || price <= 0) {
      alert('成交价无效');
      return;
    }
    let lots = null;
    if (action !== 'clear') {
      const lotsRaw = prompt(`${labels[action]} - 手数（1手=100股）`, '1');
      if (lotsRaw == null) return;
      lots = Number(lotsRaw);
      if (!Number.isFinite(lots) || lots <= 0) {
        alert('手数无效');
        return;
      }
    }
    await fetchJson(`${API}/pool/${id}/fill`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action, price, lots }),
    });
    openDetails.add(String(id));
    await Promise.all([loadPool(), loadAlerts()]);
  }

  async function deleteFill(id, fillId) {
    if (!confirm('删除该成交记录并重算持仓？')) return;
    await fetchJson(`${API}/pool/${id}/fills/${fillId}`, { method: 'DELETE' });
    openDetails.add(String(id));
    await loadPool();
  }

  function toggleDetail(id, tr) {
    const detail = tr.parentElement.querySelector(`tr.tech-ai-pos-detail[data-id="${id}"]`);
    if (!detail) return;
    const willOpen = detail.style.display === 'none';
    detail.style.display = willOpen ? '' : 'none';
    if (willOpen) {
      openDetails.add(String(id));
      loadFills(id).catch(() => {});
    } else {
      openDetails.delete(String(id));
    }
  }

  async function saveRow(tr, id) {
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
      const action = btn.dataset.action;
      try {
        switch (action) {
          case 'delete': await deleteRow(id); break;
          case 'toggle-pos': toggleDetail(id, tr); break;
          case 'save': await saveRow(tr, id); break;
          case 'save-params': await saveParams(id, tr); break;
          case 'fill-open': await recordFill(id, 'open'); break;
          case 'fill-add': await recordFill(id, 'add'); break;
          case 'fill-reduce': await recordFill(id, 'reduce'); break;
          case 'fill-clear': await recordFill(id, 'clear'); break;
          case 'del-fill': await deleteFill(id, btn.dataset.fillId); break;
          default: break;
        }
      } catch (err) {
        alert(err.message);
      }
    });
  }

  document.addEventListener('DOMContentLoaded', () => {
    bindEvents();
    loadAll();
  });
})();
