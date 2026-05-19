(function () {
  'use strict';

  const PAGE_SIZE = 20;
  let currentPage = 0;
  let currentKw = '';
  let totalLoaded = 0;
  let grandTotal = 0;
  let debounceTimer = null;

  const PRESET_TAGS = ['价值投资','成长股','护城河','复利','风险','仓位','逆向','长期','赛道','估值','人性','趋势'];
  let selectedTags = new Set();

  const feed = document.getElementById('quoteFeed');
  const searchInput = document.getElementById('quoteSearchInput');
  const loadMoreBtn = document.getElementById('quoteLoadMoreBtn');
  const loadMoreWrap = document.getElementById('quoteLoadMore');
  const addBtn = document.getElementById('quoteAddBtn');
  const modal = document.getElementById('quoteModal');
  const modalClose = document.getElementById('quoteModalClose');
  const modalCancel = document.getElementById('quoteModalCancel');
  const modalSave = document.getElementById('quoteModalSave');
  const tabs = document.querySelectorAll('.quote-modal-tab');
  const singlePane = document.getElementById('quoteSinglePane');
  const batchPane = document.getElementById('quoteBatchPane');

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

    const importedClass = q.importedNodeId ? 'imported' : '';
    const importedText = q.importedNodeId ? '✓ 已加入学习' : '📖 加入学习';
    const importedDisabled = q.importedNodeId ? 'disabled' : '';

    return `
      <div class="quote-card" data-id="${q.id}">
        <div class="quote-content">${esc(q.content)}</div>
        ${metaHtml ? `<div class="quote-meta">${metaHtml}</div>` : ''}
        ${tagHtml ? `<div class="quote-tags">${tagHtml}</div>` : ''}
        <div class="quote-actions">
          <button class="quote-like-btn" data-id="${q.id}">
            ❤️ <span class="like-cnt">${q.likes || 0}</span>
          </button>
          <button class="quote-import-btn ${importedClass}" data-id="${q.id}"
                  data-node="${q.importedNodeId || ''}" ${importedDisabled}>
            ${importedText}
          </button>
        </div>
      </div>`;
  }

  function appendCards(list) {
    if (list.length === 0 && currentPage === 0) {
      feed.innerHTML = '<div class="quote-empty">暂无金句，点击右上角添加第一条 💡</div>';
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
    const res = await fetch('/api/quotes?' + params);
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
      await fetch('/api/quotes/' + id + '/like', { method: 'POST' });
      const cnt = likeBtn.querySelector('.like-cnt');
      cnt.textContent = parseInt(cnt.textContent) + 1;
      likeBtn.classList.add('liked');
      return;
    }

    const importBtn = e.target.closest('.quote-import-btn');
    if (importBtn && !importBtn.disabled) {
      const id = importBtn.dataset.id;
      importBtn.disabled = true;
      importBtn.textContent = '导入中…';
      try {
        const res = await fetch('/api/quotes/' + id + '/import', { method: 'POST' });
        const data = await res.json();
        importBtn.classList.add('imported');
        importBtn.textContent = '✓ 已加入学习';
        importBtn.dataset.node = data.nodeId;
        importBtn.onclick = () => {
          window.open('/node.html?id=' + data.nodeId, '_blank');
        };
      } catch (err) {
        importBtn.disabled = false;
        importBtn.textContent = '📖 加入学习';
        alert('导入失败，请重试');
      }
      return;
    }

    // Click on imported button → jump
    if (importBtn && importBtn.classList.contains('imported') && importBtn.dataset.node) {
      window.open('/node.html?id=' + importBtn.dataset.node, '_blank');
    }
  });

  // Load more
  loadMoreBtn.addEventListener('click', () => loadPage(false));

  // Tag chip picker
  function renderTagChips() {
    const container = document.getElementById('quoteTagChips');
    if (!container) return;
    container.innerHTML = '';
    PRESET_TAGS.forEach(tag => {
      const chip = document.createElement('span');
      chip.className = 'quote-tag-chip' + (selectedTags.has(tag) ? ' selected' : '');
      chip.textContent = tag;
      chip.addEventListener('click', () => {
        if (selectedTags.has(tag)) {
          selectedTags.delete(tag);
          chip.classList.remove('selected');
        } else {
          selectedTags.add(tag);
          chip.classList.add('selected');
        }
      });
      container.appendChild(chip);
    });
  }

  function addCustomTag(tag) {
    tag = tag.trim();
    if (!tag) return;
    if (!PRESET_TAGS.includes(tag)) {
      const container = document.getElementById('quoteTagChips');
      const chip = document.createElement('span');
      chip.className = 'quote-tag-chip selected custom';
      chip.textContent = tag;
      chip.addEventListener('click', () => {
        if (selectedTags.has(tag)) {
          selectedTags.delete(tag);
          chip.classList.remove('selected');
        } else {
          selectedTags.add(tag);
          chip.classList.add('selected');
        }
      });
      container.appendChild(chip);
    }
    selectedTags.add(tag);
    document.getElementById('quoteTagCustomInput').value = '';
  }

  const tagCustomInput = document.getElementById('quoteTagCustomInput');
  if (tagCustomInput) {
    tagCustomInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        addCustomTag(tagCustomInput.value);
      }
    });
  }

  // Modal
  let activeTab = 'single';

  addBtn.addEventListener('click', () => {
    modal.classList.remove('hidden');
    switchTab('single');
    selectedTags.clear();
    renderTagChips();
    setTimeout(() => {
      const c = document.getElementById('quoteContent');
      if (c) c.focus();
    }, 50);
  });

  function closeModal() {
    modal.classList.add('hidden');
    document.getElementById('quoteContent').value = '';
    document.getElementById('quoteAuthor').value = '';
    document.getElementById('quoteSource').value = '';
    if (document.getElementById('quoteTagCustomInput')) {
      document.getElementById('quoteTagCustomInput').value = '';
    }
    document.getElementById('quoteBatch').value = '';
    selectedTags.clear();
  }

  modalClose.addEventListener('click', closeModal);
  modalCancel.addEventListener('click', closeModal);
  modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(); });

  tabs.forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
  });

  function switchTab(name) {
    activeTab = name;
    tabs.forEach(t => t.classList.toggle('active', t.dataset.tab === name));
    singlePane.style.display = name === 'single' ? '' : 'none';
    batchPane.style.display = name === 'batch' ? '' : 'none';
  }

  modalSave.addEventListener('click', async () => {
    modalSave.disabled = true;
    try {
      if (activeTab === 'single') {
        const content = document.getElementById('quoteContent').value.trim();
        if (!content) { alert('请输入金句内容'); return; }
        await fetch('/api/quotes', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            content,
            author: document.getElementById('quoteAuthor').value.trim() || null,
            source: document.getElementById('quoteSource').value.trim() || null,
            tags: selectedTags.size > 0 ? [...selectedTags].join(',') : null
          })
        });
      } else {
        const raw = document.getElementById('quoteBatch').value;
        const lines = raw.split('\n').map(l => l.trim()).filter(Boolean);
        if (lines.length === 0) { alert('请输入至少一条金句'); return; }
        await fetch('/api/quotes/batch', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(lines)
        });
      }
      closeModal();
      loadPage(true);
    } catch (err) {
      alert('保存失败，请重试');
    } finally {
      modalSave.disabled = false;
    }
  });

  // Init
  loadPage(true);
})();
