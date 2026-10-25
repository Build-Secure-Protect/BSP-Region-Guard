// The region map images (region_map.html: REGION mode, RESHAPE; region_map_draw.html: PLOT mode, DRAW), laid out from
// client/PlotMapScreen.java. The map is drawn at 8 px a block in GUI units (80 x 45 blocks); the cards in card units
// (800 x 450) shown at 80%, as the screen does.
const V = window.VARIANT;
const GOLD = '#ffd23a', VIO = '#b58cff', TQ = '#19d3b0';

// ---- made-up terrain, top-down, coloured like a Minecraft map
function hash(x, z) { let h = x * 374761393 + z * 668265263; h = (h ^ (h >> 13)) * 1274126177; return ((h ^ (h >> 16)) >>> 0) / 4294967295; }
function smooth(x, z, s) { const xi = Math.floor(x / s), zi = Math.floor(z / s), fx = x / s - xi, fz = z / s - zi;
  const a = hash(xi, zi), b = hash(xi + 1, zi), c = hash(xi, zi + 1), d = hash(xi + 1, zi + 1);
  const u = fx * fx * (3 - 2 * fx), v = fz * fz * (3 - 2 * fz); return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v; }
const H = (x, z) => smooth(x + 40, z + 7, 16) * 0.7 + smooth(x, z + 50, 6) * 0.3 + 0.18;
const S = 8, ctx = document.getElementById('c').getContext('2d'); ctx.scale(3, 3);
for (let x = 0; x < 80; x++) for (let z = 0; z < 45; z++) {
  const h = H(x, z), n = H(x, z - 1); let col;
  if (h < 0.28) col = [64, 96, 168];
  else if (h < 0.32) col = [214, 200, 140];
  else { const t = hash(x * 3, z * 5); col = [104 + t * 16, 152 + t * 18, 70 + t * 8]; }
  if ((z === 22 || z === 23) && x > 1) col = [150, 128, 96]; // a road
  if ((x === 44 || x === 45) && z < 42) col = [150, 128, 96];
  const k = h < 0.28 ? 0 : (h > n ? 14 : h < n ? -14 : 0);
  ctx.fillStyle = `rgb(${col[0] + k},${col[1] + k},${col[2] + k})`; ctx.fillRect(x * S, z * S, S, S);
}
ctx.fillStyle = 'rgba(0,0,0,.19)';
for (let x = 0; x <= 80; x++) ctx.fillRect(x * S, 0, 1, 360);
for (let z = 0; z <= 45; z++) ctx.fillRect(0, z * S, 640, 1);

// ---- regions: [id, type, x, z, w, d]; colours as RegionScreen.typeColour
const colourOf = t => t === 'claim' ? VIO : t === 'protected' ? TQ : GOLD;
const isPlot = t => t === 'plot_for_sale' || t === 'claim' || t === 'open_for_buying';
const regions = [['spawn', 'protected', 2, 2, 20, 14], ['market_plots', 'open_for_buying', 46, 3, 32, 17], ['plot_east_1', 'plot_for_sale', 48, 5, 7, 6],
  ['plot_east_2', 'plot_for_sale', 57, 5, 7, 6], ['alex_bakery', 'claim', 66, 5, 8, 6], ['market_hall', 'protected', 26, 25, 16, 11],
  ['iron_mine', 'mine', 4, 29, 10, 10], ['arena', 'protected', 60, 27, 13, 10]];
const mode = V === 'draw' ? 'plot' : 'region';
ctx.font = '9px VT323, monospace';
for (const [id, type, x, z, w, d] of regions) {
  const inMode = mode === 'region' || isPlot(type);
  const c = colourOf(type);
  if (inMode && isPlot(type) && type !== 'open_for_buying') { ctx.fillStyle = c + '30'; ctx.fillRect(x * S, z * S, w * S, d * S); }
  ctx.globalAlpha = inMode ? 1 : 0.32;
  ctx.strokeStyle = c; ctx.lineWidth = 1; ctx.strokeRect(x * S + .5, z * S + .5, w * S - 1, d * S - 1);
  ctx.globalAlpha = 1;
  if (inMode && !(V === 'reshape' && id === 'market_hall')) { ctx.fillStyle = c; ctx.fillText(id, x * S + 3, z * S + 9); }
}

if (V === 'reshape') { // market_hall picked; its east side dragged from x 41 to x 44
  const [x0, z0, x1, z1] = [26, 25, 45, 36];
  ctx.fillStyle = 'rgba(255,255,255,.19)'; ctx.fillRect(x0 * S, z0 * S, (x1 - x0) * S, (z1 - z0) * S);
  ctx.strokeStyle = '#fff'; ctx.strokeRect(x0 * S + .5, z0 * S + .5, (x1 - x0) * S - 1, (z1 - z0) * S - 1);
  ctx.strokeRect(x0 * S + 1.5, z0 * S + 1.5, (x1 - x0) * S - 3, (z1 - z0) * S - 3);
  ctx.fillStyle = TQ; ctx.fillText('market_hall', x0 * S + 4, z0 * S + 10);
  const xs = [x0 * S, (x0 + x1) / 2 * S, x1 * S], zs = [z0 * S, (z0 + z1) / 2 * S, z1 * S];
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) {
    if (i === 1 && j === 1) continue;
    ctx.fillStyle = '#000'; ctx.fillRect(xs[i] - 4, zs[j] - 4, 9, 9);
    ctx.fillStyle = (i === 2 && j === 1) ? TQ : '#fff'; ctx.fillRect(xs[i] - 3, zs[j] - 3, 7, 7);
  }
} else { // the box just drawn for a new plot for sale, inside market_plots
  const [x0, z0, x1, z1] = [48, 12, 56, 18];
  ctx.fillStyle = GOLD + '40'; ctx.fillRect(x0 * S, z0 * S, (x1 - x0) * S, (z1 - z0) * S);
  ctx.strokeStyle = GOLD; ctx.strokeRect(x0 * S + .5, z0 * S + .5, (x1 - x0) * S - 1, (z1 - z0) * S - 1);
  ctx.strokeStyle = 'rgba(255,255,255,.5)'; ctx.strokeRect(x0 * S - .5, z0 * S - .5, (x1 - x0) * S + 1, (z1 - z0) * S + 1);
}

// ---- the cards, in card units
const ui = document.getElementById('ui'), add = h => ui.insertAdjacentHTML('beforeend', h);
const W = 800, UH = 450, cw = s => s.length * 5;
const chip = (x, y, w, h, label, cls = '') => add(`<div class="ch ${cls}" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px">${label}</div>`);
const text = (x, y, s, colour) => add(`<div class="t" style="left:${x}px;top:${y}px;color:${colour}">${s}</div>`);
const card = (x, y, w, h, border) => add(`<div class="cd" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px;border-color:${border}"></div>`);
const field = (x, y, w, s) => add(`<div class="box" style="left:${x}px;top:${y}px;width:${w}px"></div>`) || text(x + 3, y + 2, s, 'var(--text)');

// top bar: modes, tools, the world, UNDO and REDO
card(0, 0, W, 26, TQ);
let x = 6;
for (const [m, on] of [['REGION', mode === 'region'], ['PLOT', mode === 'plot'], ['MINE', false]]) { chip(x, 7, cw(m) + 12, 13, m, on ? 'on' : ''); x += cw(m) + 13; }
x += 10;
for (const [t, on] of [['PICK', false], ['DRAW', V === 'draw'], ['RESHAPE', V === 'reshape']]) { chip(x, 7, cw(t) + 12, 13, t, on ? 'on' : ''); x += cw(t) + 13; }
chip(W - 28, 7, 22, 13, '&#8635;');
chip(W - 34 - cw('REDO (0)') - 10, 7, cw('REDO (0)') + 10, 13, 'REDO (0)', 'off');
const ux = W - 34 - cw('REDO (0)') - 14 - cw('UNDO (2)') - 10;
chip(ux, 7, cw('UNDO (2)') + 10, 13, 'UNDO (2)', 'on');
text(ux - 8 - cw('you are here - editable'), 10, 'you are here - editable', 'var(--muted)');
const wx = ux - 16 - cw('you are here - editable');
chip(wx - 12, 7, 12, 13, '&gt;'); text(wx - 16 - cw('overworld'), 10, 'overworld', 'var(--text)'); chip(wx - 32 - cw('overworld'), 7, 12, 13, '&lt;');

// the REGIONS list
card(8, 34, 200, 262, TQ);
text(14, 40, 'REGIONS', TQ); chip(190, 38, 12, 12, '&#9662;');
field(14, 52, 188, ''); text(17, 54, 'search regions', 'var(--hint)');
const filters = mode === 'region' ? ['ALL', 'PROT', 'SALE', 'CLAIM', 'OPEN', 'MINE'] : ['ALL', 'SALE', 'CLAIM', 'OPEN'];
filters.reduce((fx, f, i) => { chip(fx, 68, cw(f) + 8, 12, f, i === 0 ? 'on' : ''); return fx + cw(f) + 11; }, 14);
add(`<div class="abs" style="left:12px;top:91px;width:192px;height:${18 * 11 + 2}px;background:var(--inset)"></div>`);
const rows = regions.filter(([, t]) => mode === 'region' || isPlot(t));
rows.forEach(([id, t], i) => add(`<div class="row" style="left:12px;top:${92 + i * 11}px;${(V === 'reshape' && id === 'market_hall') ? 'background:rgba(25,211,176,.2)' : ''}">`
  + `<i style="background:${colourOf(t)}"></i><b>${id}</b></div>`));

if (V === 'reshape') {
  // the picked region's card, top right while reshaping
  const cx = W - 218, cy = 32, w = 210;
  card(cx, cy, w, 108, TQ);
  text(cx + 5, cy + 5, 'Market Hall (market_hall)', 'var(--text)'); chip(cx + w - 16, cy + 3, 12, 12, '&#9662;');
  text(cx + 5, cy + 17, '16 x 11, y 62 to 90, priority 5', 'var(--muted)');
  text(cx + 5, cy + 29, 'protected', 'var(--muted)');
  field(cx + 5, cy + 42, 110, 'Market Hall');
  text(cx + 122, cy + 44, 'P 5', 'var(--text)'); chip(cx + 150, cy + 42, 12, 12, '-'); chip(cx + 164, cy + 42, 12, 12, '+');
  [['PROT', 'on'], ['SALE', ''], ['OPEN', ''], ['MINE', '']].reduce((tx, [t, c]) => { chip(tx, cy + 58, cw(t) + 10, 12, t, c); return tx + cw(t) + 13; }, cx + 5);
  ['FLAGS', 'ACCESS', 'TELEPORT', 'INFO'].reduce((tx, t) => { chip(tx, cy + 74, cw(t) + 10, 12, t); return tx + cw(t) + 13; }, cx + 5);
  chip(cx + 5, cy + 90, 76, 12, 'VERTICAL FILL', 'gold'); chip(cx + 84, cy + 90, 60, 12, 'RESHAPE', 'on'); chip(cx + w - 61, cy + 90, 56, 12, 'DELETE');
  // the reshape bar
  const bx = 8, by = UH - 48 - 22, bw = 440;
  card(bx, by, bw, 48, TQ);
  text(bx + 5, by + 4, 'RESHAPE market_hall', 'var(--text)'); text(bx + 12 + cw('RESHAPE market_hall'), by + 4, 'drag a side or corner; Esc or CANCEL stops', 'var(--muted)');
  chip(bx + bw - 16, by + 2, 12, 12, '&#9662;');
  text(bx + 5, by + 19, 'Y', 'var(--muted)'); field(bx + 14, by + 16, 40, '62'); text(bx + 59, by + 19, 'to', 'var(--muted)'); field(bx + 73, by + 16, 40, '90');
  chip(bx + 119, by + 16, 76, 12, 'VERTICAL FILL', 'gold');
  chip(bx + bw - 166, by + 16, 54, 12, 'DONE', 'on'); chip(bx + bw - 109, by + 16, 48, 12, 'RESET'); chip(bx + bw - 58, by + 16, 52, 12, 'CANCEL', 'bad');
  text(bx + 5, by + 33, 'x 26, z 25 to x 44, z 35: 19 x 11, y 62 to 90', 'var(--muted)');
  add(`<div class="abs" style="left:${W - 288}px;top:${UH - 16}px;width:284px;height:12px;background:rgba(5,6,8,.75)"></div>`);
  text(W - 284, UH - 14, 'x 44  z 30   8 px a block   SPACE or right-drag pans', 'var(--text)');
} else {
  // the new plot's card beside the box
  const cx = 568, cy = 120, w = 230, h = 92;
  card(cx, cy, w, h, GOLD);
  text(cx + 5, cy + 5, 'NEW PLOT FOR SALE', GOLD); text(cx + w - 6 - cw('8 x 6'), cy + 5, '8 x 6', 'var(--muted)');
  text(cx + 5, cy + 20, 'ID', 'var(--muted)'); field(cx + 50, cy + 18, 120, 'plot_east_3');
  text(cx + 176, cy + 20, 'P 1', 'var(--text)'); chip(cx + 200, cy + 18, 12, 12, '-'); chip(cx + 214, cy + 18, 12, 12, '+');
  text(cx + 5, cy + 36, 'Y', 'var(--muted)'); field(cx + 50, cy + 34, 40, '-64'); text(cx + 94, cy + 36, 'to', 'var(--muted)'); field(cx + 108, cy + 34, 40, '319');
  chip(cx + 160, cy + 34, 66, 12, 'VERTICAL FILL', 'gold');
  text(cx + 5, cy + 52, 'PRICE', 'var(--muted)'); field(cx + 50, cy + 50, 70, '48000');
  text(cx + 5, cy + 67, 'x 48, z 12 to x 55, z 17: 8 x 6, y -64 to 319', 'var(--muted)');
  chip(cx + w - 116, cy + 79, 54, 12, 'CREATE', 'on'); chip(cx + w - 58, cy + 79, 52, 12, 'CANCEL', 'bad');
  add(`<div class="abs" style="left:${W - 288}px;top:${UH - 16}px;width:284px;height:12px;background:rgba(5,6,8,.75)"></div>`);
  text(W - 284, UH - 14, 'x 55  z 17   8 px a block   SPACE or right-drag pans', 'var(--text)');
}
