import sys

path = sys.argv[1]
s = open(path, encoding='utf-8').read().replace('\r\n', '\n')

NATIVE = r"""const CAP = window.Capacitor;
const nativeBG = !!(CAP && typeof CAP.isNativePlatform === 'function' && CAP.isNativePlatform());
let bgPlugin = null, bgWatcher = null, lastSentAt = 0, bgError = '';
if (nativeBG) {
  try {
    bgPlugin = typeof CAP.registerPlugin === 'function' ? CAP.registerPlugin('BackgroundGeolocation') : (CAP.Plugins && CAP.Plugins.BackgroundGeolocation);
  } catch (e) { bgError = String(e); }
}
function onNativeLocation(loc, err) {
  if (err) {
    gpsState = err.code === 'NOT_AUTHORIZED' ? 'denied' : 'error';
    if (err.code === 'NOT_AUTHORIZED' && confirm('Lisiera a besoin de votre position, mais n’a pas la permission.\n\nOuvrir les réglages ?')) {
      try { bgPlugin.openSettings(); } catch (e) {}
    }
    return;
  }
  gpsState = 'ok';
  if (!trackerOn) trackerStart();

  const t = (loc.time && Math.abs(Date.now() - loc.time) < 3600000) ? loc.time : Date.now();
  lastFix = { lat: loc.latitude, lng: loc.longitude, acc: loc.accuracy, speed: loc.speed, heading: loc.bearing, ts: t };
  stats.accSum += loc.accuracy; stats.accN++;
  drawMe();
  if (!firstViewDone) { firstViewDone = true; if (!zoneLayer) map.setView([loc.latitude, loc.longitude], 17); }
  if (Date.now() - lastSentAt >= sendEvery * 1000 - 400) tick();
}
let trackerOn = false;
const TrackerPlugin = nativeBG ? CAP.registerPlugin('LisieraTracker') : null;
function trackerStart() {
  if (!TrackerPlugin || !S) return;
  TrackerPlugin.start({ url: API + '?a=ping', code: S.code, token: S.token, intervalSec: sendEvery })
    .then(function () { trackerOn = true; })
    .catch(function () { trackerOn = false; });
}
function trackerStop() {
  trackerOn = false;
  if (TrackerPlugin) { TrackerPlugin.stop().catch(function () {}); }
}
function startNativeGPS() {
  if (!bgPlugin) { gpsState = 'error'; return; }
  bgPlugin.addWatcher({
    backgroundTitle: 'Lisiera – battue en cours',
    backgroundMessage: 'Votre position est partagée avec la battue.',
    requestPermissions: true,
    stale: false,
    distanceFilter: 0
  }, onNativeLocation).then(id => { bgWatcher = id; }).catch(e => { gpsState = 'error'; bgError = String(e); });
}
function stopNativeGPS() {
  trackerStop();
  if (bgPlugin && bgWatcher !== null) { try { bgPlugin.removeWatcher({ id: bgWatcher }); } catch (e) {} bgWatcher = null; }
}
if (nativeBG) {
  try {
    const SharePlugin = CAP.registerPlugin('Share');
    const nativeShare = function (d) {
      d = d || {};
      return SharePlugin.share({ title: d.title, text: d.text || undefined, url: d.text ? undefined : d.url, dialogTitle: 'Inviter avec…' })
        .catch(function (e) {
          if (/cancel/i.test(String((e && e.message) || e))) { throw new DOMException('Share canceled', 'AbortError'); }
          throw e;
        });
    };
    Object.defineProperty(navigator, 'share', { value: nativeShare, configurable: true, writable: true });
    Object.defineProperty(navigator, 'canShare', { value: function () { return true; }, configurable: true, writable: true });
  } catch (e) {}
} 
if (nativeBG) { 
  try { 
    const AppPlugin = CAP.registerPlugin('App'); 
    const onInviteUrl = function (u) { 
      const m = /\/join\/#?([A-Za-z0-9]{6})/.exec(String(u || '')); 
      if (!m) return; 
      const code = m[1].toUpperCase(); 
      if (S) { 
        if (S.code !== code) toast('Quittez d’abord la battue en cours pour en rejoindre une autre.'); 
        return; 
      } 
      $('j_code').value = code; 
      show('home'); 
      setTimeout(function () { 
        try { 
          $('j_name').focus(); 
        } catch (e) {} 
      }, 300); 
    }; 
    AppPlugin.addListener('appUrlOpen', function (ev) { 
      onInviteUrl(ev && ev.url); 
    }); 
    AppPlugin.getLaunchUrl().then(function (r) { 
      onInviteUrl(r && r.url); 
    }).catch(function () {}); 
  } catch (e) {} 
} """


def rep(old, new):
    global s
    n = s.count(old)
    if n != 1:
        sys.exit('ERREUR patch_index : motif trouvé %d fois (attendu 1) : %s' % (n, old))
    s = s.replace(old, new)


rep('<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>',
    '<script src="capacitor.js"></script>\n<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>')
rep("const API = 'api.php';", "const API = 'https://lisiera.fr/api.php';")
rep('function startGPS() {', NATIVE + 'function startGPS() {\nif (nativeBG) { startNativeGPS(); return; }')
rep('function stopGPS() {', 'function stopGPS() {\nif (nativeBG) { stopNativeGPS(); return; }')
rep('async function lockScreen() {', 'async function lockScreen() {\nif (nativeBG) return;')
rep('busy = true;', 'busy = true; lastSentAt = Date.now();')
rep('if (!wakeLock && !ended)', 'if (!nativeBG && !wakeLock && !ended)')
rep("let h = kv('Durée de session', fmtAge(dur));",
    "let h = kv('Mode', nativeBG ? (bgPlugin ? 'App Android (arrière-plan)' : 'App Android (plugin GPS absent ' + bgError + ')') : 'Navigateur') + kv('Durée de session', fmtAge(dur));")
rep("const url = location.origin + location.pathname + '#' + S.code;",
    "const url = (nativeBG ? 'https://lisiera.fr/join/' : location.origin + location.pathname) + '#' + S.code;")
rep("ex.href = API + '?a=export&code=' + encodeURIComponent(S.code) + '&token=' + encodeURIComponent(S.token);",
    "ex.href = API + '?a=export&code=' + encodeURIComponent(S.code) + '&token=' + encodeURIComponent(S.token);\n"
    "if (nativeBG) { ex.onclick = ev => { ev.preventDefault(); prompt('Copiez ce lien, puis ouvrez-le dans Chrome :', ex.href); }; }")
rep('function restartTimers() {', 'function restartTimers() {\nif (nativeBG && recOn) trackerStart();')
open(path, 'w', encoding='utf-8').write(s)

print('patch_index OK')
