(function () {
    'use strict';

    const API = '/gp/api/monitor';
    const $  = (sel) => document.querySelector(sel);
    const $$ = (sel) => Array.from(document.querySelectorAll(sel));
    let currentPool = 'all';

    async function fetchPool() {
        const url = currentPool === 'all'
            ? `${API}/pool`
            : `${API}/pool?poolType=${currentPool}`;
        try {
            const rows = await fetch(url).then((r) => r.json());
            return Array.isArray(rows) ? rows : [];
        } catch (e) {
            console.error('fetchPool failed', e);
            return [];
        }
    }

    function renderRow(r) {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><strong>${escape(r.stockName || r.stockCode)}</strong>
                <small>${r.stockCode}</small>
                <span class="pool-badge pool-badge-${r.poolType}">${r.poolType || ''}</span></td>
            <td>${fmt(r.latestPrice)} <small class="${changeClass(r.dailyChangePct)}">${fmtPct(r.dailyChangePct)}</small></td>
            <td><input type="number" step="0.01" data-field="fixedBuyPrice" value="${r.fixedBuyPrice ?? ''}" placeholder="未设"> <input type="checkbox" data-field="fixedBuyEnabled" ${r.fixedBuyEnabled ? 'checked' : ''}></td>
            <td><input type="number" step="0.01" data-field="fixedSellPrice" value="${r.fixedSellPrice ?? ''}" placeholder="未设"> <input type="checkbox" data-field="fixedSellEnabled" ${r.fixedSellEnabled ? 'checked' : ''}></td>
            <td><input type="number" step="0.1" data-field="atrAlertAmplitude" value="${r.atrAlertAmplitude ?? ''}" placeholder="1.5">× <input type="checkbox" data-field="atrAlertEnabled" ${r.atrAlertEnabled ? 'checked' : ''}></td>
            <td><input type="number" step="0.1" data-field="takeProfitPct" value="${r.takeProfitPct ?? ''}" placeholder="20">%</td>
            <td><input type="number" step="0.1" data-field="stopLossPct" value="${r.stopLossPct ?? ''}" placeholder="-8">%</td>
            <td><select data-field="serverchanTemplate">
                <option ${(r.serverchanTemplate||'standard')==='standard'?'selected':''}>standard</option>
                <option ${r.serverchanTemplate==='compact'?'selected':''}>compact</option>
                <option ${r.serverchanTemplate==='verbose'?'selected':''}>verbose</option>
            </select></td>
            <td><small>${r.lastAlertAt || '—'}</small></td>
        `;
        // attach code/pool to all field inputs
        tr.querySelectorAll('[data-field]').forEach((el) => {
            el.dataset.code = r.stockCode;
            el.dataset.pool = r.poolType || 'tech_ai';
        });
        return tr;
    }

    async function loadPool() {
        const rows = await fetchPool();
        const tbody = $('#monitor-tbody');
        tbody.innerHTML = '';
        rows.forEach((r) => tbody.appendChild(renderRow(r)));
        $('#last-update').textContent = `已加载 ${rows.length} 只 · ${new Date().toLocaleTimeString()}`;
    }

    function bindPoolPicker() {
        $$('.monitor-pool-picker button[data-pool]').forEach((btn) => {
            btn.addEventListener('click', () => {
                $$('.monitor-pool-picker button').forEach((b) => b.classList.remove('active'));
                btn.classList.add('active');
                currentPool = btn.dataset.pool;
                loadPool();
            });
        });
    }

    function bindAddForm() {
        $('#add-form').addEventListener('submit', async (e) => {
            e.preventDefault();
            const fd = new FormData(e.target);
            const body = {
                stockCode: fd.get('stockCode'),
                poolType:  fd.get('poolType'),
            };
            try {
                const r = await fetch(`${API}/pool`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body),
                });
                if (!r.ok) throw new Error('add failed');
                e.target.reset();
                loadPool();
            } catch (err) {
                alert('添加失败：' + err.message);
            }
        });
    }

    function bindFieldChange() {
        document.body.addEventListener('change', async (e) => {
            const el = e.target;
            if (!el.dataset || !el.dataset.field) return;
            const value = el.type === 'checkbox' ? (el.checked ? 1 : 0) : el.value;
            const body = { field: el.dataset.field, value };
            try {
                await fetch(`${API}/pool/${el.dataset.code}/${el.dataset.pool}/field`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body),
                });
            } catch (err) {
                console.error('field update failed', err);
            }
        });
    }

    function bindManualRun() {
        $('#run-now').addEventListener('click', async () => {
            const btn = $('#run-now');
            btn.disabled = true;
            btn.textContent = '扫描中...';
            try {
                const r = await fetch(`${API}/run?poolType=${currentPool === 'all' ? '' : currentPool}`, {
                    method: 'POST',
                });
                const data = await r.json().catch(() => ({}));
                alert(`扫描完成 · 触发 ${data.triggered ?? '?'} 条`);
                loadPool();
            } catch (err) {
                alert('扫描失败：' + err.message);
            } finally {
                btn.disabled = false;
                btn.textContent = '手动扫描';
            }
        });
    }

    function bindAlertsRefresh() {
        // 简单使用最近告警列表 — 从 invest_alert 表由后端聚合
        const el = $('#monitor-alerts');
        if (!el) return;
        el.innerHTML = '<li class="muted">告警实时写入 invest_alert 表，通过 <code>/gp/api/invest-alerts</code> 或 Server酱 查看。</li>';
    }

    /* helpers */
    function escape(s) { return (s == null ? '' : String(s)).replace(/[<>]/g, (c) => ({ '<':'&lt;','>':'&gt;' }[c])); }
    function fmt(v)    { return (v == null || v === '') ? '-' : v; }
    function fmtPct(v) { return (v == null || v === '') ? '-' : `${Number(v).toFixed(2)}%`; }
    function changeClass(v) {
        if (v == null || v === '') return '';
        return Number(v) >= 0 ? 'up' : 'down';
    }

    bindPoolPicker();
    bindAddForm();
    bindFieldChange();
    bindManualRun();
    bindAlertsRefresh();
    loadPool();
    setInterval(loadPool, 60000);
})();
