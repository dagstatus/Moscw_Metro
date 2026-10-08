'use strict';
// Метро Москвы — Telegram Mini App. Данные: metro.json (собирается tools/Build.java из data/*.csv).
// Логика та же, что в Android-приложении: MetroData.java, Router.java, MetroMapView.java.

const tg = window.Telegram && window.Telegram.WebApp && window.Telegram.WebApp.initData !== undefined ? window.Telegram.WebApp : null;
const $ = id => document.getElementById(id);

// ---------------------------------------------------------------- данные

function normalize(s) {
  return s.toLowerCase().replace(/ё/g, 'е').replace(/[-.]/g, ' ').replace(/\s+/g, ' ').trim();
}

function loadData(json) {
  const d = { lines: json.lines, stations: json.stations, edges: json.edges, transfers: json.transfers,
    updated: json.updated, source: json.source, version: json.version };
  d.stations.forEach((s, i) => { s.idx = i; s.line = d.lines.findIndex(l => l.id === s.l); });
  // пересадочные узлы
  const parent = d.stations.map((_, i) => i);
  const find = x => { while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; };
  for (const [a, b] of d.transfers) { const ra = find(a), rb = find(b); if (ra !== rb) parent[ra] = rb; }
  const gmap = new Map();
  d.stations.forEach(s => { const r = find(s.idx); if (!gmap.has(r)) gmap.set(r, gmap.size); s.group = gmap.get(r); });
  d.groupCount = gmap.size;
  // пункты поиска: одно название в одном узле
  const byKey = new Map();
  for (const s of d.stations) {
    const k = s.group + '|' + normalize(s.n);
    if (!byKey.has(k)) byKey.set(k, []);
    byKey.get(k).push(s.idx);
  }
  d.entries = [];
  d.entryOf = [];
  for (const idxs of byKey.values()) {
    const e = { name: d.stations[idxs[0]].n, stations: idxs, index: 0 };
    e.norm = normalize(e.name);
    e.colors = [...new Set(idxs.map(i => d.lines[d.stations[i].line].color))];
    e.lineNames = idxs.map(i => d.lines[d.stations[i].line].name).join(', ');
    idxs.forEach(i => d.entryOf[i] = e);
    d.entries.push(e);
  }
  const coll = new Intl.Collator('ru');
  d.entries.sort((a, b) => coll.compare(a.name, b.name));
  d.entries.forEach((e, i) => e.index = i);
  d.isTransfer = new Array(d.stations.length).fill(false);
  for (const [a, b] of d.transfers) { d.isTransfer[a] = d.isTransfer[b] = true; }
  // границы
  const bb = () => ({ l: Infinity, t: Infinity, r: -Infinity, b: -Infinity });
  d.bounds = bb(); d.core = bb();
  const grow = (r, s) => { r.l = Math.min(r.l, s.x); r.t = Math.min(r.t, s.y); r.r = Math.max(r.r, s.x); r.b = Math.max(r.b, s.y); };
  const ring = d.lines.find(l => l.id === '11');
  d.stations.forEach(s => { grow(d.bounds, s); if (ring ? s.line === d.lines.indexOf(ring) : d.lines[s.line].type !== 'mcd') grow(d.core, s); });
  // граф
  d.adj = d.stations.map(() => []);
  for (const [a, b, m] of d.edges) { d.adj[a].push([b, m, false]); d.adj[b].push([a, m, false]); }
  for (const [a, b, m] of d.transfers) {
    d.adj[a].push([b, m + d.lines[d.stations[b].line].wait, true]);
    d.adj[b].push([a, m + d.lines[d.stations[a].line].wait, true]);
  }
  return d;
}

function search(d, query, limit) {
  const q = normalize(query);
  if (!q) return [];
  const a = [], b = [], c = [];
  for (const e of d.entries) {
    if (e.norm.startsWith(q)) a.push(e);
    else if (e.norm.includes(' ' + q)) b.push(e);
    else if (e.norm.includes(q)) c.push(e);
  }
  return a.concat(b, c).slice(0, limit);
}

// ---------------------------------------------------------------- маршрут (Дейкстра)

function findRoute(d, from, to) {
  const n = d.stations.length;
  const dist = new Float64Array(n).fill(Infinity), prev = new Int32Array(n).fill(-1), prevWalk = new Uint8Array(n), done = new Uint8Array(n);
  const target = new Uint8Array(n);
  to.forEach(t => target[t] = 1);
  const heap = [];
  const push = (c, v) => { heap.push([c, v]); let i = heap.length - 1; while (i > 0) { const p = (i - 1) >> 1; if (heap[p][0] <= heap[i][0]) break; [heap[p], heap[i]] = [heap[i], heap[p]]; i = p; } };
  const pop = () => { const top = heap[0], last = heap.pop(); if (heap.length) { heap[0] = last; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < heap.length && heap[l][0] < heap[m][0]) m = l; if (r < heap.length && heap[r][0] < heap[m][0]) m = r; if (m === i) break; [heap[m], heap[i]] = [heap[i], heap[m]]; i = m; } } return top; };
  for (const s of from) { if (target[s]) return null; dist[s] = 0; push(0, s); }
  let end = -1;
  while (heap.length) {
    const [, u] = pop();
    if (done[u]) continue;
    done[u] = 1;
    if (target[u]) { end = u; break; }
    for (const [v, c, w] of d.adj[u]) {
      const nd = dist[u] + c;
      if (nd < dist[v] - 1e-4) { dist[v] = nd; prev[v] = u; prevWalk[v] = w ? 1 : 0; push(nd, v); }
    }
  }
  if (end < 0) return null;
  const nodes = [], walks = [];
  for (let v = end; v >= 0; v = prev[v]) { nodes.unshift(v); walks.unshift(prevWalk[v]); }
  const route = { nodes, steps: [], minutes: 0, transfers: 0, stops: 0 };
  const ride = v => ({ walk: false, line: d.stations[v].line, stations: [v], minutes: 0 });
  let cur = ride(nodes[0]);
  for (let i = 1; i < nodes.length; i++) {
    const u = nodes[i - 1], v = nodes[i], c = dist[v] - dist[u];
    if (walks[i]) {
      if (cur.walk) { cur.stations.push(v); cur.minutes += c; }
      else { if (cur.stations.length > 1) route.steps.push(cur); cur = { walk: true, stations: [u, v], minutes: c }; }
    } else {
      if (cur.walk) { route.steps.push(cur); cur = ride(u); }
      cur.stations.push(v); cur.minutes += c;
    }
  }
  if (cur.walk || cur.stations.length > 1) route.steps.push(cur);
  let first = true;
  for (const s of route.steps) {
    if (s.walk) {
      route.transfers++;
      s.minutes = Math.max(1, s.minutes - d.lines[d.stations[s.stations[s.stations.length - 1]].line].wait);
    } else {
      route.stops += s.stations.length - 1;
      if (first) { route.minutes += d.lines[s.line].wait; first = false; }
    }
  }
  route.minutes += dist[end];
  return route;
}

function direction(d, step) {
  const l = d.lines[step.line];
  if (l.ring || step.stations.length < 2) return null;
  const a = step.stations[step.stations.length - 2], b = step.stations[step.stations.length - 1];
  for (const p of l.paths) for (let k = 0; k + 1 < p.length; k++) {
    if (p[k] === a && p[k + 1] === b) return d.stations[p[p.length - 1]].n;
    if (p[k] === b && p[k + 1] === a) return d.stations[p[0]].n;
  }
  return null;
}

const plural = (n, one, few, many) => { const a = n % 10, b = n % 100; return a === 1 && b !== 11 ? one : a >= 2 && a <= 4 && (b < 12 || b > 14) ? few : many; };
const fmtMin = m => m < 60 ? m + ' мин' : Math.floor(m / 60) + ' ч ' + (m % 60) + ' мин';
const shortName = l => l.type === 'mcd' ? l.name.split(' ')[0] : l.type === 'mcc' ? 'МЦК' : l.name + ' линия';
const esc = s => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const dotsHtml = colors => '<span class="dots">' + colors.map(c => `<span style="color:${c}">●</span>`).join('') + '</span>';

// ---------------------------------------------------------------- схема

const PALETTE = {
  light: { bg: '#F6F6F3', text: '#1C1C1F', connector: '#C4C4C4', from: '#1E8E3E', to: '#D93025', dim: 'rgba(246,246,243,0.75)' },
  dark: { bg: '#121316', text: '#E8E8EA', connector: '#55585E', from: '#4CC274', to: '#FF6B5E', dim: 'rgba(18,19,22,0.78)' },
};

class MapView {
  constructor(canvas, d) {
    this.c = canvas; this.d = d; this.ctx = canvas.getContext('2d');
    this.scale = 1; this.tx = 0; this.ty = 0; this.insetBottom = 0; this.insetTop = 0;
    this.pal = PALETTE.light; this.route = null; this.from = null; this.to = null;
    this.labelCache = new Map();
    this.onTap = null;
    this.dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    this.font = '12.5px -apple-system, system-ui, Roboto, sans-serif';
    this.boldFont = '600 13.5px -apple-system, system-ui, Roboto, sans-serif';
    // порядок подписей: крупные узлы, затем метро, затем МЦД
    const gs = new Array(d.groupCount).fill(0);
    d.stations.forEach(s => gs[s.group]++);
    const prio = e => { const s = d.stations[e.stations[0]]; const t = d.lines[s.line].type; return gs[s.group] * 10 + (t === 'metro' ? 3 : t === 'mcc' ? 2 : 0); };
    this.labelOrder = d.entries.slice().sort((a, b) => prio(b) - prio(a)).map(e => e.index);
    this.measure();
    this.bindGestures();
    new ResizeObserver(() => this.resize()).observe(document.body);
    this.resize();
  }

  measure() {
    const ctx = this.ctx;
    ctx.font = this.font;
    this.textH = 16;
    this.labelW = this.d.entries.map(e => ctx.measureText(e.name).width);
    ctx.font = this.boldFont;
    this.labelWB = this.d.entries.map(e => ctx.measureText(e.name).width);
  }

  setPalette(p) { this.pal = p; this.draw(); }
  setSelection(from, to, route) { this.from = from; this.to = to; this.route = route; this.labelCache.clear(); this.draw(); }

  resize() {
    const w = window.innerWidth, h = window.innerHeight;
    this.w = w; this.h = h;
    this.c.width = Math.round(w * this.dpr); this.c.height = Math.round(h * this.dpr);
    const B = this.d.bounds;
    this.minScale = Math.min(w / (B.r - B.l + 200), h / (B.b - B.t + 200)) * 0.9;
    this.maxScale = Math.max(this.minScale * 6, w * 9 / (this.d.core.r - this.d.core.l));
    if (!this.positioned) { this.positioned = true; this.fit(this.d.core, false); }
    this.draw();
  }

  fit(r, animate) {
    const pad = 16, side = r.r - r.l < 3000 ? 64 : 16, top = this.insetTop + 8, ah = Math.max(this.h * 0.25, this.h - top - this.insetBottom - 2 * pad), aw = this.w - 2 * side;
    let s = Math.min(aw / Math.max(1, r.r - r.l), ah / Math.max(1, r.b - r.t));
    s = Math.max(this.minScale, Math.min(this.maxScale, s));
    const cx = this.w / 2, cy = top + pad + ah / 2;
    const tx = cx - (r.l + r.r) / 2 * s, ty = cy - (r.t + r.b) / 2 * s;
    if (animate) this.animateTo(s, tx, ty); else { this.scale = s; this.tx = tx; this.ty = ty; this.draw(); }
  }

  fitAll() { this.fit(this.d.core, true); }
  fitRoute() {
    if (!this.route) return;
    const r = { l: Infinity, t: Infinity, r: -Infinity, b: -Infinity };
    for (const n of this.route.nodes) { const s = this.d.stations[n]; r.l = Math.min(r.l, s.x); r.t = Math.min(r.t, s.y); r.r = Math.max(r.r, s.x); r.b = Math.max(r.b, s.y); }
    r.l -= 40; r.t -= 40; r.r += 40; r.b += 40;
    this.fit(r, true);
  }
  focus(st) {
    const s = this.d.stations[st];
    const t = Math.max(this.scale, Math.min(this.maxScale, this.minScale * 5));
    this.animateTo(t, this.w / 2 - s.x * t, (this.insetTop + this.h - this.insetBottom) / 2 - s.y * t);
  }

  animateTo(s1, tx1, ty1) {
    cancelAnimationFrame(this.anim);
    const s0 = this.scale, tx0 = this.tx, ty0 = this.ty, t0 = performance.now();
    const step = now => {
      const f = Math.min(1, (now - t0) / 380), e = 1 - Math.pow(1 - f, 2);
      this.scale = s0 * Math.pow(s1 / s0, e);
      const k = Math.abs(s1 - s0) < 1e-9 ? e : (this.scale - s0) / (s1 - s0);
      this.tx = tx0 + (tx1 - tx0) * k; this.ty = ty0 + (ty1 - ty0) * k;
      this.draw();
      if (f < 1) this.anim = requestAnimationFrame(step);
    };
    this.anim = requestAnimationFrame(step);
  }

  zoomAt(f, fx, fy) {
    const ns = Math.max(this.minScale, Math.min(this.maxScale, this.scale * f)), k = ns / this.scale;
    this.tx = fx - (fx - this.tx) * k; this.ty = fy - (fy - this.ty) * k; this.scale = ns;
    this.clamp(); this.draw();
  }

  clamp() {
    const B = this.d.bounds, s = this.scale;
    const l = B.l * s + this.tx, r = B.r * s + this.tx, t = B.t * s + this.ty, b = B.b * s + this.ty;
    if (r < this.w * .35) this.tx += this.w * .35 - r;
    if (l > this.w * .65) this.tx -= l - this.w * .65;
    if (b < this.h * .35) this.ty += this.h * .35 - b;
    if (t > this.h * .65) this.ty -= t - this.h * .65;
  }

  bindGestures() {
    const pts = new Map();
    let last = null, moved = 0, downAt = 0, lastTap = 0, vel = [0, 0], lastMove = 0;
    const c = this.c;
    c.addEventListener('pointerdown', e => {
      c.setPointerCapture(e.pointerId);
      pts.set(e.pointerId, [e.clientX, e.clientY]);
      cancelAnimationFrame(this.anim);
      if (pts.size === 1) { moved = 0; downAt = performance.now(); vel = [0, 0]; }
      last = null;
    });
    c.addEventListener('pointermove', e => {
      if (!pts.has(e.pointerId)) return;
      const p = pts.get(e.pointerId), dx = e.clientX - p[0], dy = e.clientY - p[1];
      pts.set(e.pointerId, [e.clientX, e.clientY]);
      if (pts.size === 1) {
        moved += Math.abs(dx) + Math.abs(dy);
        this.tx += dx; this.ty += dy; this.clamp(); this.draw();
        const now = performance.now(), dt = Math.max(1, now - lastMove); lastMove = now;
        vel = [dx / dt, dy / dt];
      } else if (pts.size === 2) {
        const [a, b] = [...pts.values()];
        const dist = Math.hypot(a[0] - b[0], a[1] - b[1]), cx = (a[0] + b[0]) / 2, cy = (a[1] + b[1]) / 2;
        if (last) { this.tx += cx - last.cx; this.ty += cy - last.cy; this.zoomAt(dist / last.dist, cx, cy); }
        last = { dist, cx, cy };
        moved += 100;
      }
    });
    const up = e => {
      if (!pts.has(e.pointerId)) return;
      pts.delete(e.pointerId);
      last = null;
      if (pts.size > 0) return;
      const now = performance.now();
      if (moved < 10 && now - downAt < 400) {
        if (now - lastTap < 300) { lastTap = 0; clearTimeout(this.tapTimer); const t = Math.min(this.maxScale, this.scale * 2.2); this.animateTo(t, e.clientX - (e.clientX - this.tx) * t / this.scale, e.clientY - (e.clientY - this.ty) * t / this.scale); return; }
        lastTap = now;
        const x = e.clientX, y = e.clientY;
        this.tapTimer = setTimeout(() => { const s = this.hit(x, y); if (s >= 0 && this.onTap) this.onTap(s); }, 260);
      } else if (now - lastMove < 60 && Math.hypot(vel[0], vel[1]) > 0.3) {
        // инерция после свайпа
        let [vx, vy] = vel, prevT = now;
        const fling = t => {
          const dt = t - prevT; prevT = t;
          this.tx += vx * dt; this.ty += vy * dt; vx *= Math.pow(0.995, dt); vy *= Math.pow(0.995, dt);
          this.clamp(); this.draw();
          if (Math.hypot(vx, vy) > 0.02) this.anim = requestAnimationFrame(fling);
        };
        this.anim = requestAnimationFrame(fling);
      }
    };
    c.addEventListener('pointerup', up);
    c.addEventListener('pointercancel', up);
    c.addEventListener('wheel', e => { e.preventDefault(); this.zoomAt(Math.exp(-e.deltaY * 0.0015), e.clientX, e.clientY); }, { passive: false });
  }

  hit(x, y) {
    let best = 26 * 26, res = -1;
    for (const s of this.d.stations) {
      const dx = s.x * this.scale + this.tx - x, dy = s.y * this.scale + this.ty - y, dd = dx * dx + dy * dy;
      if (dd < best) { best = dd; res = s.idx; }
    }
    return res;
  }

  lineWidth() { return Math.max(1.7 / this.scale, Math.min(5.5 / this.scale, 6)); }

  draw() {
    if (this.raf) return;
    this.raf = requestAnimationFrame(() => { this.raf = 0; this.render(); });
  }

  render() {
    const ctx = this.ctx, d = this.d, p = this.pal, lw = this.lineWidth(), rs = lw * 0.82;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.fillStyle = p.bg; ctx.fillRect(0, 0, this.w, this.h);
    ctx.save();
    ctx.translate(this.tx, this.ty); ctx.scale(this.scale, this.scale);
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    // переходы
    ctx.strokeStyle = p.connector; ctx.lineWidth = lw * 1.5; ctx.beginPath();
    for (const [a, b] of d.transfers) { const A = d.stations[a], B = d.stations[b]; ctx.moveTo(A.x, A.y); ctx.lineTo(B.x, B.y); }
    ctx.stroke();
    // линии
    for (const l of d.lines) {
      const w = l.type === 'metro' ? lw : lw * 0.85;
      const path = new Path2D();
      for (const pth of l.paths) pth.forEach((i, k) => { const s = d.stations[i]; k ? path.lineTo(s.x, s.y) : path.moveTo(s.x, s.y); });
      ctx.strokeStyle = l.color; ctx.lineWidth = w; ctx.stroke(path);
      if (l.type !== 'metro') { ctx.strokeStyle = p.bg; ctx.lineWidth = w * 0.38; ctx.stroke(path); }
    }
    // станции
    for (const s of d.stations) {
      const col = d.lines[s.line].color;
      ctx.beginPath();
      if (d.isTransfer[s.idx]) {
        ctx.arc(s.x, s.y, rs * 1.05, 0, 7); ctx.fillStyle = p.bg; ctx.fill();
        ctx.beginPath(); ctx.arc(s.x, s.y, rs * 0.9, 0, 7); ctx.strokeStyle = col; ctx.lineWidth = rs * 0.55; ctx.stroke();
      } else {
        ctx.arc(s.x, s.y, rs * 0.85, 0, 7); ctx.fillStyle = col; ctx.fill();
      }
    }
    ctx.restore();

    if (this.route) {
      ctx.fillStyle = p.dim; ctx.fillRect(0, 0, this.w, this.h);
      ctx.save(); ctx.translate(this.tx, this.ty); ctx.scale(this.scale, this.scale);
      const rl = lw * 1.35, rr = rs * 1.2;
      for (const st of this.route.steps) {
        ctx.beginPath();
        st.stations.forEach((i, k) => { const s = d.stations[i]; k ? ctx.lineTo(s.x, s.y) : ctx.moveTo(s.x, s.y); });
        if (st.walk) { ctx.strokeStyle = p.text; ctx.lineWidth = rl * 0.45; ctx.setLineDash([rl * 0.6, rl * 0.9]); }
        else { ctx.strokeStyle = d.lines[st.line].color; ctx.lineWidth = rl; ctx.setLineDash([]); }
        ctx.stroke();
      }
      ctx.setLineDash([]);
      for (const n of this.route.nodes) {
        const s = d.stations[n];
        ctx.beginPath(); ctx.arc(s.x, s.y, rr, 0, 7); ctx.fillStyle = p.bg; ctx.fill();
        ctx.beginPath(); ctx.arc(s.x, s.y, rr * 0.85, 0, 7); ctx.strokeStyle = d.lines[s.line].color; ctx.lineWidth = rr * 0.5; ctx.stroke();
      }
      ctx.restore();
    }
    for (const [e, col] of [[this.from, p.from], [this.to, p.to]]) {
      if (!e) continue;
      for (const st of e.stations) {
        const s = d.stations[st], x = s.x * this.scale + this.tx, y = s.y * this.scale + this.ty;
        ctx.beginPath(); ctx.arc(x, y, 7, 0, 7); ctx.fillStyle = col; ctx.fill();
        ctx.beginPath(); ctx.arc(x, y, 3, 0, 7); ctx.fillStyle = '#fff'; ctx.fill();
      }
    }
    this.renderLabels();
  }

  renderLabels() {
    const ctx = this.ctx, d = this.d;
    const level = Math.round(Math.log(this.scale) / Math.log(1.25));
    let lab = this.labelCache.get(level);
    if (!lab) { lab = this.layoutLabels(Math.pow(1.25, level)); this.labelCache.set(level, lab); }
    ctx.lineJoin = 'round';
    for (const L of lab) {
      const e = d.entries[L.e];
      let ax = 0, ay = 0;
      for (const st of e.stations) { ax += d.stations[st].x; ay += d.stations[st].y; }
      const x = ax / e.stations.length * this.scale + this.tx + L.dx, y = ay / e.stations.length * this.scale + this.ty + L.dy;
      if (x > this.w || y < -20 || y > this.h + 20 || x + L.w < 0) continue;
      ctx.font = L.bold ? this.boldFont : this.font;
      ctx.strokeStyle = this.pal.bg; ctx.lineWidth = L.bold ? 3.5 : 3.2; ctx.strokeText(e.name, x, y);
      ctx.fillStyle = this.pal.text; ctx.fillText(e.name, x, y);
    }
  }

  layoutLabels(s) {
    const d = this.d;
    const rsPx = Math.max(1.7, Math.min(5.5, 6 * s)) * 0.82 * (this.route ? 1.2 : 1), gap = rsPx + 3.5, h = this.textH;
    const forced = new Set(), ends = new Set();
    if (this.from) { forced.add(this.from.index); ends.add(this.from.index); }
    if (this.to) { forced.add(this.to.index); ends.add(this.to.index); }
    const stepEnds = new Set();
    if (this.route) {
      for (const st of this.route.steps) { stepEnds.add(d.entryOf[st.stations[0]].index); stepEnds.add(d.entryOf[st.stations[st.stations.length - 1]].index); }
      stepEnds.forEach(i => forced.add(i));
      for (const n of this.route.nodes) forced.add(d.entryOf[n].index);
    }
    const order = [...forced];
    if (!this.route) for (const i of this.labelOrder) if (!forced.has(i)) order.push(i);
    const sx = d.stations.map(st => st.x * s), sy = d.stations.map(st => st.y * s);
    const placed = [], out = [];
    for (const ei of order) {
      const e = d.entries[ei], bold = ends.has(ei) || stepEnds.has(ei);
      const tw = bold ? this.labelWB[ei] : this.labelW[ei], th = bold ? h * 1.08 : h;
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity, cx = 0, cy = 0;
      for (const st of e.stations) { minX = Math.min(minX, sx[st]); maxX = Math.max(maxX, sx[st]); minY = Math.min(minY, sy[st]); maxY = Math.max(maxY, sy[st]); cx += sx[st]; cy += sy[st]; }
      cx /= e.stations.length; cy /= e.stations.length;
      const g2 = gap * 0.75;
      const cands = [[maxX + gap, cy - th / 2], [minX - gap - tw, cy - th / 2], [maxX + g2, minY - g2 - th], [maxX + g2, maxY + g2],
        [cx - tw / 2, minY - gap - th], [cx - tw / 2, maxY + gap], [minX - g2 - tw, minY - g2 - th], [minX - g2 - tw, maxY + g2]];
      let best = Infinity, bl = 0, bt = 0;
      cands.forEach(([l, t], k) => {
        const r = l + tw, b = t + th;
        for (const p of placed) if (l < p[2] && r > p[0] && t < p[3] && b > p[1]) return;
        let score = k * 0.01;
        for (let i = 0; i < sx.length; i++) {
          if (sx[i] > l - rsPx && sx[i] < r + rsPx && sy[i] > t - rsPx && sy[i] < b + rsPx && d.entryOf[i] !== e) score += 1;
        }
        if (score < best) { best = score; bl = l; bt = t; }
      });
      if (best === Infinity) continue;
      if (best >= 1 && !forced.has(ei)) continue;
      placed.push([bl, bt, bl + tw, bt + th]);
      out.push({ e: ei, dx: bl - cx, dy: bt + th * 0.78 - cy, bold, w: tw });
    }
    return out;
  }
}

// ---------------------------------------------------------------- интерфейс

let D, map, from = null, to = null;

function themeMode() { try { return +(localStorage.getItem('theme') || 0); } catch (e) { return 0; } }

function applyTheme() {
  const mode = themeMode();
  const systemDark = tg ? tg.colorScheme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  const dark = mode === 2 || (mode === 0 && systemDark);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  if (map) map.setPalette(dark ? PALETTE.dark : PALETTE.light);
  if (tg) {
    const bg = dark ? '#121316' : '#F6F6F3';
    try { tg.setHeaderColor(bg); tg.setBackgroundColor(bg); tg.setBottomBarColor && tg.setBottomBarColor(bg); } catch (e) { /* старые клиенты */ }
  }
}

function toast(text) {
  if (tg && tg.showPopup) { try { tg.HapticFeedback.selectionChanged(); } catch (e) {} }
  let t = document.getElementById('toast');
  if (!t) { t = document.createElement('div'); t.id = 'toast'; document.body.appendChild(t);
    Object.assign(t.style, { position: 'fixed', left: '50%', top: '20%', transform: 'translateX(-50%)', background: 'rgba(0,0,0,.75)', color: '#fff', padding: '8px 14px', borderRadius: '10px', fontSize: '14px', zIndex: 20, pointerEvents: 'none' }); }
  t.textContent = text; t.style.opacity = 1;
  clearTimeout(t.timer); t.timer = setTimeout(() => t.style.opacity = 0, 1400);
}

function updateInsets() {
  const panel = $('panel');
  map.insetBottom = window.innerHeight - panel.getBoundingClientRect().top;
}

function select(isFrom, e) {
  if (isFrom) from = e; else to = e;
  $(isFrom ? 'from' : 'to').value = e ? e.name : '';
  $('suggest').hidden = true;
  document.activeElement && document.activeElement.blur();
  if (isFrom && !to) $('to').focus();
  updateRoute(true);
  saveState();
}

function saveState() {
  try { localStorage.setItem('sel', JSON.stringify([from ? from.index : -1, to ? to.index : -1])); } catch (e) {}
}

function updateRoute(animate) {
  const box = $('route');
  let r = null;
  box.innerHTML = '';
  if (from && to) {
    if (from === to) box.innerHTML = '<div class="msg">Выберите разные станции</div>';
    else {
      r = findRoute(D, from.stations, to.stations);
      box.innerHTML = r ? routeHtml(r) : '<div class="msg">Маршрут не найден</div>';
    }
    box.hidden = false;
    box.scrollTop = 0;
  } else box.hidden = true;
  map.setSelection(from, to, r);
  requestAnimationFrame(() => {
    updateInsets();
    if (!animate) return;
    if (r) map.fitRoute();
    else if (from && !to) map.focus(from.stations[0]);
    else if (to && !from) map.focus(to.stations[0]);
  });
}

function routeHtml(r) {
  let h = `<div class="head"><span class="total">≈ ${fmtMin(Math.round(r.minutes))}</span><span class="meta">${r.transfers ? r.transfers + ' ' + plural(r.transfers, 'пересадка', 'пересадки', 'пересадок') : 'без пересадок'} · ${r.stops} ${plural(r.stops, 'перегон', 'перегона', 'перегонов')}</span></div>`;
  for (const st of r.steps) {
    const a = D.stations[st.stations[0]], b = D.stations[st.stations[st.stations.length - 1]];
    if (st.walk) { h += `<div class="walk">🚶 Переход на «${esc(b.n)}», ${esc(shortName(D.lines[b.line]))} · ${fmtMin(Math.round(st.minutes))}</div>`; continue; }
    const l = D.lines[st.line], n = st.stations.length - 1, dir = direction(D, st);
    let info = `${n} ${plural(n, 'перегон', 'перегона', 'перегонов')} · ${fmtMin(Math.round(st.minutes))}`;
    if (dir && dir !== b.n) info += ` · в сторону «${esc(dir)}»`;
    h += `<div class="ride"><div class="bar" style="background:${l.color}"></div><div><div class="line" style="color:${l.color}">${esc(shortName(l))}</div>` +
      `<div class="stations">${esc(a.n)}  →  ${esc(b.n)}</div><div class="info">${info}</div></div></div>`;
  }
  return h;
}

function setupField(id, isFrom) {
  const f = $(id), sg = $('suggest');
  let items = [];
  const show = () => {
    items = search(D, f.value, 40);
    if (!items.length || document.activeElement !== f) { sg.hidden = true; return; }
    sg.innerHTML = items.map((e, i) => `<div class="item" data-i="${i}"><div>${dotsHtml(e.colors)}${esc(e.name)}</div><div class="sub">${esc(e.lineNames)}</div></div>`).join('');
    sg.hidden = false;
    sg.onpointerdown = ev => ev.preventDefault(); // не терять фокус до выбора
    sg.onclick = ev => { const it = ev.target.closest('.item'); if (it) select(isFrom, items[+it.dataset.i]); };
  };
  f.addEventListener('input', () => {
    const cur = isFrom ? from : to;
    if (cur && cur.name !== f.value) { if (isFrom) from = null; else to = null; updateRoute(false); saveState(); }
    show();
  });
  f.addEventListener('focus', () => { if (f.value) show(); });
  f.addEventListener('blur', () => setTimeout(() => { if (!['from', 'to'].includes(document.activeElement && document.activeElement.id)) sg.hidden = true; }, 150));
  f.addEventListener('keydown', e => {
    if (e.key !== 'Enter') return;
    const res = search(D, f.value, 2);
    if (res.length && (res.length === 1 || res[0].norm === normalize(f.value))) select(isFrom, res[0]);
    f.blur();
  });
}

function showAbout() {
  const p = D.updated.split('-');
  const upd = p.length === 3 ? `${p[2]}.${p[1]}.${p[0]}` : D.updated;
  $('aboutBody').innerHTML = `<b>Метро Москвы</b><br>Схема актуальна на ${upd}<br>Версия данных ${esc(D.version)}<br>После первого открытия работает без интернета.` +
    `<div class="src">Источник: ${esc(D.source)}</div><b>Линии</b>` +
    D.lines.map(l => `<div class="ln">${dotsHtml([l.color])}${esc(l.type === 'metro' ? l.id + '  ' + l.name : l.name)}</div>`).join('') +
    `<button id="aboutOk">OK</button>`;
  $('about').hidden = false;
  $('aboutOk').onclick = () => $('about').hidden = true;
}

async function main() {
  if (tg) {
    tg.ready(); tg.expand();
    try { tg.disableVerticalSwipes && tg.disableVerticalSwipes(); } catch (e) {}
    tg.onEvent('themeChanged', applyTheme);
  }
  const res = await fetch('metro.json');
  D = loadData(await res.json());
  map = new MapView($('map'), D);
  applyTheme();
  setupField('from', true);
  setupField('to', false);

  map.onTap = st => {
    const e = D.entryOf[st];
    $('sheetTitle').innerHTML = dotsHtml(e.colors) + esc(e.name) + `<div class="sub">${esc(e.lineNames)}</div>`;
    $('sheet').hidden = false;
    $('sheetFrom').onclick = () => { $('sheet').hidden = true; select(true, e); };
    $('sheetTo').onclick = () => { $('sheet').hidden = true; select(false, e); };
  };
  $('sheet').onclick = ev => { if (ev.target.id === 'sheet') $('sheet').hidden = true; };
  $('about').onclick = ev => { if (ev.target.id === 'about') $('about').hidden = true; };
  $('btnTheme').onclick = () => {
    const m = (themeMode() + 1) % 3;
    try { localStorage.setItem('theme', m); } catch (e) {}
    toast(['Тема как в Telegram', 'Светлая тема', 'Тёмная тема'][m]);
    applyTheme();
  };
  $('btnFit').onclick = () => from && to ? map.fitRoute() : map.fitAll();
  $('btnInfo').onclick = showAbout;
  $('btnClear').onclick = () => { from = to = null; $('from').value = $('to').value = ''; updateRoute(false); saveState(); };
  $('btnSwap').onclick = () => { [from, to] = [to, from]; $('from').value = from ? from.name : ''; $('to').value = to ? to.name : ''; updateRoute(true); saveState(); };
  new ResizeObserver(updateInsets).observe($('panel'));

  // маршрут из ссылки бота: ?from=<номер станции>&to=<номер станции> (порядок в metro.json) или start_param вида 12_345
  const q = new URLSearchParams(location.search);
  let f = q.get('from'), t = q.get('to');
  const sp = tg && tg.initDataUnsafe && tg.initDataUnsafe.start_param;
  if (sp && /^\d+_\d+$/.test(sp)) [f, t] = sp.split('_');
  const byStation = v => (v != null && D.entryOf[+v]) || null;
  if (f != null || t != null) { from = byStation(f); to = byStation(t); }
  else { try { const [a, b] = JSON.parse(localStorage.getItem('sel') || '[-1,-1]'); from = D.entries[a] || null; to = D.entries[b] || null; } catch (e) {} }
  $('from').value = from ? from.name : ''; $('to').value = to ? to.name : '';
  updateInsets();
  updateRoute(true);
}

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
main();
