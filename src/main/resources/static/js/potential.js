(function () {
  'use strict';

  const API = 'api/potential';
  const COLS = 11;
  const openDetails = new Set();

  function esc(v) {
    return String(v == null ? '' : v)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function fmtNum(v, d) {
    if (v == null || v === '') return '—';
    const n = Number(v);
    return Number.isFinite(n) ? n.toFixed(d) : '—';
  }

  function fmtMoney(v) {
    if (v == null || v === '') return '—';
    const n = Number(v);
    return Number.isFinite(n) ? n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—';
  }

  function fmtSigned(v, pct) {
    if (v == null) return '—';
    const n = Number(v);
    if (!Number.isFinite(n)) return '—';
    const cls = n >= 0 ? 'pot-up' : 'pot-down';
    const sign = n > 0 ? '+' : '';
    const pctT = pct == null ? '' : `（${sign}${Number(pct).toFixed(2)}%）`;
    return `<span class="${cls}">${sign}${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}${pctT}</span>`;
  }

  function fmtTime(v) {
    if (!v) return '—';
    return String(v).replace('T', ' ').slice(0, 19);
  }

  function inp(v, fb) {
    if (v == null || v === '') return fb;
    const n = Number(v);
    return Number.isFinite(n) ? String(n) : fb;
  }

  const ST = { none: '空仓', holding: '持仓', scaled: '减仓', exited: '离场' };

  function badge(sig) {
    if (!sig) return '';
    const m = { ADD: ['加仓', 'add'], STOP: ['清仓', 'stop'], TP: ['止盈', 'tp'] };
    const it = m[sig];
    return it ? `<span class="pot-badge pot-badge-${it[1]}">${it[0]}</span>` : '';
  }

  async function fj(url, opts) {
    const r = await fetch(url, opts);
    if (!r.ok) { const e = await r.json().catch(() => ({})); throw new Error(e.message || '请求失败'); }
    return r.json();
  }

  /* ===== main table ===== */

  async function loadAll() { await loadPool(); }

  async function loadPool() {
    const wrap = document.getElementById('poolTableWrap');
    wrap.innerHTML = '<div class="pot-empty">加载潜力池...</div>';
    try {
      const rows = await fj(`${API}/pool`);
      document.getElementById('poolCount').textContent = `${rows.length} 只`;
      if (!rows.length) { wrap.innerHTML = '<div class="pot-empty">暂无潜力股票，先加入一个标的。</div>'; return; }
      wrap.innerHTML = `
        <table class="pot-table">
          <colgroup>
            <col class="pot-col-stock" />
            <col class="pot-col-status" />
            <col class="pot-col-price" />
            <col class="pot-col-pos" />
            <col class="pot-col-pnl" />
            <col class="pot-col-next" />
            <col class="pot-col-stop" />
            <col class="pot-col-target" />
            <col class="pot-col-params" />
            <col class="pot-col-memo" />
            <col class="pot-col-actions" />
          </colgroup>
          <thead><tr>
            <th>股票</th><th>状态</th><th>现价</th><th>持仓 / 成本</th>
            <th>浮动盈亏</th><th>下一加仓</th><th>移动止损</th><th>目标止盈</th>
            <th>参数</th><th>备注</th><th>操作</th>
          </tr></thead>
          <tbody>${rows.map(renderRow).join('')}</tbody>
        </table>`;
      for (const r of rows) {
        if (openDetails.has(String(r.id))) {
          const d = wrap.querySelector(`tr.pot-detail[data-id="${r.id}"]`);
          if (d) { d.style.display = ''; loadFills(r.id).catch(() => {}); }
        }
      }
    } catch (e) { wrap.innerHTML = `<div class="pot-empty">加载失败：${esc(e.message)}</div>`; }
  }

  function renderRow(r) {
    const has = Number(r.positionLots || 0) > 0;
    const roadmap = (r.roadmap && r.roadmap.length > 0) ? r.roadmap : null;
    const posCell = has
      ? `<div class="pot-stat"><strong>${fmtNum(r.positionLots, 0)}手</strong><span>成本 ${fmtNum(r.avgCost, 2)}</span></div>`
      : (roadmap ? renderRoadmapMini(roadmap) : '—');
    const stopHtml = has
      ? (r.currentStopPrice == null ? '—' : `${fmtNum(r.currentStopPrice, 2)}${r.stopBelowCost ? ' <span class="pot-warn">低于成本</span>' : ''}`)
      : (roadmap ? renderRoadmapStop(roadmap) : '—');
    const nextHtml = has
      ? (r.nextAddPrice == null ? '—' : `<div class="pot-stat"><strong>${fmtNum(r.nextAddPrice, 2)}</strong><span>${fmtNum(r.nextAddLots, 0)}手</span></div>`)
      : (roadmap && roadmap.length > 1 ? `<div class="pot-stat"><strong>${fmtNum(roadmap[1].price, 2)}</strong><span>${fmtNum(roadmap[1].lots, 0)}手</span></div>` : '—');
    const pnlHtml = has
      ? fmtSigned(r.floatingPnl, r.floatingPnlPct)
      : (roadmap ? renderRoadmapPnl(roadmap) : '—');
    return `<tr data-id="${r.id}">
        <td class="pot-td-name">
          <div class="pot-name">${esc(r.stockName)}</div>
          <div class="pot-code">${esc(r.stockCode)}</div>
        </td>
        <td>
          <div class="pot-status-cell">
            <select class="pot-sel" data-field="status">
              <option value="watching" ${r.status==='watching'?'selected':''}>观察</option>
              <option value="holding" ${r.status==='holding'?'selected':''}>持仓</option>
              <option value="exited" ${r.status==='exited'?'selected':''}>离场</option>
            </select>
            <div class="pot-state">${ST[r.positionState]||'空仓'}</div>
            ${badge(r.pendingSignal)}
          </div>
        </td>
        <td class="pot-price">${fmtNum(r.latestPrice, 2)}</td>
        <td class="pot-td-pos">${posCell}</td>
        <td>${pnlHtml}</td>
        <td>${nextHtml}</td>
        <td>${stopHtml}</td>
        <td>${fmtNum(r.targetSellPrice, 2)}</td>
        <td class="pot-td-params">
          <span class="pot-p"><em>步</em>${esc(inp(r.addStepPct,'10'))}%</span>
          <span class="pot-p"><em>撤</em>${esc(inp(r.trailPct,'10'))}%</span>
          <span class="pot-p"><em>目标</em>${r.targetSellPrice?fmtNum(r.targetSellPrice,0):'—'}</span>
        </td>
        <td><input class="pot-input" data-field="memo" value="${esc(r.memo||'')}" placeholder="备注" /></td>
        <td>
          <div class="pot-acts">
            <button class="pot-btn" data-action="toggle">展开</button>
            <button class="pot-btn" data-action="save">保存</button>
            <button class="pot-btn pot-btn-del" data-action="delete">删除</button>
          </div>
        </td>
      </tr>
      ${renderDetail(r)}`;
  }

  /** watching: roadmap as compact lines */
  function renderRoadmapMini(rm) {
    return rm.map(l => {
      const warn = l.stopBelowCost ? ' <span class="pot-warn">亏</span>' : '';
      return `<div class="pot-rm-line"><span class="pot-rm-label">${l.label}</span><strong>${fmtNum(l.price,2)}</strong><span>止损 ${fmtNum(l.stopPrice,2)}${warn}</span><small>${fmtNum(l.totalLots,0)}手 / 均价 ${fmtNum(l.avgCost,2)}</small></div>`;
    }).join('');
  }

  function renderRoadmapStop(rm) {
    const first = rm[0];
    if (!first) return '—';
    const warn = first.stopBelowCost ? ' <span class="pot-warn">低于成本</span>' : '';
    return `${fmtNum(first.stopPrice, 2)}${warn}`;
  }

  function renderRoadmapPnl(rm) {
    if (!rm.length) return '—';
    const entry = rm[0].price;
    return `<span class="pot-sub">入场${fmtNum(entry,2)}</span>`;
  }

  function paramRow(label, field, value, step, isText) {
    if (isText) return `<label><span>${label}</span><input type="text" data-pfield="${field}" value="${esc(value==null?'':value)}" /></label>`;
    return `<label><span>${label}</span><input type="number" min="0" step="${step||'0.01'}" data-pfield="${field}" value="${esc(value==null?'':value)}" /></label>`;
  }

  function renderDetail(r) {
    const open = openDetails.has(String(r.id));
    return `
      <tr class="pot-detail" data-id="${r.id}" style="display:${open?'':'none'}">
        <td colspan="${COLS}">
          <div class="pot-detail-wrap">
            <div class="pot-detail-row">
              <div class="pot-metrics">
                <div class="pot-m"><span>首仓价</span><strong>${fmtNum(r.entryPrice,2)}</strong></div>
                <div class="pot-m"><span>目标止盈</span><strong>${fmtNum(r.targetSellPrice,2)}</strong></div>
                <div class="pot-m"><span>总投入</span><strong>${fmtMoney(r.totalInvested)}</strong></div>
                <div class="pot-m"><span>已实现盈亏</span><strong>${fmtSigned(r.realizedPnl,null)}</strong></div>
                <div class="pot-m"><span>建仓后最高</span><strong>${fmtNum(r.peakPrice,2)}</strong></div>
                <div class="pot-m"><span>加仓次数</span><strong>${r.addCount==null?'0':r.addCount}</strong></div>
                ${r.useAtr?`<div class="pot-m"><span>ATR</span><strong>${fmtNum(r.atrValue,2)}</strong></div>`:''}
              </div>
              <div class="pot-fill-btns">
                <button class="pot-btn pot-btn-buy" data-action="fill-open">建仓</button>
                <button class="pot-btn pot-btn-buy" data-action="fill-add">加仓</button>
                <button class="pot-btn pot-btn-sell" data-action="fill-reduce">减仓</button>
                <button class="pot-btn pot-btn-sell" data-action="fill-clear">清仓</button>
              </div>
            </div>
            <div class="pot-detail-row">
              <div class="pot-adv-params">
                ${paramRow('加仓步长%','addStepPct',r.addStepPct,'0.1')}
                ${paramRow('移动止损%','trailPct',r.trailPct,'0.1')}
                ${paramRow('目标止盈价','targetSellPrice',r.targetSellPrice,'0.01')}
                ${paramRow('加仓手数表','addSizeSchedule',r.addSizeSchedule,null,true)}
                ${paramRow('最大手数','maxLots',r.maxLots,'1')}
                ${paramRow('止盈比例%','takeProfitPct',r.takeProfitPct,'1')}
                ${paramRow('时间止损天','timeStopDays',r.timeStopDays,'1')}
                <label class="pot-check"><span>启用ATR模式</span><input type="checkbox" data-pfield="useAtr" ${r.useAtr?'checked':''} /></label>
                ${paramRow('ATR周期(日)','atrPeriod',r.atrPeriod,'1')}
                ${paramRow('ATR加仓倍数','atrAddMult',r.atrAddMult,'0.1')}
                ${paramRow('ATR止损倍数','atrTrailMult',r.atrTrailMult,'0.1')}
              </div>
              <button class="pot-btn" data-action="save-params">保存参数</button>
            </div>
            <div class="pot-help">
              <strong>ATR模式</strong>
              <span>关闭：下一加仓价 = 上次买入价 × (1 + 加仓步长%)，移动止损 = 建仓后最高价 × (1 - 移动止损%)。</span>
              <span>启用：下一加仓价 = 上次买入价 + ATR × 加仓倍数，移动止损 = 建仓后最高价 - ATR × 止损倍数。</span>
            </div>
            <div class="pot-fills" data-fills-id="${r.id}"><div class="pot-empty">加载流水...</div></div>
          </div>
        </td>
      </tr>`;
  }

  async function loadFills(id) {
    const box = document.querySelector(`.pot-fills[data-fills-id="${id}"]`);
    if (!box) return;
    try {
      const fills = await fj(`${API}/pool/${id}/fills`);
      if (!fills.length) { box.innerHTML = '<div class="pot-empty">暂无成交流水。</div>'; return; }
      const al = { open:'建仓', add:'加仓', reduce:'减仓', clear:'清仓' };
      box.innerHTML = `<table class="pot-fills-tbl">
        <thead><tr><th>时间</th><th>操作</th><th>价格</th><th>手数</th><th>金额</th><th></th></tr></thead>
        <tbody>${fills.map(f=>`<tr>
          <td>${fmtTime(f.filledAt)}</td><td>${al[f.action]||esc(f.action)}</td>
          <td>${fmtNum(f.price,2)}</td><td>${fmtNum(f.lots,0)}</td><td>${fmtMoney(f.amount)}</td>
          <td><button class="pot-link" data-action="del-fill" data-fill-id="${f.id}">删</button></td>
        </tr>`).join('')}</tbody></table>`;
    } catch (e) { box.innerHTML = `<div class="pot-empty">加载失败：${esc(e.message)}</div>`; }
  }

  /* ===== actions ===== */

  async function addStock() {
    const input = document.getElementById('stockInput');
    const kw = input.value.trim();
    if (!kw) { alert('请输入股票代码或名称'); return; }
    await fj(`${API}/pool`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({keyword:kw,status:'watching'}) });
    input.value = '';
    await loadPool();
  }

  async function uf(id, f, v) {
    await fj(`${API}/pool/${id}/field`, { method:'PATCH', headers:{'Content-Type':'application/json'}, body:JSON.stringify({field:f,value:v}) });
  }

  async function del(id) {
    if (!confirm('确定删除？流水一并删除。')) return;
    await fj(`${API}/pool/${id}`, {method:'DELETE'});
    openDetails.delete(String(id));
    await loadPool();
  }

  async function saveRow(tr, id) {
    const memo = tr.querySelector('[data-field="memo"]').value;
    const status = tr.querySelector('[data-field="status"]').value;
    await uf(id, 'memo', memo);
    await uf(id, 'status', status);
    await loadPool();
  }

  async function saveParams(id, tr) {
    const detail = tr.closest('tbody')?.querySelector(`tr.pot-detail[data-id="${id}"]`) || tr.closest('tr.pot-detail');
    if (!detail) return;
    for (const el of detail.querySelectorAll('[data-pfield]')) {
      const f = el.dataset.pfield;
      const v = el.type === 'checkbox' ? (el.checked ? '1' : '0') : el.value;
      await uf(id, f, v);
    }
    await loadPool();
  }

  async function recordFill(id, action) {
    const lb = { open:'建仓', add:'加仓', reduce:'减仓', clear:'清仓' };
    const pr = prompt(`${lb[action]} - 成交价（元）`);
    if (pr == null) return;
    const price = Number(pr);
    if (!Number.isFinite(price) || price <= 0) { alert('成交价无效'); return; }
    let lots = null;
    if (action !== 'clear') {
      const lr = prompt(`${lb[action]} - 手数（1手=100股）`, '1');
      if (lr == null) return;
      lots = Number(lr);
      if (!Number.isFinite(lots) || lots <= 0) { alert('手数无效'); return; }
    }
    await fj(`${API}/pool/${id}/fill`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({action,price,lots}) });
    openDetails.add(String(id));
    await loadPool();
  }

  async function deleteFill(id, fid) {
    if (!confirm('删除该流水并重算持仓？')) return;
    await fj(`${API}/pool/${id}/fills/${fid}`, {method:'DELETE'});
    openDetails.add(String(id));
    await loadPool();
  }

  function toggle(id, tr) {
    const d = tr.parentElement.querySelector(`tr.pot-detail[data-id="${id}"]`);
    if (!d) return;
    const open = d.style.display === 'none';
    d.style.display = open ? '' : 'none';
    if (open) { openDetails.add(String(id)); loadFills(id).catch(()=>{}); }
    else { openDetails.delete(String(id)); }
  }

  async function runMonitor() {
    const btn = document.getElementById('runMonitorBtn');
    btn.disabled = true; btn.textContent = '扫描中...';
    try {
      const r = await fj(`${API}/monitor/run`, {method:'POST'});
      alert(`扫描完成，触发 ${r.triggered||0} 条信号`);
      await loadPool();
    } catch (e) { alert(e.message); }
    finally { btn.disabled = false; btn.textContent = '手动扫描'; }
  }

  function bindEvents() {
    document.getElementById('refreshBtn')?.addEventListener('click', loadAll);
    document.getElementById('runMonitorBtn')?.addEventListener('click', runMonitor);
    document.getElementById('addStockBtn')?.addEventListener('click', () => addStock().catch(e => alert(e.message)));
    document.getElementById('stockInput')?.addEventListener('keydown', e => { if (e.key==='Enter') addStock().catch(err => alert(err.message)); });
    document.getElementById('poolTableWrap')?.addEventListener('click', async e => {
      const btn = e.target.closest('button[data-action]');
      if (!btn) return;
      const tr = btn.closest('tr');
      const id = tr?.dataset.id;
      if (!id) return;
      const act = btn.dataset.action;
      try {
        switch (act) {
          case 'delete': await del(id); break;
          case 'toggle': toggle(id, tr); break;
          case 'save': await saveRow(tr, id); break;
          case 'save-params': await saveParams(id, tr); break;
          case 'fill-open': await recordFill(id, 'open'); break;
          case 'fill-add': await recordFill(id, 'add'); break;
          case 'fill-reduce': await recordFill(id, 'reduce'); break;
          case 'fill-clear': await recordFill(id, 'clear'); break;
          case 'del-fill': await deleteFill(id, btn.dataset.fillId); break;
        }
      } catch (err) { alert(err.message); }
    });
  }

  document.addEventListener('DOMContentLoaded', () => { bindEvents(); loadAll(); });
})();
