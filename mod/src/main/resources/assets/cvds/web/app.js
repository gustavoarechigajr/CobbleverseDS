'use strict';
// Cobbleverse DS touch UI. Talks to the mod over localhost: long-polls /api/state, posts /api/action.

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const store = {
  get(k, d) { try { const v = localStorage.getItem('cvds.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('cvds.' + k, JSON.stringify(v)); } catch {} },
};

let state = null;
let rev = -1;
let tab = 'storage';
let mode = 'move';
let selected = null; // slot index picked as the source of a move
let lastScreenKind = null;
let lastMenuId = 0;
let busy = false;
let lastGridSig = ''; // menu + items JSON; the grid is only rebuilt when it changes

// ---------- networking ----------
async function api(path, body) {
  const opts = body === undefined ? {} : { method: 'POST', body: JSON.stringify(body), headers: { 'X-CVDS': '1', 'Content-Type': 'application/json' } };
  const r = await fetch(path, opts);
  const j = await r.json();
  if (j.ok === false) throw new Error(j.error || 'failed');
  return j;
}

async function act(body) {
  if (busy) return;
  busy = true;
  try {
    await api('/api/action', body);
  } catch (e) {
    toast(e.message);
  } finally {
    busy = false;
  }
}

async function pollLoop() {
  for (;;) {
    try {
      const r = await fetch(`/api/state?rev=${rev}&wait=1500`);
      const s = await r.json();
      if (s.rev !== rev) {
        rev = s.rev;
        state = s;
        try { render(); } catch (e) { console.error(e); toast('UI error: ' + e.message); }
      }
    } catch (e) {
      setStatus('Waiting for the game…', '', 'down');
      await sleep(1000);
    }
  }
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

let toastTimer;
function toast(msg, info) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = info ? 'info' : '';
  t.style.display = 'block';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.style.display = 'none'), 2600);
}

// ---------- status bar ----------
function setStatus(main, side, conn) {
  $('#st-main').textContent = main;
  $('#st-side').textContent = side;
  if (conn) document.body.dataset.conn = conn; // drives the Pokédex LEDs
}

function clock(t) {
  const mins = Math.floor(((t + 6000) % 24000) / 1000 * 60);
  return String(Math.floor(mins / 60)).padStart(2, '0') + ':' + String(mins % 60).padStart(2, '0');
}

function renderStatus() {
  if (!state || !state.inGame) return setStatus(state && state.error ? 'Error: ' + state.error : 'Not in a world', '', state && state.error ? 'down' : 'wait');
  const s = state.status;
  const weather = s.thunder ? 'Thunder' : s.rain ? 'Rain' : 'Clear';
  setStatus(`${s.biomeName || s.biome || ''}  ${s.x}, ${s.y}, ${s.z}`,
    `${s.world ? s.world + '  ·  ' : ''}Day ${s.day} ${clock(s.time)}  ${weather}${s.canSleep ? '  Can sleep' : ''}  ♥ ${Math.ceil(s.hp)}  🍗 ${s.food}  Lv ${s.xp}${s.fps != null ? '  ' + s.fps + ' fps' : ''}`, 'ok');
}

// ---------- tabs ----------
function showTab(name) {
  tab = name;
  $$('#tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === name));
  $$('.tab').forEach(t => t.classList.toggle('on', t.id === 'tab-' + name));
  if (name === 'keys') loadKeys();
  if (name === 'craft') loadRecipes();
  if (name === 'chat') loadChat();
  if (name === 'pc') { pcSig = ''; loadPc(); }
  if (name === 'battle') loadBattle();
  if (name === 'spawns') { loadSpawns.last = Date.now(); loadSpawns(); }
  render();
}
$$('#tabs button').forEach(b => b.addEventListener('click', () => showTab(b.dataset.tab)));

function render() {
  renderStatus();
  if (!state || !state.inGame) return;
  const kind = state.screen && state.screen.kind;
  const menuId = state.menu ? state.menu.id : 0;
  if (kind !== lastScreenKind || (kind === 'container' && menuId !== lastMenuId)) {
    // Record the new kind first: showTab() re-enters render().
    const prev = lastScreenKind;
    lastScreenKind = kind;
    if (menuId !== lastMenuId) setSelected(null); // a selection never carries into another container
    lastMenuId = menuId;
    if (kind === 'container' && prev !== null) { showTab('storage'); wake(); }
    if (kind === 'pc' && prev !== null) { showTab('pc'); wake(); }
    if (kind === 'container' && tab === 'craft') loadRecipes();
  }
  battleWatch();
  if (tab === 'storage') { renderGrid(); if (netActive() && !net) loadNet(); }
  if (tab === 'party') renderParty();
}
let lastPartySig = '';

// ---------- storage ----------
function itemInfo(k) { return (state.items && state.items[k]) || { name: k, max: 64 }; }
function iconUrl(k) { return '/api/icon/' + encodeURIComponent(k); }
function menuSlots() { return (state.menu && state.menu.slots) || []; }
function slotByIndex(i) { return menuSlots().find(s => s.i === i); }

// Scrolling containers (Sophisticated Backpacks, big modded chests) park the rows that are scrolled out of
// view at negative coordinates. Lay the container out as a plain grid in slot order instead, so every row
// shows, and push the player's inventory down below it.
function layoutSlots(slots) {
  const c = slots.filter(s => s.g === 'c');
  const off = s => s.x < 0 || s.y < 0;
  if (!c.some(off)) return slots;
  const vis = c.filter(s => !off(s));
  if (!vis.length) return slots;
  const xs = [...new Set(vis.map(s => s.x))].sort((a, b) => a - b), cols = xs.length;
  const x0 = xs[0], y0 = Math.min(...vis.map(s => s.y)), first = Math.min(...c.map(s => s.i));
  const oldBottom = Math.max(...vis.map(s => s.y));
  const at = new Map(c.map(s => { const k = s.i - first; return [s.i, { x: x0 + (k % cols) * 18, y: y0 + Math.floor(k / cols) * 18 }]; }));
  const dy = Math.max(...[...at.values()].map(p => p.y)) - oldBottom;
  return slots.map(s => s.g === 'c' ? Object.assign({}, s, at.get(s.i)) : s.y > oldBottom ? Object.assign({}, s, { y: s.y + dy }) : s);
}

function renderGrid(force) {
  const grid = $('#grid');
  const sig = JSON.stringify([state.menu, state.screen, state.status.hotbar, $('#inv-search').value, selected, $('#grid-wrap').clientWidth, $('#grid-wrap').clientHeight]);
  if (!force && sig === lastGridSig) return;
  lastGridSig = sig;
  const slots = layoutSlots(menuSlots());
  const kind = state.screen.kind;
  $('#screen-title').textContent = kind === 'container' ? (state.screen.title || 'Container') :
    kind === 'creative' ? 'Creative inventory isn\'t supported. Showing your inventory.' : 'Your inventory';
  if (!slots.length) { grid.innerHTML = ''; return; }
  if (selected != null && !(slotByIndex(selected) || {}).k) setSelected(null);

  const minX = Math.min(...slots.map(s => s.x)), minY = Math.min(...slots.map(s => s.y));
  const bw = Math.max(...slots.map(s => s.x)) - minX + 18, bh = Math.max(...slots.map(s => s.y)) - minY + 18;
  const wrap = $('#grid-wrap');
  const availW = wrap.clientWidth - 8, availH = wrap.clientHeight - 40;
  let scale = Math.min(availW / bw, availH / bh, 7);
  scale = Math.max(scale, 80 / 18); // never smaller than a ~6 mm touch target on the Thor; scroll instead
  grid.style.width = Math.round(bw * scale) + 'px';
  grid.style.height = Math.round(bh * scale) + 'px';
  // Drag in any direction when the grid fits; a tall modded container keeps vertical scrolling.
  grid.style.touchAction = bh * scale > availH + 40 ? 'pan-y' : 'none';

  const q = $('#inv-search').value.trim().toLowerCase();
  const sel = state.status.hotbar;
  const html = [];
  for (const s of slots) {
    const left = Math.round((s.x - minX) * scale), top = Math.round((s.y - minY) * scale), size = Math.round(18 * scale) - 2;
    let cls = 'slot g-' + s.g;
    if (s.i === selected) cls += ' sel';
    if (s.g === 'h' && slotHotbarIndex(s) === sel && kind !== 'container') cls += ' hb';
    let inner = '';
    if (s.k) {
      const info = itemInfo(s.k);
      if (q) cls += info.name.toLowerCase().includes(q) ? ' hit' : ' miss';
      inner = `<img src="${iconUrl(s.k)}" alt="" onerror="this.remove()"><div class="txt">${esc(info.name)}</div>`;
      if (s.n > 1) inner += `<span class="n">${s.n}</span>`;
      if (s.d != null && s.d < 1) {
        const hue = Math.round(s.d * 120);
        inner += `<div class="dur"><i style="width:${Math.round(s.d * 100)}%;background:hsl(${hue},80%,50%)"></i></div>`;
      }
    } else if (q) cls += ' miss';
    html.push(`<div class="${cls}" data-i="${s.i}" style="left:${left}px;top:${top}px;width:${size}px;height:${size}px">${inner}</div>`);
  }
  grid.innerHTML = html.join('');
  // Hide the text fallback once an icon loads.
  $$('.slot img', grid).forEach(img => {
    const hide = () => { const t = img.nextElementSibling; if (t) t.style.display = 'none'; };
    if (img.complete && img.naturalWidth) hide(); else img.addEventListener('load', hide);
  });
  $('#bulk').classList.toggle('hidden', kind !== 'container');
  $('#more').classList.toggle('hidden', kind !== 'container' || !!(state.menu && state.menu.grid)); // nothing to take/store on a crafting grid
  // A terminal has no container slots: only storing (all / matching the network) applies.
  const term = !!state.screen.terminal;
  $$('#bulk [data-bulk="take"], #bulk [data-bulk="sortc"]').forEach(b => b.classList.toggle('hidden', term));
}

function slotHotbarIndex(s) {
  // Hotbar slots appear in menu order; the first "h" slot is hotbar 0.
  const hb = menuSlots().filter(x => x.g === 'h');
  return hb.indexOf(s);
}

/** Replaces an element's HTML only when it changed, so taps aren't lost to needless rebuilds. */
function setHtml(sel, html) {
  const el = $(sel);
  if (el.dataset.html !== html) { el.innerHTML = html; el.dataset.html = html; }
}

function esc(s) { return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

function setSelected(i) {
  if (i == null) { pendingAmount = null; const h = $('#sel-hint'); if (h) h.textContent = 'Tap where it goes'; }
  selected = i;
  const bar = $('#selbar');
  if (i == null) { bar.classList.add('hidden'); $('#hotbarbar').classList.add('hidden'); }
  else {
    const s = slotByIndex(i);
    $('#sel-name').textContent = s && s.k ? `${itemInfo(s.k).name}${s.n > 1 ? ' ×' + s.n : ''}` : '';
    // one bar at a time, floating over the top buttons (see app.css)
    bar.classList.toggle('hidden', mode === 'hotbar');
    $('#hotbarbar').classList.toggle('hidden', mode !== 'hotbar');
    const hn = $('#hb-name');
    if (hn) hn.textContent = $('#sel-name').textContent;
  }
  $$('.slot').forEach(el => el.classList.toggle('sel', Number(el.dataset.i) === i));
}

// One-shot amount for the next tap-to-move ("Move half…" / "Move one…" in the item menu).
let pendingAmount = null;
function itemMenu(i) {
  const s = slotByIndex(i);
  if (!s || !s.k) return;
  const menu = state.menu.id, name = itemInfo(s.k).name;
  const pick = (amount, how) => () => { pendingAmount = amount; setSelected(i); $('#sel-hint').textContent = how; };
  sheet(`${name}${s.n > 1 ? ' ×' + s.n : ''}`, null, [
    { label: 'Quick move', sub: 'to the other side', run: () => { setSelected(null); act({ kind: 'inv', op: 'quick', slot: i, menu }); } },
    { label: 'Move half…', sub: 'then tap where', disabled: s.n < 2, run: pick('half', 'Tap where half goes') },
    { label: 'Move one…', sub: 'then tap where', run: pick('one', 'Tap where one goes') },
    { label: 'To hotbar…', sub: 'pick a slot 1–9', run: () => sheet('Put ' + name + ' in hotbar slot', null,
        [1, 2, 3, 4, 5, 6, 7, 8, 9].map(n => ({ label: String(n), run: () => { setSelected(null); act({ kind: 'inv', op: 'hotbar', slot: i, hotbar: n - 1, menu }); } }))) },
    { label: 'Move all like this', sub: 'every ' + name, run: () => { setSelected(null); act({ kind: 'inv', op: 'quickAll', group: s.g === 'c' ? 'c' : 'pc', key: s.k, menu }); } },
    { label: 'Drop one', run: () => { setSelected(null); act({ kind: 'inv', op: 'drop', slot: i, all: false, menu }); } },
    { label: 'Drop stack', run: () => { setSelected(null); act({ kind: 'inv', op: 'drop', slot: i, all: true, menu }); } },
  ]);
}
$('#more').addEventListener('click', () => {
  const bs = $$('#bulk [data-bulk]').filter(b => !b.classList.contains('hidden'));
  sheet('Container', null, bs.map(b => ({ label: b.textContent, run: () => b.click() })));
});

(() => {
  const bar = $('#hotbarbar');
  bar.innerHTML = '<span id="hb-name"></span><span>to slot</span>' + [1, 2, 3, 4, 5, 6, 7, 8, 9].map(n => `<button data-hb="${n - 1}">${n}</button>`).join('') + '<button class="cancel">Cancel</button>';
  bar.addEventListener('click', async e => {
    const b = e.target.closest('button');
    if (!b || selected == null) return;
    if (b.classList.contains('cancel')) { setSelected(null); return; }
    await act({ kind: 'inv', op: 'hotbar', slot: selected, hotbar: Number(b.dataset.hb), menu: state.menu.id });
    setSelected(null);
  });
})();

/**
 * Touch gestures for a grid of items: tap, long-press, and drag-and-drop. A drag starts once the
 * finger moves past a small threshold (so taps and long-presses still work); a picture of the item
 * follows the finger and the slot under it lights up. Elements are found by position at the end,
 * so the grid may be re-rendered by a state update mid-drag.
 */
function gestures(container, itemSel, { canDrag, onTap, onLong, onDrop }) {
  let down = null, timer = null, ghost = null, over = null;
  const at = (x, y) => { const el = document.elementFromPoint(x, y); return el && el.closest(itemSel); };
  const clearOver = () => { if (over) over.classList.remove('drop'); over = null; };
  const end = () => {
    clearTimeout(timer); timer = null;
    if (ghost) ghost.remove();
    ghost = null;
    clearOver();
    container.querySelectorAll('.dragging').forEach(el => el.classList.remove('dragging'));
    down = null;
  };
  container.addEventListener('pointerdown', e => {
    if (e.isPrimary === false) return;
    const el = e.target.closest(itemSel);
    if (!el) return;
    down = { el, x: e.clientX, y: e.clientY, long: false, dragging: false };
    try { container.setPointerCapture(e.pointerId); } catch {}
    timer = setTimeout(() => {
      if (!down || down.dragging) return;
      down.long = true;
      if (navigator.vibrate) navigator.vibrate(30);
      onLong && onLong(down.el);
    }, 450);
  });
  container.addEventListener('pointermove', e => {
    if (!down || down.long) return;
    if (!down.dragging) {
      if (Math.hypot(e.clientX - down.x, e.clientY - down.y) < 12) return;
      clearTimeout(timer); timer = null;
      if (!canDrag(down.el)) { down.cancelled = true; return; }
      down.dragging = true;
      down.el.classList.add('dragging');
      ghost = document.createElement('div');
      ghost.className = 'ghost';
      const img = down.el.querySelector('img');
      ghost.innerHTML = img ? `<img src="${img.src}" alt="">` : `<span>${esc((down.el.textContent || '').trim().slice(0, 12))}</span>`;
      document.body.appendChild(ghost);
    }
    ghost.style.transform = `translate(${e.clientX - 56}px, ${e.clientY - 56}px)`;
    const t = at(e.clientX, e.clientY);
    if (t !== over) { clearOver(); if (t && t !== down.el) { over = t; t.classList.add('drop'); } }
  });
  container.addEventListener('pointerup', e => {
    if (!down) return;
    const d = down;
    if (d.dragging) {
      const t = at(e.clientX, e.clientY);
      end();
      if (t && t !== d.el) onDrop(d.el, t);
      return;
    }
    end();
    if (!d.long && !d.cancelled) onTap(d.el);
  });
  container.addEventListener('pointercancel', end);
}

// Storage grid: tap-tap or drag to move, long-press to quick-move (like shift-click).
gestures($('#grid'), '.slot', {
  canDrag: el => !!(slotByIndex(Number(el.dataset.i)) || {}).k,
  onTap: el => tapSlot(Number(el.dataset.i)),
  onLong: el => { setSelected(null); itemMenu(Number(el.dataset.i)); },
  onDrop: (from, to) => {
    const amount = pendingAmount || 'all';
    setSelected(null);
    act({ kind: 'inv', op: 'move', from: Number(from.dataset.i), to: Number(to.dataset.i), amount, menu: state.menu.id });
  },
});

function tapSlot(i) {
  const s = slotByIndex(i);
  if (!s) return;
  const menu = state.menu.id;
  if (mode === 'quick') {
    if (s.k) act({ kind: 'inv', op: 'quick', slot: i, menu });
    return;
  }
  if (mode === 'hotbar') {
    setSelected(s.k && selected !== i ? i : null);
    return;
  }
  if (selected == null) {
    if (s.k) setSelected(i);
    return;
  }
  if (selected === i) { setSelected(null); return; }
  const amount = pendingAmount || 'all';
  const from = selected;
  setSelected(null);
  act({ kind: 'inv', op: 'move', from, to: i, amount, menu });
}

$('#selbar').addEventListener('click', async e => {
  const b = e.target.closest('button');
  if (!b || selected == null) return;
  const s = slotByIndex(selected);
  const menu = state.menu.id;
  if (b.dataset.sel === 'options') { const i = selected; setSelected(null); itemMenu(i); return; }
  switch (b.dataset.sel) {
    case 'all-like': {
      const group = s.g === 'c' ? 'c' : 'pc';
      await act({ kind: 'inv', op: 'quickAll', group, key: s.k, menu });
      break;
    }
    case 'drop1': await act({ kind: 'inv', op: 'drop', slot: selected, all: false, menu }); break;
    case 'drop': await act({ kind: 'inv', op: 'drop', slot: selected, all: true, menu }); break;
  }
  setSelected(null);
});

$('#bulk').addEventListener('click', async e => {
  const b = e.target.closest('button');
  if (!b) return;
  const menu = state.menu.id;
  switch (b.dataset.bulk) {
    case 'take': await act({ kind: 'inv', op: 'quickAll', group: 'c', menu }); break;
    case 'store': await act({ kind: 'inv', op: 'quickAll', group: 'p', menu }); break;
    case 'matching': {
      const keys = state.screen.terminal && net ? net.list.map(e => e.k) :
        [...new Set(menuSlots().filter(s => s.g === 'c' && s.k).map(s => s.k))];
      for (const key of keys) await act({ kind: 'inv', op: 'quickAll', group: 'pc', key, menu });
      if (!keys.length) toast('Nothing in the container to match', true);
      break;
    }
    case 'sortc': await act({ kind: 'inv', op: 'sort', group: 'c', menu }); break;
    case 'sortp': await act({ kind: 'inv', op: 'sort', group: 'p', menu }); break;
  }
});

$('#inv-search').addEventListener('input', () => renderGrid());
window.addEventListener('resize', () => render());


// ---------- Tom's Simple Storage terminal ----------
let net = null, netSig = '', netTimer = null;
function fmtCount(n) { return n >= 1e6 ? (n / 1e6).toFixed(n >= 1e7 ? 0 : 1) + 'M' : n >= 1e4 ? Math.round(n / 1000) + 'k' : n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n); }
async function loadNet() {
  if (loadNet.busy) return;
  loadNet.busy = true;
  try { await loadNetInner(); } finally { loadNet.busy = false; }
}
async function loadNetInner() {
  try {
    const r = await api('/api/terminal?sig=' + netSig);
    if (r.same) return;
    netSig = r.sig;
    net = r;
    renderNet();
  } catch (e) { $('#net-info').textContent = e.message; }
}
function netActive() { return state && state.screen && state.screen.terminal; }
// Pinned network items sit first (per device).
let netPins = store.get('netPins', []);
function netMenu(k) {
  const name = ((net && net.items && net.items[k]) || {}).name || k, pinned = netPins.includes(k);
  sheet(name, null, [
    { label: 'Take a stack', run: () => act({ kind: 'terminal', k }) },
    { label: 'Take one', run: () => act({ kind: 'terminal', k, one: true }) },
    { label: pinned ? 'Unpin' : 'Pin to top', run: () => { netPins = pinned ? netPins.filter(x => x !== k) : [...netPins, k]; store.set('netPins', netPins); renderNet(); } },
  ]);
}
function renderNet() {
  const box = $('#net');
  if (!netActive() || !net || !net.open) { box.classList.add('hidden'); return; }
  box.classList.remove('hidden');
  const q = $('#net-search').value.trim().toLowerCase();
  const items = net.items || {};
  let list = net.list.filter(e => !q || ((items[e.k] || {}).name || e.k).toLowerCase().includes(q) || e.k.includes(q));
  if ($('#net-sort').value === 'name') list.sort((a, b) => ((items[a.k] || {}).name || '').localeCompare((items[b.k] || {}).name || ''));
  else list.sort((a, b) => b.n - a.n);
  list.sort((a, b) => netPins.includes(b.k) - netPins.includes(a.k)); // stable: pins first, order kept
  $('#net-info').textContent = `${net.list.length} kinds · ${net.free}/${net.slots} slots free`;
  const html = list.slice(0, 400).map(e => {
    const name = (items[e.k] || {}).name || e.k;
    return `<div class="ni${netPins.includes(e.k) ? ' pin' : ''}" data-k="${esc(e.k)}" title="${esc(name)}"><img src="${iconUrl(e.k)}" alt="" onerror="this.remove()" onload="this.nextElementSibling.style.display='none'"><div class="txt">${esc(name)}</div><span class="n">${fmtCount(e.n)}</span></div>`;
  }).join('');
  const g = $('#net-grid');
  if (g.dataset.html !== html) { g.innerHTML = html || '<div class="note">Nothing matches.</div>'; g.dataset.html = html; }
}
(() => {
  const g = $('#net-grid');
  let timer = null, k = null, long = false, sx = 0, sy = 0;
  g.addEventListener('pointerdown', e => {
    const el = e.target.closest('.ni');
    if (!el) return;
    k = el.dataset.k; long = false; sx = e.clientX; sy = e.clientY;
    timer = setTimeout(() => { long = true; if (navigator.vibrate) navigator.vibrate(30); netMenu(k); }, 450);
  });
  const cancel = () => { clearTimeout(timer); timer = null; };
  g.addEventListener('pointermove', e => { if (Math.hypot(e.clientX - sx, e.clientY - sy) > 20) cancel(); });
  g.addEventListener('pointercancel', cancel);
  g.addEventListener('pointerup', () => {
    const was = timer != null;
    cancel();
    if (!long && was && k) act({ kind: 'terminal', k, one: false });
  });
})();
$('#net-search').addEventListener('input', renderNet);
$('#net-sort').addEventListener('change', renderNet);
setInterval(() => { if (tab === 'storage' && netActive()) loadNet(); else if (!netActive()) { $('#net').classList.add('hidden'); netSig = ''; } }, 700);

// ---------- crafting ----------
let recipeTimer;
$('#craft-search').addEventListener('input', () => { clearTimeout(recipeTimer); recipeTimer = setTimeout(loadRecipes, 250); });

let craftFavs = store.get('craftFavs', []);
async function loadRecipes() {
  const q = $('#craft-search').value;
  let res;
  try {
    res = await api('/api/recipes?q=' + encodeURIComponent(q) + '&fav=' + encodeURIComponent(craftFavs.join(',')));
  } catch (e) {
    $('#recipes').innerHTML = '';
    $('#craft-note').textContent = e.message;
    return;
  }
  if (res.error) {
    $('#recipes').innerHTML = '';
    $('#craft-note').textContent = 'Open a crafting table to craft 3×3 recipes. With no screen open you can craft 2×2 recipes.';
    return;
  }
  const grid = state && state.menu && state.menu.grid;
  $('#craft-note').textContent = (grid ? `${grid[0]}×${grid[1]} grid. ` : '') + (q ? '' : 'Showing what you can make right now.');
  state.items = Object.assign({}, state.items, res.items);
  const ingChip = g => `<span class="ing ${g.have >= g.need ? 'ok' : 'short'}" title="${esc(((state.items || {})[g.k] || {}).name || '')}"><img src="${iconUrl(g.k)}" alt="" onerror="this.remove()">${g.have}/${g.need}${g.any ? '*' : ''}</span>`;
  $('#recipes').innerHTML = res.recipes.map(r => {
    // How many sets the ingredients allow (a hint; the game caps it by what fits the grid).
    const sets = (r.ingr || []).length ? Math.min(...r.ingr.map(g => Math.floor(g.have / g.need))) : (r.can ? 1 : 0);
    const fav = craftFavs.includes(r.id);
    return `
    <div class="card ${r.can ? '' : 'no'}" data-id="${esc(r.id)}">
      <div class="top"><img src="${iconUrl(r.k)}" alt="" onerror="this.style.visibility='hidden'">
        <div class="meta"><div class="name">${esc(r.name)}${r.n > 1 ? ' ×' + r.n : ''}</div>
        <div class="ings">${(r.ingr || []).map(ingChip).join('') || `<span class="sub">${esc((r.ings || []).join(', '))}</span>`}</div></div>
        <button class="fav${fav ? ' on' : ''}" data-fav="${esc(r.id)}" title="Favourite">${fav ? '★' : '☆'}</button></div>
      <div class="acts">${[1, 4, 16].map(n => `<button data-c="${n}" ${r.can && sets >= n ? '' : 'disabled'}>${n === 1 ? 'Craft 1' : '×' + n}</button>`).join('')}<button data-c="max" ${r.can ? '' : 'disabled'}>Max${sets > 1 ? ' (' + sets + ')' : ''}</button></div>
    </div>`;
  }).join('') || `<div class="note">${q ? 'No recipe makes "' + esc(q) + '".' : 'Nothing in your inventory can be crafted on this grid. Search for an item to see its recipe.'}</div>`;
}

$('#recipes').addEventListener('click', async e => {
  const f = e.target.closest('[data-fav]');
  if (f) {
    const id = f.dataset.fav;
    craftFavs = craftFavs.includes(id) ? craftFavs.filter(x => x !== id) : [...craftFavs, id];
    store.set('craftFavs', craftFavs);
    loadRecipes();
    return;
  }
  const b = e.target.closest('button[data-c]');
  if (!b || b.disabled) return;
  const id = b.closest('.card').dataset.id;
  const c = b.dataset.c;
  await act(c === 'max' ? { kind: 'craft', id, max: true } : { kind: 'craft', id, count: Number(c) });
  setTimeout(loadRecipes, 300);
});

// ---------- keybinds ----------
let keys = [];
// Pack keys that open something are favourites by default (only the ones the installed mods register).
const DEFAULT_FAVS = ['key.inventory', 'key.cobblemon.throwpartypokemon', 'key.cobblemon.summary', 'key.cobblemon.pokedex',
  'key.cobblemon.pokenavigator', 'key.lumymon.access_pc', 'keybind.sophisticatedbackpacks.open_backpack',
  'key.toms_storage.open_terminal', 'gui.xaero_open_map', 'key.togglePerspective', 'key.playerlist'];
let favs = store.get('favs', null);

async function loadKeys(force) {
  if (keys.length && !force) return renderKeys();
  try {
    keys = (await api('/api/keys')).keys;
  } catch (e) { toast(e.message); return; }
  const has = id => keys.some(k => k.id === id);
  if (favs == null) favs = DEFAULT_FAVS.filter(has);
  else {
    // Defaults added in a later version join existing favourites once; un-starring one keeps it off.
    const offered = store.get('favsOffered', ['key.inventory', 'key.cobblemon.throwpartypokemon', 'key.cobblemon.summary',
      'key.cobblemon.pokedex', 'key.cobblemon.pokenavigator', 'key.togglePerspective', 'key.playerlist']);
    const fresh = DEFAULT_FAVS.filter(id => !offered.includes(id) && has(id) && !favs.includes(id));
    if (fresh.length) favs = [...favs, ...fresh];
  }
  store.set('favs', favs);
  store.set('favsOffered', DEFAULT_FAVS);
  renderKeys();
}

// Keybinds that belong to a tab show up there as big shortcut keys (only if the pack registers them).
const SHORTCUTS = {
  storage: [['keybind.sophisticatedbackpacks.open_backpack', 'Backpack', 'sophisticatedbackpacks:diamond_backpack'],
            ['key.toms_storage.open_terminal', 'Terminal', 'toms_storage:wireless_terminal'],
            ['accessories.key.open_accessories_screen', 'Accessories', 'minecraft:leather_chestplate']],
  party: [['key.cobblemon.summary', 'Summary', 'minecraft:book'], ['key.lumymon.dismount', 'Dismount', 'minecraft:saddle']],
  pc: [['key.lumymon.access_pc', 'Open PC', 'cobblemon:pc']],
  battle: [['key.fightorflight.startbattle', 'Battle an angry Pokémon', 'minecraft:iron_sword'],
           ['key.cobblemon-battle-extras.battle_logs', 'Battle log', 'minecraft:writable_book']],
  spawns: [['gui.xaero_open_map', 'World map', 'minecraft:filled_map'], ['gui.xaero_waypoints_key', 'Waypoints', 'minecraft:compass']],
};
function renderShortcuts() {
  $$('.shortcuts[data-sc]').forEach(el => {
    const html = (SHORTCUTS[el.dataset.sc] || []).filter(([id]) => keys.some(k => k.id === id)).map(([id, label, icon]) =>
      `<button class="sc" data-kb="${esc(id)}" title="${esc((keys.find(k => k.id === id) || {}).bound || '')}"><img src="${iconUrl(icon)}" alt="" onerror="this.remove()">${esc(label)}</button>`).join('');
    if (el.dataset.html !== html) { el.innerHTML = html; el.dataset.html = html; }
  });
  // in battle the Battle-an-angry-Pokémon key makes no sense; outside one, the battle log doesn't either
  const inBattle = !!(battle && battle.active);
  $$('.shortcuts[data-sc="battle"] [data-kb]').forEach(b => b.classList.toggle('hidden', (b.dataset.kb === 'key.fightorflight.startbattle') === inBattle));
}

// Keys that do something only while held: the panel toggles them (tap = hold down, tap again = let go).
const HOLD_KEYS = new Set(['key.sneak', 'key.sprint', 'key.use', 'key.attack', 'key.forward', 'key.back', 'key.left', 'key.right', 'key.jump',
  'key.playerlist', 'zoomify.key.zoom', 'key.pingwheel.ping_location', 'key.cobblemon.ridingfreelook', 'key.cobblemonraiddens.mouse', 'key.catchrate.show_comparison']);
let hiddenCats = store.get('hiddenCats', []), hideUnbound = store.get('hideUnbound', false);
function keyButton(k) {
  const on = favs.includes(k.id), hold = HOLD_KEYS.has(k.id);
  return `<div class="key${k.held ? ' held' : ''}" data-key="${esc(k.id)}"><button class="press" data-kb="${esc(k.id)}" ${hold ? 'data-hold="1"' : ''}>${esc(k.label)}<small>${esc(k.bound || 'unbound')}${hold ? (k.held ? ' · HELD — tap to let go' : ' · tap to hold') : ''}</small></button><button class="star" data-star="${esc(k.id)}">${on ? '★' : '☆'}</button></div>`;
}
function keyFavMenu(id) {
  const i = favs.indexOf(id), k = keys.find(x => x.id === id);
  if (i < 0 || !k) return;
  const moveTo = j => () => { const f = favs.filter(x => x !== id); f.splice(j, 0, id); favs = f; store.set('favs', favs); renderKeys(); };
  sheet(k.label, 'Reorder this favourite.', [
    { label: 'Move to start', disabled: i === 0, run: moveTo(0) },
    { label: '◀ Earlier', disabled: i === 0, run: moveTo(i - 1) },
    { label: 'Later ▶', disabled: i === favs.length - 1, run: moveTo(i + 1) },
    { label: 'Move to end', disabled: i === favs.length - 1, run: moveTo(favs.length - 1) },
    { label: 'Remove from favourites', run: () => { favs = favs.filter(x => x !== id); store.set('favs', favs); renderKeys(); } },
  ]);
}
function keySections() {
  const cats = [...new Set(keys.map(k => k.cat))];
  const toggle = c => () => { hiddenCats = hiddenCats.includes(c) ? hiddenCats.filter(x => x !== c) : [...hiddenCats, c]; store.set('hiddenCats', hiddenCats); renderKeys(); keySections(); };
  sheet('Keybind sections', 'Hide the sections you never use. Favourites always show.', [
    { label: hideUnbound ? 'Show unbound keys' : 'Hide unbound keys', run: () => { hideUnbound = !hideUnbound; store.set('hideUnbound', hideUnbound); renderKeys(); keySections(); } },
    ...cats.map(c => ({ label: c, sub: hiddenCats.includes(c) ? 'Hidden · tap to show' : 'Shown · tap to hide', cls: hiddenCats.includes(c) ? '' : 'on', run: toggle(c) }))]);
}

function renderKeys() {
  renderShortcuts();
  const q = $('#key-search').value.trim().toLowerCase();
  $('#key-favs').innerHTML = favs.map(id => keys.find(k => k.id === id)).filter(Boolean).map(keyButton).join('') || '<div class="note">No favourites yet.</div>';
  const cats = new Map();
  for (const k of keys) {
    if (q && !(k.label + ' ' + k.cat + ' ' + k.id).toLowerCase().includes(q)) continue;
    if (!q && (hiddenCats.includes(k.cat) || (hideUnbound && !k.bound))) continue;
    if (!cats.has(k.cat)) cats.set(k.cat, []);
    cats.get(k.cat).push(k);
  }
  $('#key-list').innerHTML = [...cats].map(([cat, ks]) =>
    `<details ${q ? 'open' : ''}><summary>${esc(cat)} (${ks.length})</summary><div class="keys">${ks.map(keyButton).join('')}</div></details>`).join('');
}

// Long-press a favourite to reorder it (the click that follows is swallowed).
let keyLongTimer = null, keyLongFired = false;
$('#key-favs').addEventListener('pointerdown', e => {
  const el = e.target.closest('.key');
  keyLongFired = false;
  if (!el) return;
  keyLongTimer = setTimeout(() => { keyLongFired = true; if (navigator.vibrate) navigator.vibrate(30); keyFavMenu(el.dataset.key); }, 500);
});
['pointerup', 'pointercancel', 'pointerleave'].forEach(t => $('#key-favs').addEventListener(t, () => clearTimeout(keyLongTimer)));
$('#key-sections').addEventListener('click', keySections);
document.addEventListener('click', async e => {
  const p = e.target.closest('[data-kb]');
  if (p && keyLongFired && p.closest('#key-favs')) { keyLongFired = false; return; }
  if (p && p.dataset.hold) {
    const k = keys.find(x => x.id === p.dataset.kb);
    await act({ kind: 'key', id: p.dataset.kb, hold: !(k && k.held) });
    if (k) k.held = !k.held;
    renderKeys();
    return;
  }
  if (p) { act({ kind: 'key', id: p.dataset.kb }); return; }
  const s = e.target.closest('[data-star]');
  if (s) {
    const id = s.dataset.star;
    favs = favs.includes(id) ? favs.filter(x => x !== id) : [...favs, id];
    store.set('favs', favs);
    renderKeys();
  }
});
$('#key-search').addEventListener('input', renderKeys);
$('#key-refresh').addEventListener('click', () => loadKeys(true));

// ---------- party ----------
function renderParty() {
  const p = state.party;
  const sig = JSON.stringify([p || null, partyMoveFrom]);
  if (sig === lastPartySig && $('#party').childElementCount) return;
  lastPartySig = sig;
  const el = $('#party');
  if (!p) { el.innerHTML = '<div class="note">Cobblemon isn\'t loaded.</div>'; return; }
  el.innerHTML = p.slots.map((m, i) => {
    if (!m.name) return `<div class="mon empty" data-p="${i}">Empty</div>`;
    const frac = m.maxHp ? m.hp / m.maxHp : 0;
    const hue = Math.round(frac * 120);
    // The sprite is the centrepiece; name + reorder key on top, stats underneath.
    return `<div class="mon ${p.sel === i ? 'sel' : ''}${partyMoveFrom === i ? ' moving' : ''}${m.fainted ? ' fainted' : ''}" data-p="${i}">
      <div class="top"><div class="nm">${esc(m.name)}${m.shiny ? ' ✨' : ''}</div></div>
      <div class="art">${m.k ? `<img src="${iconUrl(m.k)}" alt="" onerror="this.remove()">` : ''}</div>
      <div class="sub"><b>Lv ${m.lvl}</b> · ${m.hp}/${m.maxHp} HP${m.fainted ? ' · Fainted' : ''}${m.status ? ' · ' + esc(m.status.toUpperCase()) : ''}</div>
      <div class="hp"><i style="width:${Math.round(frac * 100)}%;background:hsl(${hue},75%,45%)"></i></div>
      <div class="pills">${typePills(m.types)}${m.held ? `<span class="held">${m.hk ? `<img src="${iconUrl(m.hk)}" alt="" onerror="this.remove()">` : '◆ '}${esc(m.held)}</span>` : ''}</div>
      <button class="send" data-send="${i}">Send out <small>or recall</small></button>
    </div>`;
  }).join('');
}
let partyMoveFrom = null; // kept for the render signature; reordering is drag (or Move in the summary)
async function partyMove(from, to) {
  if (from === to) return;
  await act({ kind: 'pc', op: 'move', from: { type: 'party', slot: from }, to: { type: 'party', slot: to } });
  lastPartySig = '';
}
function partySummary(slot) {
  const m = ((state && state.party && state.party.slots) || [])[slot];
  if (!m || !m.name) return;
  act({ kind: 'party', slot }); // also makes it the selected party slot, like tapping it in game
  const moves = [0, 1, 2, 3, 4, 5].filter(i => i !== slot).map(i => {
    const o = state.party.slots[i];
    return { label: `Slot ${i + 1}`, sub: o && o.name ? 'Swap with ' + o.name : 'Empty', run: () => partyMove(slot, i) };
  });
  sheet(m.name + (m.shiny ? ' ★' : '') + genderMark(m), monDetailHtml(m) + '<h3>Move to</h3>', moves);
}
// The Send out key is its own target: keep the card's tap/drag gestures off it.
$('#party').addEventListener('pointerdown', e => { if (e.target.closest('[data-send]')) e.stopPropagation(); }, true);
$('#party').addEventListener('click', e => {
  const b = e.target.closest('[data-send]');
  if (b) act({ kind: 'party', slot: Number(b.dataset.send), send: true });
});
gestures($('#party'), '.mon', {
  canDrag: el => !el.classList.contains('empty'),
  onTap: el => { if (!el.classList.contains('empty')) partySummary(Number(el.dataset.p)); },
  onLong: el => { if (!el.classList.contains('empty')) partySummary(Number(el.dataset.p)); },
  onDrop: (from, to) => partyMove(Number(from.dataset.p), Number(to.dataset.p)),
});


// ---------- Cobblemon PC ----------
let pc = null, pcSig = '', pcBox = store.get('pcBox', 0), pcSel = null; // pcSel: {type, box?, slot, id}
let pcTimer = null;
const STAT_LABEL = { HP: 'HP', ATTACK: 'Atk', DEFENCE: 'Def', SPECIAL_ATTACK: 'SpA', SPECIAL_DEFENCE: 'SpD', SPEED: 'Spe' };

async function loadPc() {
  if (loadPc.busy) return;
  loadPc.busy = true;
  try { await loadPcInner(); } finally { loadPc.busy = false; }
}
async function loadPcInner() {
  try {
    const r = await api('/api/pc?sig=' + pcSig);
    if (r.same) return;
    pcSig = r.sig;
    pc = r;
    renderPc();
  } catch (e) {
    $('#pc-note').textContent = e.message;
  }
}
function pcLoop() {
  clearInterval(pcTimer);
  pcTimer = setInterval(() => { if (tab === 'pc') loadPc(); }, 1000);
}

function monAt(ref) {
  if (!pc || !ref) return null;
  const m = ref.type === 'pc' ? (((pc.boxes || [])[ref.box] || {}).slots || [])[ref.slot] : (pc.party || [])[ref.slot];
  return m && m.id ? m : null;
}
function sameRef(a, b) { return a && b && a.type === b.type && a.slot === b.slot && (a.type !== 'pc' || a.box === b.box); }
function monMatches(m, q) {
  if (!q) return false;
  const hay = [m.name, m.species, ...(m.types || []), m.shiny ? 'shiny' : '', m.nature, m.ability, m.held || ''].join(' ').toLowerCase();
  return q.split(/\s+/).every(w => hay.includes(w));
}

function pkCell(m, ref, q) {
  let cls = 'pk' + (ref.type === 'party' ? ' party' : '');
  if (sameRef(ref, pcSel)) cls += ' sel';
  if (q) cls += m && m.id && monMatches(m, q) ? ' hit' : ' miss';
  const data = `data-type="${ref.type}" data-slot="${ref.slot}"` + (ref.type === 'pc' ? ` data-box="${ref.box}"` : '');
  if (!m || !m.id) return `<div class="${cls}" ${data}></div>`;
  if (m.hp === 0 || m.fainted) cls += ' faint';
  const img = m.k ? `<img src="${iconUrl(m.k)}" alt="" onerror="this.remove()">` : '';
  return `<div class="${cls}" ${data}>${img}<span class="lv">Lv${m.lvl}</span>${m.shiny ? '<span class="shiny">★</span>' : ''}<span class="nm">${esc(m.name)}</span></div>`;
}

function renderPc() {
  if (!pc) return;
  const boxes = pc.boxes || [];
  pcBox = Math.max(0, Math.min(pcBox, boxes.length - 1));
  $('#pc-note').textContent = !boxes.length ? 'Open a PC in game once to load your boxes.' :
    pc.open ? '' : 'Browsing your last-opened PC. Open a PC in game to move, sort or release.';
  const sel = $('#pc-box');
  const opts = boxes.map((b, i) => `<option value="${i}">${esc(b.name || 'Box ' + (i + 1))} (${b.slots.filter(m => m.id).length})</option>`).join('');
  if (sel.dataset.opts !== opts) { sel.innerHTML = opts; sel.dataset.opts = opts; }
  sel.value = String(pcBox);
  const q = $('#pc-search').value.trim().toLowerCase();
  const box = boxes[pcBox];
  $('#pc-grid').innerHTML = box ? box.slots.map((m, i) => pkCell(m, { type: 'pc', box: pcBox, slot: i }, q)).join('') : '';
  $('#pc-party').innerHTML = (pc.party || []).map((m, i) => pkCell(m, { type: 'party', slot: i }, q)).join('');
  const sm = $('#pc-sortmode');
  if (!sm.childElementCount && pc.sortModes) sm.innerHTML = pc.sortModes.map(m => `<option>${m}</option>`).join('');
  // Search results across every box.
  const res = $('#pc-results');
  if (q) {
    const hits = [];
    boxes.forEach((b, bi) => b.slots.forEach((m, si) => { if (m.id && monMatches(m, q)) hits.push({ m, bi, si }); }));
    (pc.party || []).forEach((m, si) => { if (m.id && monMatches(m, q)) hits.push({ m, bi: -1, si }); });
    res.innerHTML = hits.slice(0, 40).map(h => `<button data-hb="${h.bi}" data-hs="${h.si}">${h.bi < 0 ? 'Party' : 'Box ' + (h.bi + 1)} · ${esc(h.m.name)} Lv${h.m.lvl}${h.m.shiny ? ' ★' : ''}</button>`).join('') || '<span class="note">No matches</span>';
    res.classList.remove('hidden');
  } else res.classList.add('hidden');
  renderPcDetail();
  pcMultiRender();
}

// What the details panel shows when nothing is selected: this box at a glance.
function pcSummaryHtml() {
  const box = pc && pc.boxes && pc.boxes[pcBox];
  const mons = box ? box.slots.filter(m => m.id) : [];
  if (!mons.length) return '<h2>Empty box</h2><div>Tap a Pokémon to see details. Tap one, then another slot to move or swap them. Use “Select several” to move a group.</div>';
  const lv = mons.map(m => m.lvl), shinies = mons.filter(m => m.shiny).length;
  const types = {};
  mons.forEach(m => (m.types || []).forEach(t => { types[t] = (types[t] || 0) + 1; }));
  const top = Object.entries(types).sort((a, b) => b[1] - a[1]).slice(0, 6);
  const perfect = mons.filter(m => m.ivTotal >= 150).length;
  return `<h2>${esc(box.name || 'Box ' + (pcBox + 1))}</h2>
    <div><b>${mons.length}</b>/30 Pokémon · Lv ${Math.min(...lv)}–${Math.max(...lv)}${shinies ? ` · <b>${shinies}</b> shiny` : ''}${perfect ? ` · <b>${perfect}</b> with 150+ IVs` : ''}</div>
    <h3>Types</h3><div>${top.map(([t, n]) => typePills([t]).replace('</span>', ` ×${n}</span>`)).join('')}</div>
    <h3>Tips</h3><div>Tap one for details · tap another slot to move or swap · “Select several” moves a group.</div>`;
}
// Multi-select: tap several, then move them all to another box.
let pcMulti = null; // Set of "box:slot" while selecting several
function pcMultiRender() {
  const on = pcMulti != null;
  $('#pc-multi').textContent = on ? 'Done' : 'Select several';
  $('#pc-multi').classList.toggle('on', on);
  $('#pc-multi-move').classList.toggle('hidden', !on);
  $('#pc-multi-move').textContent = on ? `Move ${pcMulti.size} to box…` : '';
  $('#pc-multi-move').disabled = !on || !pcMulti.size;
  $$('#pc-grid .pk').forEach(el => el.classList.toggle('multi', on && pcMulti.has(el.dataset.box + ':' + el.dataset.slot)));
}
async function pcMoveGroup(to) {
  const picks = [...pcMulti].map(s => s.split(':').map(Number)).filter(([b]) => b !== to);
  const free = pc.boxes[to].slots.map((m, i) => m.id ? -1 : i).filter(i => i >= 0);
  if (picks.length > free.length) return toast(`Only ${free.length} free slots in that box`);
  pcMulti = null; pcMultiRender();
  for (let i = 0; i < picks.length; i++) await act({ kind: 'pc', op: 'move', from: { type: 'pc', box: picks[i][0], slot: picks[i][1] }, to: { type: 'pc', box: to, slot: free[i] } });
  toast(`Moved ${picks.length} to ${pc.boxes[to].name || 'Box ' + (to + 1)}`, true);
  pcSig = ''; setTimeout(loadPc, 300);
}
function genderMark(m) { return m.gender === 'MALE' ? ' ♂' : m.gender === 'FEMALE' ? ' ♀' : ''; }
// Summary shared by the PC details panel and the Party card sheet.
function monDetailHtml(m) {
  const ivs = Object.entries(m.ivs || {}).map(([k, v]) =>
    `<div class="iv"><span>${STAT_LABEL[k] || k}</span><div class="bar2"><i class="${v === 31 ? 'max' : ''}" style="width:${Math.round(v / 31 * 100)}%"></i></div><b>${v}</b></div>`).join('');
  return `<div>${esc(m.species)}${m.dex ? ' #' + m.dex : ''} · Lv ${m.lvl} · ${m.hp}/${m.maxHp} HP</div>
    <div style="margin:6px 0">${typePills(m.types)}</div>
    ${m.nature ? `<div>Nature: <b>${esc(m.nature)}</b> · Ability: <b>${esc(m.ability)}</b></div>` : ''}
    ${m.held ? `<div>Holding ${esc(m.held)}</div>` : ''}
    ${m.ivs ? `<h3>IVs (${m.ivTotal}/186)</h3>${ivs}` : ''}
    ${m.moves ? `<h3>Moves</h3><div>${m.moves.map(esc).join(' · ') || '—'}</div>` : ''}`;
}
function renderPcDetail() {
  const m = monAt(pcSel);
  const d = $('#pc-detail');
  const rel = $('#pc-release');
  if (!m) {
    if (pcSel) pcSel = null;
    d.innerHTML = pcSummaryHtml();
    rel.classList.add('hidden');
    return;
  }
  d.innerHTML = `<h2>${esc(m.name)}${m.shiny ? ' ★' : ''}${genderMark(m)}</h2>` + monDetailHtml(m);
  rel.classList.toggle('hidden', !pc.open && pcSel.type === 'pc');
  if (rel.dataset.for !== m.id) { rel.classList.remove('armed'); rel.textContent = 'Release'; rel.dataset.for = m.id; }
}

async function pcAct(body) {
  await act(Object.assign({ kind: 'pc' }, body));
  pcSig = '';
  setTimeout(loadPc, 250);
}

function pcTap(el) {
  const ref = { type: el.dataset.type, slot: Number(el.dataset.slot) };
  if (ref.type === 'pc') ref.box = Number(el.dataset.box);
  const m = monAt(ref);
  if (!pcSel) {
    if (m) { pcSel = ref; renderPc(); }
    return;
  }
  if (sameRef(pcSel, ref)) { pcSel = null; renderPc(); return; }
  const from = pcSel;
  pcSel = null;
  pcAct({ op: 'move', from, to: ref });
}
function pcRef(el) {
  const ref = { type: el.dataset.type, slot: Number(el.dataset.slot) };
  if (ref.type === 'pc') ref.box = Number(el.dataset.box);
  return ref;
}
gestures($('#pc-main'), '.pk', {
  canDrag: el => pcMulti == null && !!monAt(pcRef(el)),
  onTap: el => {
    if (pcMulti != null) {
      if (el.dataset.type !== 'pc' || !monAt(pcRef(el))) return;
      const k = el.dataset.box + ':' + el.dataset.slot;
      if (pcMulti.has(k)) pcMulti.delete(k); else pcMulti.add(k);
      pcMultiRender();
      return;
    }
    pcTap(el);
  },
  onDrop: (from, to) => { pcSel = null; pcAct({ op: 'move', from: pcRef(from), to: pcRef(to) }); },
});
$('#pc-multi').addEventListener('click', () => {
  if (pc && !pc.open && pcMulti == null) return toast('Open a PC in game to move Pokémon');
  pcMulti = pcMulti == null ? new Set() : null; pcSel = null; renderPc(); pcMultiRender();
});
$('#pc-multi-move').addEventListener('click', () => {
  if (!pcMulti || !pcMulti.size) return;
  sheet(`Move ${pcMulti.size} Pokémon to`, null, pc.boxes.map((b, i) => {
    const free = b.slots.filter(m => !m.id).length;
    return { label: b.name || 'Box ' + (i + 1), sub: `${free} free`, disabled: free === 0 || i === pcBox && false, run: () => pcMoveGroup(i) };
  }));
});
$('#pc-prev').addEventListener('click', () => { if (pc && pc.boxes) { pcBox = (pcBox - 1 + pc.boxes.length) % pc.boxes.length; store.set('pcBox', pcBox); renderPc(); } });
$('#pc-next').addEventListener('click', () => { if (pc && pc.boxes) { pcBox = (pcBox + 1) % pc.boxes.length; store.set('pcBox', pcBox); renderPc(); } });
$('#pc-box').addEventListener('change', e => { pcBox = Number(e.target.value); store.set('pcBox', pcBox); renderPc(); });
$('#pc-search').addEventListener('input', () => renderPc());
$('#pc-results').addEventListener('click', e => {
  const b = e.target.closest('button');
  if (!b) return;
  const bi = Number(b.dataset.hb), si = Number(b.dataset.hs);
  pcSel = bi < 0 ? { type: 'party', slot: si } : { type: 'pc', box: bi, slot: si };
  if (bi >= 0) { pcBox = bi; store.set('pcBox', pcBox); }
  renderPc();
});
$('#pc-sort').addEventListener('click', () => pcAct({ op: 'sort', box: pcBox, mode: $('#pc-sortmode').value }));
let releaseTimer;
$('#pc-release').addEventListener('click', e => {
  const b = e.currentTarget;
  const m = monAt(pcSel);
  if (!m) return;
  if (!b.classList.contains('armed')) {
    // Two taps: the first arms the button for 3 seconds.
    b.classList.add('armed');
    b.textContent = `Tap again to release ${m.name} forever`;
    clearTimeout(releaseTimer);
    releaseTimer = setTimeout(() => { b.classList.remove('armed'); b.textContent = 'Release'; }, 3000);
    return;
  }
  clearTimeout(releaseTimer);
  const at = pcSel;
  pcSel = null;
  pcAct({ op: 'release', at, id: m.id });
});
pcLoop();


// ---------- battle helper ----------
const TYPES = ['normal','fire','water','electric','grass','ice','fighting','poison','ground','flying','psychic','bug','rock','ghost','dragon','dark','steel','fairy'];
// CHART[attacker] = {defender: multiplier}; anything missing is 1x (standard Gen 6+ chart).
const CHART = {
  normal: { rock: .5, ghost: 0, steel: .5 },
  fire: { fire: .5, water: .5, grass: 2, ice: 2, bug: 2, rock: .5, dragon: .5, steel: 2 },
  water: { fire: 2, water: .5, grass: .5, ground: 2, rock: 2, dragon: .5 },
  electric: { water: 2, electric: .5, grass: .5, ground: 0, flying: 2, dragon: .5 },
  grass: { fire: .5, water: 2, grass: .5, poison: .5, ground: 2, flying: .5, bug: .5, rock: 2, dragon: .5, steel: .5 },
  ice: { fire: .5, water: .5, grass: 2, ice: .5, ground: 2, flying: 2, dragon: 2, steel: .5 },
  fighting: { normal: 2, ice: 2, poison: .5, flying: .5, psychic: .5, bug: .5, rock: 2, ghost: 0, dark: 2, steel: 2, fairy: .5 },
  poison: { grass: 2, poison: .5, ground: .5, rock: .5, ghost: .5, steel: 0, fairy: 2 },
  ground: { fire: 2, electric: 2, grass: .5, poison: 2, flying: 0, bug: .5, rock: 2, steel: 2 },
  flying: { electric: .5, grass: 2, fighting: 2, bug: 2, rock: .5, steel: .5 },
  psychic: { fighting: 2, poison: 2, psychic: .5, dark: 0, steel: .5 },
  bug: { fire: .5, grass: 2, fighting: .5, poison: .5, flying: .5, psychic: 2, ghost: .5, dark: 2, steel: .5, fairy: .5 },
  rock: { fire: 2, ice: 2, fighting: .5, ground: .5, flying: 2, bug: 2, steel: .5 },
  ghost: { normal: 0, psychic: 2, ghost: 2, dark: .5 },
  dragon: { dragon: 2, steel: .5, fairy: 0 },
  dark: { fighting: .5, psychic: 2, ghost: 2, dark: .5, fairy: .5 },
  steel: { fire: .5, water: .5, electric: .5, ice: 2, rock: 2, steel: .5, fairy: 2 },
  fairy: { fire: .5, fighting: 2, poison: .5, dragon: 2, dark: 2, steel: .5 },
};
const TYPE_COLOR = { normal: '#9fa19f', fire: '#e62829', water: '#2980ef', electric: '#fac000', grass: '#3fa129', ice: '#3dcef3', fighting: '#ff8000', poison: '#9141cb', ground: '#915121', flying: '#81b9ef', psychic: '#ef4179', bug: '#91a119', rock: '#afa981', ghost: '#704170', dragon: '#5060e1', dark: '#624d4e', steel: '#60a1b8', fairy: '#ef70ef' };
function effectiveness(moveType, defTypes) {
  const row = CHART[(moveType || '').toLowerCase()];
  if (!row) return null;
  return (defTypes || []).reduce((m, t) => m * (row[t.toLowerCase()] ?? 1), 1);
}
function effLabel(x) {
  if (x == null) return ['', ''];
  if (x === 0) return ['No effect', 'eff-0'];
  if (x >= 4) return ['Super effective ×4', 'eff-4'];
  if (x >= 2) return ['Super effective ×2', 'eff-2'];
  if (x <= .25) return ['Not very effective ×¼', 'eff-025'];
  if (x < 1) return ['Not very effective ×½', 'eff-05'];
  return ['Normal ×1', ''];
}
let battle = null, battleTimer = null, tabBeforeBattle = null, wasInBattle = false;
async function loadBattle() {
  if (loadBattle.busy) return;
  loadBattle.busy = true;
  try { await loadBattleInner(); } finally { loadBattle.busy = false; }
}
async function loadBattleInner() {
  try { battle = await api('/api/battle'); renderBattle(); } catch (e) { $('#b-note').textContent = e.message; }
}
function hpBar(m) {
  const frac = m.flat ? (m.maxHp ? m.hp / m.maxHp : 0) : m.hp;
  const hue = Math.round(Math.max(0, Math.min(1, frac)) * 120);
  const txt = m.flat ? `${Math.round(m.hp)}/${Math.round(m.maxHp)} HP` : `${Math.round(frac * 100)}% HP`;
  return `<div class="sub">${txt}</div><div class="hp"><i style="width:${Math.round(frac * 100)}%;background:hsl(${hue},75%,45%)"></i></div>`;
}
const LIGHT_TYPES = new Set(['electric', 'ice', 'normal', 'fairy', 'ground', 'steel', 'flying', 'rock', 'bug']);
// Darken a #rrggbb colour (the Thor's WebView is Chrome 109: no CSS color-mix()).
function darken(hex, f) {
  const n = parseInt(hex.slice(1), 16);
  return '#' + [16, 8, 0].map(b => Math.round(((n >> b) & 255) * f).toString(16).padStart(2, '0')).join('');
}
function typeInk(t) { return LIGHT_TYPES.has((t || '').toLowerCase()) ? 'color:#141414;text-shadow:none' : ''; }
function typePills(types) { return (types || []).map(t => `<span class="pill" style="background:${TYPE_COLOR[t.toLowerCase()] || '#555'};${typeInk(t)}">${esc(t)}</span>`).join(''); }
function monCard(m, foe) {
  const st = Object.entries(m.stages || {}).map(([k, v]) => `<span class="stage ${v > 0 ? 'up' : 'down'}">${esc(k.toUpperCase())} ${v > 0 ? '+' : ''}${v}</span>`).join('');
  return `<div class="bm ${foe ? 'foe' : ''}">${m.k ? `<img class="spr" src="${iconUrl(m.k)}" alt="" onerror="this.remove()">` : ''}<div class="info"><div class="nm">${esc(m.name)}${m.shiny ? ' ★' : ''} <span class="sub">Lv ${m.lvl}${m.status ? ' · ' + esc(m.status.toUpperCase()) : ''}</span></div>
    ${hpBar(m)}<div style="margin-top:6px">${typePills(m.types)}</div><div>${st}</div></div></div>`;
}
function renderBattle() {
  const b = battle;
  if (!b || !b.active) {
    setHtml('#b-sides', ''); // via setHtml, so the next identical render isn't skipped
    setHtml('#b-moves', ''); // via setHtml, so the next identical render isn't skipped
    setHtml('#b-bench', ''); // via setHtml, so the next identical render isn't skipped
    $('#b-switch-h').classList.add('hidden');
    $('#b-view').classList.add('hidden');
    $('#b-views').classList.add('hidden');
    $('#b-bag').classList.add('hidden');
    $('#b-note').textContent = 'Not in a battle. This tab opens by itself when one starts.';
    renderShortcuts();
    return;
  }
  // Same as Cobblemon's "Press R to move around": hide the battle screen to walk, aim or throw a ball.
  const view = $('#b-view');
  view.classList.toggle('hidden', !!b.spectating);
  view.classList.toggle('on', !!b.minimised);
  view.textContent = b.minimised ? 'Back to battle' : 'Move around';
  $('#b-views').classList.toggle('hidden', !!b.spectating);
  renderShortcuts();
  renderBag();
  const foe = (b.theirs || [])[0];
  setHtml('#b-sides', (b.theirs || []).map(m => monCard(m, true)).join('') + (b.mine || []).map(m => monCard(m, false)).join(''));
  const r = b.request;
  const myTypes = ((b.mine || [])[0] || {}).types || [];
  if (!r) {
    $('#b-note').textContent = b.spectating ? 'Spectating.' : 'Waiting for the turn…';
    lastTurnSig = '';
    setHtml('#b-gimmicks', ''); $('#b-gimmicks').classList.add('hidden');
    setHtml('#b-moves', ''); // via setHtml, so the next identical render isn't skipped
    setHtml('#b-bench', ''); // via setHtml, so the next identical render isn't skipped
    $('#b-switch-h').classList.add('hidden');
    return;
  }
  $('#b-note').textContent = (b.minimised ? 'Battle screen hidden: you can move. ' : '') +
    (r.forceSwitch ? 'Choose a Pokémon to send in.' : r.singles ? 'Tap a move · hold for details.' : 'Double battle: tap a move, then its target · hold for details.');
  setHtml('#b-moves', r.forceSwitch ? '' : (r.moves || []).map(m => {
    const [label, cls] = foe ? effLabel(m.cat === 'status' ? null : effectiveness(m.type, foe.types)) : ['', ''];
    const stab = myTypes.some(t => t.toLowerCase() === (m.type || '').toLowerCase()) && m.cat !== 'status';
    const col = TYPE_COLOR[(m.type || '').toLowerCase()] || '#555';
    const pow = m.power > 0 ? `Power ${Math.round(m.power)}` : '';
    const acc = m.acc > 0 ? `Acc ${Math.round(m.acc)}%` : '';
    return `<button class="mv" data-move="${esc(m.id)}" style="background:linear-gradient(180deg, ${col}, ${darken(col, .75)});${typeInk(m.type)}" ${m.disabled || m.pp <= 0 ? 'aria-disabled="true"' : ''}>
      <span class="t">${esc(gimmick === 'zmove' && m.zmove ? m.zmove : gimmick === 'max' && m.maxmove ? m.maxmove : m.name)}</span>
      <span class="row"><span>${esc(m.type || '')} · ${esc(m.cat || '')}${stab ? ' · STAB' : ''}</span><span>PP ${m.pp}/${m.maxPp}</span></span>
      <span class="row"><span>${pow} ${acc}</span></span>
      <span class="eff ${cls}">${m.pp <= 0 ? 'No PP left' : m.disabled ? 'Disabled this turn' : label}</span></button>`;
  }).join(''));
  $('#b-switch-h').classList.remove('hidden');
  const why = p => p.active ? 'In battle' : p.hp <= 0 ? 'Fainted' : r.trapped ? 'Trapped' : '';
  setHtml('#b-bench', (r.bench || []).map(p => `<button data-sw="${p.id}" ${why(p) ? 'disabled' : ''}>${esc(p.name)} Lv${p.lvl}<small>${why(p) || `${p.hp}/${p.maxHp} HP`}</small></button>`).join(''));
  // A new turn starts on the moves again (after using the Bag, say).
  // A new turn = the request comes back after a "waiting" stretch (an item use doesn't change PP).
  if (!lastTurnSig) { lastTurnSig = 'turn'; gimmick = null; if (bagView !== 'moves') { bagView = 'moves'; bagSel = null; } }
  renderGimmicks(r);
}
let lastTurnSig = '';
$('#b-gimmicks').addEventListener('click', e => {
  const b = e.target.closest('[data-gim]');
  if (!b) return;
  gimmick = gimmick === b.dataset.gim ? null : b.dataset.gim;
  if (gimmick) toast(`${GIMMICK[gimmick]} is on: tap a move to use it`, true);
  setHtml('#b-moves', ''); // redraw names (Z / Max versions)
  renderBattle();
});
const moveById = id => ((battle && battle.request && battle.request.moves) || []).find(m => m.id === id);
// Battle gimmick picked for the next move (mega / ultra / zmove / max / terastal), cleared each turn.
let gimmick = null;
const GIMMICK = { mega: 'Mega Evolve', ultra: 'Ultra Burst', zmove: 'Z-Power', max: 'Dynamax', terastal: 'Terastallize' };
function renderGimmicks(r) {
  const list = (r && !r.forceSwitch && r.gimmicks) || [];
  if (gimmick && !list.includes(gimmick)) gimmick = null;
  const html = list.map(g => `<button data-gim="${g}" class="gim gim-${g}${gimmick === g ? ' on' : ''}">${GIMMICK[g] || g}${g === 'terastal' && r.teraType ? `<small>${esc(r.teraType)}</small>` : ''}</button>`).join('');
  setHtml('#b-gimmicks', html);
  $('#b-gimmicks').classList.toggle('hidden', !list.length);
}
async function chooseMove(id, target) {
  const g = gimmick;
  gimmick = null;
  await act(Object.assign({ kind: 'battle', op: 'move', id }, target ? { target } : {}, g ? { gimmick: g } : {}));
  loadBattle();
}
function moveInfo(m) {
  const bits = [m.type, m.cat, m.power > 0 ? `Power ${Math.round(m.power)}` : '', m.acc > 0 ? `Accuracy ${Math.round(m.acc)}%` : '', `PP ${m.pp}/${m.maxPp}`].filter(Boolean).join(' · ');
  sheet(m.name, `<b>${esc(bits)}</b><br>${esc(m.desc || 'No description.')}`);
}
gestures($('#b-moves'), '.mv', {
  canDrag: () => false,
  onLong: el => { const m = moveById(el.dataset.move); if (m) moveInfo(m); },
  onTap: el => {
    const m = moveById(el.dataset.move);
    if (!m) return;
    if (m.pp <= 0 || m.disabled) { toast(m.pp <= 0 ? 'No PP left for ' + m.name : m.name + ' is disabled this turn'); return; }
    const ts = m.targets || [];
    // Double battles: pick the target when there's a real choice.
    if (!battle.request.singles && ts.length > 1)
      sheet(m.name, 'Choose a target.', ts.map(t => ({ label: t.name || t.pnx, sub: t.ally ? 'Your side' : 'Opponent', run: () => chooseMove(m.id, t.pnx) })));
    else chooseMove(m.id);
  },
  onDrop: () => {},
});
$('#b-bench').addEventListener('click', async e => {
  const b = e.target.closest('[data-sw]');
  if (!b || b.disabled) return;
  await act({ kind: 'battle', op: 'switch', id: b.dataset.sw });
  loadBattle();
});
// ---------- bottom sheet: a big-button menu or info card over the panel ----------
// opts: [{ label, sub, cls, run }]; tapping outside or Close dismisses it.
function sheet(title, body, opts = []) {
  const el = $('#sheet'), p = el.querySelector('.panel');
  p.innerHTML = `<h2>${esc(title)}</h2>${body ? `<div class="body">${body}</div>` : ''}` +
    (opts.length ? `<div class="opts">${opts.map((o, i) => `<button data-opt="${i}" class="${o.cls || ''}" ${o.disabled ? 'disabled' : ''}>${esc(o.label)}${o.sub ? `<small>${esc(o.sub)}</small>` : ''}</button>`).join('')}</div>` : '') +
    `<button class="close">Close</button>`;
  el.classList.remove('hidden');
  el.onclick = e => {
    const b = e.target.closest('[data-opt]');
    if (b && !b.disabled) { closeSheet(); opts[Number(b.dataset.opt)].run(); return; }
    if (e.target === el || e.target.closest('.close')) closeSheet();
  };
}
function closeSheet() { $('#sheet').classList.add('hidden'); }

// ---------- battle bag: items and Poké Balls (inventory + backpacks) ----------
let bagView = 'moves', bag = null, bagSel = null, bagBusy = false, bagLoadedAt = 0;
async function loadBag() {
  try { bag = await api('/api/bag'); bagLoadedAt = Date.now(); } catch (e) { bag = { error: e.message }; }
  renderBag();
}
function bagCount(e) {
  return `<b>×${e.inv + e.pack}</b>${e.pack ? `<span class="pack">${e.inv ? e.pack + ' in backpack' : 'backpack'}</span>` : ''}`;
}
function renderBag() {
  document.querySelectorAll('#b-views [data-bv]').forEach(b => b.classList.toggle('on', b.dataset.bv === bagView));
  const showMoves = bagView === 'moves';
  $('#b-moves').classList.toggle('hidden', !showMoves);
  $('#b-bag').classList.toggle('hidden', showMoves);
  if (showMoves) return;
  const el = $('#b-bag');
  if (!bag) { el.innerHTML = '<div class="note">Opening the bag…</div>'; return; }
  if (bag.error) { el.innerHTML = `<div class="note">${esc(bag.error)}</div>`; return; }
  const icon = k => `<img src="${iconUrl(k)}" alt="" onerror="this.remove()">`;
  if (bagView === 'bag') {
    const list = bag.items || [];
    if (!list.length) { el.innerHTML = `<div class="note">No battle items in your inventory${bag.backpacks ? ' or backpacks' : ''}.</div>`; return; }
    el.innerHTML = list.map(e => {
      const sel = bagSel === e.k, usable = !e.inGame && e.targets.length > 0;
      let more = '';
      if (sel && usable) more = `<div class="tg">${e.targets.map(t => `<button data-use="${esc(e.k)}" data-t="${esc(t.id)}" data-n="${esc(t.name)}">${esc(t.name)}<small>${t.hp}/${t.maxHp} HP</small></button>`).join('')}</div>`;
      else if (sel) more = `<div class="why">${e.inGame ? 'Needs a move picked: use it on the game screen.' : bag.canAct ? 'Nothing to use it on right now.' : 'Wait for your turn.'}</div>`;
      // A div, not a button: the target buttons go inside it (buttons can't nest).
      return `<div class="bi tap${sel ? ' sel' : ''}${usable ? '' : ' off'}" role="button" tabindex="0">
        <div class="row1" data-bi="${esc(e.k)}">${icon(e.k)}<div><div class="nm">${esc(e.name)}</div><div class="ct">${bagCount(e)}${usable && !sel ? ' · tap to use' : sel ? ' · tap again to close' : ''}</div></div></div>${more}</div>`;
    }).join('');
  } else {
    const list = bag.balls || [];
    if (!list.length) { el.innerHTML = `<div class="note">No Poké Balls in your inventory${bag.backpacks ? ' or backpacks' : ''}.</div>`; return; }
    el.innerHTML = (bag.wild ? '' : '<div class="note">Balls can only be thrown at wild Pokémon. You can still put one in your hand.</div>') + list.map(e => `
      <div class="bi"><div class="row1">${icon(e.k)}<div><div class="nm">${esc(e.name)}</div><div class="ct">${bagCount(e)}</div></div></div>
        <div class="acts"><button class="throw" data-throw="${esc(e.k)}" ${bag.wild ? '' : 'disabled'}>Throw</button><button data-equip="${esc(e.k)}">Equip</button></div></div>`).join('');
  }
}
async function bagAct(body, done) {
  if (bagBusy) return;
  bagBusy = true;
  try { await api('/api/bag', body); toast(done, true); bagSel = null; }
  catch (e) { toast(e.message); }
  finally { bagBusy = false; }
  await loadBag();
  loadBattle();
}
$('#b-views').addEventListener('click', e => {
  const b = e.target.closest('[data-bv]');
  if (!b) return;
  bagView = b.dataset.bv; bagSel = null;
  renderBag();
  if (bagView !== 'moves') loadBag();
});
$('#b-bag').addEventListener('click', e => {
  const name = k => (((bag && [...(bag.items || []), ...(bag.balls || [])]) || []).find(x => x.k === k) || {}).name || 'item';
  const u = e.target.closest('[data-use]');
  if (u) { const t = u.dataset.n || ''; bagAct({ op: 'use', k: u.dataset.use, target: u.dataset.t }, `Used ${name(u.dataset.use)} on ${t}`); return; }
  const th = e.target.closest('[data-throw]');
  if (th && !th.disabled) { bagAct({ op: 'throw', k: th.dataset.throw }, `Threw a ${name(th.dataset.throw)}`); return; }
  const eq = e.target.closest('[data-equip]');
  if (eq) { bagAct({ op: 'equip', k: eq.dataset.equip }, `${name(eq.dataset.equip)} is in your hand`); return; }
  const bi = e.target.closest('[data-bi]');
  if (bi) { bagSel = bagSel === bi.dataset.bi ? null : bi.dataset.bi; renderBag(); }
});
setInterval(() => { if (tab === 'battle' && bagView !== 'moves' && Date.now() - bagLoadedAt > 3000) loadBag(); }, 1000);
$('#b-view').addEventListener('click', async () => {
  await act({ kind: 'battle', op: battle && battle.minimised ? 'show' : 'hide' });
  loadBattle();
});
setInterval(() => { if (tab === 'battle') loadBattle(); }, 600);
function battleWatch() {
  const now = !!(state && state.battle);
  const was = wasInBattle;
  wasInBattle = now; // before showTab(), which re-enters render()
  if (now && !was) { tabBeforeBattle = tab; showTab('battle'); wake(); }
  if (!now && was && tab === 'battle' && tabBeforeBattle) showTab(tabBeforeBattle);
}


// ---------- spawn finder ----------
let spawnData = null, spawnBiome = null, spawnLoading = false;
async function loadSpawns() {
  if (spawnLoading) return;
  spawnLoading = true;
  $('#sp-where').textContent = 'Checking…';
  try {
    spawnData = await api('/api/spawns');
    spawnBiome = state && state.status ? state.status.biome : null;
    spawnCondAt = spawnCond();
  } catch (e) { spawnData = null; $('#sp-list').innerHTML = `<div class="note">${esc(e.message)}</div>`; }
  spawnLoading = false;
  renderSpawns();
  announceTracked();
}
function renderSpawns() {
  if (!spawnData) return;
  const st = state && state.status;
  $('#sp-where').textContent = st ? `${st.biomeName || st.biome} · ${clock(st.time)}${st.rain ? ' · rain' : ''}` : '';
  const missing = $('#sp-missing').checked;
  const LABEL = { CAUGHT: 'Caught', ENCOUNTERED: 'Seen', NONE: 'New' };
  const html = (spawnData.buckets || []).map(b => {
    const list = b.list.filter(s => !missing || s.dexState !== 'CAUGHT');
    if (!list.length) return '';
    return `<div class="spb"><h3>${esc(b.bucket.replace('-', ' '))}</h3>` + list.map(s => `<div class="sp${spTracked.includes(s.name) ? ' tracked' : ''}" data-sp="${esc(s.name)}">
      ${s.k ? `<img src="${iconUrl(s.k)}" alt="" onerror="this.remove()">` : ''}
      <span class="nm">${esc(s.name)}${s.dex ? ` <span class="note">#${s.dex}</span>` : ''}<br>${typePills((s.types || '').split('/').filter(Boolean))}</span>
      ${s.dexState ? `<span class="badge ${s.dexState}">${LABEL[s.dexState] || s.dexState}</span>` : ''}
      <span class="pct">${s.pct}%</span></div>`).join('') + '</div>';
  }).join('');
  $('#sp-list').innerHTML = html || `<div class="note">${missing ? 'You have caught everything that spawns here.' : 'Nothing can spawn here right now.'}</div>`;
}
$('#sp-refresh').addEventListener('click', loadSpawns);
$('#sp-list').addEventListener('click', e => {
  const row = e.target.closest('[data-sp]');
  if (!row) return;
  const name = row.dataset.sp, on = spTracked.includes(name);
  sheet(name, on ? 'You are tracking this Pokémon: the panel tells you when it can spawn where you are.' : 'Track it to get a message when it can spawn where you are, from any tab.', [
    { label: on ? 'Stop tracking' : 'Track ' + name, run: () => { spTracked = on ? spTracked.filter(x => x !== name) : [...spTracked, name]; store.set('spTracked', spTracked); renderSpawns(); } },
  ]);
});
$('#sp-missing').addEventListener('change', renderSpawns);
setInterval(() => {
  // refresh when you walk into a different biome, or every 30 s (time of day / weather change spawns)
  if (tab !== 'spawns' || !state || !state.status) return;
  if (state.status.biome !== spawnBiome || spawnCond() !== spawnCondAt || Date.now() - (loadSpawns.last || 0) > 30000) { loadSpawns.last = Date.now(); loadSpawns(); }
}, 3000);
// Day/night and weather change what spawns: refresh when they flip, not only on a timer.
function spawnCond() { const s = state && state.status; return s ? `${s.time >= 12542 && s.time <= 23459 ? 'night' : 'day'}|${s.rain}|${s.thunder}` : ''; }
let spawnCondAt = '';
// Tracked Pokémon: say so when one can spawn where you are (checked on biome or weather change, any tab).
let spTracked = store.get('spTracked', []), spTrackKey = '';
setInterval(async () => {
  if (!spTracked.length || !state || !state.status || tab === 'spawns' || spawnLoading) return;
  const key = state.status.biome + '|' + spawnCond();
  if (key === spTrackKey) return;
  spTrackKey = key;
  await loadSpawns();
}, 5000);
function announceTracked() {
  if (!spawnData || !spTracked.length) return;
  const here = [];
  (spawnData.buckets || []).forEach(b => b.list.forEach(s => { if (spTracked.includes(s.name)) here.push(`${s.name} (${s.pct}%)`); }));
  const msg = here.join(', ');
  if (msg && msg !== announceTracked.last) toast('Can spawn here: ' + msg, true);
  announceTracked.last = msg;
}

// ---------- chat ----------
let quick = store.get('quick', []);
function renderQuick() {
  $('#quick-cmds').innerHTML = quick.map((c, i) => `<button data-q="${i}">${esc(c)}</button>`).join('');
}
async function loadChat() {
  if (loadChat.busy) return;
  loadChat.busy = true;
  try { await loadChatInner(); } finally { loadChat.busy = false; }
}
async function loadChatInner() {
  try {
    const r = await api('/api/chat');
    const log = $('#chat-log');
    const runs = r.rich || r.lines.map(l => [[l, '', false]]);
    log.innerHTML = runs.map((line, i) => {
      const text = line.map(x => x[0]).join('');
      const player = /^<[^>]+>/.test(text);
      return `<div class="${player ? 'msg-player' : 'msg-system'}">` + line.map(([t, c, b]) =>
        `<span style="${c ? 'color:' + c + ';' : ''}${b ? 'font-weight:900' : ''}">${esc(t)}</span>`).join('') + '</div>';
    }).join('');
    log.scrollTop = log.scrollHeight;
  } catch {}
}
async function sendChat(text) {
  if (!text.trim()) return;
  await act({ kind: 'chat', text });
  setTimeout(loadChat, 400);
}
$('#chat-send').addEventListener('click', () => { const i = $('#chat-input'); sendChat(i.value); i.value = ''; });
$('#chat-input').addEventListener('keydown', e => { if (e.key === 'Enter') $('#chat-send').click(); });
$('#chat-save').addEventListener('click', () => {
  const v = $('#chat-input').value.trim();
  if (!v) return toast('Type a message or command first, then ☆ saves it as a button', true);
  if (!quick.includes(v)) { quick.push(v); store.set('quick', quick); renderQuick(); }
});
let quickLong = false, quickTimer = null;
$('#quick-cmds').addEventListener('pointerdown', e => {
  const b = e.target.closest('[data-q]');
  quickLong = false;
  if (!b) return;
  quickTimer = setTimeout(() => { quickLong = true; quickMenu(Number(b.dataset.q)); }, 500);
});
['pointerup', 'pointercancel', 'pointerleave'].forEach(t => $('#quick-cmds').addEventListener(t, () => clearTimeout(quickTimer)));
function quickMenu(i) {
  const c = quick[i];
  const save = () => { store.set('quick', quick); renderQuick(); };
  sheet(c, 'Edit this quick command.', [
    { label: 'Send it', run: () => sendChat(c) },
    { label: 'Edit in the box', sub: 'then ☆ to save the new one', run: () => { $('#chat-input').value = c; quick.splice(i, 1); save(); } },
    { label: '◀ Earlier', disabled: i === 0, run: () => { quick.splice(i, 1); quick.splice(i - 1, 0, c); save(); } },
    { label: 'Later ▶', disabled: i === quick.length - 1, run: () => { quick.splice(i, 1); quick.splice(i + 1, 0, c); save(); } },
    { label: 'Remove', run: () => { quick.splice(i, 1); save(); } },
  ]);
}
$('#quick-cmds').addEventListener('click', e => {
  const b = e.target.closest('[data-q]');
  if (quickLong) { quickLong = false; return; }
  if (b) sendChat(quick[Number(b.dataset.q)]);
});
$('#quick-cmds').addEventListener('contextmenu', e => {
  const b = e.target.closest('[data-q]');
  if (!b) return;
  e.preventDefault();
  quick.splice(Number(b.dataset.q), 1);
  store.set('quick', quick);
  renderQuick();
});
setInterval(() => { if (tab === 'chat') loadChat(); }, 2500);

// Keybinds are needed by the shortcut keys on every tab: load them once the game is in a world.
setInterval(() => { if (!keys.length && state && state.inGame) loadKeys(); }, 3000);

// Swipe left/right on an empty part of a page to change tab (not on grids or controls that drag).
(() => {
  const order = () => $$('#tabs [data-tab]').map(b => b.dataset.tab);
  let sx = 0, sy = 0, t0 = 0, ok = false;
  $('main').addEventListener('pointerdown', e => {
    ok = !e.target.closest('.slot, .pk, .mon, .ni, .mv, button, input, select, #grid, #pc-main, #net-grid, #chat-log');
    sx = e.clientX; sy = e.clientY; t0 = Date.now();
  });
  $('main').addEventListener('pointerup', e => {
    if (!ok) return;
    const dx = e.clientX - sx, dy = e.clientY - sy;
    if (Math.abs(dx) < 160 || Math.abs(dy) > 90 || Date.now() - t0 > 600) return;
    const o = order(), i = o.indexOf(tab);
    const next = o[(i + (dx < 0 ? 1 : -1) + o.length) % o.length];
    showTab(next);
  });
})();

// The Android wrapper keeps its window unfocusable so the controller stays with the game;
// it only takes focus while a text field is being edited.
document.addEventListener('focusin', e => { if (e.target.matches('input') && window.CVDSApp) CVDSApp.setFocusable(true); });
document.addEventListener('focusout', e => { if (e.target.matches('input') && window.CVDSApp) CVDSApp.setFocusable(false); });
const unfocus = () => { if (window.CVDSApp) CVDSApp.setFocusable(false); };
window.addEventListener('pagehide', unfocus);
document.addEventListener('visibilitychange', () => { if (document.hidden) unfocus(); });

// ---------- OLED care ----------
// The panel goes fully black after a while without touches (black OLED pixels are off), and
// everything drifts a few pixels every minute so nothing sits on the same pixels for hours.
const IDLE_CHOICES = [15, 30, 45, 60, 120, 300];
let idleSec = store.get('idleSec', 45);
let lastTouch = Date.now();
const shade = document.createElement('div');
shade.id = 'shade';
document.body.appendChild(shade);
function setDark(d) {
  shade.classList.toggle('on', d);
  if (!d) lastTouch = Date.now();
  else if (document.activeElement && document.activeElement.matches('input')) document.activeElement.blur();
}
function isDark() { return shade.classList.contains('on'); }
document.addEventListener('pointerdown', () => { lastTouch = Date.now(); }, true);
// The waking tap lands on the shade, so it never presses whatever is underneath.
shade.addEventListener('pointerup', () => setTimeout(() => setDark(false), 0));
setInterval(() => { if (!isDark() && Date.now() - lastTouch > idleSec * 1000) setDark(true); }, 1000);
function wake() { setDark(false); }
setInterval(() => {
  const dx = Math.round(Math.random() * 6 - 3), dy = Math.round(Math.random() * 6 - 3);
  document.body.style.transform = `translate(${dx}px, ${dy}px)`;
}, 60000);
$('#st-side').addEventListener('click', () => {
  idleSec = IDLE_CHOICES[(IDLE_CHOICES.indexOf(idleSec) + 1) % IDLE_CHOICES.length];
  store.set('idleSec', idleSec);
  toast(`Screen goes dark after ${idleSec < 60 ? idleSec + ' s' : idleSec / 60 + ' min'} without touches`, true);
});

renderQuick();
pollLoop();
