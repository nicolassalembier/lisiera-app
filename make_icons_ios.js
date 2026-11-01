// Génère l'icône iOS (1024x1024, sans transparence) de Lisiera à partir de l'image placée à la racine du dépôt (PNG ou JPG, de préférence nommée icone.*).
// Si aucune image n'est trouvée, l'icône par défaut de Capacitor est conservée (le build continue).
const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const SET = process.env.IOS_ICONSET || 'ios/App/App/Assets.xcassets/AppIcon.appiconset';
const imgs = fs.readdirSync('.').filter((f) => /\.(png|jpe?g)$/i.test(f))
  .sort((a, b) => (/^icone/i.test(b) ? 1 : 0) - (/^icone/i.test(a) ? 1 : 0));
const CANDIDATES = [process.env.ICON_SRC, ...imgs].filter(Boolean);
const hex = (c) => '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('').toUpperCase();

async function main() {
  const src = CANDIDATES.find((f) => fs.existsSync(f));
  if (!src) { console.log('Aucune image PNG/JPG à la racine du dépôt : icône par défaut conservée.'); return; }
  if (!fs.existsSync(SET)) { console.error('Dossier AppIcon.appiconset introuvable : ' + SET); process.exit(1); }

  const { data, info } = await sharp(src).removeAlpha().raw().toBuffer({ resolveWithObject: true });
  const w = info.width, h = info.height, N = w * h;
  const mx = (i) => Math.max(data[i * 3], data[i * 3 + 1], data[i * 3 + 2]);

  // 1) Cadre noir autour de l'image : on le repère depuis les bords (noir pur, pas le vert foncé du logo)
  const black = new Uint8Array(N);
  const stack = [];
  const push = (x, y) => { const i = y * w + x; if (!black[i] && mx(i) < 28) { black[i] = 1; stack.push(i); } };
  for (let x = 0; x < w; x++) { push(x, 0); push(x, h - 1); }
  for (let y = 0; y < h; y++) { push(0, y); push(w - 1, y); }
  while (stack.length) {
    const i = stack.pop(), x = i % w, y = (i - x) / w;
    if (x > 0) push(x - 1, y); if (x < w - 1) push(x + 1, y);
    if (y > 0) push(x, y - 1); if (y < h - 1) push(x, y + 1);
  }
  // on élargit de 6 px pour couvrir le liseré gris anti-crénelé
  const R = 6, fill = new Uint8Array(N);
  for (let i = 0; i < N; i++) {
    if (!black[i]) continue;
    const x = i % w, y = (i - x) / w;
    for (let dy = -R; dy <= R; dy++) {
      const yy = y + dy; if (yy < 0 || yy >= h) continue;
      for (let dx = -R; dx <= R; dx++) { const xx = x + dx; if (xx >= 0 && xx < w) fill[yy * w + xx] = 1; }
    }
  }

  // 2) Couleur crème du fond = moyenne des pixels très clairs hors cadre
  let sr = 0, sg = 0, sb = 0, sn = 0;
  for (let i = 0; i < N; i++) {
    if (fill[i]) continue;
    const r = data[i * 3], g = data[i * 3 + 1], b = data[i * 3 + 2];
    if (Math.min(r, g, b) > 235) { sr += r; sg += g; sb += b; sn++; }
  }
  if (!sn) { console.error('Fond clair introuvable dans l\'image.'); process.exit(1); }
  const cream = [Math.round(sr / sn), Math.round(sg / sn), Math.round(sb / sn)];

  // 3) Image nettoyée : le cadre noir devient crème, et on mesure la boîte du logo
  const clean = Buffer.from(data);
  let x0 = w, y0 = h, x1 = 0, y1 = 0;
  for (let i = 0; i < N; i++) {
    if (fill[i]) { clean[i * 3] = cream[0]; clean[i * 3 + 1] = cream[1]; clean[i * 3 + 2] = cream[2]; continue; }
    const d = Math.abs(data[i * 3] - cream[0]) + Math.abs(data[i * 3 + 1] - cream[1]) + Math.abs(data[i * 3 + 2] - cream[2]);
    if (d > 150) { const x = i % w, y = (i - x) / w; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  }
  const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, D = Math.max(x1 - x0, y1 - y0);
  console.log('Logo détecté : ' + (x1 - x0) + 'x' + (y1 - y0) + ' px, fond ' + hex(cream));

  // 4) Fenêtre carrée centrée sur le logo, complétée en crème si elle dépasse de l'image
  async function window(side) {
    const left = Math.round(cx - side / 2), top = Math.round(cy - side / 2);
    const sl = Math.max(0, left), st = Math.max(0, top);
    const sw = Math.min(w, left + side) - sl, sh = Math.min(h, top + side) - st;
    const piece = await sharp(clean, { raw: { width: w, height: h, channels: 3 } })
      .extract({ left: sl, top: st, width: sw, height: sh }).png().toBuffer();
    return sharp({ create: { width: side, height: side, channels: 3, background: { r: cream[0], g: cream[1], b: cream[2] } } })
      .composite([{ input: piece, left: sl - left, top: st - top }]).png().toBuffer();
  }
  const lgBuf = await window(Math.round(D * 1.14));

  // Nom du fichier 1024 attendu par le projet Capacitor (Contents.json), sinon nom par défaut
  let name = 'AppIcon-512@2x.png';
  try {
    const c = JSON.parse(fs.readFileSync(path.join(SET, 'Contents.json'), 'utf8'));
    const im = (c.images || []).find((i) => i.filename);
    if (im) name = im.filename;
  } catch (e) {}
  await sharp(lgBuf).resize(1024, 1024).removeAlpha().png().toFile(path.join(SET, name));
  console.log('Icône iOS générée à partir de ' + src + ' : ' + path.join(SET, name));
}
main().catch((e) => { console.error(e); process.exit(1); });
