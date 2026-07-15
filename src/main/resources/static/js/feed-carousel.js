/**
 * 首页混合轮播：金句 + 通知（每日复盘 / 个股分析 / 大阳线 / AI 监控）
 *
 * 行为：
 * - 启动时并发拉 5 个数据源，按 ts 倒序合并
 * - 5s 自动切换下一条（hover 暂停）
 * - 每个 slide 左侧带类型 tag（金句 / 通知子类型）
 * - 通知类 hover 时右侧滑出「查看」按钮 → 跳对应管理页
 * - 点击 slide 主体 → 跳对应前台页（金句 → study.html；通知 → 原 href）
 * - 左右箭头、底部小圆点、键盘左右、触屏滑动均支持
 */
(function () {
  'use strict';

  const AUTO_INTERVAL_MS = 5000;       // 5 秒切换
  const QUOTE_FETCH_SIZE = 6;          // 金句最多取 6 条
  const NOTIF_MAX_ITEMS = 8;           // 通知最多 8 条
  const FEED_MAX_ITEMS = 14;           // 合并后最多展示 14 条

  /* ===== 类型配置 ===== */
  const TYPE = {
    quote:    { label: '金句',     cls: 'type-quote'    },
    recap:    { label: '每日复盘', cls: 'type-recap'    },
    analysis: { label: '个股分析', cls: 'type-analysis' },
    bigyang:  { label: '大阳线',   cls: 'type-biyang'   },
    ai:       { label: 'AI 监控',  cls: 'type-ai'       },
  };

  // 通知类条目：默认跳转 + 「查看」按钮跳转
  const NOTIF_HREF = {
    recap:    'market-recap.html',
    analysis: 'prosperity-pick.html',
    bigyang:  'invest.html',
    ai:       'monitor.html',
  };

  const track = document.getElementById('feedTrack');
  const dots  = document.getElementById('feedDots');
  const prev  = document.getElementById('feedPrev');
  const next  = document.getElementById('feedNext');
  const root  = document.getElementById('feedCarousel');
  if (!track || !prev || !next || !root) return;

  let items = [];          // 合并后的 {type, content, meta, href, adminHref, ts}
  let index = 0;
  let timer = null;

  /* ===== utils ===== */
  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function linkify(s) {
    if (s == null) return '';
    const urlRe = /(https?:\/\/[^\s<>"{}|\\^`[\]]+)/gi;
    const raw = String(s);
    let out = '';
    let last = 0;
    let m;
    while ((m = urlRe.exec(raw)) !== null) {
      out += esc(raw.slice(last, m.index));
      let url = m[1];
      const clean = url.replace(/[.,;!?\)\]\'\"]+$/, '');
      urlRe.lastIndex -= (url.length - clean.length);
      out += '<a href="' + esc(clean) + '" target="_blank" rel="noopener noreferrer" onclick="event.stopPropagation();">' + esc(clean) + '</a>';
      last = urlRe.lastIndex;
    }
    out += esc(raw.slice(last));
    return out;
  }

  function fmtTime(input) {
    if (!input) return '';
    const d = new Date(input);
    if (isNaN(d.getTime())) return '';
    const now = new Date();
    const diff = (now - d) / 1000;
    if (diff < 60) return '刚刚';
    if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
    if (diff < 86400 && d.getDate() === now.getDate()) return d.toTimeString().slice(0, 5);
    if (diff < 86400 * 7) return d.toTimeString().slice(0, 5);
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return mm + '-' + dd;
  }

  /* ===== 数据源 ===== */
  function fetchQuotes() {
    return fetch('api/quotes?size=' + QUOTE_FETCH_SIZE, { headers: { Accept: 'application/json' } })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        const list = (data && data.list) || [];
        return list.map(q => ({
          type: 'quote',
          content: q.content || '',
          meta: [q.author, q.source ? '《' + q.source + '》' : ''].filter(Boolean).join(' · '),
          href: 'study.html?kw=' + encodeURIComponent((q.content || '').slice(0, 12)),
          adminHref: '',   // 金句不显示「查看」按钮（点击主体即跳转 study）
          ts: q.updatedAt || q.createdAt || '',
          _raw: q,
        }));
      })
      .catch(() => []);
  }

  function fetchRecap() {
    return fetch('api/market-recaps/badge', { headers: { Accept: 'application/json' } })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (!data) return [];
        const out = [];
        if (data.latestId && data.latestTradeDate) {
          out.push({
            type: 'recap',
            content: data.latestTradeDate + ' 复盘已更新（今天 ' + (data.today || 0) + ' · 昨天 ' + (data.yesterday || 0) + '）',
            meta: fmtTime(data.latestTradeDate),
            href: 'market-recap.html?id=' + data.latestId,
            adminHref: 'market-recap.html',
            ts: (data.latestTradeDate || '') + ' 18:00',
          });
        } else if ((data.today || 0) > 0) {
          out.push({
            type: 'recap',
            content: '今日已更新 ' + data.today + ' 篇复盘',
            meta: '今日',
            href: 'market-recap.html',
            adminHref: 'market-recap.html',
            ts: new Date().toISOString().slice(0, 10),
          });
        }
        return out;
      })
      .catch(() => []);
  }

  function fetchAnalysis() {
    return fetch('api/stock-analysis/list?limit=3&size=3', { headers: { Accept: 'application/json' } })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (!data || !Array.isArray(data.records)) return [];
        return data.records
          .filter(r => r.status === 'SUCCESS' && r.stockName)
          .slice(0, 3)
          .map(r => {
            let summary = r.summaryOneLiner || '';
            const name = (r.stockName || '').trim();
            if (name && summary.startsWith(name)) {
              summary = summary.slice(name.length).replace(/^[，,。\s]+/, '');
            }
            const one = summary ? ' · ' + summary : '';
            const verdict = r.verdict ? ' · ' + r.verdict : '';
            return {
              type: 'analysis',
              content: r.stockName + ' 已生成' + verdict + one,
              meta: fmtTime(r.submittedAt),
              href: 'prosperity-pick.html?id=' + r.id,
              adminHref: 'prosperity-pick.html',
              ts: r.submittedAt || '',
            };
          });
      })
      .catch(() => []);
  }

  function fetchBigYang() {
    return fetch('api/invest/big-yang/alerts', { headers: { Accept: 'application/json' } })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (!Array.isArray(data) || data.length === 0) return [];
        return data.slice(0, 3).map(a => {
          const price = a.triggerPrice != null ? ' @ ' + Number(a.triggerPrice).toFixed(2) : '';
          return {
            type: 'bigyang',
            content: (a.stockName || a.stockCode || '股票') + ' ' + (a.title || '大阳线触发') + price,
            meta: fmtTime(a.triggerAt),
            href: 'invest.html',
            adminHref: 'invest.html',
            ts: a.triggerAt || '',
          };
        });
      })
      .catch(() => []);
  }

  // 2026-07-02 池子重构：科技AI 实时监控下线（QMT/Server酱/分钟异动告警），
  // /api/tech-ai/alerts 端点已删除。监控告警后续会从统一 MonitorService 输出。
  function fetchAiAlerts() {
    return Promise.resolve([]);
  }

  /* ===== 合并 ===== */
  function merge(all) {
    const flat = [].concat(...all);
    if (flat.length === 0) {
      return [{
        type: 'quote',
        content: '欢迎使用投资助手，输入股票代码或名称开始分析',
        meta: '系统',
        href: '',
        adminHref: '',
        ts: new Date().toISOString(),
      }];
    }
    flat.sort((a, b) => new Date(b.ts || 0).getTime() - new Date(a.ts || 0).getTime());
    return flat.slice(0, FEED_MAX_ITEMS);
  }

  /* ===== 渲染 ===== */
  function renderDots() {
    if (!dots) return;
    dots.innerHTML = items.map((_, i) =>
      '<span class="feed-dot' + (i === index ? ' active' : '') + '" data-i="' + i + '" role="tab" aria-label="第 ' + (i + 1) + ' 条"></span>'
    ).join('');
  }

  function buildSlide(it) {
    const t = TYPE[it.type] || { label: '信息', cls: 'type-quote' };
    const actionBtn = it.adminHref
      ? '<button class="feed-action" type="button" data-admin-href="' + esc(it.adminHref) + '" aria-label="查看' + esc(t.label) + '">查看</button>'
      : '';
    return ''
      + '<div class="feed-slide" data-type="' + esc(it.type) + '"' + (it.href ? ' data-href="' + esc(it.href) + '"' : '') + '>'
      +   '<span class="feed-tag ' + t.cls + '">' + esc(t.label) + '</span>'
      +   '<div class="feed-body">'
      +     '<div class="feed-content">' + linkify(it.content) + '</div>'
      +     (it.meta ? '<div class="feed-meta">' + esc(it.meta) + '</div>' : '')
      +   '</div>'
      +   actionBtn
      + '</div>';
  }

  function render() {
    track.innerHTML = items.map(buildSlide).join('');
    index = 0;
    track.style.transform = 'translateX(0)';
    renderDots();
    prev.disabled = items.length < 2;
    next.disabled = items.length < 2;
  }

  /* ===== 切换 ===== */
  function go(i, resetTimer) {
    if (items.length === 0) return;
    index = (i + items.length) % items.length;
    track.style.transform = 'translateX(-' + (index * 100) + '%)';
    renderDots();
    if (resetTimer) startAuto();
  }

  function startAuto() {
    stopAuto();
    if (items.length < 2) return;
    timer = setInterval(() => go(index + 1, false), AUTO_INTERVAL_MS);
  }
  function stopAuto() {
    if (timer) { clearInterval(timer); timer = null; }
  }

  /* ===== 事件 ===== */
  prev.addEventListener('click', () => go(index - 1, true));
  next.addEventListener('click', () => go(index + 1, true));

  // 点击 slide：查看按钮 → admin；其余 → href
  track.addEventListener('click', (e) => {
    const actionBtn = e.target.closest('.feed-action');
    if (actionBtn) {
      e.stopPropagation();
      e.preventDefault();
      const href = actionBtn.dataset.adminHref;
      if (href) window.location.href = href;
      return;
    }
    // 链接交给浏览器
    if (e.target.closest('a')) return;
    const slide = e.target.closest('.feed-slide');
    if (!slide) return;
    const href = slide.dataset.href;
    if (href) window.location.href = href;
  });

  if (dots) {
    dots.addEventListener('click', (e) => {
      const d = e.target.closest('.feed-dot');
      if (!d) return;
      go(parseInt(d.dataset.i, 10) || 0, true);
    });
  }

  // hover 暂停
  root.addEventListener('mouseenter', stopAuto);
  root.addEventListener('mouseleave', startAuto);

  // 触屏滑动
  let touchStartX = 0, touchDeltaX = 0, touching = false;
  root.addEventListener('touchstart', (e) => {
    touching = true;
    touchStartX = e.touches[0].clientX;
    touchDeltaX = 0;
    stopAuto();
  }, { passive: true });
  root.addEventListener('touchmove', (e) => {
    if (!touching) return;
    touchDeltaX = e.touches[0].clientX - touchStartX;
  }, { passive: true });
  root.addEventListener('touchend', () => {
    if (!touching) return;
    touching = false;
    if (Math.abs(touchDeltaX) > 40) {
      go(touchDeltaX < 0 ? index + 1 : index - 1, false);
    }
    startAuto();
  });

  // 键盘左右
  root.setAttribute('tabindex', '0');
  root.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowLeft') go(index - 1, true);
    else if (e.key === 'ArrowRight') go(index + 1, true);
  });

  /* ===== 启动 ===== */
  Promise.all([fetchQuotes(), fetchRecap(), fetchAnalysis(), fetchBigYang(), fetchAiAlerts()])
    .then(merge)
    .then(result => {
      items = result;
      render();
      startAuto();
    })
    .catch(err => {
      console.warn('feed 轮播加载失败:', err);
      items = [{
        type: 'quote',
        content: '内容加载失败，请刷新页面重试',
        meta: '',
        href: '',
        adminHref: '',
        ts: '',
      }];
      render();
    });
})();