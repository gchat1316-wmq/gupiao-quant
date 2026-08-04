(function () {
    'use strict';

    const API = '/gp/api/etf-model';
    const $ = (sel) => document.querySelector(sel);
    const $$ = (sel) => Array.from(document.querySelectorAll(sel));

    const TRADE_TYPES = {
        BUY: [
            ['OPEN', '建仓（第1批）'],
            ['ADD', '加仓（第2/3批）'],
            ['T_TRADE', '做T买回'],
            ['RECOUP', '回补'],
            ['OTHER', '其他'],
        ],
        SELL: [
            ['TP1', '止盈+5% 减1/3'],
            ['TP2', '止盈+10% 再减1/3'],
            ['TRAIL_EXIT', '移动止盈清仓（破20日线）'],
            ['SL1', '止损第一档 减半'],
            ['SL2', '止损第二档 再减半/清仓'],
            ['GUARD_CUT', '保命线降1/4'],
            ['T_TRADE', '做T卖出'],
            ['OTHER', '其他'],
        ],
    };
    const TYPE_LABELS = {};
    TRADE_TYPES.BUY.concat(TRADE_TYPES.SELL).forEach(([v, l]) => { TYPE_LABELS[v] = l; });

    let poolCache = [];
    let navChart = null;

    function headers(json) {
        const h = (window.GPAuth && GPAuth.headers()) || {};
        if (json) h['Content-Type'] = 'application/json';
        return h;
    }

    async function api(path, options) {
        const r = await fetch(API + path, options);
        if (!r.ok) {
            let msg = 'HTTP ' + r.status;
            try {
                const body = await r.json();
                msg = body.message || body.error || msg;
            } catch (e) { /* ignore */ }
            throw new Error(msg);
        }
        return r.json();
    }

    /* ═══════ 持仓总览 + 摘要 ═══════ */

    async function loadOverview() {
        let data;
        try {
            data = await api('/overview', { headers: headers() });
        } catch (e) {
            $('#positions-tbody').innerHTML =
                `<tr><td colspan="12" class="muted">加载失败：${esc(e.message)}（需管理员登录）</td></tr>`;
            return;
        }
        const s = data.summary || {};
        $('#sum-total').textContent = money(s.totalAsset);
        $('#sum-mv').textContent = money(s.marketValue);
        $('#sum-cash').textContent = money(s.cash);
        $('#sum-pos-pct').textContent = pct(s.positionPct);
        $('#sum-dd').textContent = pct(s.drawdownPct);

        const banner = $('#etf-guard-banner');
        if (s.inCalm) {
            banner.hidden = false;
            banner.textContent = `🚨 组合保命线冷静期至 ${s.calmUntil} — 按纪律暂缓买入/加仓（提醒照发并附冷静标注）`;
        } else {
            banner.hidden = true;
        }

        const tbody = $('#positions-tbody');
        tbody.innerHTML = '';
        (data.positions || []).forEach((p) => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><strong>${esc(p.stockName || p.stockCode)}</strong><small>${esc(p.stockCode)}</small></td>
                <td>${catBadge(p.category)}</td>
                <td>${num(p.latestPrice, 3)} <small class="${cls(p.dailyChangePct)}">${pct(p.dailyChangePct)}</small></td>
                <td>${p.shares || 0}</td>
                <td>${num(p.dilutedCost, 3)}</td>
                <td class="${cls(p.profitPct)}"><strong>${pct(p.profitPct)}</strong></td>
                <td>${money(p.marketValue)}<small>投入 ${money(p.netInvested)}</small></td>
                <td>${p.batchesUsed || 0}/3</td>
                <td>${tierBadges(p)}</td>
                <td>${num(p.ma5, 3)} / ${num(p.ma20, 3)}<small>近20日 ${pct(p.rise20Pct)}</small></td>
                <td>${tierAdvice(p)}</td>
                <td>${recoupBadge(p.recoupStatus)}</td>
            `;
            tbody.appendChild(tr);
        });
        if (!(data.positions || []).length) {
            tbody.innerHTML = '<tr><td colspan="12" class="muted">池子为空，请先在「ETF池管理」中添加。</td></tr>';
        }
    }

    function tierBadges(p) {
        return [
            badge('+5%减⅓', p.tp1Done, 'on-tp'),
            badge('+10%再减⅓', p.tp2Done, 'on-tp'),
            badge(p.category === 'BROAD' ? '-15%减半' : '-10%减半', p.sl1Done, 'on-sl'),
            badge(p.category === 'BROAD' ? '-30%再减半' : '-18%清仓', p.sl2Done, 'on-sl'),
        ].join('');
    }
    function badge(label, on, onCls) {
        return `<span class="etf-badge ${on ? onCls : ''}">${label}${on ? ' ✓' : ''}</span>`;
    }
    function catBadge(cat) {
        return cat === 'BROAD'
            ? '<span class="etf-badge cat-broad">宽基</span>'
            : '<span class="etf-badge cat-sector">行业</span>';
    }
    function tierAdvice(p) {
        if (!p.buyTier) return `<small class="muted">${esc(p.buyTierReason || '-')}</small>`;
        const b = p.buyTier === 'LIGHT'
            ? '<span class="etf-badge tier-light">轻仓 ≤5000</span>'
            : '<span class="etf-badge tier-mid">中仓 1~2万</span>';
        return `${b}<small>${esc(p.buyTierReason || '')}</small>`;
    }
    function recoupBadge(st) {
        if (st === 'WAITING') return '<span class="etf-badge recoup-waiting">待回补</span>';
        if (st === 'READY') return '<span class="etf-badge recoup-ready">可回补</span>';
        return '<span class="muted">—</span>';
    }

    /* ═══════ 交易记录 ═══════ */

    async function loadTrades() {
        let rows = [];
        try {
            rows = await api('/trades', { headers: headers() });
        } catch (e) { /* 未登录等 */ }
        const tbody = $('#trades-tbody');
        tbody.innerHTML = '';
        rows.forEach((t) => {
            const pool = poolCache.find((p) => p.id === t.poolId);
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${esc((t.tradeTime || '').replace('T', ' ').slice(0, 16))}</td>
                <td><strong>${esc(pool ? pool.stockName : t.stockCode)}</strong><small>${esc(t.stockCode)}</small></td>
                <td class="${t.direction === 'BUY' ? 'up' : 'down'}">${t.direction === 'BUY' ? '买入' : '卖出'}</td>
                <td>${esc(TYPE_LABELS[t.tradeType] || t.tradeType)}</td>
                <td>${num(t.price, 3)}</td>
                <td>${t.shares}</td>
                <td>${money(t.amount)}</td>
                <td>${esc(t.source || 'MANUAL')}</td>
                <td>${esc(t.memo || '')}</td>
                <td><button class="etf-del-btn" data-del-trade="${t.id}">删除</button></td>
            `;
            tbody.appendChild(tr);
        });
        if (!rows.length) {
            tbody.innerHTML = '<tr><td colspan="10" class="muted">暂无交易记录。</td></tr>';
        }
    }

    function refreshTradeTypeOptions() {
        const dir = $('#trade-direction').value;
        $('#trade-type').innerHTML = TRADE_TYPES[dir]
            .map(([v, l]) => `<option value="${v}">${l}</option>`)
            .join('');
    }

    function bindTradeForm() {
        $('#trade-direction').addEventListener('change', refreshTradeTypeOptions);
        $('#trade-form').addEventListener('submit', async (e) => {
            e.preventDefault();
            const fd = new FormData(e.target);
            const body = {
                poolId: Number(fd.get('poolId')),
                direction: fd.get('direction'),
                tradeType: fd.get('tradeType'),
                price: Number(fd.get('price')),
                shares: Number(fd.get('shares')),
            };
            if (fd.get('amount')) body.amount = Number(fd.get('amount'));
            if (fd.get('tradeTime')) body.tradeTime = fd.get('tradeTime') + ':00';
            if (fd.get('memo')) body.memo = fd.get('memo');
            try {
                const result = await api('/trades', {
                    method: 'POST', headers: headers(true), body: JSON.stringify(body),
                });
                const warnBox = $('#trade-warnings');
                if (result.warnings && result.warnings.length) {
                    warnBox.hidden = false;
                    warnBox.innerHTML = '<strong>⚠️ 纪律提醒（已保存）：</strong><br>' +
                        result.warnings.map(esc).join('<br>');
                } else {
                    warnBox.hidden = true;
                }
                e.target.reset();
                refreshTradeTypeOptions();
                loadTrades();
                loadOverview();
            } catch (err) {
                alert('保存失败：' + err.message);
            }
        });
        document.body.addEventListener('click', async (e) => {
            const id = e.target.dataset && e.target.dataset.delTrade;
            if (!id) return;
            if (!confirm('删除该笔交易？档位状态将按剩余流水重算。')) return;
            try {
                await api('/trades/' + id, { method: 'DELETE', headers: headers() });
                loadTrades();
                loadOverview();
            } catch (err) {
                alert('删除失败：' + err.message);
            }
        });
    }

    /* ═══════ ETF池管理 ═══════ */

    async function loadPool() {
        try {
            poolCache = await api('/pool', { headers: headers() });
        } catch (e) {
            poolCache = [];
        }
        $('#trade-pool').innerHTML = poolCache
            .map((p) => `<option value="${p.id}">${esc(p.stockName || p.stockCode)} (${esc(p.stockCode)})</option>`)
            .join('');
        const tbody = $('#pool-tbody');
        tbody.innerHTML = '';
        poolCache.forEach((p) => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${esc(p.stockCode)}</td>
                <td><input data-pool-field="stockName" data-pool-id="${p.id}" value="${esc(p.stockName || '')}"></td>
                <td><select data-pool-field="category" data-pool-id="${p.id}">
                    <option value="BROAD" ${p.category === 'BROAD' ? 'selected' : ''}>宽基</option>
                    <option value="SECTOR" ${p.category === 'SECTOR' ? 'selected' : ''}>行业/主题</option>
                </select></td>
                <td><input data-pool-field="memo" data-pool-id="${p.id}" value="${esc(p.memo || '')}"></td>
                <td><button class="etf-del-btn" data-del-pool="${p.id}">移除</button></td>
            `;
            tbody.appendChild(tr);
        });
    }

    function bindPoolForm() {
        $('#pool-form').addEventListener('submit', async (e) => {
            e.preventDefault();
            const fd = new FormData(e.target);
            try {
                await api('/pool', {
                    method: 'POST', headers: headers(true),
                    body: JSON.stringify({
                        stockCode: fd.get('stockCode'),
                        stockName: fd.get('stockName'),
                        category: fd.get('category'),
                        memo: fd.get('memo') || null,
                    }),
                });
                e.target.reset();
                loadPool();
                loadOverview();
            } catch (err) {
                alert('添加失败：' + err.message);
            }
        });
        document.body.addEventListener('change', async (e) => {
            const el = e.target;
            if (!el.dataset || !el.dataset.poolField) return;
            try {
                await api('/pool/' + el.dataset.poolId, {
                    method: 'PATCH', headers: headers(true),
                    body: JSON.stringify({ [el.dataset.poolField]: el.value }),
                });
                loadOverview();
            } catch (err) {
                alert('修改失败：' + err.message);
            }
        });
        document.body.addEventListener('click', async (e) => {
            const id = e.target.dataset && e.target.dataset.delPool;
            if (!id) return;
            if (!confirm('从池中移除该 ETF？（有交易记录时仅隐藏，流水保留）')) return;
            try {
                await api('/pool/' + id, { method: 'DELETE', headers: headers() });
                loadPool();
                loadOverview();
            } catch (err) {
                alert('移除失败：' + err.message);
            }
        });
    }

    /* ═══════ 净值曲线 ═══════ */

    async function loadNav() {
        let rows = [];
        try {
            rows = await api('/nav', { headers: headers() });
        } catch (e) { /* ignore */ }
        const ctx = $('#nav-chart');
        if (navChart) navChart.destroy();
        navChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: rows.map((r) => r.snapDate),
                datasets: [
                    {
                        label: '总资产',
                        data: rows.map((r) => r.totalAsset),
                        borderColor: '#2f7d5d',
                        backgroundColor: 'rgba(47,125,93,0.08)',
                        fill: true,
                        tension: 0.2,
                    },
                    {
                        label: '历史最高',
                        data: rows.map((r) => r.peakAsset),
                        borderColor: '#b48a3a',
                        borderDash: [6, 4],
                        pointRadius: 0,
                    },
                ],
            },
            options: { responsive: true, interaction: { mode: 'index', intersect: false } },
        });
    }

    /* ═══════ 提醒历史 ═══════ */

    async function loadAlerts() {
        let rows = [];
        try {
            rows = await api('/alerts', { headers: headers() });
        } catch (e) { /* ignore */ }
        const ul = $('#alerts-list');
        ul.innerHTML = '';
        rows.forEach((a) => {
            const li = document.createElement('li');
            li.innerHTML = `<strong>${esc(a.title || a.signalType)}</strong>` +
                `<span class="t">${esc((a.triggerAt || '').replace('T', ' ').slice(0, 16))}` +
                `${a.pushed ? ' · 已推送' : ' · 未推送'}</span>`;
            ul.appendChild(li);
        });
        if (!rows.length) {
            ul.innerHTML = '<li class="muted">暂无提醒记录。</li>';
        }
    }

    /* ═══════ 模型参数 ═══════ */

    async function loadConfig() {
        let cfg;
        try {
            cfg = await api('/config', { headers: headers() });
        } catch (e) {
            return;
        }
        const form = $('#config-form');
        ['totalCapital', 'singleMaxPct', 'portfolioMaxPct', 'lightBatchMaxAmount',
         'midBatchMinAmount', 'midBatchMaxAmount', 'bigRiseThresholdPct',
         'portfolioDrawdownPct', 'calmDays', 'inceptionDate'].forEach((k) => {
            if (form.elements[k] && cfg[k] != null) form.elements[k].value = cfg[k];
        });
        $('#btn-clear-calm').hidden = !cfg.calmUntil;
    }

    function bindConfigForm() {
        $('#config-form').addEventListener('submit', async (e) => {
            e.preventDefault();
            const fd = new FormData(e.target);
            const body = {};
            fd.forEach((v, k) => { if (v !== '') body[k] = v; });
            try {
                await api('/config', {
                    method: 'PUT', headers: headers(true), body: JSON.stringify(body),
                });
                alert('已保存');
                loadOverview();
            } catch (err) {
                alert('保存失败：' + err.message);
            }
        });
        $('#btn-clear-calm').addEventListener('click', async () => {
            if (!confirm('确定解除冷静期？')) return;
            await api('/config', {
                method: 'PUT', headers: headers(true), body: JSON.stringify({ clearCalm: true }),
            });
            loadConfig();
            loadOverview();
        });
    }

    /* ═══════ 顶部按钮 / Tabs ═══════ */

    function bindActions() {
        $('#btn-run').addEventListener('click', async () => {
            const btn = $('#btn-run');
            btn.disabled = true;
            btn.textContent = '扫描中…';
            try {
                const r = await api('/run', { method: 'POST', headers: headers() });
                alert(`扫描完成 · 触发 ${r.triggered} 条推送`);
                loadAlerts();
            } catch (err) {
                alert('扫描失败：' + err.message);
            } finally {
                btn.disabled = false;
                btn.textContent = '手动扫描';
            }
        });
        $('#btn-sync').addEventListener('click', async () => {
            const btn = $('#btn-sync');
            btn.disabled = true;
            btn.textContent = '同步中…';
            try {
                await api('/sync-kline', { method: 'POST', headers: headers() });
                alert('日K同步完成');
                loadOverview();
            } catch (err) {
                alert('同步失败：' + err.message);
            } finally {
                btn.disabled = false;
                btn.textContent = '同步日K';
            }
        });
    }

    function bindTabs() {
        $$('#etf-tabs button').forEach((btn) => {
            btn.addEventListener('click', () => {
                $$('#etf-tabs button').forEach((b) => b.classList.remove('active'));
                btn.classList.add('active');
                const tab = btn.dataset.tab;
                $$('.etf-pane').forEach((p) => { p.hidden = p.dataset.pane !== tab; });
                if (tab === 'nav') loadNav();
                if (tab === 'alerts') loadAlerts();
                if (tab === 'trades') loadTrades();
                if (tab === 'pool') loadPool();
                if (tab === 'config') loadConfig();
            });
        });
    }

    /* ═══════ helpers ═══════ */

    function esc(s) {
        return (s == null ? '' : String(s)).replace(/[&<>"]/g,
            (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
    }
    function num(v, digits) { return v == null ? '-' : Number(v).toFixed(digits == null ? 2 : digits); }
    function money(v) { return v == null ? '-' : Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 0 }) + ' 元'; }
    function pct(v) { return v == null ? '-' : Number(v).toFixed(2) + '%'; }
    function cls(v) { return v == null ? '' : (Number(v) >= 0 ? 'up' : 'down'); }

    /* ═══════ init ═══════ */

    async function init() {
        bindTabs();
        bindActions();
        bindTradeForm();
        bindPoolForm();
        bindConfigForm();
        refreshTradeTypeOptions();
        await loadPool();
        await loadOverview();
        await loadTrades();
        loadConfig();
        setInterval(loadOverview, 60000);
    }

    // 登录角色确定后重新拉取（修复未带 token 的首轮请求）
    document.addEventListener('gp:role-changed', () => {
        loadPool();
        loadOverview();
        loadTrades();
        loadConfig();
    });

    init();
})();
