/**
 * 通知轮播条
 * - 启动时并行拉取 4 个 API，合并成通知
 * - 自动 4s 切换，悬停暂停
 * - 点击圆点/条目跳转
 * - 失败/空数据：降级文案
 */
(function () {
  'use strict';

  const TAG_CONFIG = {
    recap:    { label: '每日复盘', cls: 'tag-recap'    },
    analysis: { label: '个股分析', cls: 'tag-analysis' },
    bigyang:  { label: '大阳线',   cls: 'tag-biyang'   },
    ai:       { label: 'AI 监控',  cls: 'tag-ai'       },
  };
  const INTERVAL = 4000;
  const MAX_ITEMS = 8;

  const list = document.getElementById('notifList');
  const dotsWrap = document.getElementById('notifDots');
  const bar = document.getElementById('notifBar');
  if (!list || !dotsWrap) return;

  let items = [];      // {kind, label, text, time, href}
  let current = 0;
  let timer = null;

  /* ===== 时间格式化 ===== */
  function fmtTime(input) {
    if (!input) return '';
    const d = new Date(input);
    if (isNaN(d.getTime())) return '';
    const now = new Date();
    const diff = (now - d) / 1000; // 秒
    if (diff < 60) return '刚刚';
    if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
    if (diff < 86400 && d.getDate() === now.getDate()) {
      return d.toTimeString().slice(0, 5); // HH:MM
    }
    if (diff < 86400 * 7) {
      const wd = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()];
      return d.toTimeString().slice(0, 5); // 当天外的用 HH:MM
    }
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return mm + '-' + dd;
  }

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /* ===== 4 个数据源 ===== */
  function fetchRecap() {
    return fetch('api/market-recaps/badge', { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data) return null;
        const out = [];
        if (data.latestId && data.latestTradeDate) {
          out.push({
            kind: 'recap',
            text: data.latestTradeDate + ' 复盘已更新 (今天 ' + (data.today || 0) + ' · 昨天 ' + (data.yesterday || 0) + ')',
            time: data.latestTradeDate,
            href: 'market-recap.html?id=' + data.latestId,
            ts: data.latestTradeDate + ' 18:00',
          });
        } else if ((data.today || 0) > 0) {
          out.push({
            kind: 'recap',
            text: '今日已更新 ' + data.today + ' 篇复盘',
            time: '今日',
            href: 'market-recap.html',
            ts: new Date().toISOString().slice(0, 10),
          });
        }
        return out;
      })
      .catch(function () { return null; });
  }

  function fetchAnalysis() {
    return fetch('api/stock-analysis/list?limit=3&size=3', { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data || !Array.isArray(data.records)) return null;
        return data.records
          .filter(function (r) { return r.status === 'SUCCESS' && r.stockName; })
          .slice(0, 3)
          .map(function (r) {
            const one = r.summaryOneLiner ? ' · ' + r.summaryOneLiner : '';
            return {
              kind: 'analysis',
              text: r.stockName + ' 已生成' + (r.verdict ? ' · ' + r.verdict : '') + one,
              time: fmtTime(r.submittedAt),
              href: 'stock-analysis.html?id=' + r.id,
              ts: r.submittedAt || '',
            };
          });
      })
      .catch(function () { return null; });
  }

  function fetchBigYang() {
    return fetch('api/invest/big-yang/alerts', { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!Array.isArray(data) || data.length === 0) return null;
        return data.slice(0, 3).map(function (a) {
          const price = a.triggerPrice != null ? ' @ ' + Number(a.triggerPrice).toFixed(2) : '';
          return {
            kind: 'bigyang',
            text: (a.stockName || a.stockCode || '股票') + ' ' + (a.title || '大阳线触发') + price,
            time: fmtTime(a.triggerAt),
            href: 'invest.html',
            ts: a.triggerAt || '',
          };
        });
      })
      .catch(function () { return null; });
  }

  function fetchAiAlerts() {
    return fetch('api/tech-ai/alerts', { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!Array.isArray(data) || data.length === 0) return null;
        return data.slice(0, 3).map(function (a) {
          return {
            kind: 'ai',
            text: (a.stockCode || '股票') + ' ' + (a.title || a.signalType || '监控告警'),
            time: fmtTime(a.triggerAt),
            href: 'tech-ai.html',
            ts: a.triggerAt || '',
          };
        });
      })
      .catch(function () { return null; });
  }

  /* ===== 合并 + 渲染 ===== */
  function buildFallback() {
    // 一个都没有数据时的占位
    return [{
      kind: 'recap',
      text: '通知中心就绪 · 等待更新...',
      time: '',
      href: null,
      ts: new Date().toISOString(),
    }];
  }

  function merge(list) {
    const flat = list.filter(Boolean).reduce(function (a, b) { return a.concat(b); }, []);
    if (flat.length === 0) return buildFallback();
    // 按 ts 倒序
    flat.sort(function (a, b) {
      const ta = new Date(a.ts || 0).getTime();
      const tb = new Date(b.ts || 0).getTime();
      return tb - ta;
    });
    return flat.slice(0, MAX_ITEMS);
  }

  function render() {
    list.innerHTML = items.map(function (it, i) {
      const tag = TAG_CONFIG[it.kind] || { label: '通知', cls: 'tag-recap' };
      const li = document.createElement('li');
      li.className = 'notif-item' + (i === 0 ? ' active' : '');
      li.dataset.kind = it.kind;
      if (it.href) li.style.cursor = 'pointer';
      li.innerHTML =
        '<span class="notif-tag ' + tag.cls + '">' + escapeHtml(tag.label) + '</span>' +
        '<span class="notif-text">' + escapeHtml(it.text) + '</span>' +
        '<span class="notif-time">' + escapeHtml(it.time) + '</span>';
      if (it.href) {
        li.addEventListener('click', function () { window.location.href = it.href; });
      }
      return li.outerHTML;
    }).join('');

    dotsWrap.innerHTML = items.map(function (_, i) {
      return '<button class="notif-dot' + (i === 0 ? ' active' : '') +
        '" data-i="' + i + '" aria-label="切换到第 ' + (i + 1) + ' 条"></button>';
    }).join('');

    // 重新挂点击事件（点击条目跳转已在创建时绑定）
    dotsWrap.onclick = function (e) {
      const btn = e.target.closest('.notif-dot');
      if (!btn) return;
      go(Number(btn.dataset.i));
      start();
    };
  }

  function go(idx) {
    if (items.length === 0) return;
    current = (idx + items.length) % items.length;
    Array.from(list.querySelectorAll('.notif-item')).forEach(function (el, i) {
      el.classList.toggle('active', i === current);
    });
    Array.from(dotsWrap.querySelectorAll('.notif-dot')).forEach(function (d, i) {
      d.classList.toggle('active', i === current);
    });
  }

  function start() {
    stop();
    if (items.length <= 1) return;
    timer = setInterval(function () { go(current + 1); }, INTERVAL);
  }

  function stop() {
    if (timer) { clearInterval(timer); timer = null; }
  }

  if (bar) {
    bar.addEventListener('mouseenter', stop);
    bar.addEventListener('mouseleave', start);
  }

  /* ===== 启动 ===== */
  Promise.all([fetchRecap(), fetchAnalysis(), fetchBigYang(), fetchAiAlerts()])
    .then(merge)
    .then(function (result) {
      items = result;
      render();
      start();
    })
    .catch(function () {
      items = buildFallback();
      render();
    });
})();

/* ===== 搜索区空状态自适应居中 ===== */
(function () {
  'use strict';
  const stage = document.getElementById('searchStage');
  const resultSection = document.getElementById('resultSection');
  if (!stage || !resultSection) return;

  sync();
  const obs = new MutationObserver(sync);
  obs.observe(resultSection, { attributes: true, attributeFilter: ['class'] });

  function sync() {
    const hasResult = !resultSection.classList.contains('hidden');
    stage.classList.toggle('is-centered', !hasResult);
  }
})();
