(function () {
  'use strict';

  const els = {
    myGrid: document.getElementById('myCourseGrid'),
    pubGrid: document.getElementById('publicCourseGrid'),
    myTabs: document.getElementById('studyMyTabs'),
    pubTabs: document.getElementById('studyPublicTabs'),
    cntAll: document.getElementById('cntAll'),
    cntCreated: document.getElementById('cntCreated'),
    cntLearning: document.getElementById('cntLearning'),
    cntPending: document.getElementById('cntPending'),
    cntDone: document.getElementById('cntDone'),
    openBtn: document.getElementById('openUploadBtn'),
    modal: document.getElementById('uploadModal'),
    closeBtn: document.getElementById('closeUploadBtn'),
    cancelBtn: document.getElementById('cancelUploadBtn'),
    fileInput: document.getElementById('uploadFile'),
    drop: document.getElementById('uploadDrop'),
    list: document.getElementById('uploadList'),
    count: document.getElementById('uploadCount'),
    createBtn: document.getElementById('createCourseBtn')
  };

  let homeData = null;
  let pickedFile = null;
  let myFilter = 'all';
  let pubFilter = 1;

  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function fmtCnt(n) {
    if (n == null) return '0';
    if (n >= 10000) return (n / 10000).toFixed(1).replace(/\.0$/, '') + 'w';
    return String(n);
  }

  function progressBadge(c) {
    if (c.status === 'processing') {
      return '<span class="badge">' + (c.progress || 0) + '%</span>';
    }
    return '';
  }

  function courseCard(c) {
    const cover =
      '<div class="course-cover" style="background:' + esc(c.coverColor || '#e8f5e9') + '">' +
        esc(c.coverText || '📘') +
      '</div>';
    const masteredLine =
      c.totalCnt && c.totalCnt > 0
        ? (c.masteredCnt || 0) + '/' + c.totalCnt + ' 已掌握知识点'
        : (c.status === 'processing' ? '解析中…' : '暂无知识点');
    const summary = c.summary
      ? '<div class="course-summary">' + esc(c.summary) + '</div>'
      : '';
    const meta =
      '<div class="course-meta">' +
        '<span>' + esc(c.owner || '') + '</span>' +
        '<span>' + fmtCnt(c.learnerCnt || 0) + '人学习</span>' +
      '</div>';
    return (
      '<div class="course-card ' + (c.status === 'processing' ? 'processing' : '') + '" data-id="' + c.id + '">' +
        progressBadge(c) +
        cover +
        '<div class="course-title">' + esc(c.title) + '</div>' +
        summary +
        '<div class="course-progress">' + esc(masteredLine) + '</div>' +
        meta +
      '</div>'
    );
  }

  function filterMy(list, key) {
    if (key === 'all') return list;
    if (key === 'created') return list.filter(c => c.owner === '由我创建');
    if (key === 'learning') return list.filter(c => c.learnStatus === 'learning');
    if (key === 'pending') return list.filter(c => c.learnStatus === 'pending');
    if (key === 'done') return list.filter(c => c.learnStatus === 'done');
    return list;
  }

  function filterPublic(list, catId) {
    if (!catId || catId === 1) return list;
    return list.filter(c => c.categoryId === catId);
  }

  function renderMy() {
    if (!homeData) return;
    const list = filterMy(homeData.myCourses, myFilter);
    els.myGrid.innerHTML = list.map(courseCard).join('') ||
      '<div style="color:#9ca3af;padding:24px;">暂无项目</div>';
    const m = homeData.myCounts;
    els.cntAll.textContent = '(' + m.all + ')';
    els.cntCreated.textContent = '(' + m.created + ')';
    els.cntLearning.textContent = '(' + m.learning + ')';
    els.cntPending.textContent = '(' + m.pending + ')';
    els.cntDone.textContent = '(' + m.done + ')';
  }

  function renderPublicTabs() {
    if (!homeData) return;
    els.pubTabs.innerHTML = homeData.categories.map(c =>
      '<button class="study-tab ' + (c.id === pubFilter ? 'active' : '') + '" data-cat="' + c.id + '">' +
      esc(c.name) + '</button>'
    ).join('');
  }

  function renderPublic() {
    if (!homeData) return;
    const list = filterPublic(homeData.publicCourses, pubFilter);
    els.pubGrid.innerHTML = list.map(courseCard).join('') ||
      '<div style="color:#9ca3af;padding:24px;">该分类暂无项目</div>';
  }

  async function loadHome() {
    try {
      const resp = await fetch('api/study/home');
      if (!resp.ok) throw new Error('加载失败 ' + resp.status);
      homeData = await resp.json();
      renderMy();
      renderPublicTabs();
      renderPublic();
    } catch (e) {
      els.myGrid.innerHTML = '<div style="color:#dc2626;padding:24px;">' + esc(e.message) + '</div>';
    }
  }

  function bindGridClicks() {
    function go(e) {
      const card = e.target.closest('.course-card');
      if (!card) return;
      const id = card.getAttribute('data-id');
      if (!id) return;
      window.location.href = 'course.html?id=' + id;
    }
    els.myGrid.addEventListener('click', go);
    els.pubGrid.addEventListener('click', go);
  }

  function bindTabs() {
    els.myTabs.addEventListener('click', function (e) {
      const btn = e.target.closest('.study-tab');
      if (!btn) return;
      Array.from(els.myTabs.querySelectorAll('.study-tab'))
        .forEach(b => b.classList.toggle('active', b === btn));
      myFilter = btn.getAttribute('data-tab');
      renderMy();
    });

    els.pubTabs.addEventListener('click', function (e) {
      const btn = e.target.closest('.study-tab');
      if (!btn) return;
      Array.from(els.pubTabs.querySelectorAll('.study-tab'))
        .forEach(b => b.classList.toggle('active', b === btn));
      pubFilter = Number(btn.getAttribute('data-cat'));
      renderPublic();
    });
  }

  // ===== Upload modal =====
  function openModal() {
    els.modal.classList.remove('hidden');
  }
  function closeModal() {
    els.modal.classList.add('hidden');
    pickedFile = null;
    els.fileInput.value = '';
    els.list.innerHTML = '';
    els.count.textContent = '0';
    els.createBtn.disabled = true;
  }

  function fmtSize(n) {
    if (!n) return '';
    const kb = n / 1024;
    if (kb < 1024) return kb.toFixed(1) + ' KB';
    return (kb / 1024).toFixed(1) + ' MB';
  }

  function onPickFile(f) {
    if (!f) return;
    if (f.size > 100 * 1024 * 1024) {
      alert('文件超过 100MB,请重新选择');
      return;
    }
    pickedFile = f;
    els.list.innerHTML =
      '<div class="upload-row">' +
        '<span>📄</span>' +
        '<span class="name">' + esc(f.name) + '</span>' +
        '<span class="size">' + fmtSize(f.size) + '</span>' +
      '</div>';
    els.count.textContent = '1';
    els.createBtn.disabled = false;
  }

  async function onCreate() {
    if (!pickedFile) return;
    els.createBtn.disabled = true;
    els.createBtn.textContent = '处理中...';
    try {
      const form = new FormData();
      form.append('file', pickedFile);
      const resp = await fetch('api/study/upload', { method: 'POST', body: form });
      if (!resp.ok) {
        const text = await resp.text();
        throw new Error('上传失败: ' + text);
      }
      const data = await resp.json();
      closeModal();
      alert('项目已创建: ' + data.title + '\n' + (data.message || ''));
      window.location.href = 'course.html?id=' + data.courseId;
    } catch (e) {
      alert(e.message || '上传失败');
      els.createBtn.disabled = false;
      els.createBtn.textContent = '创建项目';
    }
  }

  function bindUpload() {
    els.openBtn.addEventListener('click', openModal);
    els.closeBtn.addEventListener('click', closeModal);
    els.cancelBtn.addEventListener('click', closeModal);
    els.modal.addEventListener('click', function (e) {
      if (e.target === els.modal) closeModal();
    });
    els.fileInput.addEventListener('change', function (e) {
      onPickFile(e.target.files && e.target.files[0]);
    });
    els.drop.addEventListener('dragover', function (e) {
      e.preventDefault();
      els.drop.style.borderColor = '#2e7d32';
    });
    els.drop.addEventListener('dragleave', function () {
      els.drop.style.borderColor = '';
    });
    els.drop.addEventListener('drop', function (e) {
      e.preventDefault();
      els.drop.style.borderColor = '';
      const f = e.dataTransfer.files && e.dataTransfer.files[0];
      onPickFile(f);
    });
    els.drop.addEventListener('click', function (e) {
      if (e.target === els.fileInput) return;
      const tag = e.target.tagName;
      if (tag === 'LABEL' || tag === 'BUTTON') return;
      els.fileInput.click();
    });
    els.createBtn.addEventListener('click', onCreate);
  }

  window.addEventListener('DOMContentLoaded', function () {
    bindGridClicks();
    bindTabs();
    bindUpload();
    loadHome();
  });
})();
