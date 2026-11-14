// Teinte les icônes de l'appli de test en orange, pour ne pas la confondre avec Lisiera (test fermé Google).
// À lancer APRÈS make_icons.js. Si aucune icône n'est trouvée, le build continue avec l'icône actuelle.
const sharp = require('sharp');
const fs = require('fs');
const path = require('path');
const RES = process.env.RES || 'android/app/src/main/res';
async function main() {
  if (!fs.existsSync(RES)) { console.log('Dossier res introuvable : icône inchangée.'); return; }
  let n = 0;
  for (const d of fs.readdirSync(RES)) {
    if (!/^mipmap-/.test(d)) continue;
    const dir = path.join(RES, d);
    for (const f of fs.readdirSync(dir)) {
      if (!/^ic_launcher.*\.png$/.test(f)) continue;
      const file = path.join(dir, f);
      const buf = await sharp(file).tint({ r: 255, g: 138, b: 0 }).png().toBuffer();
      fs.writeFileSync(file, buf);
      n++;
    }
  }
  console.log(n ? 'Icônes teintées en orange : ' + n + ' fichier(s).' : 'Aucune icône PNG trouvée : icône inchangée.');
}
main().catch((e) => { console.error(e); process.exit(1); });
