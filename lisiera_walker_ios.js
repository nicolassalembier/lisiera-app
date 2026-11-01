/* ===== iOS : mode promeneur en JavaScript (position en arrière-plan + notifications locales) ===== */
/* Équivalent iOS du service Android LisieraWalkerService : mêmes méthodes (walkStart, walkStop, walkConfig, walkState, walkZones, walkTrack, walkClear). */
const LisieraWalkerIOS = (function () {
  const MAX_MS = 8 * 60 * 60 * 1000, ZONES_EVERY = 60000, RESET_M = 150, NEAR_M = 2000, FAR_M = 2500, ACQ_MAX_MS = 90000;
  const SHARE_SEND_M = 300, SHARE_LEAVE_M = 450, SHARE_EVERY = 8000, SHARE_MAX_AGE = 30000, SHARE_MAX_ACC = 100;
  const TRACK_KEY = 'lisiera_walk_track';
  const CAPX = window.Capacitor;
  const LN = (function () { try { return CAPX.registerPlugin('LocalNotifications'); } catch (e) { return null; } })();
  const bg = () => (typeof bgPlugin !== 'undefined' ? bgPlugin : null);

  let running = false, startedAt = 0, alertM = 200, recording = true, zonesUrl = '', zonesRev = '';
  let zones = [], zonesArr = [], levels = {}, netOk = true, zonesAt = 0, lastZonesTry = 0, fetching = false;
  let hasFix = false, fix = null, nearest = null, level = 0, alertSeq = 0, alertLevel = 0, gpsOn = true;
  let watcherId = null, watchDist = 0, acquiring = true, restarting = false, authErr = false, firstCb = null;
  let timers = [], trackN = 0, hasRec = false, lastRecT = 0, lastRecLat = 0, lastRecLng = 0;
  let shareOn = false, shareNear = false, shareErr = false, shareSent = false, sharing = false, shareGen = 0, lastShare = 0, wid = '';
  let nid = 0;

  /* ---------- géométrie (identique au service Android) ---------- */
  function distM(la1, lo1, la2, lo2) {
    const kx = Math.cos((la1 + la2) / 2 * Math.PI / 180) * 111320, ky = 110540;
    return Math.hypot((lo2 - lo1) * kx, (la2 - la1) * ky);
  }
  function inPoly(lat, lng, poly) {
    let inside = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
      const yi = poly[i][0], xi = poly[i][1], yj = poly[j][0], xj = poly[j][1];
      if (((yi > lat) !== (yj > lat)) && (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)) inside = !inside;
    }
    return inside;
  }
  function nearestOn(lat, lng, poly) {
    if (inPoly(lat, lng, poly)) return [0, lat, lng];
    const kx = Math.cos(lat * Math.PI / 180) * 111320, ky = 110540;
    let best = Infinity, bl = lat, bg2 = lng;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
      const ax = (poly[j][1] - lng) * kx, ay = (poly[j][0] - lat) * ky;
      const bx = (poly[i][1] - lng) * kx, by = (poly[i][0] - lat) * ky;
      const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
      let t = l2 > 0 ? -(ax * dx + ay * dy) / l2 : 0;
      t = Math.max(0, Math.min(1, t));
      const px = ax + t * dx, py = ay + t * dy, d = Math.hypot(px, py);
      if (d < best) { best = d; bl = lat + py / ky; bg2 = lng + px / kx; }
    }
    return [best, bl, bg2];
  }
  function fmtDist(d) {
    if (d < 1000) return '≈ ' + (Math.round(d / 10) * 10) + ' m';
    return '≈ ' + (d / 1000).toFixed(1).replace('.', ',') + ' km';
  }

  /* ---------- notifications locales (seulement quand l'écran n'est pas au premier plan) ---------- */
  function askNotif() { try { if (LN && LN.requestPermissions) LN.requestPermissions().catch(function () {}); } catch (e) {} }
  function notify(title, body, always) {
    try {
      if (!always && document.visibilityState === 'visible') return;
      if (!LN) return;
      nid = (nid % 2000000000) + 1;
      LN.schedule({ notifications: [{ id: nid, title: title, body: body, schedule: { at: new Date(Date.now() + 1000) } }] }).catch(function () {});
    } catch (e) {}
  }
  try { window.__lisNotify = function (title, body) { notify(title, body, false); }; window.__lisAskNotif = askNotif; } catch (e) {}

  /* ---------- trace locale ---------- */
  function trackRaw() { try { return localStorage.getItem(TRACK_KEY) || ''; } catch (e) { return ''; } }
  function trackCount() { const r = trackRaw(); if (!r) return 0; let n = 0; for (let i = 0; i < r.length; i++) if (r.charCodeAt(i) === 10) n++; return n; }
  function clearTrackStore() { try { localStorage.removeItem(TRACK_KEY); } catch (e) {} trackN = 0; hasRec = false; lastRecT = 0; }
  function record(lat, lng, acc, tms) {
    if (!recording || acc > 50) return;
    const t = Math.floor(tms / 1000);
    if (hasRec) {
      if (t <= lastRecT) return;
      if (distM(lastRecLat, lastRecLng, lat, lng) < 4 && t - lastRecT < 30) return;
    }
    const line = t + ',' + lat.toFixed(6) + ',' + lng.toFixed(6) + ',' + Math.round(acc) + '\n';
    try { localStorage.setItem(TRACK_KEY, trackRaw() + line); trackN++; hasRec = true; lastRecT = t; lastRecLat = lat; lastRecLng = lng; } catch (e) {}
  }
  function readTrack(max) {
    const out = [], lines = trackRaw().split('\n');
    for (let i = 0; i < lines.length && out.length < max; i++) {
      const p = lines[i].split(',');
      if (p.length < 4) continue;
      const a = [parseInt(p[0], 10), parseFloat(p[1]), parseFloat(p[2]), parseInt(p[3], 10)];
      if (a.some(function (v) { return !isFinite(v); })) continue;
      out.push(a);
    }
    return out;
  }

  /* ---------- zones de battue ---------- */
  async function fetchZones() {
    const ctrl = new AbortController(), to = setTimeout(function () { ctrl.abort(); }, 8000);
    try {
      const r = await fetch(zonesUrl, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rev: zonesRev }), cache: 'no-store', signal: ctrl.signal });
      if (!r.ok) { netOk = false; return; }
      const j = await r.json();
      if (!j.unchanged) {
        const za = Array.isArray(j.zones) ? j.zones : [], list = [];
        za.forEach(function (z) {
          if (!z || !z.id || !Array.isArray(z.poly) || z.poly.length < 3) return;
          const poly = z.poly.map(function (p) { return [Number(p[0]), Number(p[1])]; });
          if (poly.some(function (p) { return !isFinite(p[0]) || !isFinite(p[1]); })) return;
          list.push({ id: String(z.id), poly: poly });
        });
        zones = list; zonesArr = za; zonesRev = j.rev || '';
        if (fix) evaluate(fix.lat, fix.lng, fix.acc, !!fix.prov);
      }
      netOk = true; zonesAt = Date.now();
    } catch (e) { netOk = false; }
    finally { clearTimeout(to); }
  }
  function maybeRefreshZones(now) {
    if (!zonesUrl || fetching || now - lastZonesTry < ZONES_EVERY) return;
    fetching = true; lastZonesTry = now;
    fetchZones().then(function () { fetching = false; }, function () { fetching = false; });
  }

  /* ---------- évaluation, alertes ---------- */
  function raiseAlert(lvl, d) {
    const title = lvl >= 2 ? 'Vous êtes dans une zone de battue en cours' : 'Attention, vous approchez d’une zone de battue en cours !';
    const body = lvl >= 2
      ? 'Signalez votre présence à voix haute, restez visible et quittez la zone.'
      : 'Battue déclarée à ' + fmtDist(Math.max(10, d)) + '. Restez visible, évitez la zone et éloignez-vous.';
    notify(title, body, false);
    alertSeq++; alertLevel = lvl;
  }
  function evaluate(lat, lng, acc, coarse) {
    const slack = Math.min(acc, 50);
    let bestD = -1, bestLat = lat, bestLng = lng, maxLevel = 0, aLevel = 0, aD = 0, rawMin = Infinity;
    zones.forEach(function (z) {
      const r = nearestOn(lat, lng, z.poly);
      if (r[0] < rawMin) rawMin = r[0];
      const d = Math.max(0, r[0] - slack);
      if (bestD < 0 || d < bestD) { bestD = d; bestLat = r[1]; bestLng = r[2]; }
      const lvl = d <= 0 ? 2 : (d <= alertM ? 1 : 0), lv = levels[z.id] || 0;
      if (coarse) { /* position provisoire : distance affichée, ni alerte ni changement d'état */ }
      else if (lvl > lv) { levels[z.id] = lvl; if (lvl > aLevel) { aLevel = lvl; aD = d; } }
      else if (d > alertM + RESET_M) levels[z.id] = 0;
      if (lvl > maxLevel) maxLevel = lvl;
    });
    nearest = bestD >= 0 ? { d: bestD, lat: bestLat, lng: bestLng } : null;
    level = maxLevel;
    maybeShare(lat, lng, acc, coarse, fix ? fix.ts : Date.now(), rawMin);
    if (aLevel > 0) raiseAlert(aLevel, aD);
    adapt(bestD);
  }

  /* ---------- signalement de présence aux chasseurs (facultatif, anonyme) ---------- */
  function newWid() {
    const a = new Uint8Array(8);
    if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(a);
    else for (let i = 0; i < 8; i++) a[i] = Math.floor(Math.random() * 256);
    return Array.from(a, function (v) { return ('0' + v.toString(16)).slice(-2); }).join('');
  }
  const pingUrl = () => zonesUrl.indexOf('a=walker_zones') >= 0 ? zonesUrl.replace('a=walker_zones', 'a=walker_ping') : '';
  const leaveUrl = () => zonesUrl.indexOf('a=walker_zones') >= 0 ? zonesUrl.replace('a=walker_zones', 'a=walker_leave') : '';
  async function postJson(url, body) {
    const ctrl = new AbortController(), to = setTimeout(function () { ctrl.abort(); }, 8000);
    try {
      const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), cache: 'no-store', signal: ctrl.signal });
      return r.status;
    } finally { clearTimeout(to); }
  }
  function sendLeaveNow(url, id) { postJson(url, { wid: id }).catch(function () { /* la position expire côté serveur au bout de 5 minutes */ }); }
  function maybeShare(lat, lng, acc, coarse, ts, rawMin) {
    if (!shareOn || !running) return;
    const now = Date.now(), t = Math.abs(now - ts) < 3600000 ? ts : now, ageMs = Math.max(0, now - t);
    if (coarse || acc > SHARE_MAX_ACC || ageMs > SHARE_MAX_AGE) return;
    const pu = pingUrl();
    if (!pu) return;
    if (rawMin <= SHARE_SEND_M) {
      if (now - lastShare < SHARE_EVERY) return;
      lastShare = now;
      sendPing(pu, lat, lng, Math.round(acc), ageMs);
    } else if (rawMin > SHARE_LEAVE_M) sendLeave();
  }
  function sendPing(url, lat, lng, acc, ageMs) {
    if (sharing) return;
    sharing = true;
    const gen = shareGen;
    postJson(url, { wid: wid, lat: lat, lng: lng, acc: acc, age_ms: ageMs }).then(function (st) {
      if (st === 200) { if (gen === shareGen) { shareSent = true; shareNear = true; } shareErr = false; }
      else shareErr = true;
    }, function () { shareErr = true; }).then(function () { sharing = false; });
  }
  function sendLeave() {
    shareNear = false;
    const inflight = sharing;
    shareGen++;
    if (!shareSent && !inflight) return;
    shareSent = false;
    const lu = leaveUrl();
    if (lu) sendLeaveNow(lu, wid);
  }

  /* ---------- position (plugin de géolocalisation en arrière-plan) ---------- */
  function onLoc(loc, err) {
    if (err) {
      if (err.code === 'NOT_AUTHORIZED') { authErr = true; gpsOn = false; }
      if (firstCb) { const f = firstCb; firstCb = null; f(); }
      return;
    }
    if (!running || !loc) return;
    if (firstCb) { const f = firstCb; firstCb = null; f(); }
    gpsOn = true;
    const now = Date.now();
    if (now - startedAt > MAX_MS) { autoStop(); return; }
    const acc = typeof loc.accuracy === 'number' ? loc.accuracy : 999;
    const ts = (loc.time && Math.abs(now - loc.time) < 3600000) ? loc.time : now;
    const coarse = acc > 200;
    if (acquiring && acc <= 50) acquiring = false;
    hasFix = true;
    fix = { lat: loc.latitude, lng: loc.longitude, acc: acc, ts: ts, prov: coarse };
    evaluate(fix.lat, fix.lng, acc, coarse);
    record(fix.lat, fix.lng, acc, ts);
    maybeRefreshZones(now);
  }
  function addWatch(dist) {
    const b = bg();
    if (!b) return Promise.reject(new Error('NO_PLUGIN'));
    watchDist = dist;
    return b.addWatcher({
      backgroundTitle: 'Lisiera – mode promeneur',
      backgroundMessage: 'Surveillance des battues proches.',
      requestPermissions: true, stale: false, distanceFilter: dist
    }, onLoc).then(function (id) { watcherId = id; });
  }
  function dropWatch() {
    const b = bg(), id = watcherId;
    watcherId = null;
    if (b && id !== null) { try { return Promise.resolve(b.removeWatcher({ id: id })).catch(function () {}); } catch (e) {} }
    return Promise.resolve();
  }
  function adapt(nearD) {
    if (acquiring || !running || restarting) return;
    let want = watchDist;
    if (nearD < 0 || nearD > FAR_M) want = 30;
    else if (nearD < NEAR_M) want = 5;
    else if (watchDist === 0) want = 30;
    if (want === watchDist) return;
    restarting = true;
    dropWatch().then(function () { return running ? addWatch(want) : null; }).catch(function () {}).then(function () { restarting = false; });
  }
  function endAcquisition() {
    if (!acquiring || !running) return;
    acquiring = false;
    adapt(nearest ? nearest.d : -1);
  }
  function clearTimers() { timers.forEach(function (t) { clearTimeout(t); clearInterval(t); }); timers = []; }
  function stopAll() {
    const was = running;
    running = false;
    clearTimers();
    if (was && shareSent) { shareSent = false; const lu = leaveUrl(); if (lu) sendLeaveNow(lu, wid); }
    shareOn = false;
    return dropWatch();
  }
  function autoStop() {
    notify('Lisiera – promenade terminée', 'Arrêt automatique après 8 heures. Rouvrez Lisiera pour garder ou effacer la trace.', true);
    stopAll();
  }

  /* ---------- interface identique au plugin Android ---------- */
  return {
    async walkStart(o) {
      o = o || {};
      if (!bg()) throw new Error('NO_PLUGIN');
      if (running) { await this.walkConfig({ alertM: o.alertM, share: o.share }); return; }
      askNotif();
      zonesUrl = o.url || ''; alertM = Math.max(50, Math.min(2000, Number(o.alertM) || 200));
      recording = o.record !== false;
      if (o.fresh !== false) clearTrackStore(); else trackN = trackCount();
      zones = []; zonesArr = []; zonesRev = ''; levels = {}; netOk = true; zonesAt = 0; lastZonesTry = 0; fetching = false;
      hasFix = false; fix = null; nearest = null; level = 0; alertSeq = 0; alertLevel = 0; gpsOn = true; authErr = false;
      shareOn = !!o.share; shareNear = false; shareErr = false; shareSent = false; sharing = false; shareGen = 0; lastShare = 0; wid = newWid();
      acquiring = true; restarting = false; startedAt = Date.now(); running = true;
      const first = new Promise(function (res) { firstCb = res; });
      try { await addWatch(0); } catch (e) { running = false; firstCb = null; throw e; }
      await Promise.race([first, new Promise(function (res) { setTimeout(res, 1800); })]);
      if (authErr) { await stopAll(); throw new Error('NO_PERMISSION'); }
      timers.push(setTimeout(endAcquisition, ACQ_MAX_MS));
      timers.push(setTimeout(function () { if (running) autoStop(); }, MAX_MS));
      timers.push(setInterval(function () { if (running) maybeRefreshZones(Date.now()); }, 15000));
      maybeRefreshZones(Date.now());
    },
    async walkStop() { await stopAll(); },
    async walkConfig(o) {
      o = o || {};
      if (o.alertM !== undefined && o.alertM !== null) {
        alertM = Math.max(50, Math.min(2000, Number(o.alertM) || 200)); levels = {};
        if (fix && running) evaluate(fix.lat, fix.lng, fix.acc, !!fix.prov);
      }
      if (o.share !== undefined && o.share !== null) {
        shareOn = !!o.share;
        if (!shareOn) sendLeave();
        else if (fix && running) evaluate(fix.lat, fix.lng, fix.acc, !!fix.prov);
      }
    },
    async walkState() {
      const s = { running: running };
      if (hasFix && fix) s.fix = { lat: fix.lat, lng: fix.lng, acc: fix.acc, ts: fix.ts, prov: !!fix.prov };
      if (nearest) s.nearest = { d: nearest.d, lat: nearest.lat, lng: nearest.lng };
      s.level = level; s.netOk = netOk; s.zonesAt = zonesAt; s.zrev = zonesRev;
      s.alertSeq = alertSeq; s.alertLevel = alertLevel; s.startedAt = startedAt; s.gps = gpsOn;
      s.share = shareOn; s.shareNear = shareNear; s.shareErr = shareErr;
      s.trackN = running ? trackN : trackCount();
      return s;
    },
    async walkZones() { return { rev: zonesRev, zones: zonesArr }; },
    async walkTrack() { return { points: readTrack(30000) }; },
    async walkClear() { clearTrackStore(); }
  };
})();
/* ===== fin iOS promeneur ===== */
