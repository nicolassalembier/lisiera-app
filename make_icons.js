// Génère l'icône Lisiera (chasseur dans une punaise de géolocalisation) pour le projet Android Capacitor.
const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const RES = process.env.RES || 'android/app/src/main/res';
const BG = '#F1ECDC';     // fond crème
const GREEN = '#1B3A2A';  // vert foncé
const BLAZE = '#E8731A';  // orange de sécurité (bandeau de la casquette)

const PIN = `
<defs><clipPath id="c"><circle cx="512" cy="420" r="156"/></clipPath></defs>
<path d="M512 810 C430 700 302 580 302 420 A210 210 0 1 1 722 420 C722 580 594 700 512 810 Z" fill="${GREEN}"/>
<circle cx="512" cy="420" r="150" fill="${BG}"/>
<g clip-path="url(#c)" fill="${GREEN}">
  <ellipse cx="512" cy="645" rx="150" ry="140"/>
  <circle cx="512" cy="440" r="55"/>
  <path d="M452 388 C452 322 572 322 572 388 Z"/>
  <ellipse cx="512" cy="390" rx="108" ry="16"/>
</g>
<path d="M454 374 L570 374 L572 388 L452 388 Z" fill="${BLAZE}"/>`;

const svgFg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024"><g transform="translate(512 512) scale(0.92) translate(-512 -512)">${PIN}</g></svg>`;
const big = `<g transform="translate(512 512) scale(1.12) translate(-512 -512)">${PIN}</g>`;
const svgSq = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024"><rect width="1024" height="1024" rx="200" fill="${BG}"/>${big}</svg>`;
const svgRound = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024"><circle cx="512" cy="512" r="512" fill="${BG}"/>${big}</svg>`;

const DENS = { mdpi: [48, 108], hdpi: [72, 162], xhdpi: [96, 216], xxhdpi: [144, 324], xxxhdpi: [192, 432] };

async function png(svg, size, file) {
  await sharp(Buffer.from(svg), { density: 144 }).resize(size, size).png().toFile(file);
}

(async () => {
  if (!fs.existsSync(RES)) { console.error('Dossier res introuvable : ' + RES); process.exit(1); }
  for (const [d, [leg, ado]] of Object.entries(DENS)) {
    const dir = path.join(RES, 'mipmap-' + d);
    fs.mkdirSync(dir, { recursive: true });
    for (const f of fs.readdirSync(dir)) {
      if (/^ic_launcher.*\.(png|webp)$/.test(f)) fs.unlinkSync(path.join(dir, f));
    }
    await png(svgSq, leg, path.join(dir, 'ic_launcher.png'));
    await png(svgRound, leg, path.join(dir, 'ic_launcher_round.png'));
    await png(svgFg, ado, path.join(dir, 'ic_launcher_foreground.png'));
  }
  const any = path.join(RES, 'mipmap-anydpi-v26');
  fs.mkdirSync(any, { recursive: true });
  const xml = '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    + '    <background android:drawable="@color/ic_launcher_background"/>\n'
    + '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n</adaptive-icon>\n';
  fs.writeFileSync(path.join(any, 'ic_launcher.xml'), xml);
  fs.writeFileSync(path.join(any, 'ic_launcher_round.xml'), xml);
  const vals = path.join(RES, 'values');
  fs.mkdirSync(vals, { recursive: true });
  fs.writeFileSync(path.join(vals, 'ic_launcher_background.xml'),
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">' + BG + '</color>\n</resources>\n');
  console.log('Icônes générées dans ' + RES);
})().catch(e => { console.error(e); process.exit(1); });
