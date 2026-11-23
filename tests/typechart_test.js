// Checks the UI's type chart against well-known matchups: node tests/typechart_test.js
const fs = require('fs');
const src = fs.readFileSync(__dirname + '/../mod/src/main/resources/assets/cvds/web/app.js', 'utf8');
const chart = src.slice(src.indexOf('const TYPES'), src.indexOf('const TYPE_COLOR'));
const eff = src.slice(src.indexOf('function effectiveness'), src.indexOf('function effLabel'));
const { TYPES, CHART, effectiveness } = new Function(chart + eff + 'return { TYPES, CHART, effectiveness };')();
const cases = [
  ['electric', ['water', 'flying'], 4], ['ground', ['flying'], 0], ['ice', ['dragon', 'ground'], 4],
  ['fire', ['water', 'rock'], 0.25], ['fighting', ['ghost'], 0], ['dragon', ['fairy'], 0],
  ['fairy', ['dragon', 'dark'], 4], ['steel', ['fairy'], 2], ['psychic', ['dark'], 0],
  ['normal', ['normal'], 1], ['grass', ['water', 'ground'], 4], ['bug', ['fire', 'flying'], 0.25],
  ['poison', ['steel'], 0], ['ghost', ['normal'], 0], ['water', ['Fire'], 2],
];
let bad = 0;
for (const [atk, def, want] of cases) {
  const got = effectiveness(atk, def);
  if (got !== want) { bad++; console.log(`FAIL ${atk} -> ${def}: got ${got}, want ${want}`); }
}
for (const a of Object.keys(CHART)) for (const d of Object.keys(CHART[a]))
  if (!TYPES.includes(a) || !TYPES.includes(d)) { bad++; console.log('unknown type', a, d); }
if (TYPES.length !== 18 || Object.keys(CHART).length !== 18) { bad++; console.log('chart must cover 18 types'); }
console.log(bad ? `${bad} failures` : `type chart ok (${cases.length} matchups)`);
process.exit(bad ? 1 : 0);
