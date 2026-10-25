// A plot for sale in a small isometric world with its outline and marker, as client/PlotMarkers draws them in game
// (D105 to D110), still. Used by plot_markers.html and plot_look.html. drawPlot(canvas, look) draws at the canvas's CSS size,
// three times as sharp for render.sh.
(function () {
  const hexA = (hex, a) => { const n = parseInt(hex.slice(1), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})`; };
  const noise = (x, z) => { const s = Math.sin(x * 12.9898 + z * 78.233) * 43758.5453; return s - Math.floor(s); };

  window.drawPlot = function (canvas, look) {
    const W = canvas.clientWidth, H = canvas.clientHeight, K = 3;
    canvas.width = W * K; canvas.height = H * K;
    const ctx = canvas.getContext('2d');
    ctx.scale(K, K);
    const GRID = 13, PLOT = [3, 10], TOP = 9;
    const TW = Math.min(W / (GRID + 1), 20), TH = TW / 2, BH = TH;
    const OX = W / 2, OY = H * 0.36;
    const iso = (x, y, z) => [OX + (x - z) * TW / 2, OY + (x + z) * TH / 2 - y * BH];
    const sky = ctx.createLinearGradient(0, 0, 0, H); sky.addColorStop(0, '#0f1a26'); sky.addColorStop(1, '#070a0e');
    ctx.fillStyle = sky; ctx.fillRect(0, 0, W, H);
    // ground, with a path round the plot
    for (let x = 0; x < GRID; x++) for (let z = 0; z < GRID; z++) {
      const n = noise(x, z), inPlot = x >= PLOT[0] && x <= PLOT[1] && z >= PLOT[0] && z <= PLOT[1];
      let col = n < .08 ? '#7a7d6b' : n < .2 ? '#4d7f34' : n < .6 ? '#5a8e3c' : '#629a42';
      if (!inPlot && (x === 2 || z === 2)) col = n < .5 ? '#8b6a45' : '#7d5f3d';
      const p = [iso(x, 0, z), iso(x + 1, 0, z), iso(x + 1, 0, z + 1), iso(x, 0, z + 1)];
      ctx.fillStyle = col; ctx.beginPath(); p.forEach((q, i) => i ? ctx.lineTo(...q) : ctx.moveTo(...q)); ctx.closePath(); ctx.fill();
      ctx.strokeStyle = 'rgba(0,0,0,.1)'; ctx.lineWidth = .5; ctx.stroke();
    }
    // the outline in the plot's colour
    const [x0, x1] = [PLOT[0], PLOT[1] + 1], c = [[x0, x0], [x1, x0], [x1, x1], [x0, x1]];
    const loop = y => { ctx.beginPath(); c.forEach(([x, z], i) => i ? ctx.lineTo(...iso(x, y, z)) : ctx.moveTo(...iso(x, y, z))); ctx.closePath(); };
    ctx.fillStyle = hexA(look.colour, .16); loop(0); ctx.fill();
    ctx.strokeStyle = look.colour; ctx.lineWidth = 1.2; loop(0); ctx.stroke();
    ctx.globalAlpha = .45; loop(TOP); ctx.stroke();
    for (const [x, z] of c) { ctx.beginPath(); ctx.moveTo(...iso(x, 0, z)); ctx.lineTo(...iso(x, TOP, z)); ctx.stroke(); }
    ctx.globalAlpha = 1; ctx.lineWidth = 1;
    const mid = (PLOT[0] + PLOT[1] + 1) / 2, s = look.size, t = look.text;
    const tag = (text, x, y, scale) => {
      ctx.font = `${Math.round(9 * scale)}px VT323, monospace`;
      const w = ctx.measureText(text).width + 5 * scale, h = 9 * scale;
      ctx.fillStyle = 'rgba(0,0,0,.5)'; ctx.fillRect(x - w / 2, y - h, w, h);
      ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'bottom'; ctx.fillText(text, x, y - .5 * scale);
    };
    if (look.marker === 'arrow') {
      const [cx, cy] = iso(mid, Math.min(look.height, TOP - 4 * s), mid);
      const L = 4 * s * BH, hw = 1.6 * s * BH, sw = .55 * s * BH, head = 1.5 * s * BH;
      ctx.beginPath();
      ctx.moveTo(cx - sw, cy - L); ctx.lineTo(cx + sw, cy - L); ctx.lineTo(cx + sw, cy - head); ctx.lineTo(cx + hw, cy - head);
      ctx.lineTo(cx, cy); ctx.lineTo(cx - hw, cy - head); ctx.lineTo(cx - sw, cy - head); ctx.closePath();
      ctx.fillStyle = hexA(look.colour, .2); ctx.fill(); ctx.strokeStyle = hexA(look.colour, .6); ctx.stroke();
      tag(look.name, cx, cy - L - 3, t);
    } else if (look.marker === 'sphere') {
      const r = 1.3 * s * BH, [cx, cy0] = iso(mid, Math.min(look.height, TOP - 2 * s) + 1.3 * s, mid), cy = cy0;
      // for a still picture the name sits on the front of the ring, left to right; the back half shows faint dots
      const letters = look.name.split(''), orbit = 1.6 * Math.max(s, t) * 1.3 * BH, n = letters.length;
      const ring = front => {
        if (!front) {
          ctx.fillStyle = look.colour; ctx.globalAlpha = .35;
          for (let i = 0; i < 9; i++) { const a = Math.PI + (i + .5) * Math.PI / 9; ctx.fillRect(cx + Math.cos(a) * orbit - .6, cy + Math.sin(a) * r * .35 - .6, 1.2, 1.2); }
          return;
        }
        letters.forEach((ch, i) => {
          const u = -.85 + 1.7 * i / Math.max(1, n - 1); // evenly across the front of the ring
          ctx.font = `${Math.round(.62 * t * BH * 1.4)}px VT323, monospace`;
          ctx.globalAlpha = 1; ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
          ctx.fillText(ch, cx + u * orbit, cy + Math.sqrt(1 - u * u) * r * .35);
        });
      };
      ring(false);
      const g = ctx.createRadialGradient(cx - r * .35, cy - r * .35, r * .1, cx, cy, r);
      g.addColorStop(0, hexA(look.colour, .55)); g.addColorStop(.6, hexA(look.colour, .22)); g.addColorStop(1, hexA(look.colour, .08));
      ctx.globalAlpha = 1; ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = hexA(look.colour, .6); ctx.stroke();
      ring(true); ctx.globalAlpha = 1;
    } else if (look.marker === 'beacon') {
      const [gx, gy] = iso(mid, 0, mid), bw = .5 * s * BH;
      const g = ctx.createLinearGradient(0, gy, 0, 0); g.addColorStop(0, hexA(look.colour, .4)); g.addColorStop(1, hexA(look.colour, 0));
      ctx.fillStyle = g; ctx.fillRect(gx - bw / 2, 0, bw, gy);
      ctx.fillStyle = 'rgba(255,255,255,.45)'; ctx.fillRect(gx - bw / 6, 0, bw / 3, gy);
      const [cx, cy] = iso(mid, Math.min(look.height, TOP - 1), mid), k = .9 * s * BH;
      ctx.beginPath(); ctx.moveTo(cx, cy - k * 1.4); ctx.lineTo(cx + k * .7, cy - k * .7); ctx.lineTo(cx, cy); ctx.lineTo(cx - k * .7, cy - k * .7); ctx.closePath();
      ctx.fillStyle = hexA(look.colour, .75); ctx.fill(); ctx.strokeStyle = 'rgba(255,255,255,.7)'; ctx.stroke();
      tag(look.name, cx, cy - k * 1.4 - 3, t);
    } else if (look.marker === 'sign') {
      const [cx, cy] = iso(mid, Math.min(look.height, TOP - 2.6 * s), mid), w = 5.2 * s * BH, h = 2.8 * s * Math.max(1, t) * BH, x = cx - w / 2, y = cy - h;
      ctx.fillStyle = 'rgba(110,79,47,.85)'; ctx.fillRect(x, y, w, h);
      ctx.strokeStyle = look.colour; ctx.lineWidth = 1.5; ctx.strokeRect(x, y, w, h); ctx.lineWidth = 1;
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      // the three lines scale with the board, as in game (client/PlotMarkers.sign)
      const f = k => `${(k * s * t * BH).toFixed(1)}px VT323, monospace`;
      ctx.fillStyle = look.colour; ctx.font = f(.72); ctx.fillText('FOR SALE', cx, y + h * .3);
      ctx.fillStyle = '#fff'; ctx.font = f(.52); ctx.fillText(look.name, cx, y + h * .57);
      ctx.fillStyle = '#e9d9b4'; ctx.font = f(.44); ctx.fillText(look.price, cx, y + h * .8);
    } else {
      tag(look.name, ...iso(mid, TOP + .5, mid), t);
    }
  };
})();
