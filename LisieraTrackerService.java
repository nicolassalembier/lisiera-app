package fr.lisiera.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;


import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service natif : lit la position et l'envoie au serveur, même quand la page web
 * de l'application est suspendue par Android (écran éteint).
 */
public class LisieraTrackerService extends Service {
    private static final String CHANNEL = "lisiera_tracker";
    private static final int NOTIF_ID = 4711;

    private LocationManager lm;
    private volatile Location last;
    private volatile boolean listening = false;
    private volatile String url = "";
    private volatile String code = "";
    private volatile String token = "";
    private volatile int interval = 15;
    private volatile long lastSentAt = 0;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private ExecutorService io;
    private ScheduledExecutorService sched;
    private PowerManager.WakeLock wake;
    private boolean scheduled = false;
    static volatile boolean appVisible = false;
    static volatile boolean alertSound = true;
    private static final String CH_WK = "lisiera_wk";
    private static final String CH_WK_Q = "lisiera_wk_q";
    private static final int ID_WK = 4731;
    private static final long WK_REARM_MS = 120000L, WK_FORGET_MS = 600000L;
    private static final double WK_TTL_S = 120.0, WK_CHEF_D = 200.0, WK_ME_D = 200.0, WK_ME_D3 = 100.0;
    private static final String[] WK_DIRS = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
    private static final class WkEp { int max = 0; long outAt = 0; }
    private static final class WkIt { String id; int lvl; boolean inside; double dme = -1; String dir = ""; double dz; }
    private final HashMap<String, WkEp> wkEp = new HashMap<String, WkEp>();


    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            Location p = last;
            boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
            boolean pGps = p != null && LocationManager.GPS_PROVIDER.equals(p.getProvider());
            long now = System.currentTimeMillis();
            if (p == null || gps || !pGps || now - p.getTime() > 20000) {
                last = l;
            }
            maybeSend();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        io = Executors.newSingleThreadExecutor();
        sched = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null) {
            String u = in.getStringExtra("url");
            String c = in.getStringExtra("code");
            String t = in.getStringExtra("token");
            if (u != null) url = u;
            if (c != null) code = c;
            if (t != null) token = t;
            interval = Math.max(3, in.getIntExtra("interval", 15));
        }
        try {
            startForegroundNow();
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lisiera:tracker");
                wake.acquire(12L * 60L * 60L * 1000L);
            }
        } catch (Exception e) {
            // sans importance
        }
        startLocation();
        if (!scheduled) {
            scheduled = true;
            sched.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        startLocation();
                        maybeSend();
                    } catch (Throwable t) {
                        // ignore
                    }
                }
            }, 2, 2, TimeUnit.SECONDS);
        }
        return START_NOT_STICKY;
    }

    private void startForegroundNow() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Lisiera – partage de position", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Lisiera – position partagée");
        b.setContentText("Votre position est envoyée à la battue.");
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation);
        b.setOngoing(true);
        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (li != null) {
            b.setContentIntent(PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void startLocation() {
        if (listening) return;
        try {
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f, listener, Looper.getMainLooper());
            listening = true;
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, listener, Looper.getMainLooper());
            } catch (Exception e) {
                // pas de reseau de localisation : on garde le GPS
            }
        } catch (Exception e) {
            listening = false;
        }
    }

    private void maybeSend() {
        long now = System.currentTimeMillis();
        if (now - lastSentAt < interval * 1000L - 500L) return;
        if (!busy.compareAndSet(false, true)) return;
        lastSentAt = now;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    sendOnce();
                } catch (Throwable t) {
                    // ignore : nouvel essai au prochain intervalle
                } finally {
                    busy.set(false);
                }
            }
        });
    }

        /* ---------- alerte « promeneur signale » ecran eteint (memes regles que la page) ---------- */

    private static double wkHav(double la1, double lo1, double la2, double lo2) {
        double R = 6371000.0, rad = Math.PI / 180.0;
        double dLat = (la2 - la1) * rad, dLng = (lo2 - lo1) * rad;
        double x = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(la1 * rad) * Math.cos(la2 * rad) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(x));
    }

    private static String wkDir(double la1, double lo1, double la2, double lo2) {
        double rad = Math.PI / 180.0;
        double y = Math.sin((lo2 - lo1) * rad) * Math.cos(la2 * rad);
        double x = Math.cos(la1 * rad) * Math.sin(la2 * rad) - Math.sin(la1 * rad) * Math.cos(la2 * rad) * Math.cos((lo2 - lo1) * rad);
        double deg = (Math.atan2(y, x) / rad + 360.0) % 360.0;
        return WK_DIRS[(int) (Math.round(deg / 45.0) % 8)];
    }

    private static String wkRound(double m) {
        double v = Math.max(10.0, Math.round(m / 10.0) * 10.0);
        return v < 1000.0 ? "≈ " + Math.round(v) + " m" : String.format(Locale.FRANCE, "≈ %.1f km", v / 1000.0);
    }

    private void handleWalkers(JSONObject j) {
        try {
            JSONObject b = j.optJSONObject("battue"), me = j.optJSONObject("me");
            JSONArray ws = j.optJSONArray("walkers");
            long now = System.currentTimeMillis();
            boolean on = b != null && me != null && "active".equals(b.optString("status")) && b.optBoolean("walker_alerts", true);
            boolean mgr = me != null && me.optBoolean("is_chef", false);
            boolean obs = me != null && "observateur".equals(me.optString("role", "poste"));
            Location l = last;
            long fa = l == null ? Long.MAX_VALUE : now - l.getTime();
            boolean haveMe = !obs && l != null && fa < 120000L && fa > -60000L;
            List<WkIt> fired = new ArrayList<WkIt>();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            if (on && ws != null && (mgr || !obs)) {
                for (int i = 0; i < ws.length(); i++) {
                    JSONObject w = ws.getJSONObject(i);
                    String id = w.optString("id", "");
                    if (id.length() == 0 || w.optDouble("age", 9999.0) > WK_TTL_S) continue;
                    WkIt it = new WkIt();
                    it.id = id;
                    it.dz = w.optDouble("dz", 99999.0);
                    it.inside = it.dz <= 0.0;
                    if (haveMe) {
                        double wl = w.optDouble("lat", 0.0), wo = w.optDouble("lng", 0.0);
                        it.dme = wkHav(l.getLatitude(), l.getLongitude(), wl, wo);
                        it.dir = wkDir(l.getLatitude(), l.getLongitude(), wl, wo);
                    }
                    if (mgr && it.dz <= WK_CHEF_D) it.lvl = 1;
                    if (it.inside) it.lvl = 2;
                    if (it.dme >= 0 && it.dme <= WK_ME_D) it.lvl = Math.max(it.lvl, 2);
                    if (it.dme >= 0 && it.dme <= WK_ME_D3) it.lvl = 3;
                    seen.add(id);
                    WkEp e = wkEp.get(id);
                    if (e == null) { e = new WkEp(); wkEp.put(id, e); }
                    if (it.lvl == 0) { if (e.outAt == 0) e.outAt = now; continue; }
                    if (e.outAt != 0 && now - e.outAt > WK_REARM_MS) e.max = 0;
                    e.outAt = 0;
                    if (it.lvl > e.max) { e.max = it.lvl; fired.add(it); }
                }
            }
            java.util.Iterator<java.util.Map.Entry<String, WkEp>> iter = wkEp.entrySet().iterator();
            while (iter.hasNext()) {
                java.util.Map.Entry<String, WkEp> en = iter.next();
                if (seen.contains(en.getKey())) continue;
                if (en.getValue().outAt == 0) en.getValue().outAt = now;
                else if (now - en.getValue().outAt > WK_FORGET_MS) iter.remove();
            }
            if (fired.isEmpty() || appVisible) return;
            WkIt top = fired.get(0);
            for (WkIt c : fired) {
                double dc = c.dme < 0 ? 1e9 : c.dme, dt = top.dme < 0 ? 1e9 : top.dme;
                if (c.lvl > top.lvl || (c.lvl == top.lvl && dc < dt)) top = c;
            }
            String ident = " Identifiez avant de tirer et appliquez les consignes du chef.";
            String title, text;
            if (top.lvl == 3) {
                title = "Promeneur très proche !";
                text = "Un promeneur est signalé à " + wkRound(top.dme) + " de vous, direction " + top.dir + "." + ident;
            } else if (top.dme >= 0 && top.dme <= WK_ME_D) {
                title = "Promeneur à proximité";
                text = "Un promeneur est signalé à " + wkRound(top.dme) + " de vous, direction " + top.dir + "." + ident;
            } else if (top.inside) {
                title = "Promeneur dans la zone de battue";
                text = "Un promeneur équipé de Lisiera est signalé dans la zone." + ident;
            } else {
                title = "Promeneur près de la zone";
                text = "Un promeneur équipé de Lisiera est signalé à " + wkRound(top.dz) + " de la zone. Relayez au talkie si besoin.";
            }
            if (fired.size() > 1) text += " + " + (fired.size() - 1) + " autre(s) promeneur(s) signalé(s).";
            wkNotify(top.lvl, title, text);
        } catch (Exception e) {
            // reponse inattendue : pas d'alerte, le partage de position continue
        }
    }

    @SuppressWarnings("deprecation")
    private void wkNotify(int lvl, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            boolean snd = lvl >= 2 && alertSound;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel a = new NotificationChannel(CH_WK, "Lisiera – promeneur signalé (son)", NotificationManager.IMPORTANCE_HIGH);
                a.enableVibration(false);
                nm.createNotificationChannel(a);
                NotificationChannel q = new NotificationChannel(CH_WK_Q, "Lisiera – promeneur signalé (silencieux)", NotificationManager.IMPORTANCE_HIGH);
                q.enableVibration(false);
                q.setSound(null, null);
                nm.createNotificationChannel(q);
            }
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, snd ? CH_WK : CH_WK_Q) : new Notification.Builder(this);
            nb.setContentTitle(title);
            nb.setContentText(text);
            nb.setStyle(new Notification.BigTextStyle().bigText(text));
            nb.setSmallIcon(android.R.drawable.ic_dialog_alert);
            nb.setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) {
                nb.setPriority(Notification.PRIORITY_HIGH);
                if (snd) nb.setDefaults(Notification.DEFAULT_SOUND);
            }
            Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (li != null) {
                nb.setContentIntent(PendingIntent.getActivity(this, 1, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            nm.notify(ID_WK, nb.build());
        } catch (Exception e) {
            // notification refusee : la vibration ci-dessous reste possible
        }
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            long[] p = lvl >= 3 ? new long[]{0, 700, 200, 700, 200, 700} : (lvl == 2 ? new long[]{0, 500, 200, 500} : new long[]{0, 300});
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(p, -1));
            } else {
                v.vibrate(p, -1);
            }
        } catch (Exception e) {
            // pas de vibreur ou permission absente
        }
    }

    private void sendOnce() throws Exception {
        Location l = last;
        if (l == null || url.length() == 0) return;
        JSONObject p = new JSONObject();
        p.put("code", code);
        p.put("token", token);
        p.put("lat", l.getLatitude());
        p.put("lng", l.getLongitude());
        p.put("acc", l.hasAccuracy() ? (double) l.getAccuracy() : JSONObject.NULL);
        p.put("speed", l.hasSpeed() ? (double) l.getSpeed() : JSONObject.NULL);
        p.put("heading", l.hasBearing() ? (double) l.getBearing() : JSONObject.NULL);
        p.put("fix_age_ms", Math.max(0L, System.currentTimeMillis() - l.getTime()));
        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi != null) {
            int lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (lv >= 0 && sc > 0) p.put("battery", Math.round(lv * 100f / sc));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] data = p.toString().getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();
            int status = c.getResponseCode();
            InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                body = bos.toString("UTF-8");
            }
            if (status == 403 || status == 404) {
                stopSelf();
                return;
            }
            try {
                JSONObject jr = new JSONObject(body);
                String st = jr.getJSONObject("battue").optString("status");
                handleWalkers(jr);
                if ("ended".equals(st)) stopSelf();
            } catch (Exception e) {
                // reponse inattendue : on continue
            }
        } finally {
            c.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (lm != null) lm.removeUpdates(listener);
        } catch (Exception e) {
            // ignore
        }
        listening = false;
        try {
            if (sched != null) sched.shutdownNow();
            if (io != null) io.shutdownNow();
        } catch (Exception e) {
            // ignore
        }
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
