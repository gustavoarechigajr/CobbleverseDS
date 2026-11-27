// UI regression test: runs the real index.html + app.js in jsdom against a fake mod server and
// walks it through game state changes. Run: node tests/ui_test.js
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const WEB = path.join(__dirname, '../mod/src/main/resources/assets/cvds/web');
const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8').replace(/<script src="app.js"><\/script>/, '');
const appJs = fs.readFileSync(process.env.CVDS_APP_JS || path.join(WEB, 'app.js'), 'utf8');

let failures = 0;
const check = (cond, msg) => { if (!cond) { failures++; console.log('FAIL ' + msg); } else console.log('ok   ' + msg); };

// ---- fake server ----
let rev = 1;
let st = null;
const actions = [];
const slot = (i, x, y, g, k, n) => (k ? { i, x, y, g, k, n } : { i, x, y, g });
function inventorySlots(offset) {
  const out = [];
  for (let r = 0; r < 3; r++) for (let c = 0; c < 9; c++) out.push(slot(offset + r * 9 + c, 8 + c * 18, 84 + r * 18, 'p'));
  for (let c = 0; c < 9; c++) out.push(slot(offset + 27 + c, 8 + c * 18, 142, 'h', c === 0 ? 'minecraft:stone' : null, 5));
  return out;
}
const base = () => ({
  inGame: true,
  status: { x: 1, y: 64, z: 2, biomeName: 'Plains', day: 3, time: 6000, rain: false, thunder: false, canSleep: false, hp: 20, maxHp: 20, food: 20, xp: 5, hotbar: 0 },
  items: { 'minecraft:stone': { name: 'Stone', max: 64 }, 'minecraft:diamond': { name: 'Diamond', max: 64 } },
  party: { sel: 0, slots: [{ id: 'a', name: 'Pikachu', species: 'pikachu', lvl: 12, hp: 30, maxHp: 35, types: ['Electric'] }, {}, {}, {}, {}, {}] },
});
const STATES = {
  none: () => Object.assign(base(), { screen: { kind: 'none' }, menu: { id: 0, slots: inventorySlots(9), grid: [2, 2] } }),
  chest: () => Object.assign(base(), {
    screen: { kind: 'container', title: 'Chest' },
    menu: { id: 3, slots: [slot(0, 8, 18, 'c', 'minecraft:diamond', 12), ...Array.from({ length: 26 }, (_, i) => slot(i + 1, 8 + ((i + 1) % 9) * 18, 18 + Math.floor((i + 1) / 9) * 18, 'c')), ...inventorySlots(27)] },
  }),
  terminal: () => Object.assign(base(), { screen: { kind: 'container', terminal: true, title: 'S. Terminal' }, menu: { id: 5, slots: inventorySlots(0) } }),
  pc: () => Object.assign(base(), { screen: { kind: 'pc' }, menu: { id: 0, slots: inventorySlots(9) } }),
  battle: () => Object.assign(base(), { screen: { kind: 'other' }, battle: true, menu: { id: 0, slots: inventorySlots(9) } }),
  table: () => Object.assign(base(), { screen: { kind: 'container', title: 'Crafting' }, menu: { id: 7, grid: [3, 3], slots: inventorySlots(10) } }),
};
function setState(name) { st = STATES[name](); rev++; }
let caveMap = false;
const torchIngr = [{ k: 'minecraft:coal', need: 1, have: 23 }, { k: 'minecraft:stick', need: 1, have: 8 }];
const RECIPES = [
  { id: 'minecraft:torch', k: 'minecraft:torch', n: 4, name: 'Torch', fav: false, ingr: torchIngr },
  { id: 'minecraft:stick', k: 'minecraft:stick', n: 4, name: 'Stick', fav: false, ingr: [{ k: 'minecraft:oak_planks', need: 2, have: 4 }] },
  { id: 'minecraft:ladder', k: 'minecraft:ladder', n: 3, name: 'Ladder', fav: false, ingr: [{ k: 'minecraft:stick', need: 7, have: 12 }] },
];
let craftable = ['minecraft:torch'];
const recipeQueries = [];
const jbState = { musicReady: true, songs: [{ id: 'file:cobblemon:battle/wild_battle_kanto', name: 'Wild Battle Kanto', len: 102, kind: 'battle', src: 'COBBLEVERSE Soundtrack' },
  { id: 'file:minecraft:music/game/key', name: 'Battle! N', artist: 'Game Freak', len: 136, kind: 'music', src: 'JigglyRadio' },
  { id: 'minecraft:cat', name: 'C418 - cat', len: 185, k: 'minecraft:music_disc_cat', have: true, kind: 'disc' },
  { id: 'minecraft:pigstep', name: 'Lena Raine - Pigstep', len: 149, k: 'minecraft:music_disc_pigstep', have: false }], queue: [], pos: 0, playing: false, repeat: 'off', volume: 100 };
const json = (o) => ({ ok: true, status: 200, json: async () => o, headers: { get: () => 'application/json' } });
async function fakeFetch(url, opts) {
  const u = new URL(url, 'http://x');
  if (u.pathname === '/api/state') {
    if (Number(u.searchParams.get('rev')) === rev) await new Promise(r => setTimeout(r, 30));
    return json(Object.assign({ rev }, st));
  }
  if (u.pathname === '/api/action') {
    if (!opts.headers || opts.headers['X-CVDS'] !== '1') return json({ ok: false, error: 'missing header' });
    actions.push(JSON.parse(opts.body)); return json({ ok: true });
  }
  if (u.pathname === '/api/keys') return json({ ok: true, keys: [{ id: 'key.inventory', label: 'Inventory', cat: 'Inventory', bound: 'E' }, { id: 'key.sneak', label: 'Sneak', cat: 'Movement', bound: 'Shift', held: false }] });
  if (u.pathname === '/api/recipes') {
    recipeQueries.push(Object.fromEntries(u.searchParams));
    // Like the mod: what can be made now (by name), plus every recipe in keep= even if it can't be made any more.
    const keep = (u.searchParams.get('keep') || '').split(',').filter(Boolean);
    const list = RECIPES.filter(r => craftable.includes(r.id) || keep.includes(r.id)).map(r => Object.assign({}, r, { can: craftable.includes(r.id) }));
    list.sort((a, b) => (keep.includes(a.id) ? 0 : 1) - (keep.includes(b.id) ? 0 : 1) || a.name.localeCompare(b.name));
    return json({ ok: true, recipes: list, items: {} });
  }
  if (u.pathname === '/api/jukebox') {
    if (opts && opts.method === 'POST') {
      const b = JSON.parse(opts.body); actions.push(Object.assign({ via: 'jukebox' }, b));
      if (b.op === 'add') jbState.queue.push(...(b.ids || [b.id]));
      if (b.op === 'play') { jbState.playing = true; jbState.pos = b.i || 0; jbState.now = { id: jbState.queue[jbState.pos], t: 3, len: 185 }; }
      if (b.op === 'stop') { jbState.playing = false; delete jbState.now; }
      if (b.op === 'remove') jbState.queue.splice(b.i, 1);
      return json(Object.assign({ ok: true }, JSON.parse(JSON.stringify(jbState))));
    }
    return json(Object.assign({ ok: true }, JSON.parse(JSON.stringify(jbState))));
  }
  if (u.pathname === '/api/chat') return json({ ok: true, lines: ['hello'], rich: [[['<Ash> ', '', false], ['hi', '#ff5555', true]]] });
  if (u.pathname === '/api/pc') return json({ sig: 's1', open: true, party: st.party.slots, boxes: [{ name: 'Box 1', slots: [{ id: 'p1', name: 'Eevee', species: 'Eevee', lvl: 5, types: ['Normal'], ivs: { HP: 31 }, ivTotal: 31, moves: ['Tackle'] }, ...Array(29).fill({})] }], items: {}, sortModes: ['NAME'] });
  if (u.pathname === '/api/terminal') return json({ sig: 't1', open: true, menu: 5, list: [{ k: 'minecraft:diamond', n: 1234 }], items: { 'minecraft:diamond': { name: 'Diamond', max: 64 } }, free: 10, slots: 54 });
  if (u.pathname === '/api/spawns') return json({ ok: true, items: {}, buckets: [{ bucket: 'common', list: [{ name: 'Pidgey', pct: 40, dex: 16, dexState: 'CAUGHT', types: 'normal/flying' }, { name: 'Rattata', pct: 35, dex: 19, dexState: 'NONE' }] }, { bucket: 'ultra-rare', list: [] }] });
  if (u.pathname === '/api/pokemon') return json({ ok: true, slot: 0, name: 'Pikachu', species: 'Pikachu', dex: 25, lvl: 12, hp: 30, maxHp: 35, types: ['Electric'],
    nature: 'Brave', ability: 'Static', nickname: '', friendship: 70, xp: 1500, ivs: { HP: 31, ATTACK: 10 }, ivTotal: 41, evs: { HP: 4 }, evTotal: 4,
    moveList: [{ id: 'thunderbolt', name: 'Thunderbolt', type: 'Electric', cat: 'special', power: 90, acc: 100, pp: 15, maxPp: 15 },
               { id: 'quickattack', name: 'Quick Attack', type: 'Normal', cat: 'physical', power: 40, acc: 100, pp: 30, maxPp: 30 }],
    bench: [{ id: 'thundershock', name: 'Thunder Shock', type: 'Electric', cat: 'special', power: 40, acc: 100 }],
    evolutions: [{ i: 0, name: 'Raichu' }], items: {} });
  if (u.pathname === '/api/mirror') return { ok: true, status: 204, headers: { get: () => null }, json: async () => ({}) };
  if (u.pathname === '/api/map') return json({ ok: true, dim: caveMap ? 'minecraft:overworld~c1' : 'minecraft:overworld', mappable: true, x: 0, z: 0, yaw: 0, tile: 256, tiles: [], xaero: true,
    ...(caveMap ? { cave: true, caveFrom: -33, caveTo: 31 } : {}),
    waypoints: [{ i: 0, name: 'Home', initials: 'H', x: 100, y: 64, z: 100 }], mons: [{ name: 'Pidgey', lvl: 4, x: -30, z: 0, wild: true, shiny: false }] });
  if (u.pathname === '/api/bag') {
    if (opts && opts.method === 'POST') {
      const b = JSON.parse(opts.body); actions.push(Object.assign({ via: 'bag' }, b));
      return json(b.op === 'heal' ? { ok: true, used: ['Potion → Pikachu'], missing: ['Gengar needs a Revive'] } : { ok: true });
    }
    return json({ ok: true, inBattle: true, wild: true, canAct: true, backpacks: true,
      items: [{ k: 'cobblemon:max_revive', name: 'Max Revive', inv: 64, pack: 0, inGame: false, targets: [{ id: 'p9', name: 'Gengar', hp: 0, maxHp: 104 }] },
              { k: 'cobblemon:ether', name: 'Ether', inv: 1, pack: 0, inGame: true, targets: [] }],
      balls: [{ k: 'cobblemon:poke_ball', name: 'Poké Ball', inv: 0, pack: 12, chance: 37 }] });
  }
  if (u.pathname === '/api/battle') return json(st.battle && st.waiting ? { active: true, mine: [], theirs: [] } : st.battle ? { active: true, minimised: !!st.minimised, mine: [{ name: 'Pikachu', species: 'Pikachu', lvl: 12, hp: 30, maxHp: 35, flat: true, types: ['Electric'] }], theirs: [{ name: 'Gyarados', species: 'Gyarados', lvl: 20, hp: 1, maxHp: 1, flat: false, types: ['Water', 'Flying'] }], request: { singles: true, gimmicks: ['mega', 'zmove', 'terastal'], teraType: 'Fire', moves: [{ id: 'thunderbolt', name: 'Thunderbolt', zmove: 'Gigavolt Havoc', pp: 15, maxPp: 15, type: 'Electric', cat: 'special', power: 90, acc: 100 }, { id: 'lightscreen', name: 'Light Screen', pp: 30, maxPp: 30, type: 'Psychic', cat: 'status', power: 0, acc: 0 }], bench: [] } } : { active: false });
  return { ok: false, status: 404, json: async () => ({ ok: false, error: 'nope' }) };
}

// ---- boot the page ----
const errors = [];
const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true, url: 'http://127.0.0.1:25599/' });
const w = dom.window;
w.fetch = fakeFetch;
w.console.error = (...a) => errors.push(a.join(' '));
w.addEventListener('error', e => errors.push(String(e.error && e.error.stack || e.message)));
setState('none');
// Test-only handle into the page's script scope (strict-mode declarations aren't globals).
w.eval(appJs + '\nwindow.__t = { iconErr, retryFailedIcons, showTab, layoutSlots, mapTap, loadMap, setLastTouch: v => { lastTouch = v; } };');

const sleep = ms => new Promise(r => setTimeout(r, ms));
const $ = s => w.document.querySelector(s);
const $$ = s => [...w.document.querySelectorAll(s)];
const activeTab = () => ($('#tabs button.on') || {}).dataset?.tab;

(async () => {
  await sleep(200);
  check($('#st-main').textContent.includes('Plains'), 'status bar shows position and biome');
  check(activeTab() === 'storage', 'starts on Storage');
  check(w.document.querySelectorAll('#grid .slot').length === 36, 'inventory grid has 36 slots');

  setState('chest'); await sleep(200);
  check(activeTab() === 'storage', 'chest opening keeps/switches to Storage');
  check(w.document.querySelectorAll('#grid .slot').length === 63, 'chest + inventory = 63 slots');
  check(!$('#st-main').textContent.includes('Waiting'), 'no render error after chest opens');

  // tap diamond, then an inventory slot -> move action
  const tap = (el) => { el.dispatchEvent(new w.MouseEvent('pointerdown', { bubbles: true, clientX: 1, clientY: 1 })); el.dispatchEvent(new w.MouseEvent('pointerup', { bubbles: true, clientX: 1, clientY: 1 })); };
  const sizeBefore = $('#grid .slot').style.width;
  tap($('#grid .slot[data-i="0"]'));
  await sleep(50);
  check($('#grid .slot').style.width === sizeBefore && !$('#selbar').classList.contains('hidden'), 'selecting an item does not resize the grid (no "zoom" jump)');
  tap($('#grid .slot[data-i="30"]'));
  await sleep(50);
  const mv = actions.find(a => a.op === 'move');
  check(mv && mv.from === 0 && mv.to === 30 && mv.amount === 'all' && mv.menu === 3, 'tap-tap sends a move with the menu id');

  // drag and drop (jsdom has no layout: point elementFromPoint at the drop target)
  {
    const src = $('#grid .slot[data-i="0"]'), dst = $('#grid .slot[data-i="31"]');
    const n = actions.length;
    w.document.elementFromPoint = () => dst;
    const pe = (t, el, x) => el.dispatchEvent(new w.MouseEvent(t, { bubbles: true, clientX: x, clientY: 0 }));
    pe('pointerdown', src, 0); pe('pointermove', $('#grid'), 40); pe('pointermove', $('#grid'), 80);
    check(!!w.document.querySelector('.ghost'), 'dragging shows the item under the finger');
    pe('pointerup', $('#grid'), 80);
    await sleep(50);
    const d = actions.slice(n).find(a => a.op === 'move');
    check(d && d.from === 0 && d.to === 31 && !w.document.querySelector('.ghost'), 'dropping on a slot moves the stack there');
    w.document.elementFromPoint = () => null;
  }

  // a selection must not carry over into another container
  tap($('#grid .slot[data-i="0"]'));
  setState('none'); await sleep(150);
  const before = actions.length;
  tap($('#grid .slot[data-i="20"]')); await sleep(50);
  check(actions.length === before, 'selection is cleared when the container changes');
  // a scrolling container (backpack) parks a row at y=-100: the panel lays it out below the others
  {
    const w2 = w;
    const fake = [];
    for (let i = 0; i < 24; i++) fake.push({ i, g: 'c', x: 8 + (i % 12) * 18, y: i < 12 ? 18 : -100 });
    for (let i = 0; i < 9; i++) fake.push({ i: 24 + i, g: 'h', x: 38 + i * 18, y: 60 });
    const out = w2.__t.layoutSlots(fake);
    const hidden = out.filter(s => s.g === 'c' && s.i >= 12);
    check(hidden.every(s => s.y === 36) && out.find(s => s.i === 24).y === 78, 'off-screen backpack rows are laid out in order, inventory pushed down');
  }
  // long-press a stack: the item menu (replaces the old mode keys)
  const longPress = async el => { el.dispatchEvent(new w.MouseEvent('pointerdown', { bubbles: true, clientX: 1, clientY: 1 })); await sleep(520); el.dispatchEvent(new w.MouseEvent('pointerup', { bubbles: true, clientX: 1, clientY: 1 })); };
  const full = $$('#grid .slot').find(el => el.querySelector('img, .txt'));
  if (full) {
    await longPress(full);
    check(!$('#sheet').classList.contains('hidden') && $('#sheet').textContent.includes('Quick move') && $('#sheet').textContent.includes('Drop stack'), 'long-press opens the item menu');
    actions.length = 0;
    [...$$('#sheet [data-opt]')].find(b => b.textContent.startsWith('Quick move')).click(); await sleep(50);
    check(actions.some(a => a.kind === 'inv' && a.op === 'quick'), 'Quick move from the menu');
  }
  if (!$('#more').classList.contains('hidden')) {
    $('#more').click(); await sleep(20);
    check($('#sheet').textContent.includes('Store all'), 'More… lists the container actions');
    $('#sheet .close').click();
  }
  w.__t.showTab('party'); await sleep(100);
  check($('#party').textContent.includes('Pikachu'), 'party tab lists the party');

  // party: tapping a card opens the Pokémon page (the Summary as big buttons)
  actions.length = 0;
  tap($('#party [data-p="0"]')); await sleep(150);
  check(!$('#pkpage').classList.contains('hidden') && $('#pk-name').textContent.includes('Pikachu'), 'tapping a party card opens its Pokémon page');
  check(actions.some(a => a.kind === 'party' && a.slot === 0 && !a.send), 'tapping a party card selects it');
  check($('#pk-body').textContent.includes('Thunderbolt') && $('#pk-evo').textContent.includes('Raichu'), 'the page shows moves and a waiting evolution');
  actions.length = 0;
  $('#pk-body [data-pkdn="0"]').click(); await sleep(50);
  check(actions.some(a => a.kind === 'pokemon' && a.op === 'swapMoves' && a.a === 0 && a.b === 1), '▼ reorders moves');
  $('#pk-body [data-pkchg="0"]').click(); await sleep(20);
  [...$$('#sheet [data-opt]')].find(b => b.textContent.includes('Thunder Shock')).click(); await sleep(50);
  check(actions.some(a => a.op === 'benchMove' && a.from === 'thunderbolt' && a.to === 'thundershock'), 'Change swaps in another move it can use');
  $('#pk-evo [data-pkevo]').click(); await sleep(20);
  [...$$('#sheet [data-opt]')].find(b => b.textContent.includes('Evolve')).click(); await sleep(50);
  check(actions.some(a => a.op === 'evolve' && a.i === 0), 'Evolve sends the chosen evolution');
  $('#pk-tabs [data-pkt="info"]').click(); await sleep(20);
  $('#pk-nick').value = 'Sparky'; $('#pk-save').click(); await sleep(50);
  check(actions.some(a => a.op === 'rename' && a.name === 'Sparky'), 'Rename sends the nickname');
  $('#pk-close').click(); await sleep(20);
  check($('#pkpage').classList.contains('hidden'), 'Close hides the Pokémon page');
  actions.length = 0;
  $('#party [data-send="0"]').click(); await sleep(50);
  check(actions.some(a => a.kind === 'party' && a.slot === 0 && a.send), 'each card has its own Send out key');

  w.__t.showTab('craft'); await sleep(300);
  check($('#recipes .ing') && $('#recipes').textContent.includes('23/1'), 'recipes show ingredients with have/need');
  actions.length = 0; $('#recipes [data-c="4"]').click(); await sleep(50);
  check(actions.some(a => a.kind === 'craft' && a.count === 4), 'craft ×4 sends a count');
  $('#recipes [data-fav]').click(); await sleep(100);
  check(JSON.parse(w.localStorage.getItem('cvds.craftFavs') || '[]').includes('minecraft:torch'), 'starring a recipe saves it');

  w.__t.showTab('keys'); await sleep(300);
  const sneak = $('#key-list [data-kb="key.sneak"]') || $('#key-favs [data-kb="key.sneak"]');
  check(sneak && sneak.textContent.includes('tap to hold'), 'hold-type keys say tap to hold');
  actions.length = 0; sneak.click(); await sleep(50);
  check(actions.some(a => a.kind === 'key' && a.id === 'key.sneak' && a.hold === true), 'tapping a hold key holds it');

  w.__t.showTab('pc'); await sleep(400);
  check($('#pc-detail').textContent.includes('/30 Pokémon'), 'PC details show a box summary when nothing is selected');

  w.__t.showTab('chat'); await sleep(300);
  check(!!$('#chat-log span[style*="#ff5555"]'), 'chat keeps message colours');

  w.__t.showTab('spawns'); await sleep(200);
  check($('#sp-list').textContent.includes('Rattata') && $('#sp-list').textContent.includes('Caught'), 'spawn list shows species and dex status');
  $('#sp-missing').checked = true; $('#sp-missing').dispatchEvent(new w.Event('change'));
  check(!$('#sp-list').textContent.includes('Pidgey') && $('#sp-list').textContent.includes('Rattata'), '"only not caught" hides caught species');

  setState('pc'); await sleep(300);
  check(activeTab() === 'pc', 'PC opening switches to the PC tab');
  check($('#pc-grid').textContent.includes('Eevee'), 'PC grid shows box contents');

  setState('battle'); await sleep(300);
  check(activeTab() === 'battle', 'battle start switches to the Battle tab');
  await sleep(700);
  check($('#b-moves').textContent.includes('×4'), 'Thunderbolt vs Water/Flying shows ×4');
  // a turn plays out (no request) and comes back unchanged: moves and Switch must be drawn again
  st.waiting = true; await sleep(800);
  st.waiting = false; await sleep(800);
  check($$('#b-moves .mv').length > 0, 'moves come back after the turn plays out');
  actions.length = 0; tap($('#b-moves .mv')); await sleep(50);
  check(actions.some(a => a.kind === 'battle' && a.op === 'move' && a.id === 'thunderbolt'), 'tapping a move chooses it');
  check($$('#b-gimmicks [data-gim]').length === 3 && $('#b-gimmicks').textContent.includes('Fire'), 'available gimmicks show as keys (with the Tera type)');
  $('#b-gimmicks [data-gim="zmove"]').click(); await sleep(50);
  check($('#b-moves').textContent.includes('Gigavolt Havoc'), 'Z-Power shows the Z-move names');
  actions.length = 0; tap($('#b-moves .mv')); await sleep(50);
  check(actions.some(a => a.op === 'move' && a.gimmick === 'zmove'), 'a move picked with Z-Power on sends the gimmick');
  $('#b-gimmicks [data-gim="zmove"]').click(); await sleep(50);
  const ls = $$('#b-moves .mv').find(b => b.dataset.move === 'lightscreen');
  check(ls && ls.getAttribute('aria-disabled') === 'true' && ls.textContent.includes('No Z-Move'), 'with Z-Power on, a move with no Z version is greyed out');
  actions.length = 0; tap(ls); await sleep(50);
  check(!actions.some(a => a.op === 'move'), 'tapping a move with no Z version sends nothing');
  $('#b-gimmicks [data-gim="zmove"]').click(); await sleep(50);
  check(!$('#st-main').textContent.includes('Waiting'), 'no render error during battle');
  check(!$('#b-view').classList.contains('hidden') && $('#b-view').textContent === 'Move around', 'battle shows a Move around key');
  actions.length = 0; $('#b-view').click(); await sleep(50);
  check(actions.some(a => a.kind === 'battle' && a.op === 'hide'), 'Move around hides the battle screen');
  st.minimised = true; await sleep(700);
  check($('#b-view').textContent === 'Back to battle' && $('#b-note').textContent.includes('you can move'), 'hidden battle offers Back to battle');
  actions.length = 0; $('#b-view').click(); await sleep(50);
  check(actions.some(a => a.kind === 'battle' && a.op === 'show'), 'Back to battle reopens the battle screen');
  st.minimised = false;
  $('#b-views [data-bv="bag"]').click(); await sleep(100);
  check(!$('#b-bag').classList.contains('hidden') && $('#b-moves').classList.contains('hidden'), 'Bag view replaces the moves');
  check($('#b-bag').textContent.includes('Max Revive') && $('#b-bag').textContent.includes('Ether'), 'Bag lists battle items');
  {
    const row = $('#b-bag .bi');
    await sleep(1400); // two battle refreshes
    check(row.isConnected, 'the Bag is not rebuilt by the battle refresh (no icon flicker)');
  }
  $('#b-bag [data-bi="cobblemon:max_revive"]').click(); await sleep(20);
  check(!!$('#b-bag [data-use]'), 'tapping an item shows who it can be used on');
  actions.length = 0; $('#b-bag [data-use]').click(); await sleep(100);
  check(actions.some(a => a.via === 'bag' && a.op === 'use' && a.k === 'cobblemon:max_revive' && a.target === 'p9'), 'using an item sends item + target');
  $('#b-bag [data-bi="cobblemon:ether"]').click(); await sleep(20);
  check($('#b-bag').textContent.includes('game screen') && !$('#b-bag [data-use]'), 'PP items say to use them in game');
  $('#b-views [data-bv="balls"]').click(); await sleep(100);
  check(($('#b-bag .chance') || {}).textContent?.includes('37%'), 'each ball shows its chance to catch');
  check($('#b-bag').textContent.includes('backpack'), 'balls from the backpack are marked');
  actions.length = 0; $('#b-bag [data-throw]').click(); await sleep(100);
  check(actions.some(a => a.via === 'bag' && a.op === 'throw' && a.k === 'cobblemon:poke_ball'), 'Throw sends the ball');
  actions.length = 0; $('#b-bag [data-equip]').click(); await sleep(100);
  check(actions.some(a => a.via === 'bag' && a.op === 'equip'), 'Equip sends the ball');
  $('#b-views [data-bv="moves"]').click(); await sleep(50);
  check(!$('#b-moves').classList.contains('hidden'), 'Moves view comes back');

  $('#b-views [data-bv="balls"]').click(); await sleep(100);
  setState('none'); await sleep(300);
  check(activeTab() === 'pc', 'battle end returns to the previous tab');
  w.__t.showTab('battle'); await sleep(3500);
  check($('#b-bag').classList.contains('hidden') && $('#b-views').classList.contains('hidden'), 'outside a battle the Bag/Poké Balls view stays hidden (no flicker)');
  w.__t.showTab('pc'); await sleep(100);

  w.__t.showTab('storage');
  setState('terminal'); await sleep(1000);
  check(!$('#net').classList.contains('hidden'), 'terminal shows the network panel');
  check($('#net-grid').textContent.includes('1.2k'), 'network counts are abbreviated');
  check($('#bulk [data-bulk="take"]').classList.contains('hidden'), 'terminal hides Take all');
  $('#more').click(); await sleep(20);
  check(!$('#more').classList.contains('hidden') && $('#sheet').textContent.includes('Store all') && !$('#sheet').textContent.includes('Take all'), 'More… lists the container actions that apply');
  $('#sheet .close').click(); await sleep(20);
  const n0 = actions.length;
  const tile = $('#net-grid .ni');
  tile.dispatchEvent(new w.MouseEvent('pointerdown', { bubbles: true })); tile.dispatchEvent(new w.MouseEvent('pointerup', { bubbles: true })); tile.click();
  await sleep(100);
  const sent = actions.slice(n0);
  check(sent.length === 1 && sent[0].kind === 'terminal', 'a terminal tap sends one terminal action (no stray key press)');

  // map: tap empty map -> add waypoint; tap a waypoint -> its sheet
  w.__t.showTab('map'); await sleep(300);
  check(!!$('#tab-map.on #map-canvas'), 'Map tab shows the map');
  actions.length = 0;
  w.__t.mapTap(0, 0); await sleep(20);
  check($('#sheet').textContent.includes('Add waypoint here'), 'tapping the map offers to add a waypoint');
  [...$$('#sheet [data-opt]')].find(b => b.textContent.startsWith('Add waypoint')).click(); await sleep(100);
  check(actions.some(a => a.kind === 'map' && a.op === 'addWaypoint' && a.x === 0 && a.z === 0), 'Add waypoint sends the spot');
  w.__t.mapTap(201, 201); await sleep(20);
  check($('#sheet').textContent.includes('Home') && $('#sheet').textContent.includes('Delete'), 'tapping a waypoint opens it');
  $('#sheet .close').click();
  w.__t.showTab('storage'); await sleep(100);

  // menu mirror: menus with no panel page show as a picture you tap directly
  st = Object.assign(STATES.none(), { screen: { kind: 'summary', cls: 'com.cobblemon.mod.common.client.gui.summary.Summary', title: 'Summary',
    summary: { tab: 1, side: 0, sel: 0, party: ['Pikachu'] } } }); rev++;
  await sleep(400);
  check(!$('#pkpage').classList.contains('hidden') && $('#screenpad').classList.contains('hidden'), "the game's Summary opens the Pokémon page, not the mirror");
  $('#pk-game').click(); await sleep(100);
  check(!$('#screenpad').classList.contains('hidden'), 'Game view shows the real Summary in the mirror');
  st = Object.assign(STATES.none(), { screen: { kind: 'other', cls: 'com.cobblemon.mod.common.client.gui.pokedex.PokedexGUI', title: 'Pokédex' } }); rev++;
  await sleep(400);
  check(!$('#screenpad').classList.contains('hidden') && $('#sp-title').textContent === 'Pokédex', 'a menu with no panel page opens the mirror');
  check(!$('#pad') && !$('#sum-next'), 'the touchpad and its cursor controls are gone');
  actions.length = 0;
  $('#mir-down').click(); await sleep(80);
  check(actions.filter(a => a.kind === 'pointer' && a.op === 'scroll' && a.dy === -1).length === 5, 'Scroll down sends five scroll notches');
  actions.length = 0;
  tap($('#mirror')); await sleep(80);
  const t1 = actions.find(a => a.kind === 'pointer' && a.op === 'tap');
  check(t1 && t1.button === 0 && t1.u >= 0 && t1.u <= 1 && t1.v >= 0 && t1.v <= 1, 'tapping the picture clicks at that spot (u, v)');
  actions.length = 0;
  $('#sp-back').click(); await sleep(50);
  check(actions.some(a => a.kind === 'pointer' && a.op === 'escape'), 'Back sends Esc');
  $('#sp-hide').click(); await sleep(50);
  check($('#screenpad').classList.contains('hidden') && !$('#sp-show').classList.contains('hidden'), 'Hide tucks the mirror into a button');
  st = Object.assign(STATES.none(), { screen: { kind: 'other', cls: 'com.cobblemon.mod.common.client.gui.battle.BattleGUI', title: '' } }); rev++;
  await sleep(400);
  check($('#screenpad').classList.contains('hidden') && $('#sp-show').classList.contains('hidden'), 'no mirror over the battle screen');
  st = { inGame: false, screen: { kind: 'menu', cls: 'net.minecraft.class_442', title: 'Title screen' } }; rev++;
  await sleep(400);
  check(!$('#screenpad').classList.contains('hidden'), 'the mirror also works outside a world (title screen)');
  setState('none'); await sleep(300);

  // idle: dim first, black after 10 minutes or when not in a world
  w.__t.setLastTouch(Date.now() - 50 * 1000);
  await sleep(1200);
  check($('#shade').classList.contains('dim') && !$('#shade').classList.contains('on'), 'screen dims (not black) after the idle time');
  w.__t.setLastTouch(Date.now() - 11 * 60 * 1000);
  await sleep(1200);
  check($('#shade').classList.contains('on') && !$('#shade').classList.contains('dim'), 'screen goes black after 10 minutes untouched');
  setState('chest'); await sleep(200);
  check(!$('#shade').classList.contains('on') && !$('#shade').classList.contains('dim'), 'a container wakes it from black');
  st = { inGame: false, screen: { kind: 'menu', cls: 'net.minecraft.class_442', title: 'Title screen' } }; rev++;
  await sleep(400);
  w.__t.setLastTouch(Date.now() - 50 * 1000);
  await sleep(1200);
  check($('#shade').classList.contains('on'), 'idle outside a world goes straight to black');
  $('#shade').dispatchEvent(new w.Event('pointerup')); await sleep(50);
  check(!$('#shade').classList.contains('on'), 'a tap on the shade wakes it');
  setState('none'); await sleep(300);
  w.__t.setLastTouch(Date.now() - 50 * 1000);
  await sleep(1200);
  check($('#shade').classList.contains('dim'), 'dims again when idle');
  setState('chest'); await sleep(200);
  check(!$('#shade').classList.contains('dim'), 'opening a container wakes the screen');

  w.__t.showTab('spawns'); await sleep(200);
  const row = $('#sp-list [data-sp]');
  if (row) {
    row.click(); await sleep(20);
    [...$$('#sheet [data-opt]')].find(b => b.textContent.startsWith('Track')).click(); await sleep(50);
    check(JSON.parse(w.localStorage.getItem('cvds.spTracked') || '[]').length === 1, 'tracking a spawn saves it');
  }
  // Heal party: offered when someone is hurt, lists what it used and what was missing
  setState('none'); w.__t.showTab('party'); await sleep(200);
  check(!$('#party-heal').disabled, 'Heal party is offered when a Pokémon is hurt');
  actions.length = 0; $('#party-heal').click(); await sleep(150);
  check(actions.some(a => a.via === 'bag' && a.op === 'heal'), 'Heal party asks the server to heal');
  check($('#sheet').textContent.includes('Potion → Pikachu') && $('#sheet').textContent.includes('needs a Revive'), 'Heal party lists what it used and what is missing');
  $('#sheet .close').click(); await sleep(20);


  // cave map label
  caveMap = true; w.__t.showTab('map'); await w.__t.loadMap(); await sleep(50);
  check($('#map-info').textContent.includes('Cave map · y -33 to 31'), 'underground the map says it shows the cave layer');
  caveMap = false;

  // ---- icons: a failed icon is retried, not dropped (the game may still be starting) ----
  {
    const img = w.document.createElement('img');
    img.setAttribute('src', '/api/icon/minecraft%3Astone');
    $('#grid').appendChild(img);
    w.__t.iconErr(img);
    check(img.isConnected && img.style.visibility === 'hidden' && img.dataset.tries === '1', 'a failed icon is hidden and retried, not removed');
    img.dataset.tries = '99'; w.__t.iconErr(img);
    check(img.isConnected && img.classList.contains('icon-failed'), 'an icon that keeps failing stays and keeps being retried (it may be a dropped connection)');
    const before = img.getAttribute('src');
    w.__t.retryFailedIcons(); await sleep(60);
    check(img.getAttribute('src') !== before && img.getAttribute('src').startsWith('/api/icon/minecraft%3Astone'), 'when the connection comes back, failed icons are asked for again at once');
    img.remove();
  }

  // ---- crafting: a crafting table opens Craft; cards keep their places ----
  craftable = ['minecraft:torch', 'minecraft:stick'];
  w.__t.showTab('party'); await sleep(50);
  setState('table'); await sleep(400);
  check(activeTab() === 'craft', 'opening a crafting table switches to the Craft tab');
  const names = () => $$('#recipes .card .name').map(e => e.textContent.replace(/ ×\d+$/, ''));
  check(names().join() === 'Stick,Torch', 'crafting table lists what can be made (' + names().join() + ')');
  const firstCard = $('#recipes .card');
  // Crafting all the planks: Stick can't be made now, Ladder can.
  craftable = ['minecraft:torch', 'minecraft:ladder'];
  actions.length = 0;
  $('#recipes .card[data-id="minecraft:torch"] [data-c="1"]').click(); await sleep(600);
  check(actions.some(a => a.kind === 'craft' && a.id === 'minecraft:torch'), 'Craft 1 sends the craft');
  check((recipeQueries[recipeQueries.length - 1].keep || '').includes('minecraft:stick'), 'the reload asks to keep the cards already shown');
  check(names().join() === 'Stick,Torch,Ladder', 'cards keep their places after a craft; new ones join at the end (' + names().join() + ')');
  const stick = $('#recipes .card[data-id="minecraft:stick"]');
  check(stick && stick.classList.contains('no') && stick.querySelector('[data-c="1"]').disabled, 'a recipe you can no longer make stays, greyed out');
  setState('none'); await sleep(300);
  check(activeTab() === 'party', 'closing the crafting table goes back to the previous tab');
  setState('chest'); await sleep(300);
  check(activeTab() === 'storage', 'a chest still opens Storage');
  setState('none'); await sleep(200);
  void firstCard;

  // ---- jukebox ----
  w.__t.showTab('music'); await sleep(300);
  check($('#jb-songs').textContent.includes('C418 - cat') && $('#jb-songs').textContent.includes('3:05'), 'Music lists the songs with their length');
  actions.length = 0;
  $('#jb-songs [data-jbadd="minecraft:cat"]').click(); await sleep(100);
  check(actions.some(a => a.via === 'jukebox' && a.op === 'add' && a.id === 'minecraft:cat'), 'tapping a song queues it');
  check($('#jb-queue').textContent.includes('C418 - cat'), 'the queue shows it');
  $('#jb-queue [data-jbplay="0"]').click(); await sleep(100);
  check(actions.some(a => a.via === 'jukebox' && a.op === 'play' && a.i === 0), 'tapping a queued song plays from there');
  check($('#jb-title').textContent === 'C418 - cat' && $('#jb-play').textContent === '■' && !!$('#jb-queue .cur'), 'now playing shows the song and a Stop key');
  $('#jb-filter [data-jbf="have"]').click(); await sleep(50);
  check(!$('#jb-songs').textContent.includes('Pigstep') && $('#jb-songs').textContent.includes('cat'), '"Discs I have" hides songs you have no disc for');
  $('#jb-filter [data-jbf="battle"]').click(); await sleep(50);
  check($('#jb-songs').textContent.includes('Wild Battle Kanto') && !$('#jb-songs').textContent.includes('cat') && $('#jb-songs').textContent.includes('COBBLEVERSE Soundtrack'), '"Battle" lists the pack\'s battle music, with its source pack');
  $('#jb-filter [data-jbf="music"]').click(); await sleep(50);
  check($('#jb-songs').textContent.includes('Battle! N') && $('#jb-songs').textContent.includes('Game Freak') && !$('#jb-songs').textContent.includes('Kanto'), '"Background" lists the pack\'s background music, with the title and artist from the file');
  actions.length = 0; $('#jb-songs [data-jbnow]').click(); await sleep(80);
  check(actions.some(a => a.via === 'jukebox' && a.op === 'playNow' && a.id === 'file:minecraft:music/game/key'), 'pack music can be played like a disc');
  $('#jb-filter [data-jbf="all"]').click();
  $('#jb-play').click(); await sleep(100);
  check(actions.some(a => a.via === 'jukebox' && a.op === 'stop'), 'Stop stops the music');
  const songRow = $('#jb-songs .jb-row');
  await sleep(1300);
  check(songRow.isConnected, 'the song list is not rebuilt by the 1 s refresh when nothing changed');

  check(errors.length === 0, 'no console errors' + (errors.length ? ': ' + errors[0] : ''));
  console.log(failures ? `\n${failures} UI checks failed` : '\nall UI checks passed');
  process.exit(failures ? 1 : 0);
})();
