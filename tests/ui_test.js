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
  if (u.pathname === '/api/action') { actions.push(JSON.parse(opts.body)); return json({ ok: true }); }
  if (u.pathname === '/api/keys') return json({ ok: true, keys: [{ id: 'key.inventory', label: 'Inventory', cat: 'Inventory', bound: 'E' }] });
  if (u.pathname === '/api/recipes') return json({ ok: true, recipes: [], items: {} });
  if (u.pathname === '/api/chat') return json({ ok: true, lines: ['hello'] });
  if (u.pathname === '/api/pc') return json({ sig: 's1', open: true, party: st.party.slots, boxes: [{ name: 'Box 1', slots: [{ id: 'p1', name: 'Eevee', species: 'Eevee', lvl: 5, types: ['Normal'], ivs: { HP: 31 }, ivTotal: 31, moves: ['Tackle'] }, ...Array(29).fill({})] }], items: {}, sortModes: ['NAME'] });
  if (u.pathname === '/api/terminal') return json({ sig: 't1', open: true, menu: 5, list: [{ k: 'minecraft:diamond', n: 1234 }], items: { 'minecraft:diamond': { name: 'Diamond', max: 64 } }, free: 10, slots: 54 });
  if (u.pathname === '/api/battle') return json(st.battle ? { active: true, mine: [{ name: 'Pikachu', species: 'Pikachu', lvl: 12, hp: 30, maxHp: 35, flat: true, types: ['Electric'] }], theirs: [{ name: 'Gyarados', species: 'Gyarados', lvl: 20, hp: 1, maxHp: 1, flat: false, types: ['Water', 'Flying'] }], request: { singles: true, moves: [{ id: 'thunderbolt', name: 'Thunderbolt', pp: 15, maxPp: 15, type: 'Electric', cat: 'special', power: 90, acc: 100 }], bench: [] } } : { active: false });
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
w.eval(appJs + '\nwindow.__t = { showTab, setLastTouch: v => { lastTouch = v; } };');

const sleep = ms => new Promise(r => setTimeout(r, ms));
const $ = s => w.document.querySelector(s);
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
  tap($('#grid .slot[data-i="0"]'));
  tap($('#grid .slot[data-i="30"]'));
  await sleep(50);
  const mv = actions.find(a => a.op === 'move');
  check(mv && mv.from === 0 && mv.to === 30 && mv.amount === 'all' && mv.menu === 3, 'tap-tap sends a move with the menu id');

  setState('none'); await sleep(150);
  w.__t.showTab('party'); await sleep(100);
  check($('#party').textContent.includes('Pikachu'), 'party tab lists the party');

  setState('pc'); await sleep(300);
  check(activeTab() === 'pc', 'PC opening switches to the PC tab');
  check($('#pc-grid').textContent.includes('Eevee'), 'PC grid shows box contents');

  setState('battle'); await sleep(300);
  check(activeTab() === 'battle', 'battle start switches to the Battle tab');
  await sleep(700);
  check($('#b-moves').textContent.includes('×4'), 'Thunderbolt vs Water/Flying shows ×4');
  check(!$('#st-main').textContent.includes('Waiting'), 'no render error during battle');

  setState('none'); await sleep(300);
  check(activeTab() === 'pc', 'battle end returns to the previous tab');

  w.__t.showTab('storage');
  setState('terminal'); await sleep(1000);
  check(!$('#net').classList.contains('hidden'), 'terminal shows the network panel');
  check($('#net-grid').textContent.includes('1.2k'), 'network counts are abbreviated');
  check($('#bulk [data-bulk="take"]').classList.contains('hidden'), 'terminal hides Take all');

  // idle blackout
  w.__t.setLastTouch(Date.now() - 10 * 60 * 1000);
  await sleep(1200);
  check($('#shade').classList.contains('on'), 'screen goes dark when idle');
  setState('chest'); await sleep(200);
  check(!$('#shade').classList.contains('on'), 'opening a container wakes the screen');

  check(errors.length === 0, 'no console errors' + (errors.length ? ': ' + errors[0] : ''));
  console.log(failures ? `\n${failures} UI checks failed` : '\nall UI checks passed');
  process.exit(failures ? 1 : 0);
})();
