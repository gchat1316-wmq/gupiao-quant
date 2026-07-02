/**
 * 股票池 CRUD 管理面板（admin 后台）。
 *
 * 依赖：
 *   - 后端 API（/api/invest/pool 全部）
 *   - DOM 容器 #panel-poolcrud（由 admin-users.html 注入）
 *   - 全局 fetch（带 Authorization header 由 admin-users.html 注入或自动带 cookie）
 *
 * 不依赖任何第三方库；拖拽用 HTML5 native drag & drop API。
 */
(function () {
  'use strict';

  // ── 状态 ─────────────────────────────────────────
  var currentPool = 'tech_ai';
  var poolData = [];
  var searchKeyword = '';
  // 拖拽源行 id（拖动结束后立刻清空）
  var draggingId = null;

  // ── 分类 label ──────────────────────────────────
  var POOL_LABELS = {
    tech_ai: '科技AI（10×PS）',
    innovative_drug: '创新药',
    quality: '质量优选'
  };

  // ── 工具函数 ──────────────────────────────────
  function $(id) { return document.getElementById(id); }

  function escHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function fmtDecimal(v, suffix) {
    if (v == null) return '—';
    var n = typeof v === 'number' ? v : parseFloat(v);
    if (isNaN(n)) return '—';
    return n.toFixed(2) + (suffix || '');
  }

  function setMsg(id, text, isErr) {
    var el = $(id);
    if (!el) return;
    el.textContent = text || '';
    el.className = 'msg ' + (isErr ? 'err' : 'ok');
    if (text) setTimeout(function () {
      el.textContent = '';
      el.className = 'msg';
    }, 3500);
  }

  // 统一 fetch wrapper（带 Authorization header）
  // admin-users.html 已通过 cookie 登录态，但保持 fetch headers 显式不带也行；
  // 这里直接用相对路径，cookie 自动带
  function authFetch(url, opts) {
    opts = opts || {};
    if (!opts.credentials) opts.credentials = 'include';
    if (!opts.headers) opts.headers = {};
    if (opts.body && typeof opts.body === 'object' && !(opts.body instanceof FormData)) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(opts.body);
    }
    return fetch(url, opts).then(function (r) {
      if (!r.ok) {
        return r.text().then(function (t) {
          throw new Error('HTTP ' + r.status + ': ' + (t || r.statusText));
        });
      }
      // 204 no content
      if (r.status === 204) return null;
      var ct = r.headers.get('content-type') || '';
      if (ct.indexOf('application/json') >= 0) return r.json();
      return r.text();
    });
  }

  // ── 列表加载 / 渲染 ──────────────────────────────
  function loadPool() {
    return authFetch('/api/invest/pool?poolType=' + encodeURIComponent(currentPool))
      .then(function (data) {
        poolData = (data || []).slice().sort(function (a, b) {
          // 优先按 displayOrder 升序，未填的排最后
          var ao = a.displayOrder == null ? 999999 : a.displayOrder;
          var bo = b.displayOrder == null ? 999999 : b.displayOrder;
          if (ao !== bo) return ao - bo;
          return (a.id || 0) - (b.id || 0);
        });
        renderTable();
        updateTabCounts();
      })
      .catch(function (e) {
        setMsg('poolcrudPanelMsg', '加载失败：' + e.message, true);
      });
  }

  function updateTabCounts() {
    // 分类 tab 上显示计数（每个 tab 各拉一次太重，所以只在切换时拉；
    // 这里简化：切到当前 tab 后，更新当前 tab 的角标，跨 tab 计数首次打开才准）
    var cur = $('poolcrudTabCurrent');
    if (cur) cur.textContent = (poolData.length || 0) + ' 只';
  }

  function renderTable() {
    var tbody = $('poolcrudTbody');
    if (!tbody) return;
    var kw = searchKeyword.trim().toLowerCase();
    var filtered = poolData.filter(function (it) {
      if (!kw) return true;
      return (it.stockCode && it.stockCode.toLowerCase().indexOf(kw) >= 0)
        || (it.stockName && it.stockName.toLowerCase().indexOf(kw) >= 0)
        || (it.statusLabel && it.statusLabel.toLowerCase().indexOf(kw) >= 0)
        || (it.profitLevel && it.profitLevel.toLowerCase().indexOf(kw) >= 0);
    });

    if (filtered.length === 0) {
      tbody.innerHTML = '<tr><td colspan="9" class="poolcrud-empty">' +
        (poolData.length === 0
          ? '当前分类暂无股票。点击「+ 新增股票」或「📷 截图批量导入」添加。'
          : '没有匹配的股票。')
        + '</td></tr>';
      var cnt = $('poolcrudCount');
      if (cnt) cnt.textContent = '共 ' + poolData.length + ' 只 · 过滤 ' + filtered.length;
      return;
    }

    tbody.innerHTML = filtered.map(function (it) {
      var memo = it.memo || '';
      var memoShort = memo.length > 30 ? memo.slice(0, 30) + '…' : memo;
      var under = fmtDecimal(it.undervaluedPrice);
      var fair = fmtDecimal(it.fairPrice);
      var over = fmtDecimal(it.overvaluedPrice);
      var tgt = fmtDecimal(it.targetPrice);
      return ''
        + '<tr draggable="true" data-id="' + it.id + '">'
        +   '<td class="poolcrud-drag">≡</td>'
        +   '<td class="poolcrud-order">' + (it.displayOrder == null ? '—' : it.displayOrder) + '</td>'
        +   '<td class="poolcrud-code">' + escHtml(it.stockCode) + '</td>'
        +   '<td class="poolcrud-name">' + escHtml(it.stockName || '—') + '</td>'
        +   '<td class="poolcrud-valuation">'
        +     '<span class="val-low">' + under + '</span>'
        +     '<span class="val-mid">' + fair + '</span>'
        +     '<span class="val-high">' + over + '</span>'
        +   '</td>'
        +   '<td class="poolcrud-target">' + tgt + '</td>'
        +   '<td class="poolcrud-memo" data-id="' + it.id + '" title="' + escHtml(memo) + '">' + escHtml(memoShort) + '</td>'
        +   '<td class="poolcrud-status">' + escHtml(it.statusLabel || '—') + '</td>'
        +   '<td class="poolcrud-actions">'
        +     '<button class="btn-sm" data-action="edit" data-id="' + it.id + '">✎ 编辑</button>'
        +     '<button class="btn-sm danger" data-action="delete" data-id="' + it.id + '">🗑 删除</button>'
        +   '</td>'
        + '</tr>';
    }).join('');

    var cnt = $('poolcrudCount');
    if (cnt) cnt.textContent = '共 ' + poolData.length + ' 只 · 过滤 ' + filtered.length;
    bindTableEvents();
  }

  // ── 拖拽 ──────────────────────────────────────
  function bindTableEvents() {
    var tbody = $('poolcrudTbody');
    if (!tbody) return;

    // 行内编辑/删除按钮（事件委托）
    tbody.querySelectorAll('button[data-action]').forEach(function (btn) {
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        var id = parseInt(btn.dataset.id, 10);
        if (btn.dataset.action === 'edit') openEditModal(id);
        else if (btn.dataset.action === 'delete') confirmDelete(id);
      });
    });

    // 备注 cell 点击内联编辑
    tbody.querySelectorAll('td.poolcrud-memo').forEach(function (cell) {
      cell.addEventListener('click', function (e) {
        // 如果已经在编辑态，忽略
        if (cell.querySelector('textarea')) return;
        e.stopPropagation();
        var id = parseInt(cell.dataset.id, 10);
        startMemoEdit(cell, id);
      });
    });

    // 拖拽：HTML5 native
    tbody.querySelectorAll('tr[draggable="true"]').forEach(function (tr) {
      tr.addEventListener('dragstart', function (e) {
        draggingId = parseInt(tr.dataset.id, 10);
        tr.classList.add('poolcrud-dragging');
        e.dataTransfer.effectAllowed = 'move';
        // 必须 setData 才能在 Firefox 触发 drag
        try { e.dataTransfer.setData('text/plain', String(draggingId)); } catch (_) {}
      });
      tr.addEventListener('dragend', function () {
        draggingId = null;
        tr.classList.remove('poolcrud-dragging');
        tbody.querySelectorAll('tr.poolcrud-drop-before, tr.poolcrud-drop-after')
          .forEach(function (r) {
            r.classList.remove('poolcrud-drop-before', 'poolcrud-drop-after');
          });
      });
      tr.addEventListener('dragover', function (e) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        var rect = tr.getBoundingClientRect();
        var before = (e.clientY - rect.top) < rect.height / 2;
        tbody.querySelectorAll('tr.poolcrud-drop-before, tr.poolcrud-drop-after')
          .forEach(function (r) { r.classList.remove('poolcrud-drop-before', 'poolcrud-drop-after'); });
        tr.classList.add(before ? 'poolcrud-drop-before' : 'poolcrud-drop-after');
      });
      tr.addEventListener('drop', function (e) {
        e.preventDefault();
        var srcId = draggingId;
        if (!srcId) return;
        var dstId = parseInt(tr.dataset.id, 10);
        if (srcId === dstId) return;
        var rect = tr.getBoundingClientRect();
        var before = (e.clientY - rect.top) < rect.height / 2;
        reorderLocal(srcId, dstId, before, function () {
          submitReorder();
        });
      });
    });
  }

  function reorderLocal(srcId, dstId, before, done) {
    var srcIdx = poolData.findIndex(function (x) { return x.id === srcId; });
    var dstIdx = poolData.findIndex(function (x) { return x.id === dstId; });
    if (srcIdx < 0 || dstIdx < 0) return;
    var item = poolData.splice(srcIdx, 1)[0];
    // 重新计算 dstIdx（如果 srcIdx < dstIdx，splice 后 dstIdx 需要 -1）
    if (srcIdx < dstIdx) dstIdx--;
    var insertAt = before ? dstIdx : dstIdx + 1;
    poolData.splice(insertAt, 0, item);
    renderTable();
    if (done) done();
  }

  function submitReorder() {
    // 等距步长 10/20/30...
    var items = poolData.map(function (it, i) {
      return { id: it.id, displayOrder: (i + 1) * 10 };
    });
    authFetch('/api/invest/pool/reorder', { method: 'POST', body: items })
      .then(function () {
        setMsg('poolcrudPanelMsg', '✓ 已重排（' + items.length + ' 只）', false);
        // 不需要重新拉，后端 cache 已 evict，下次访问才命中
        // 但本地 poolData 里的 displayOrder 字段没更新，更新一下
        poolData.forEach(function (it, i) { it.displayOrder = (i + 1) * 10; });
        renderTable();
      })
      .catch(function (e) {
        setMsg('poolcrudPanelMsg', '重排失败：' + e.message + '（已回滚）', true);
        loadPool();
      });
  }

  // ── 备注内联编辑 ──────────────────────────────
  function startMemoEdit(cell, id) {
    var cur = poolData.find(function (x) { return x.id === id; });
    if (!cur) return;
    var ta = document.createElement('textarea');
    ta.className = 'poolcrud-memo-input';
    ta.value = cur.memo || '';
    ta.rows = 2;
    cell.innerHTML = '';
    cell.appendChild(ta);
    ta.focus();
    ta.select();

    var saved = false;
    var save = function () {
      if (saved) return;
      saved = true;
      var v = ta.value;
      authFetch('/api/invest/pool/' + id + '/field', {
        method: 'PATCH',
        body: { field: 'memo', value: v }
      })
      .then(function () {
        cur.memo = v;
        setMsg('poolcrudPanelMsg', '✓ 备注已更新', false);
        renderTable();
      })
      .catch(function (e) {
        setMsg('poolcrudPanelMsg', '备注保存失败：' + e.message, true);
        renderTable();
      });
    };
    var cancel = function () { if (!saved) { saved = true; renderTable(); } };

    ta.addEventListener('blur', save);
    ta.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { e.preventDefault(); cancel(); }
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); ta.blur(); }
    });
  }

  // ── 新增 / 编辑 modal ──────────────────────────
  var editingId = null;

  function openAddModal() {
    editingId = null;
    $('poolcrudEditTitle').textContent = '新增股票';
    fillModalFromItem({
      poolType: currentPool,
      displayOrder: ((poolData[poolData.length - 1] || {}).displayOrder || 0) + 10
    });
    showModal('poolcrudEditModal');
    setTimeout(function () { $('poolcrudKeyword').focus(); }, 50);
  }

  function openEditModal(id) {
    var item = poolData.find(function (x) { return x.id === id; });
    if (!item) return;
    editingId = id;
    $('poolcrudEditTitle').textContent = '编辑 #' + id + ' · ' + (item.stockCode || '');
    fillModalFromItem(item);
    showModal('poolcrudEditModal');
  }

  function fillModalFromItem(it) {
    $('poolcrudKeyword').value = it.stockCode || it.stockName || '';
    $('poolcrudKeyword').disabled = !!it.id; // 编辑时锁定代码/名称（修改股票 = 新增+删除）
    $('poolcrudPoolType').value = it.poolType || currentPool;
    $('poolcrudMemo').value = it.memo || '';
    $('poolcrudUndervalued').value = it.undervaluedPrice == null ? '' : it.undervaluedPrice;
    $('poolcrudFair').value = it.fairPrice == null ? '' : it.fairPrice;
    $('poolcrudOvervalued').value = it.overvaluedPrice == null ? '' : it.overvaluedPrice;
    $('poolcrudTargetBuy').value = it.targetBuyPrice == null ? '' : it.targetBuyPrice;
    $('poolcrudTargetSell').value = it.targetSellPrice == null ? '' : it.targetSellPrice;
    $('poolcrudRevY0').value = it.revenueForecastY0 == null ? '' : it.revenueForecastY0;
    $('poolcrudRevY1').value = it.revenueForecastY1 == null ? '' : it.revenueForecastY1;
    $('poolcrudRevY2').value = it.revenueForecastY2 == null ? '' : it.revenueForecastY2;
    $('poolcrudRev2023').value = it.revenue2023 == null ? '' : it.revenue2023;
    $('poolcrudRev2024').value = it.revenue2024 == null ? '' : it.revenue2024;
    $('poolcrudRev2025').value = it.revenue2025 == null ? '' : it.revenue2025;
    $('poolcrudQ1GM').value = it.q1GrossMargin == null ? '' : it.q1GrossMargin;
    $('poolcrudQ1NM').value = it.q1NetMargin == null ? '' : it.q1NetMargin;
    $('poolcrudQ1RG').value = it.q1RevenueGrowth == null ? '' : it.q1RevenueGrowth;
    $('poolcrudMinPs5y').value = it.minPs5y == null ? '' : it.minPs5y;
    $('poolcrudTargetMC').value = it.targetMarketCap == null ? '' : it.targetMarketCap;
    $('poolcrudDisplayOrder').value = it.displayOrder == null ? '' : it.displayOrder;
  }

  function readModalToBody() {
    function numOrNull(id) {
      var v = $(id).value;
      if (v === '' || v == null) return null;
      var n = parseFloat(v);
      return isNaN(n) ? null : n;
    }
    function intOrNull(id) {
      var v = $(id).value;
      if (v === '' || v == null) return null;
      var n = parseInt(v, 10);
      return isNaN(n) ? null : n;
    }
    return {
      keyword: $('poolcrudKeyword').value.trim(),
      poolType: $('poolcrudPoolType').value,
      memo: $('poolcrudMemo').value || null,
      undervaluedPrice: numOrNull('poolcrudUndervalued'),
      fairPrice: numOrNull('poolcrudFair'),
      overvaluedPrice: numOrNull('poolcrudOvervalued'),
      targetBuyPrice: numOrNull('poolcrudTargetBuy'),
      targetSellPrice: numOrNull('poolcrudTargetSell'),
      revenueForecastY0: numOrNull('poolcrudRevY0'),
      revenueForecastY1: numOrNull('poolcrudRevY1'),
      revenueForecastY2: numOrNull('poolcrudRevY2'),
      revenue2023: numOrNull('poolcrudRev2023'),
      revenue2024: numOrNull('poolcrudRev2024'),
      revenue2025: numOrNull('poolcrudRev2025'),
      q1GrossMargin: numOrNull('poolcrudQ1GM'),
      q1NetMargin: numOrNull('poolcrudQ1NM'),
      q1RevenueGrowth: numOrNull('poolcrudQ1RG'),
      minPs5y: numOrNull('poolcrudMinPs5y'),
      targetMarketCap: numOrNull('poolcrudTargetMC'),
      displayOrder: intOrNull('poolcrudDisplayOrder')
    };
  }

  function saveModal() {
    var body = readModalToBody();
    if (!editingId && !body.keyword) {
      setMsg('poolcrudEditMsg', '请输入股票代码或名称', true);
      return;
    }
    var btn = $('poolcrudEditSave');
    btn.disabled = true;
    btn.textContent = '保存中...';
    var p;
    if (editingId) {
      // 编辑时后端 PUT /pool/{id} 接受同一 body（keyword 会被忽略）
      p = authFetch('/api/invest/pool/' + editingId, { method: 'PUT', body: body });
    } else {
      p = authFetch('/api/invest/pool', { method: 'POST', body: body });
    }
    p.then(function () {
      hideModal('poolcrudEditModal');
      setMsg('poolcrudPanelMsg', '✓ 已保存', false);
      return loadPool();
    })
    .catch(function (e) {
      setMsg('poolcrudEditMsg', '保存失败：' + e.message, true);
    })
    .then(function () {
      btn.disabled = false;
      btn.textContent = '保存';
    });
  }

  // ── 删除 ──────────────────────────────────────
  function confirmDelete(id) {
    var item = poolData.find(function (x) { return x.id === id; });
    if (!item) return;
    var label = (item.stockCode || '') + ' ' + (item.stockName || '');
    if (!window.confirm('确认从股票池删除 ' + label + '？\n（关联的持仓记录不会被自动删除）')) return;
    authFetch('/api/invest/pool/' + id, { method: 'DELETE' })
      .then(function () {
        setMsg('poolcrudPanelMsg', '✓ 已删除 ' + label, false);
        return loadPool();
      })
      .catch(function (e) {
        setMsg('poolcrudPanelMsg', '删除失败：' + e.message, true);
      });
  }

  // ── OCR 批量导入（多图 + 主选/备选）──────────────────────
  // 阶段：上传文件（带 label）→ 依次 OCR → 合并预览 → 批量入库
  var ocrPendingFiles = []; // [{file, label:'main'/'backup'}]
  var ocrParsedRows = [];   // 识别出的条目，每项带 imageLabel
  var ocrOcrInFlight = false;

  function openOcrModal() {
    ocrPendingFiles = [];
    ocrParsedRows = [];
    var fileInput = $('poolcrudOcrFile');
    if (fileInput) fileInput.value = '';
    var area = $('poolcrudOcrUploadArea');
    if (area) area.classList.remove('hidden');
    var filesBox = $('poolcrudOcrFiles');
    if (filesBox) { filesBox.classList.add('hidden'); filesBox.innerHTML = ''; }
    var table = $('poolcrudOcrPreview');
    if (table) {
      table.classList.add('hidden');
      table.innerHTML = '';
    }
    var btn = $('poolcrudOcrImportBtn');
    if (btn) { btn.disabled = true; btn.textContent = '批量入库'; }
    var msg = $('poolcrudOcrMsg');
    if (msg) { msg.textContent = '请上传截图（PNG/JPG/WebP，可多张）'; msg.className = 'msg'; }
    showModal('poolcrudOcrModal');
  }

  /** 文件 input 改变时回调：把 FileList 转成 ocrPendingFiles。 */
  function handleOcrFileList(fileList) {
    if (!fileList || fileList.length === 0) return;
    // 第一张默认主选，其余默认备选
    var startIndex = ocrPendingFiles.length;
    Array.prototype.forEach.call(fileList, function (f, i) {
      if (!f.type || !/^image\//.test(f.type)) return;
      var defaultLabel = (startIndex + i === 0 && ocrPendingFiles.filter(function (x) { return x.label === 'main'; }).length === 0)
          ? 'main' : 'backup';
      ocrPendingFiles.push({ file: f, label: defaultLabel });
    });
    renderOcrFiles();
  }

  /** 渲染"待上传文件 + 主选/备选 + 删除"列表。 */
  function renderOcrFiles() {
    var box = $('poolcrudOcrFiles');
    if (!box) return;
    if (ocrPendingFiles.length === 0) {
      box.classList.add('hidden');
      box.innerHTML = '';
      return;
    }
    box.classList.remove('hidden');
    var isTechAi = (currentPool === 'tech_ai');
    box.innerHTML = ''
      + '<div style="font-size:13px;color:var(--text-muted);margin-bottom:6px;">已选 ' + ocrPendingFiles.length + ' 张：' + (isTechAi ? '可标注「主选/备选」' : '其他池不展示主选/备选') + '</div>'
      + ocrPendingFiles.map(function (entry, i) {
          var labelInput = isTechAi
              ? '<label style="margin-left:12px;"><input type="radio" name="ocr-label-' + i + '" value="main" ' + (entry.label === 'main' ? 'checked' : '') + ' /> 主选</label>'
              + '<label style="margin-left:6px;"><input type="radio" name="ocr-label-' + i + '" value="backup" ' + (entry.label === 'backup' ? 'checked' : '') + ' /> 备选</label>'
              : '<span style="margin-left:12px;color:var(--text-muted);font-size:12px;">（非 tech_ai 池）</span>';
          return '<div style="display:flex;align-items:center;padding:6px 8px;border:1px solid var(--border);border-radius:4px;margin-bottom:6px;background:var(--bg-elev);">'
            + '<span style="flex:1;font-size:13px;">📷 ' + escHtml(entry.file.name) + ' <span style="color:var(--text-muted);font-size:11px;">(' + Math.round(entry.file.size / 1024) + ' KB)</span></span>'
            + labelInput
            + '<button type="button" class="btn-sm ocr-file-remove" data-idx="' + i + '" style="margin-left:8px;">×</button>'
            + '</div>';
        }).join('')
      + '<div style="margin-top:8px;">'
      +   '<button type="button" class="btn-sm" id="poolcrudOcrAddMore">+ 添加更多</button>'
      +   '<button type="button" class="primary-btn" id="poolcrudOcrStartBtn" style="margin-left:8px;">🔍 开始识别</button>'
      + '</div>';

    // 绑定单选按钮 → 更新 label
    box.querySelectorAll('input[type=radio]').forEach(function (r) {
      r.addEventListener('change', function () {
        var name = r.name;
        var idx = parseInt(name.replace('ocr-label-', ''), 10);
        if (ocrPendingFiles[idx]) ocrPendingFiles[idx].label = r.value;
      });
    });
    // 删除
    box.querySelectorAll('.ocr-file-remove').forEach(function (b) {
      b.addEventListener('click', function () {
        var idx = parseInt(b.getAttribute('data-idx'), 10);
        ocrPendingFiles.splice(idx, 1);
        renderOcrFiles();
      });
    });
    // 添加更多
    var addMore = $('poolcrudOcrAddMore');
    if (addMore) addMore.addEventListener('click', function () {
      var fi = $('poolcrudOcrFile');
      if (fi) fi.click();
    });
    // 开始识别
    var startBtn = $('poolcrudOcrStartBtn');
    if (startBtn) startBtn.addEventListener('click', runOcrBatch);
  }

  /** 串行 OCR 每张图，结果合并到 ocrParsedRows。 */
  async function runOcrBatch() {
    if (ocrOcrInFlight) return;
    if (ocrPendingFiles.length === 0) {
      setMsg('poolcrudOcrMsg', '请先选择截图', true);
      return;
    }
    ocrOcrInFlight = true;
    var msg = $('poolcrudOcrMsg');
    msg.textContent = '解析中，请稍候...';
    msg.className = 'msg';
    var allRows = [];
    var failed = 0;
    var firstErr = null;
    for (var i = 0; i < ocrPendingFiles.length; i++) {
      var entry = ocrPendingFiles[i];
      msg.textContent = '正在解析第 ' + (i + 1) + ' / ' + ocrPendingFiles.length + ' 张：' + entry.file.name;
      try {
        var rows = await ocrOneImage(entry.file, entry.label);
        rows.forEach(function (r) { r.imageLabel = entry.label; });
        allRows = allRows.concat(rows);
      } catch (e) {
        failed++;
        if (!firstErr) firstErr = e.message || String(e);
        console.error('OCR fail:', entry.file.name, e);
      }
    }
    ocrOcrInFlight = false;
    ocrParsedRows = allRows;
    if (allRows.length === 0) {
      var errDetail = firstErr ? '：' + firstErr : '';
      msg.textContent = 'OCR 全部失败（' + failed + ' 张），请检查截图' + errDetail;
      msg.className = 'msg err';
      msg.title = firstErr || '';
      return;
    }
    msg.textContent = '✓ 识别出 ' + allRows.length + ' 条' + (failed > 0 ? '（' + failed + ' 张图失败）' : '') + '，请确认后入库';
    msg.className = failed > 0 ? 'msg warn' : 'msg ok';
    renderOcrPreview(allRows);
  }

  /** 单图 OCR：返回识别条目（不带 imageLabel，由调用方追加）。
   *  2026-07-02 池子重构：所有池子统一走 /api/invest/pool/import-image（按 defaultPoolType 区分）。
   */
  function ocrOneImage(file, label) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () {
        var b64 = String(reader.result).split(',')[1] || '';
        authFetch('/api/invest/pool/import-image', {
          method: 'POST',
          body: { imageBase64: b64, fileName: file.name, defaultPoolType: currentPool }
        })
        .then(function (res) {
          var rows = (res && res.items) ? res.items : (Array.isArray(res) ? res : []);
          resolve(rows);
        })
        .catch(function (e) {
          // 把后端真实错误抛出去，外层累积到 msg
          var detail = (e && e.message) ? e.message : String(e);
          reject(new Error(detail));
        });
      };
      reader.onerror = function () { reject(new Error('读取文件失败')); };
      reader.readAsDataURL(file);
    });
  }

  function renderOcrPreview(rows) {
    var preview = $('poolcrudOcrPreview');
    var area = $('poolcrudOcrUploadArea');
    if (area) area.classList.add('hidden');
    var filesBox = $('poolcrudOcrFiles');
    if (filesBox) filesBox.classList.add('hidden');
    if (!preview) return;
    preview.classList.remove('hidden');
    var showLabelCol = (currentPool === 'tech_ai');
    var labelHeader = showLabelCol ? '<th>主选/备选</th>' : '';
    preview.innerHTML = ''
      + '<table class="poolcrud-ocr-table">'
      + '<thead><tr>'
      + '<th><input type="checkbox" id="poolcrudOcrAll" checked /></th>'
      + '<th>代码</th><th>名称</th><th>分类</th>'
      + labelHeader
      + '</tr></thead>'
      + '<tbody>'
      + rows.map(function (r, i) {
          var poolType = r.poolType || currentPool;
          var labelCell = showLabelCol
              ? '<td><select class="form-input poolcrud-ocr-label">'
              +   '<option value="main"' + (r.imageLabel === 'main' ? ' selected' : '') + '>主选</option>'
              +   '<option value="backup"' + (r.imageLabel === 'backup' ? ' selected' : '') + '>备选</option>'
              + '</select></td>'
              : '';
          return '<tr data-idx="' + i + '">'
            + '<td><input type="checkbox" class="poolcrud-ocr-row" checked /></td>'
            + '<td><input type="text" class="form-input poolcrud-ocr-code" value="' + escHtml(r.stockCode || '') + '" /></td>'
            + '<td><input type="text" class="form-input poolcrud-ocr-name" value="' + escHtml(r.stockName || '') + '" /></td>'
            + '<td><select class="form-input poolcrud-ocr-pool">'
            +   '<option value="tech_ai"' + (poolType === 'tech_ai' ? ' selected' : '') + '>科技AI（10×PS）</option>'
            +   '<option value="innovative_drug"' + (poolType === 'innovative_drug' ? ' selected' : '') + '>创新药</option>'
            +   '<option value="quality"' + (poolType === 'quality' ? ' selected' : '') + '>质量优选</option>'
            + '</select></td>'
            + labelCell
            + '</tr>';
        }).join('')
      + '</tbody></table>';

    $('poolcrudOcrAll').addEventListener('change', function (e) {
      preview.querySelectorAll('.poolcrud-ocr-row').forEach(function (c) { c.checked = e.target.checked; });
    });
    var importBtn = $('poolcrudOcrImportBtn');
    if (importBtn) importBtn.disabled = false;
  }

  /** 2026-07-02 池子重构：所有池子统一走 /api/invest/pool/batch-import，tech_ai 走 invest 默认分支。 */
  function submitOcrImport() {
    var preview = $('poolcrudOcrPreview');
    if (!preview) return;
    var rows = preview.querySelectorAll('tbody tr');
    var picked = [];
    rows.forEach(function (tr) {
      var cb = tr.querySelector('.poolcrud-ocr-row');
      if (!cb || !cb.checked) return;
      var item = {
        stockCode: tr.querySelector('.poolcrud-ocr-code').value.trim(),
        stockName: tr.querySelector('.poolcrud-ocr-name').value.trim(),
        poolType: tr.querySelector('.poolcrud-ocr-pool').value
      };
      var labelEl = tr.querySelector('.poolcrud-ocr-label');
      if (labelEl) item.imageLabel = labelEl.value;
      // 携带完整 PS 表字段（如果有）
      var idx = parseInt(tr.getAttribute('data-idx'), 10);
      var src = ocrParsedRows[idx];
      if (src) {
        item.revenue2023 = src.revenue2023;
        item.revenue2024 = src.revenue2024;
        item.revenue2025 = src.revenue2025;
        item.revenueForecastY0 = src.revenueForecastY0;
        item.revenueForecastY1 = src.revenueForecastY1;
        item.revenueForecastY2 = src.revenueForecastY2;
        item.q1GrossMargin = src.q1GrossMargin;
        item.q1NetMargin = src.q1NetMargin;
        item.q1RevenueGrowth = src.q1RevenueGrowth;
        item.minPs5y = src.minPs5y;
        item.currentMarketCap = src.currentMarketCap;
        item.ytdGainPct = src.ytdGainPct;
      }
      picked.push(item);
    });
    if (picked.length === 0) {
      setMsg('poolcrudOcrMsg', '请至少勾选一条', true);
      return;
    }
    var btn = $('poolcrudOcrImportBtn');
    btn.disabled = true;
    btn.textContent = '入库中...';

    // 2026-07-02 池子重构：所有池子（含 tech_ai）统一走 /api/invest/pool/batch-import。
    // tech_ai 池仍支持 imageLabel（主选/备选），由 OcrPoolImportService 根据 poolType 分发。
    var url = '/api/invest/pool/batch-import';
    var body = { items: picked };

    authFetch(url, {
      method: 'POST',
      body: body
    })
    .then(function (res) {
      var inserted = (res && (res.inserted || res.successCount)) || picked.length;
      hideModal('poolcrudOcrModal');
      setMsg('poolcrudPanelMsg', '✓ 已入库 ' + inserted + ' 只', false);
      // 切到对应分类的 tab 并刷新
      var firstPool = picked[0].poolType;
      if (firstPool && firstPool !== currentPool) {
        currentPool = firstPool;
        syncTabs();
      }
      return loadPool();
    })
    .catch(function (e) {
      setMsg('poolcrudOcrMsg', '入库失败：' + e.message, true);
    })
    .then(function () {
      btn.disabled = false;
      btn.textContent = '批量入库';
    });
  }

  // ── Modal 显隐 ──────────────────────────────────
  function showModal(id) { $(id).classList.remove('hidden'); }
  function hideModal(id) { $(id).classList.add('hidden'); }

  // ── Tab 切换 ──────────────────────────────────
  function syncTabs() {
    document.querySelectorAll('.poolcrud-tab').forEach(function (b) {
      b.classList.toggle('active', b.dataset.pool === currentPool);
    });
  }

  function switchPool(pool) {
    if (!pool || pool === currentPool) return;
    currentPool = pool;
    syncTabs();
    searchKeyword = '';
    var search = $('poolcrudSearch');
    if (search) search.value = '';
    loadPool();
  }

  // ── 初始化入口（admin-users.html 调用） ─────────────
  window.initPoolCrudPanel = function () {
    // 分类 tab
    document.querySelectorAll('.poolcrud-tab').forEach(function (b) {
      b.addEventListener('click', function () { switchPool(b.dataset.pool); });
    });

    // 工具栏按钮
    var addBtn = $('poolcrudAddBtn');
    if (addBtn) addBtn.addEventListener('click', openAddModal);
    var importBtn = $('poolcrudImportBtn');
    if (importBtn) importBtn.addEventListener('click', openOcrModal);
    var refreshBtn = $('poolcrudRefreshBtn');
    if (refreshBtn) refreshBtn.addEventListener('click', function () { loadPool(); });

    // 搜索
    var search = $('poolcrudSearch');
    if (search) search.addEventListener('input', function () {
      searchKeyword = search.value || '';
      renderTable();
    });

    // Edit modal
    var editClose = $('poolcrudEditClose');
    if (editClose) editClose.addEventListener('click', function () { hideModal('poolcrudEditModal'); });
    var editCancel = $('poolcrudEditCancel');
    if (editCancel) editCancel.addEventListener('click', function () { hideModal('poolcrudEditModal'); });
    var editSave = $('poolcrudEditSave');
    if (editSave) editSave.addEventListener('click', saveModal);
    var editMask = $('poolcrudEditModal');
    if (editMask) editMask.addEventListener('click', function (e) {
      if (e.target === editMask) hideModal('poolcrudEditModal');
    });

    // OCR modal
    var ocrClose = $('poolcrudOcrClose');
    if (ocrClose) ocrClose.addEventListener('click', function () { hideModal('poolcrudOcrModal'); });
    var ocrCancel = $('poolcrudOcrCancel');
    if (ocrCancel) ocrCancel.addEventListener('click', function () { hideModal('poolcrudOcrModal'); });
    var ocrImportBtn = $('poolcrudOcrImportBtn');
    if (ocrImportBtn) ocrImportBtn.addEventListener('click', submitOcrImport);
    var ocrMask = $('poolcrudOcrModal');
    if (ocrMask) ocrMask.addEventListener('click', function (e) {
      if (e.target === ocrMask) hideModal('poolcrudOcrModal');
    });

    // OCR 上传（多图）
    var ocrUploadArea = $('poolcrudOcrUploadArea');
    var ocrFile = $('poolcrudOcrFile');
    if (ocrUploadArea && ocrFile) {
      ocrUploadArea.addEventListener('click', function () { ocrFile.click(); });
      ocrFile.addEventListener('change', function () {
        if (ocrFile.files && ocrFile.files.length) handleOcrFileList(ocrFile.files);
        // 清空 value，否则选同一组文件不会触发 change
        ocrFile.value = '';
      });
      ocrUploadArea.addEventListener('dragover', function (e) {
        e.preventDefault();
        ocrUploadArea.classList.add('dragover');
      });
      ocrUploadArea.addEventListener('dragleave', function () {
        ocrUploadArea.classList.remove('dragover');
      });
      ocrUploadArea.addEventListener('drop', function (e) {
        e.preventDefault();
        ocrUploadArea.classList.remove('dragover');
        var fs = e.dataTransfer.files;
        if (fs && fs.length) handleOcrFileList(fs);
      });
    }

    syncTabs();
    loadPool();
  };
})();