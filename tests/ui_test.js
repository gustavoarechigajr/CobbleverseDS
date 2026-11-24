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
};
function setState(name) { st = STATES[name](); rev++; }
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
  if (u.pathname === '/api/recipes') return json({ ok: true, recipes: [{ id: 'minecraft:torch', k: 'minecraft:torch', n: 4, name: 'Torch', can: true, fav: false,
    ingr: [{ k: 'minecraft:coal', need: 1, have: 23 }, { k: 'minecraft:stick', need: 1, have: 8 }] }], items: {} });
  if (u.pathname === '/api/chat') return json({ ok: true, lines: ['hello'], rich: [[['<Ash> ', '', false], ['hi', '#ff5555', true]]] });
  if (u.pathname === '/api/pc') return json({ sig: 's1', open: true, party: st.party.slots, boxes: [{ name: 'Box 1', slots: [{ id: 'p1', name: 'Eevee', species: 'Eevee', lvl: 5, types: ['Normal'], ivs: { HP: 31 }, ivTotal: 31, moves: ['Tackle'] }, ...Array(29).fill({})] }], items: {}, sortModes: ['NAME'] });
  if (u.pathname === '/api/terminal') return json({ sig: 't1', open: true, menu: 5, list: [{ k: 'minecraft:diamond', n: 1234 }], items: { 'minecraft:diamond': { name: 'Diamond', max: 64 } }, free: 10, slots: 54 });
  if (u.pathname === '/api/spawns') return json({ ok: true, items: {}, buckets: [{ bucket: 'common', list: [{ name: 'Pidgey', pct: 40, dex: 16, dexState: 'CAUGHT', types: 'normal/flying' }, { name: 'Rattata', pct: 35, dex: 19, dexState: 'NONE' }] }, { bucket: 'ultra-rare', list: [] }] });
  if (u.pathname === '/api/map') return json({ ok: true, dim: 'minecraft:overworld', mappable: true, x: 0, z: 0, yaw: 0, tile: 256, tiles: [], xaero: true,
    waypoints: [{ i: 0, name: 'Home', initials: 'H', x: 100, y: 64, z: 100 }], mons: [{ name: 'Pidgey', lvl: 4, x: -30, z: 0, wild: true, shiny: false }] });
  if (u.pathname === '/api/bag') {
    if (opts && opts.method === 'POST') { actions.push(Object.assign({ via: 'bag' }, JSON.parse(opts.body))); return json({ ok: true }); }
    return json({ ok: true, inBattle: true, wild: true, canAct: true, backpacks: true,
      items: [{ k: 'cobblemon:max_revive', name: 'Max Revive', inv: 64, pack: 0, inGame: false, targets: [{ id: 'p9', name: 'Gengar', hp: 0, maxHp: 104 }] },
              { k: 'cobblemon:ether', name: 'Ether', inv: 1, pack: 0, inGame: true, targets: [] }],
      balls: [{ k: 'cobblemon:poke_ball', name: 'Poké Ball', inv: 0, pack: 12 }] });
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
w.eval(appJs + '\nwindow.__t = { showTab, layoutSlots, mapTap, loadMap, setLastTouch: v => { lastTouch = v; } };');

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

  // party: tap a card for its summary sheet; reorder from the sheet (drag needs a real layout)
  actions.length = 0;
  tap($('#party [data-p="0"]')); await sleep(50);
  check(!$('#sheet').classList.contains('hidden') && $('#sheet').textContent.includes('Pikachu'), 'tapping a party card opens its summary');
  check(actions.some(a => a.kind === 'party' && a.slot === 0 && !a.send), 'tapping a party card selects it');
  [...$('#sheet').querySelectorAll('[data-opt]')].find(b => b.textContent.includes('Slot 3')).click(); await sleep(50);
  const re = actions.find(a => a.kind === 'pc' && a.op === 'move');
  check(re && re.from.type === 'party' && re.from.slot === 0 && re.to.slot === 2, 'party reorder sends a party move');
  check($('#sheet').classList.contains('hidden'), 'choosing an option closes the sheet');
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
  $('#b-bag [data-bi="cobblemon:max_revive"]').click(); await sleep(20);
  check(!!$('#b-bag [data-use]'), 'tapping an item shows who it can be used on');
  actions.length = 0; $('#b-bag [data-use]').click(); await sleep(100);
  check(actions.some(a => a.via === 'bag' && a.op === 'use' && a.k === 'cobblemon:max_revive' && a.target === 'p9'), 'using an item sends item + target');
  $('#b-bag [data-bi="cobblemon:ether"]').click(); await sleep(20);
  check($('#b-bag').textContent.includes('game screen') && !$('#b-bag [data-use]'), 'PP items say to use them in game');
  $('#b-views [data-bv="balls"]').click(); await sleep(100);
  check($('#b-bag').textContent.includes('backpack'), 'balls from the backpack are marked');
  actions.length = 0; $('#b-bag [data-throw]').click(); await sleep(100);
  check(actions.some(a => a.via === 'bag' && a.op === 'throw' && a.k === 'cobblemon:poke_ball'), 'Throw sends the ball');
  actions.length = 0; $('#b-bag [data-equip]').click(); await sleep(100);
  check(actions.some(a => a.via === 'bag' && a.op === 'equip'), 'Equip sends the ball');
  $('#b-views [data-bv="moves"]').click(); await sleep(50);
  check(!$('#b-moves').classList.contains('hidden'), 'Moves view comes back');

  setState('none'); await sleep(300);
  check(activeTab() === 'pc', 'battle end returns to the previous tab');

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

  // screen controls: Cobblemon Summary + touchpad
  st = Object.assign(STATES.none(), { screen: { kind: 'summary', cls: 'com.cobblemon.mod.common.client.gui.summary.Summary', title: '',
    summary: { tab: 1, side: 0, sel: 0, party: ['Pikachu', 'Vulpix', '', '', '', ''] } } }); rev++;
  await sleep(400);
  check(!$('#screenpad').classList.contains('hidden') && $('#sum-name').textContent.includes('Pikachu'), 'Summary open shows the screen controls');
  check($('#sum-tabs [data-sumtab="1"]').classList.contains('on'), 'the open Summary page is highlighted');
  actions.length = 0;
  $('#sum-next').click(); await sleep(50);
  $('#sum-tabs [data-sumtab="2"]').click(); await sleep(50);
  $('#sp-back').click(); await sleep(50);
  check(actions.some(a => a.kind === 'summary' && a.op === 'select' && a.i === 1), 'Next ▶ selects the next Pokémon');
  check(actions.some(a => a.kind === 'summary' && a.op === 'tab' && a.i === 2), 'page keys switch the Summary page');
  check(actions.some(a => a.kind === 'pointer' && a.op === 'escape'), 'Back / Close sends Esc');
  actions.length = 0;
  tap($('#pad')); await sleep(100);
  check(actions.some(a => a.kind === 'pointer' && a.op === 'click'), 'tapping the touchpad clicks');
  $('#sp-hide').click(); await sleep(50);
  check($('#screenpad').classList.contains('hidden') && !$('#sp-show').classList.contains('hidden'), 'Hide tucks the controls into a button');
  st = Object.assign(STATES.none(), { screen: { kind: 'other', cls: 'com.cobblemon.mod.common.client.gui.battle.BattleGUI', title: '' } }); rev++;
  await sleep(400);
  check($('#screenpad').classList.contains('hidden') && $('#sp-show').classList.contains('hidden'), 'no screen controls over the battle screen');
  setState('none'); await sleep(300);

  // idle blackout
  w.__t.setLastTouch(Date.now() - 10 * 60 * 1000);
  await sleep(1200);
  check($('#shade').classList.contains('on'), 'screen goes dark when idle');
  setState('chest'); await sleep(200);
  check(!$('#shade').classList.contains('on'), 'opening a container wakes the screen');

  w.__t.showTab('spawns'); await sleep(200);
  const row = $('#sp-list [data-sp]');
  if (row) {
    row.click(); await sleep(20);
    [...$$('#sheet [data-opt]')].find(b => b.textContent.startsWith('Track')).click(); await sleep(50);
    check(JSON.parse(w.localStorage.getItem('cvds.spTracked') || '[]').length === 1, 'tracking a spawn saves it');
  }
  check(errors.length === 0, 'no console errors' + (errors.length ? ': ' + errors[0] : ''));
  console.log(failures ? `\n${failures} UI checks failed` : '\nall UI checks passed');
  process.exit(failures ? 1 : 0);
})();
