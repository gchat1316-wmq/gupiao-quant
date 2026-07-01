(function () {
  'use strict';

  const PAGE_SIZE = 20;
  let currentPage = 0;
  let currentKw = '';
  let totalLoaded = 0;
  let grandTotal = 0;
  let debounceTimer = null;

  const feed = document.getElementById('quoteFeed');
  const searchInput = document.getElementById('quoteSearchInput');
  const loadMoreBtn = document.getElementById('quoteLoadMoreBtn');
  const loadMoreWrap = document.getElementById('quoteLoadMore');

  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function renderCard(q) {
    const tags = (q.tags || '').split(',').map(t => t.trim()).filter(Boolean);
    const tagHtml = tags.map(t =>
      `<span class="quote-tag" data-tag="${esc(t)}">${esc(t)}</span>`
    ).join('');

    const metaHtml = [
      q.author ? `<span class="quote-author">✍️ ${esc(q.author)}</span>` : '',
      q.source ? `<span class="quote-source">《${esc(q.source)}》</span>` : ''
    ].filter(Boolean).join('');

    return `
      <div class="quote-card" data-id="${q.id}">
        <div class="quote-content">${esc(q.content)}</div>
        ${metaHtml ? `<div class="quote-meta">${metaHtml}</div>` : ''}
        ${tagHtml ? `<div class="quote-tags">${tagHtml}</div>` : ''}
        <div class="quote-actions">
          <button class="quote-like-btn" data-id="${q.id}">
            ❤️ <span class="like-cnt">${q.likes || 0}</span>
          </button>
        </div>
      </div>`;
  }

  function appendCards(list) {
    if (list.length === 0 && currentPage === 0) {
      feed.innerHTML = '<div class="quote-empty">暂无金句，请联系管理员添加 💡</div>';
      return;
    }
    list.forEach(q => {
      feed.insertAdjacentHTML('beforeend', renderCard(q));
    });
  }

  async function loadPage(reset) {
    if (reset) {
      currentPage = 0;
      totalLoaded = 0;
      feed.innerHTML = '';
    }
    const params = new URLSearchParams({ kw: currentKw, page: currentPage, size: PAGE_SIZE });
    const res = await fetch('api/quotes?' + params);
    const data = await res.json();
    grandTotal = data.total;
    appendCards(data.list);
    totalLoaded += data.list.length;
    currentPage++;

    if (totalLoaded >= grandTotal) {
      loadMoreWrap.style.display = 'none';
    } else {
      loadMoreWrap.style.display = '';
    }
  }

  // Search
  searchInput.addEventListener('input', () => {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      currentKw = searchInput.value.trim();
      loadPage(true);
    }, 300);
  });

  // Tag click → fill search
  feed.addEventListener('click', async (e) => {
    const tag = e.target.closest('.quote-tag');
    if (tag) {
      searchInput.value = tag.dataset.tag;
      currentKw = tag.dataset.tag;
      loadPage(true);
      return;
    }

    const likeBtn = e.target.closest('.quote-like-btn');
    if (likeBtn) {
      const id = likeBtn.dataset.id;
      await fetch('api/quotes/' + id + '/like', { method: 'POST' });
      const cnt = likeBtn.querySelector('.like-cnt');
      cnt.textContent = parseInt(cnt.textContent) + 1;
      likeBtn.classList.add('liked');
      return;
    }
  });

  // Load more
  loadMoreBtn.addEventListener('click', () => loadPage(false));

  // Init
  loadPage(true);
})();