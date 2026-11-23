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
        render();
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
    `Day ${s.day} ${clock(s.time)} · ${weather}${sleepTxt} · ♥ ${Math.ceil(s.hp)}/${Math.round(s.maxHp)} · 🍗 ${s.food} · Lv ${s.xp}`);
}

// ---------- tabs ----------
function showTab(name) {
  tab = name;
  $$('#tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === name));
  $$('.tab').forEach(t => t.classList.toggle('on', t.id === 'tab-' + name));
  if (name === 'keys') loadKeys();
  if (name === 'craft') loadRecipes();
  if (name === 'chat') loadChat();
  render();
}
$$('#tabs button').forEach(b => b.addEventListener('click', () => showTab(b.dataset.tab)));

function render() {
  renderStatus();
  if (!state || !state.inGame) return;
  const kind = state.screen && state.screen.kind;
  if (kind !== lastScreenKind) {
    if (kind === 'container' && lastScreenKind !== null) { showTab('storage'); wake(); }
    if (kind === 'container' && tab === 'craft') loadRecipes();
    lastScreenKind = kind;
  }
  if (tab === 'storage') renderGrid();
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
      const keys = [...new Set(menuSlots().filter(s => s.g === 'c' && s.k).map(s => s.k))];
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
