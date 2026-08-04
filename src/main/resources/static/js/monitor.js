(function () {
    'use strict';

    const API = '/gp/api/monitor';
    const $  = (sel) => document.querySelector(sel);
    const $$ = (sel) => Array.from(document.querySelectorAll(sel));
    let currentPool = 'all';

    function authHeaders(extra) {
        const h = Object.assign({ 'Content-Type': 'application/json', 'Accept': 'application/json' }, extra || {});
        try {
            const t = localStorage.getItem('token');
            if (t) h['Authorization'] = 'Bearer ' + t;
        } catch (_) { /* ignore */ }
        return h;
    }

    async function fetchPool() {
        const url = currentPool === 'all'
            ? `${API}/pool`
            : `${API}/pool?poolType=${encodeURIComponent(currentPool)}`;
        try {
            const r = await fetch(url, { headers: authHeaders() });
            const rows = await r.json();
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
                <small>${escape(r.stockCode)}</small>
                <span class="pool-badge pool-badge-${escape(r.poolType)}">${escape(r.poolType || '')}</span></td>
            <td>${fmt(r.latestPrice)} <small class="${changeClass(r.dailyChangePct)}">${fmtPct(r.dailyChangePct)}</small></td>
            <td><input type="number" step="0.01" data-field="entryPrice" value="${r.entryPrice ?? ''}" placeholder="成本"></td>
            <td><input type="number" step="0.01" data-field="fixedBuyPrice" value="${r.fixedBuyPrice ?? ''}" placeholder="未设"> <input type="checkbox" data-field="fixedBuyEnabled" ${r.fixedBuyEnabled ? 'checked' : ''}></td>
            <td><input type="number" step="0.01" data-field="fixedSellPrice" value="${r.fixedSellPrice ?? ''}" placeholder="未设"> <input type="checkbox" data-field="fixedSellEnabled" ${r.fixedSellEnabled ? 'checked' : ''}></td>
            <td><input type="number" step="0.1" data-field="atrAlertAmplitude" value="${r.atrAlertAmplitude ?? ''}" placeholder="1.5">× <input type="checkbox" data-field="atrAlertEnabled" ${r.atrAlertEnabled ? 'checked' : ''}></td>
            <td><input type="number" step="0.1" data-field="takeProfitPct" value="${r.takeProfitPct ?? ''}" placeholder="20">%</td>
            <td><input type="number" step="0.1" data-field="stopLossPct" value="${r.stopLossPct ?? ''}" placeholder="-8">%</td>
            <td><select data-field="monitorMode">
                <option value="standard" ${(r.monitorMode||'standard')==='standard'?'selected':''}>standard</option>
                <option value="fixed_only" ${r.monitorMode==='fixed_only'?'selected':''}>fixed_only</option>
                <option value="atr_strict" ${r.monitorMode==='atr_strict'?'selected':''}>atr_strict</option>
            </select></td>
            <td><select data-field="serverchanTemplate">
                <option value="standard" ${(r.serverchanTemplate||'standard')==='standard'?'selected':''}>standard</option>
                <option value="compact" ${r.serverchanTemplate==='compact'?'selected':''}>compact</option>
                <option value="verbose" ${r.serverchanTemplate==='verbose'?'selected':''}>verbose</option>
            </select></td>
            <td><small>${escape(r.lastAlertAt || '—')}</small></td>
            <td><button type="button" class="btn-del" data-del-code="${escape(r.stockCode)}" data-del-pool="${escape(r.poolType || 'tech_ai')}">删除</button></td>
        `;
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
                $$('.monitor-pool-picker button[data-pool]').forEach((b) => b.classList.remove('active'));
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
            const resultEl = $('#add-result');
            try {
                const r = await fetch(`${API}/pool`, {
                    method: 'POST',
                    headers: authHeaders(),
                    body: JSON.stringify(body),
                });
                const data = await r.json().catch(() => ({}));
                if (!r.ok) throw new Error(data.message || data.error || 'add failed');
                resultEl.textContent = `完成：新增 ${data.added ?? 0}，已存在 ${data.skipped ?? 0}，失败 ${data.failed ?? 0}`;
                e.target.reset();
                loadPool();
            } catch (err) {
                resultEl.textContent = '添加失败：' + err.message;
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
                const r = await fetch(`${API}/pool/${encodeURIComponent(el.dataset.code)}/${encodeURIComponent(el.dataset.pool)}/field`, {
                    method: 'PATCH',
                    headers: authHeaders(),
                    body: JSON.stringify(body),
                });
                if (!r.ok) {
                    const data = await r.json().catch(() => ({}));
                    alert('更新失败：' + (data.message || r.status));
                }
            } catch (err) {
                console.error('field update failed', err);
                alert('更新失败：' + err.message);
            }
        });
    }

    function bindDelete() {
        document.body.addEventListener('click', async (e) => {
            const btn = e.target.closest('[data-del-code]');
            if (!btn) return;
            const code = btn.dataset.delCode;
            const pool = btn.dataset.delPool;
            if (!confirm(`确认删除 ${code}（${pool}）的监控？`)) return;
            try {
                const r = await fetch(`${API}/pool/${encodeURIComponent(code)}/${encodeURIComponent(pool)}`, {
                    method: 'DELETE',
                    headers: authHeaders(),
                });
                if (!r.ok) throw new Error('delete failed');
                loadPool();
            } catch (err) {
                alert('删除失败：' + err.message);
            }
        });
    }

    function bindManualRun() {
        $('#run-now').addEventListener('click', async () => {
            const btn = $('#run-now');
            btn.disabled = true;
            btn.textContent = '扫描中...';
            try {
                const q = currentPool === 'all' ? '' : `?poolType=${encodeURIComponent(currentPool)}`;
                const r = await fetch(`${API}/run${q}`, {
                    method: 'POST',
                    headers: authHeaders(),
                });
                const data = await r.json().catch(() => ({}));
                alert(`扫描完成 · 触发 ${data.triggered ?? '?'} 条`);
                loadPool();
                loadAlerts();
            } catch (err) {
                alert('扫描失败：' + err.message);
            } finally {
                btn.disabled = false;
                btn.textContent = '手动扫描';
            }
        });
    }

    async function loadAlerts() {
        const el = $('#monitor-alerts');
        if (!el) return;
        try {
            const r = await fetch(`${API}/alerts`, { headers: authHeaders() });
            const rows = await r.json();
            if (!Array.isArray(rows) || rows.length === 0) {
                el.innerHTML = '<li class="muted">暂无告警</li>';
                return;
            }
            el.innerHTML = rows.map((a) =>
                `<li><strong>${escape(a.title || a.signalType)}</strong>
                 <small class="muted"> · ${escape(a.stockCode || '')} · ${escape(a.triggerAt || '')}</small></li>`
            ).join('');
        } catch (e) {
            el.innerHTML = '<li class="muted">告警加载失败</li>';
        }
    }

    /* helpers */
    function escape(s) { return (s == null ? '' : String(s)).replace(/[<>&"']/g, (c) => ({ '<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;',"'":'&#39;' }[c])); }
    function fmt(v)    { return (v == null || v === '') ? '-' : v; }
    function fmtPct(v) { return (v == null || v === '') ? '-' : `${Number(v).toFixed(2)}%`; }
    function changeClass(v) {
        if (v == null || v === '') return '';
        return Number(v) >= 0 ? 'up' : 'down';
    }

    bindPoolPicker();
    bindAddForm();
    bindFieldChange();
    bindDelete();
    bindManualRun();
    loadPool();
    loadAlerts();
    setInterval(loadPool, 60000);
    setInterval(loadAlerts, 60000);
})();
