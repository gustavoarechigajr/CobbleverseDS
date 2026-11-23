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
  const opts = body === undefined ? {} : { method: 'POST', body: JSON.stringify(body) };
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
      setStatus('Waiting for the game…', '');
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
function setStatus(main, side) {
  $('#st-main').textContent = main;
  $('#st-side').textContent = side;
}

function clock(t) {
  const mins = Math.floor(((t + 6000) % 24000) / 1000 * 60);
  return String(Math.floor(mins / 60)).padStart(2, '0') + ':' + String(mins % 60).padStart(2, '0');
}

function renderStatus() {
  if (!state || !state.inGame) return setStatus(state && state.error ? 'Error: ' + state.error : 'Not in a world', '');
  const s = state.status;
  const weather = s.thunder ? 'Thunder' : s.rain ? 'Rain' : 'Clear';
  const sleepTxt = s.canSleep ? ' · Can sleep' : '';
  setStatus(`${s.x}, ${s.y}, ${s.z} · ${s.biomeName || s.biome || ''}`,
    `${s.fps != null ? s.fps + ' fps · ' : ''}Day ${s.day} ${clock(s.time)} · ${weather}${sleepTxt} · ♥ ${Math.ceil(s.hp)}/${Math.round(s.maxHp)} · 🍗 ${s.food} · Lv ${s.xp}`);
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

function renderGrid(force) {
  const grid = $('#grid');
  const sig = JSON.stringify([state.menu, state.screen, state.status.hotbar, $('#inv-search').value, selected, $('#grid-wrap').clientWidth, $('#grid-wrap').clientHeight]);
  if (!force && sig === lastGridSig) return;
  lastGridSig = sig;
  const slots = menuSlots();
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
  scale = Math.max(scale, 56 / 18); // never smaller than a comfortable touch target; scroll instead
  grid.style.width = Math.round(bw * scale) + 'px';
  grid.style.height = Math.round(bh * scale) + 'px';

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
      if (info.dur != null && info.dur < 1) {
        const hue = Math.round(info.dur * 120);
        inner += `<div class="dur"><i style="width:${Math.round(info.dur * 100)}%;background:hsl(${hue},80%,50%)"></i></div>`;
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
  // A terminal has no container slots: only storing (all / matching the network) applies.
  const term = !!state.screen.terminal;
  $$('#bulk [data-bulk="take"], #bulk [data-bulk="sortc"]').forEach(b => b.classList.toggle('hidden', term));
}

function slotHotbarIndex(s) {
  // Hotbar slots appear in menu order; the first "h" slot is hotbar 0.
  const hb = menuSlots().filter(x => x.g === 'h');
  return hb.indexOf(s);
}

function esc(s) { return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

function setSelected(i) {
  selected = i;
  const bar = $('#selbar');
  if (i == null) { bar.classList.add('hidden'); $('#hotbarbar').classList.add('hidden'); }
  else {
    const s = slotByIndex(i);
    $('#sel-name').textContent = s && s.k ? `${itemInfo(s.k).name}${s.n > 1 ? ' ×' + s.n : ''}` : '';
    bar.classList.remove('hidden');
    $('#hotbarbar').classList.toggle('hidden', mode !== 'hotbar');
  }
  $$('.slot').forEach(el => el.classList.toggle('sel', Number(el.dataset.i) === i));
}

$$('#modes button').forEach(b => b.addEventListener('click', () => {
  mode = b.dataset.mode;
  $$('#modes button').forEach(x => x.classList.toggle('on', x === b));
  setSelected(selected);
}));

(() => {
  const bar = $('#hotbarbar');
  bar.innerHTML = '<span>Put in hotbar slot:</span>' + [1, 2, 3, 4, 5, 6, 7, 8, 9].map(n => `<button data-hb="${n - 1}">${n}</button>`).join('');
  bar.addEventListener('click', async e => {
    const b = e.target.closest('button');
    if (!b || selected == null) return;
    await act({ kind: 'inv', op: 'hotbar', slot: selected, hotbar: Number(b.dataset.hb), menu: state.menu.id });
    setSelected(null);
  });
})();

// Tap / long-press handling on the grid. Long-press always quick-moves (like shift-click).
(() => {
  const grid = $('#grid');
  let timer = null, downSlot = null, longFired = false, startX = 0, startY = 0;
  grid.addEventListener('pointerdown', e => {
    const el = e.target.closest('.slot');
    if (!el) return;
    downSlot = Number(el.dataset.i);
    longFired = false;
    startX = e.clientX; startY = e.clientY;
    timer = setTimeout(() => {
      longFired = true;
      if (navigator.vibrate) navigator.vibrate(30);
      const s = slotByIndex(downSlot);
      if (s && s.k) act({ kind: 'inv', op: 'quick', slot: downSlot, menu: state.menu.id });
      setSelected(null);
    }, 450);
  });
  const cancel = () => { clearTimeout(timer); timer = null; };
  grid.addEventListener('pointermove', e => { if (Math.hypot(e.clientX - startX, e.clientY - startY) > 20) cancel(); });
  grid.addEventListener('pointercancel', cancel);
  grid.addEventListener('pointerup', e => {
    const wasTimer = timer != null;
    cancel();
    if (longFired || !wasTimer || downSlot == null) return;
    tapSlot(downSlot);
  });
})();

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
  const amount = mode === 'half' ? 'half' : mode === 'one' ? 'one' : 'all';
  const from = selected;
  setSelected(null);
  act({ kind: 'inv', op: 'move', from, to: i, amount, menu });
}

$('#selbar').addEventListener('click', async e => {
  const b = e.target.closest('button');
  if (!b || selected == null) return;
  const s = slotByIndex(selected);
  const menu = state.menu.id;
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
  try {
    const r = await api('/api/terminal?sig=' + netSig);
    if (r.same) return;
    netSig = r.sig;
    net = r;
    renderNet();
  } catch (e) { $('#net-info').textContent = e.message; }
}
function netActive() { return state && state.screen && state.screen.terminal; }
function renderNet() {
  const box = $('#net');
  if (!netActive() || !net || !net.open) { box.classList.add('hidden'); return; }
  box.classList.remove('hidden');
  const q = $('#net-search').value.trim().toLowerCase();
  const items = net.items || {};
  let list = net.list.filter(e => !q || ((items[e.k] || {}).name || e.k).toLowerCase().includes(q) || e.k.includes(q));
  if ($('#net-sort').value === 'name') list.sort((a, b) => ((items[a.k] || {}).name || '').localeCompare((items[b.k] || {}).name || ''));
  else list.sort((a, b) => b.n - a.n);
  $('#net-info').textContent = `${net.list.length} kinds · ${net.free}/${net.slots} slots free`;
  const html = list.slice(0, 400).map(e => {
    const name = (items[e.k] || {}).name || e.k;
    return `<div class="ni" data-k="${esc(e.k)}" title="${esc(name)}"><img src="${iconUrl(e.k)}" alt="" onerror="this.remove()" onload="this.nextElementSibling.style.display='none'"><div class="txt">${esc(name)}</div><span class="n">${fmtCount(e.n)}</span></div>`;
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
    timer = setTimeout(() => { long = true; if (navigator.vibrate) navigator.vibrate(30); act({ kind: 'terminal', k, one: true }); }, 450);
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

async function loadRecipes() {
  const q = $('#craft-search').value;
  let res;
  try {
    res = await api('/api/recipes?q=' + encodeURIComponent(q));
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
  $('#recipes').innerHTML = res.recipes.map(r => `
    <div class="card ${r.can ? '' : 'no'}" data-id="${esc(r.id)}">
      <img src="${iconUrl(r.k)}" alt="" onerror="this.style.visibility='hidden'">
      <div class="meta"><div class="name">${esc(r.name)}${r.n > 1 ? ' ×' + r.n : ''}</div>
      <div class="sub">${esc((r.ings || []).join(', ')) || (r.can ? 'Can craft' : 'Missing ingredients')}</div></div>
      <div class="acts"><button data-c="1" ${r.can ? '' : 'disabled'}>Craft 1</button><button data-c="max" ${r.can ? '' : 'disabled'}>Max</button></div>
    </div>`).join('') || '<div class="note">No recipes match.</div>';
}

$('#recipes').addEventListener('click', async e => {
  const b = e.target.closest('button');
  if (!b) return;
  const id = b.closest('.card').dataset.id;
  await act({ kind: 'craft', id, max: b.dataset.c === 'max' });
  setTimeout(loadRecipes, 300);
});

// ---------- keybinds ----------
let keys = [];
const DEFAULT_FAVS = ['key.inventory', 'key.cobblemon.throwpartypokemon', 'key.cobblemon.summary', 'key.cobblemon.pokedex', 'key.cobblemon.pokenavigator', 'key.togglePerspective', 'key.playerlist'];
let favs = store.get('favs', null);

async function loadKeys(force) {
  if (keys.length && !force) return renderKeys();
  try {
    keys = (await api('/api/keys')).keys;
  } catch (e) { toast(e.message); return; }
  if (favs == null) favs = DEFAULT_FAVS.filter(id => keys.some(k => k.id === id));
  renderKeys();
}

function keyButton(k) {
  const on = favs.includes(k.id);
  return `<div class="key"><button class="press" data-k="${esc(k.id)}">${esc(k.label)}<small>${esc(k.bound || 'unbound')}</small></button><button class="star" data-star="${esc(k.id)}">${on ? '★' : '☆'}</button></div>`;
}

function renderKeys() {
  const q = $('#key-search').value.trim().toLowerCase();
  $('#key-favs').innerHTML = favs.map(id => keys.find(k => k.id === id)).filter(Boolean).map(keyButton).join('') || '<div class="note">No favourites yet.</div>';
  const cats = new Map();
  for (const k of keys) {
    if (q && !(k.label + ' ' + k.cat + ' ' + k.id).toLowerCase().includes(q)) continue;
    if (!cats.has(k.cat)) cats.set(k.cat, []);
    cats.get(k.cat).push(k);
  }
  $('#key-list').innerHTML = [...cats].map(([cat, ks]) =>
    `<details ${q ? 'open' : ''}><summary>${esc(cat)} (${ks.length})</summary><div class="keys">${ks.map(keyButton).join('')}</div></details>`).join('');
}

document.addEventListener('click', e => {
  const p = e.target.closest('[data-k]');
  if (p) { act({ kind: 'key', id: p.dataset.k }); return; }
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
  const sig = JSON.stringify(p || null);
  if (sig === lastPartySig && $('#party').childElementCount) return;
  lastPartySig = sig;
  const el = $('#party');
  if (!p) { el.innerHTML = '<div class="note">Cobblemon isn\'t loaded.</div>'; return; }
  el.innerHTML = p.slots.map((m, i) => {
    if (!m.name) return `<div class="mon empty" data-p="${i}">Empty</div>`;
    const frac = m.maxHp ? m.hp / m.maxHp : 0;
    const hue = Math.round(frac * 120);
    return `<div class="mon ${p.sel === i ? 'sel' : ''}" data-p="${i}">
      <div class="nm">${esc(m.name)}${m.shiny ? ' ✨' : ''}</div>
      <div class="sub">Lv ${m.lvl} · ${m.hp}/${m.maxHp} HP${m.fainted ? ' · Fainted' : ''}${m.status ? ' · ' + esc(m.status.toUpperCase()) : ''}</div>
      <div class="hp"><i style="width:${Math.round(frac * 100)}%;background:hsl(${hue},75%,45%)"></i></div>
      <div>${(m.types || []).map(t => `<span class="pill">${esc(t)}</span>`).join('')}</div>
      ${m.held ? `<div class="sub">Holding ${esc(m.held)}</div>` : ''}
    </div>`;
  }).join('');
}
$('#party').addEventListener('click', e => {
  const m = e.target.closest('[data-p]');
  if (m) act({ kind: 'party', slot: Number(m.dataset.p) });
});
$('#party-send').addEventListener('click', () => {
  if (state && state.party) act({ kind: 'party', slot: state.party.sel, send: true });
});


// ---------- Cobblemon PC ----------
let pc = null, pcSig = '', pcBox = store.get('pcBox', 0), pcSel = null; // pcSel: {type, box?, slot, id}
let pcTimer = null;
const STAT_LABEL = { HP: 'HP', ATTACK: 'Atk', DEFENCE: 'Def', SPECIAL_ATTACK: 'SpA', SPECIAL_DEFENCE: 'SpD', SPEED: 'Spe' };

async function loadPc() {
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
}

function renderPcDetail() {
  const m = monAt(pcSel);
  const d = $('#pc-detail');
  const rel = $('#pc-release');
  if (!m) {
    if (pcSel) pcSel = null;
    d.innerHTML = 'Tap a Pokémon to see details. Tap one, then tap another slot to move or swap them.';
    rel.classList.add('hidden');
    return;
  }
  const g = m.gender === 'MALE' ? ' ♂' : m.gender === 'FEMALE' ? ' ♀' : '';
  const ivs = Object.entries(m.ivs || {}).map(([k, v]) =>
    `<div class="iv"><span>${STAT_LABEL[k] || k}</span><div class="bar2"><i class="${v === 31 ? 'max' : ''}" style="width:${Math.round(v / 31 * 100)}%"></i></div><b>${v}</b></div>`).join('');
  d.innerHTML = `<h2>${esc(m.name)}${m.shiny ? ' ★' : ''}${g}</h2>
    <div>${esc(m.species)} #${m.dex} · Lv ${m.lvl} · ${m.hp}/${m.maxHp} HP</div>
    <div style="margin:6px 0">${(m.types || []).map(t => `<span class="pill">${esc(t)}</span>`).join('')}</div>
    <div>Nature: <b>${esc(m.nature)}</b> · Ability: <b>${esc(m.ability)}</b></div>
    ${m.held ? `<div>Holding ${esc(m.held)}</div>` : ''}
    <h3>IVs (${m.ivTotal}/186)</h3>${ivs}
    <h3>Moves</h3><div>${(m.moves || []).map(esc).join(' · ') || '—'}</div>`;
  rel.classList.toggle('hidden', !pc.open && pcSel.type === 'pc');
  rel.classList.remove('armed');
  rel.textContent = 'Release';
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
$('#pc-grid').addEventListener('click', e => { const el = e.target.closest('.pk'); if (el) pcTap(el); });
$('#pc-party').addEventListener('click', e => { const el = e.target.closest('.pk'); if (el) pcTap(el); });
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
  try { battle = await api('/api/battle'); renderBattle(); } catch (e) { $('#b-note').textContent = e.message; }
}
function hpBar(m) {
  const frac = m.flat ? (m.maxHp ? m.hp / m.maxHp : 0) : m.hp;
  const hue = Math.round(Math.max(0, Math.min(1, frac)) * 120);
  const txt = m.flat ? `${Math.round(m.hp)}/${Math.round(m.maxHp)} HP` : `${Math.round(frac * 100)}% HP`;
  return `<div class="sub">${txt}</div><div class="hp"><i style="width:${Math.round(frac * 100)}%;background:hsl(${hue},75%,45%)"></i></div>`;
}
function typePills(types) { return (types || []).map(t => `<span class="pill" style="background:${TYPE_COLOR[t.toLowerCase()] || '#555'}">${esc(t)}</span>`).join(''); }
function monCard(m, foe) {
  const st = Object.entries(m.stages || {}).map(([k, v]) => `<span class="stage ${v > 0 ? 'up' : 'down'}">${esc(k.toUpperCase())} ${v > 0 ? '+' : ''}${v}</span>`).join('');
  return `<div class="bm ${foe ? 'foe' : ''}"><div class="nm">${esc(m.name)}${m.shiny ? ' ★' : ''} <span class="sub">Lv ${m.lvl}${m.status ? ' · ' + esc(m.status.toUpperCase()) : ''}</span></div>
    ${hpBar(m)}<div style="margin-top:6px">${typePills(m.types)}</div><div>${st}</div></div>`;
}
function renderBattle() {
  const b = battle;
  if (!b || !b.active) {
    $('#b-sides').innerHTML = '';
    $('#b-moves').innerHTML = '';
    $('#b-bench').innerHTML = '';
    $('#b-switch-h').classList.add('hidden');
    $('#b-note').textContent = 'Not in a battle. This tab opens by itself when one starts.';
    return;
  }
  const foe = (b.theirs || [])[0];
  $('#b-sides').innerHTML = (b.theirs || []).map(m => monCard(m, true)).join('') + (b.mine || []).map(m => monCard(m, false)).join('');
  const r = b.request;
  const myTypes = ((b.mine || [])[0] || {}).types || [];
  if (!r) {
    $('#b-note').textContent = b.spectating ? 'Spectating.' : 'Waiting for the turn…';
    $('#b-moves').innerHTML = '';
    $('#b-bench').innerHTML = '';
    $('#b-switch-h').classList.add('hidden');
    return;
  }
  $('#b-note').textContent = r.forceSwitch ? 'Choose a Pokémon to send in.' : r.singles ? 'Tap a move.' : 'Double battle: info only. Pick moves and targets on the game screen.';
  $('#b-moves').innerHTML = r.forceSwitch ? '' : (r.moves || []).map(m => {
    const [label, cls] = foe ? effLabel(m.cat === 'status' ? null : effectiveness(m.type, foe.types)) : ['', ''];
    const stab = myTypes.some(t => t.toLowerCase() === (m.type || '').toLowerCase()) && m.cat !== 'status';
    const col = TYPE_COLOR[(m.type || '').toLowerCase()] || '#555';
    const pow = m.power > 0 ? `Power ${Math.round(m.power)}` : '';
    const acc = m.acc > 0 ? `Acc ${Math.round(m.acc)}%` : '';
    return `<button class="mv" data-move="${esc(m.id)}" style="border-color:${col};background:linear-gradient(135deg, ${col}55, var(--panel2))" ${m.disabled || m.pp <= 0 || !r.singles ? 'disabled' : ''}>
      <span class="t">${esc(m.name)}</span>
      <span class="row"><span>${esc(m.type || '')} · ${esc(m.cat || '')}${stab ? ' · STAB' : ''}</span><span>PP ${m.pp}/${m.maxPp}</span></span>
      <span class="row"><span>${pow} ${acc}</span></span>
      <span class="eff ${cls}">${label}</span></button>`;
  }).join('');
  $('#b-switch-h').classList.remove('hidden');
  $('#b-bench').innerHTML = (r.bench || []).map(p => `<button data-sw="${p.id}" ${p.active || p.hp <= 0 || r.trapped ? 'disabled' : ''}>${esc(p.name)} Lv${p.lvl} · ${p.hp}/${p.maxHp}${p.active ? ' (in battle)' : ''}</button>`).join('');
}
$('#b-moves').addEventListener('click', async e => {
  const b = e.target.closest('[data-move]');
  if (!b || b.disabled) return;
  await act({ kind: 'battle', op: 'move', id: b.dataset.move });
  loadBattle();
});
$('#b-bench').addEventListener('click', async e => {
  const b = e.target.closest('[data-sw]');
  if (!b || b.disabled) return;
  await act({ kind: 'battle', op: 'switch', id: b.dataset.sw });
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

// ---------- chat ----------
let quick = store.get('quick', []);
function renderQuick() {
  $('#quick-cmds').innerHTML = quick.map((c, i) => `<button data-q="${i}">${esc(c)}</button>`).join('');
}
async function loadChat() {
  try {
    const r = await api('/api/chat');
    const log = $('#chat-log');
    log.innerHTML = r.lines.map(l => `<div>${esc(l)}</div>`).join('');
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
$('#quick-cmds').addEventListener('click', e => {
  const b = e.target.closest('[data-q]');
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

// The Android wrapper keeps its window unfocusable so the controller stays with the game;
// it only takes focus while a text field is being edited.
document.addEventListener('focusin', e => { if (e.target.matches('input') && window.CVDSApp) CVDSApp.setFocusable(true); });
document.addEventListener('focusout', e => { if (e.target.matches('input') && window.CVDSApp) CVDSApp.setFocusable(false); });

// ---------- OLED care ----------
// The panel goes fully black after a while without touches (black OLED pixels are off), and
// everything drifts a few pixels every minute so nothing sits on the same pixels for hours.
const IDLE_CHOICES = [15, 30, 45, 60, 120, 300];
let idleSec = store.get('idleSec', 45);
let lastTouch = Date.now();
const shade = document.createElement('div');
shade.id = 'shade';
document.body.appendChild(shade);
function setDark(d) { shade.classList.toggle('on', d); if (!d) lastTouch = Date.now(); }
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
