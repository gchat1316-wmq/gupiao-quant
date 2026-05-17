(function () {
  'use strict';

  const SVG_NS = 'http://www.w3.org/2000/svg';
  const NODE_W = 140;
  const NODE_H = 28;
  const X_GAP = 200;
  const Y_GAP = 36;
  const PAD = 24;

  // tree: KnowledgeNodeDTO[] (一组根节点)
  // options: { onClick(node), currentId }
  window.renderMindmap = function (container, tree, options) {
    options = options || {};
    container.innerHTML = '';
    if (!tree || tree.length === 0) {
      container.innerHTML = '<div style="color:#9ca3af;padding:24px;">暂无知识图谱</div>';
      return null;
    }

    // 1) 给每个节点计算权重 (叶=1, 内部=子节点权重和)
    function calcWeight(n) {
      if (!n.children || n.children.length === 0) {
        n._w = 1;
      } else {
        n._w = n.children.reduce(function (s, c) { return s + calcWeight(c); }, 0);
      }
      return n._w;
    }
    tree.forEach(calcWeight);

    // 2) 布局: 多个根纵向堆叠
    let maxLevelX = 0;
    let totalH = 0;

    function layout(n, x, yStart) {
      n._x = x;
      n._y = yStart + (n._w * Y_GAP) / 2;
      if (x > maxLevelX) maxLevelX = x;
      if (n.children && n.children.length) {
        let y = yStart;
        n.children.forEach(function (c) {
          layout(c, x + X_GAP, y);
          y += c._w * Y_GAP;
        });
      }
    }

    let cursorY = PAD;
    tree.forEach(function (root) {
      layout(root, PAD, cursorY);
      cursorY += root._w * Y_GAP;
    });
    totalH = cursorY + PAD;
    const totalW = maxLevelX + NODE_W + PAD;

    // 3) 构造 SVG
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('width', '100%');
    svg.setAttribute('height', '100%');
    svg.setAttribute('viewBox', '0 0 ' + totalW + ' ' + totalH);
    svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    svg.style.display = 'block';
    svg.style.userSelect = 'none';
    svg.style.cursor = 'grab';

    const zoomG = document.createElementNS(SVG_NS, 'g');
    zoomG.setAttribute('class', 'mm-zoom-pan');
    svg.appendChild(zoomG);

    function drawEdges(n) {
      if (!n.children) return;
      n.children.forEach(function (c) {
        const path = document.createElementNS(SVG_NS, 'path');
        const x1 = n._x + NODE_W;
        const y1 = n._y;
        const x2 = c._x;
        const y2 = c._y;
        const mx = (x1 + x2) / 2;
        path.setAttribute('d',
          'M' + x1 + ',' + y1 +
          ' C' + mx + ',' + y1 + ' ' + mx + ',' + y2 + ' ' + x2 + ',' + y2);
        path.setAttribute('class', 'mindmap-edge');
        zoomG.appendChild(path);
        drawEdges(c);
      });
    }
    tree.forEach(drawEdges);

    function drawNodes(n, isRoot) {
      const g = document.createElementNS(SVG_NS, 'g');
      g.setAttribute('transform', 'translate(' + n._x + ',' + (n._y - NODE_H / 2) + ')');

      const rect = document.createElementNS(SVG_NS, 'rect');
      rect.setAttribute('width', NODE_W);
      rect.setAttribute('height', NODE_H);
      rect.setAttribute('rx', 6);
      rect.setAttribute('ry', 6);
      let cls = 'mindmap-node-rect';
      if (isRoot) cls += ' root';
      if (options.currentId && n.id === options.currentId) cls += ' current';
      rect.setAttribute('class', cls);
      g.appendChild(rect);

      const text = document.createElementNS(SVG_NS, 'text');
      text.setAttribute('x', NODE_W / 2);
      text.setAttribute('y', NODE_H / 2 + 4);
      text.setAttribute('text-anchor', 'middle');
      text.setAttribute('class', 'mindmap-node-text' + (isRoot ? ' root' : ''));
      const label = (n.title || '').length > 12 ? (n.title.substring(0, 12) + '…') : (n.title || '');
      text.textContent = label;
      g.appendChild(text);

      // tooltip
      const titleEl = document.createElementNS(SVG_NS, 'title');
      titleEl.textContent = n.title || '';
      g.appendChild(titleEl);

      if (options.onClick) {
        g.style.cursor = 'pointer';
        g.addEventListener('click', function (e) {
          if (g._dragged) { g._dragged = false; return; }
          e.stopPropagation();
          options.onClick(n);
        });
      }

      zoomG.appendChild(g);

      if (n.children) n.children.forEach(function (c) { drawNodes(c, false); });
    }
    tree.forEach(function (root) { drawNodes(root, true); });

    container.appendChild(svg);

    // ===== Zoom & Pan =====
    let scale = 1;
    let tx = 0;
    let ty = 0;
    const SCALE_MIN = 0.3;
    const SCALE_MAX = 4;

    function apply() {
      zoomG.setAttribute('transform', 'translate(' + tx + ',' + ty + ') scale(' + scale + ')');
    }

    function svgPointFromEvent(e) {
      const pt = svg.createSVGPoint();
      pt.x = e.clientX;
      pt.y = e.clientY;
      const ctm = svg.getScreenCTM();
      if (!ctm) return { x: 0, y: 0 };
      const p = pt.matrixTransform(ctm.inverse());
      return { x: p.x, y: p.y };
    }

    function zoomAtCursor(deltaScale, cursorX, cursorY) {
      const newScale = Math.min(SCALE_MAX, Math.max(SCALE_MIN, scale * deltaScale));
      const ratio = newScale / scale;
      tx = cursorX - (cursorX - tx) * ratio;
      ty = cursorY - (cursorY - ty) * ratio;
      scale = newScale;
      apply();
    }

    svg.addEventListener('wheel', function (e) {
      e.preventDefault();
      const p = svgPointFromEvent(e);
      const factor = e.deltaY > 0 ? 0.9 : 1.1;
      zoomAtCursor(factor, p.x, p.y);
    }, { passive: false });

    // drag-to-pan
    let isDragging = false;
    let dragMoved = false;
    let lastX = 0, lastY = 0;
    svg.addEventListener('mousedown', function (e) {
      isDragging = true;
      dragMoved = false;
      svg.style.cursor = 'grabbing';
      const p = svgPointFromEvent(e);
      lastX = p.x; lastY = p.y;
    });
    window.addEventListener('mousemove', function (e) {
      if (!isDragging) return;
      const p = svgPointFromEvent(e);
      const dx = p.x - lastX;
      const dy = p.y - lastY;
      if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) dragMoved = true;
      tx += dx;
      ty += dy;
      lastX = p.x;
      lastY = p.y;
      apply();
      if (dragMoved) {
        Array.from(zoomG.querySelectorAll('g')).forEach(function (g) { g._dragged = true; });
      }
    });
    window.addEventListener('mouseup', function () {
      if (!isDragging) return;
      isDragging = false;
      svg.style.cursor = 'grab';
      setTimeout(function () {
        Array.from(zoomG.querySelectorAll('g')).forEach(function (g) { g._dragged = false; });
      }, 80);
    });

    // Touch (mobile) - single finger pan
    let touchLast = null;
    svg.addEventListener('touchstart', function (e) {
      if (e.touches.length === 1) {
        const p = svgPointFromEvent(e.touches[0]);
        touchLast = { x: p.x, y: p.y };
      }
    }, { passive: true });
    svg.addEventListener('touchmove', function (e) {
      if (e.touches.length === 1 && touchLast) {
        const p = svgPointFromEvent(e.touches[0]);
        tx += (p.x - touchLast.x);
        ty += (p.y - touchLast.y);
        touchLast = { x: p.x, y: p.y };
        apply();
        e.preventDefault();
      }
    }, { passive: false });

    // Public API
    const api = {
      zoomIn:  function () { zoomAtCursor(1.2, totalW / 2, totalH / 2); },
      zoomOut: function () { zoomAtCursor(0.8, totalW / 2, totalH / 2); },
      fit:     function () { scale = 1; tx = 0; ty = 0; apply(); }
    };

    apply();
    return api;
  };
})();
