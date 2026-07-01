(function () {
  'use strict';

  const track = document.getElementById('quoteCarouselTrack');
  const dots = document.getElementById('quoteCarouselDots');
  const prevBtn = document.getElementById('quoteCarouselPrev');
  const nextBtn = document.getElementById('quoteCarouselNext');
  const carousel = document.getElementById('quoteCarousel');
  if (!track || !prevBtn || !nextBtn || !carousel) return;

  let quotes = [];
  let index = 0;
  let autoTimer = null;
  const AUTO_INTERVAL_MS = 6000;

  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function buildSlide(q) {
    const meta = [
      q.author ? `<span>✍️ ${esc(q.author)}</span>` : '',
      q.source ? `<span>《${esc(q.source)}》</span>` : ''
    ].filter(Boolean).join('<span class="quote-carousel-meta-sep"> · </span>');

    return `
      <div class="quote-carousel-slide" data-id="${q.id}">
        <div class="quote-carousel-content">${esc(q.content)}</div>
        ${meta ? `<div class="quote-carousel-meta">${meta}</div>` : ''}
      </div>`;
  }

  function renderDots() {
    if (!dots) return;
    dots.innerHTML = quotes
      .map((_, i) => `<span class="quote-carousel-dot ${i === index ? 'active' : ''}" data-i="${i}" role="tab" aria-label="第 ${i + 1} 条金句"></span>`)
      .join('');
  }

  function go(i) {
    if (quotes.length === 0) return;
    index = (i + quotes.length) % quotes.length;
    track.style.transform = `translateX(-${index * 100}%)`;
    renderDots();
  }

  function startAuto() {
    stopAuto();
    if (quotes.length < 2) return;
    autoTimer = setInterval(() => go(index + 1), AUTO_INTERVAL_MS);
  }
  function stopAuto() {
    if (autoTimer) { clearInterval(autoTimer); autoTimer = null; }
  }

  function renderEmpty(msg) {
    track.innerHTML = `<div class="quote-carousel-slide"><div class="quote-carousel-empty">${esc(msg)}</div></div>`;
    if (dots) dots.innerHTML = '';
    prevBtn.disabled = true;
    nextBtn.disabled = true;
  }

  async function load() {
    try {
      const res = await fetch('api/quotes?size=20');
      if (!res.ok) throw new Error('http ' + res.status);
      const data = await res.json();
      const list = (data && data.list) || [];
      if (list.length === 0) {
        renderEmpty('暂无金句，去管理后台添加第一条吧');
        return;
      }
      quotes = list;
      track.innerHTML = quotes.map(buildSlide).join('');
      index = 0;
      track.style.transform = 'translateX(0)';
      renderDots();
      prevBtn.disabled = false;
      nextBtn.disabled = false;
      startAuto();
    } catch (err) {
      console.warn('金句轮播加载失败:', err);
      renderEmpty('金句加载失败，稍后再试');
    }
  }

  prevBtn.addEventListener('click', () => { go(index - 1); startAuto(); });
  nextBtn.addEventListener('click', () => { go(index + 1); startAuto(); });

  // 点击金句 → 跳转学习搭子金句页（搜索该条内容）
  track.addEventListener('click', (e) => {
    const slide = e.target.closest('.quote-carousel-slide');
    if (!slide) return;
    const id = slide.dataset.id;
    const q = quotes.find(q => String(q.id) === String(id));
    if (!q) return;
    window.location.href = 'study.html?kw=' + encodeURIComponent(q.content.slice(0, 12));
  });

  // 点击 dot 跳转
  if (dots) {
    dots.addEventListener('click', (e) => {
      const dot = e.target.closest('.quote-carousel-dot');
      if (!dot) return;
      go(parseInt(dot.dataset.i, 10) || 0);
      startAuto();
    });
  }

  // 鼠标 hover 暂停自动滚动
  carousel.addEventListener('mouseenter', stopAuto);
  carousel.addEventListener('mouseleave', startAuto);

  // 触摸滑动支持
  let touchStartX = 0;
  let touchDeltaX = 0;
  let touching = false;
  carousel.addEventListener('touchstart', (e) => {
    touching = true;
    touchStartX = e.touches[0].clientX;
    touchDeltaX = 0;
    stopAuto();
  }, { passive: true });
  carousel.addEventListener('touchmove', (e) => {
    if (!touching) return;
    touchDeltaX = e.touches[0].clientX - touchStartX;
  }, { passive: true });
  carousel.addEventListener('touchend', () => {
    if (!touching) return;
    touching = false;
    if (Math.abs(touchDeltaX) > 40) {
      go(touchDeltaX < 0 ? index + 1 : index - 1);
    }
    startAuto();
  });

  // 键盘左右键（获得焦点时）
  carousel.setAttribute('tabindex', '0');
  carousel.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowLeft') { go(index - 1); startAuto(); }
    else if (e.key === 'ArrowRight') { go(index + 1); startAuto(); }
  });

  load();
})();