package fr.lisiera.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mode promeneur : service de premier plan (type location) lance depuis l'ecran.
 * - recupere les contours de zones de battue publies (aucune position envoyee),
 * - calcule la distance sur le telephone et alerte (notification + vibration),
 * - enregistre la trace dans un fichier local (jamais envoyee).
 */
public class LisieraWalkerService extends Service {
    static final String ACTION_STOP = "fr.lisiera.app.WALK_STOP";
    static final String TRACK_FILE = "walk_track.csv";
    private static final String CH_RUN = "lisiera_walk";
    private static final String CH_ALERT = "lisiera_walk_alert";
    private static final int ID_RUN = 4721;
    private static final int ID_ALERT = 4722;
    static final long MAX_MS = 8L * 60L * 60L * 1000L;
    private static final long ZONES_EVERY = 60000L;
    private static final long NOTE_EVERY = 30000L;
    private static final double RESET_M = 150.0;
    private static final double NEAR_M = 2000.0;
    private static final double FAR_M = 2500.0;

    private static final Object LOCK = new Object();
    static volatile boolean running = false;
    static volatile int alertM = 200;
    static volatile boolean levelsReset = false;
    private static boolean sHasFix = false;
    private static double sLat = 0, sLng = 0;
    private static float sAcc = 0;
    private static long sTs = 0;
    private static double sNearD = -1, sNearLat = 0, sNearLng = 0;
    private static int sLevel = 0;
    private static boolean sNetOk = true;
    private static long sZonesAt = 0;
    private static String sZrev = "";
    private static String sZonesJson = "[]";
    private static long sStartedAt = 0;
    private static int sTrackN = 0;
    private static int sAlertSeq = 0;
    private static int sAlertLevel = 0;
    private static boolean sGpsOn = true;

    private static final class Zone {
        final String id;
        final double[][] poly;
        Zone(String id, double[][] poly) { this.id = id; this.poly = poly; }
    }

    private LocationManager lm;
    private NotificationManager nm;
    private Handler main;
    private ExecutorService io;
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private volatile String zonesUrl = "";
    private volatile String zonesRev = "";
    private volatile List<Zone> zones = new ArrayList<Zone>();
    private final HashMap<String, Integer> levels = new HashMap<String, Integer>();
    private boolean started = false;
    private boolean listening = false;
    private boolean recording = true;
    private boolean hasRec = false;
    private long startedAt = 0, lastZonesTry = 0, lastNote = 0, lastRecT = 0;
    private double lastRecLat = 0, lastRecLng = 0;
    private Location lastLoc;
    private long gpsEvery = 15000L;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { onFix(l); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { refreshGps(); }
        @Override public void onProviderDisabled(String provider) { refreshGps(); }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        main = new Handler(Looper.getMainLooper());
        io = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && ACTION_STOP.equals(in.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (in != null) {
            String u = in.getStringExtra("zonesUrl");
            if (u != null && u.length() > 0) zonesUrl = u;
            if (in.hasExtra("alertM")) alertM = Math.max(50, Math.min(2000, in.getIntExtra("alertM", 200)));
            recording = in.getBooleanExtra("record", true);
        }
        long now = System.currentTimeMillis();
        try {
            startForegroundNow("Surveillance des battues proches");
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            startedAt = now;
            boolean fresh = in == null || in.getBooleanExtra("fresh", true);
            if (fresh) { try { deleteFile(TRACK_FILE); } catch (Exception e) { } }
            synchronized (LOCK) {
                sStartedAt = now;
                sTrackN = fresh ? 0 : trackCount(this);
                sHasFix = false; sNearD = -1; sLevel = 0; sAlertSeq = 0; sAlertLevel = 0;
                sNetOk = true; sZonesAt = 0; sZrev = ""; sZonesJson = "[]";
            }
            running = true;
            startLocation();
            main.postDelayed(new Runnable() {
                @Override public void run() { autoStop(); }
            }, MAX_MS);
        }
        refreshGps();
        maybeRefreshZones(now);
        return START_NOT_STICKY;
    }

    private void startForegroundNow(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Lisiera – promenade", NotificationManager.IMPORTANCE_LOW));
            NotificationChannel a = new NotificationChannel(CH_ALERT, "Lisiera – alerte battue", NotificationManager.IMPORTANCE_HIGH);
            a.enableVibration(true);
            a.setVibrationPattern(new long[]{0, 400, 150, 400});
            nm.createNotificationChannel(a);
        }
        Notification n = runningNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(ID_RUN, n);
        }
    }

    private PendingIntent openIntent() {
        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (li == null) return null;
        return PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    private Notification runningNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_RUN) : new Notification.Builder(this);
        b.setContentTitle("Lisiera – mode promeneur");
        b.setContentText(text);
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation);
        b.setOngoing(true);
        PendingIntent open = openIntent();
        if (open != null) b.setContentIntent(open);
        Intent stop = new Intent(this, LisieraWalkerService.class);
        stop.setAction(ACTION_STOP);
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter",
                PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private void postNote(String title, String text) {
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_ALERT) : new Notification.Builder(this);
            b.setContentTitle(title);
            b.setContentText(text);
            b.setSmallIcon(android.R.drawable.ic_dialog_alert);
            b.setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(Notification.PRIORITY_HIGH);
                b.setDefaults(Notification.DEFAULT_ALL);
            }
            PendingIntent open = openIntent();
            if (open != null) b.setContentIntent(open);
            nm.notify(ID_ALERT, b.build());
        } catch (Exception e) {
            // notification refusee : l'alerte visuelle dans l'application reste disponible
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(int lvl) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            long[] p = lvl >= 2 ? new long[]{0, 500, 200, 500, 200, 500} : new long[]{0, 350, 150, 350};
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(p, -1));
            } else {
                v.vibrate(p, -1);
            }
        } catch (Exception e) {
            // pas de vibreur ou permission absente
        }
    }

    private static String fmtDist(double d) {
        if (d < 1000.0) return "≈ " + (Math.round(d / 10.0) * 10L) + " m";
        return String.format(Locale.FRANCE, "≈ %.1f km", d / 1000.0);
    }

    private void raiseAlert(int lvl, double d) {
        String title = lvl >= 2 ? "Vous êtes dans une zone de battue en cours" : "Attention, vous approchez d’une zone de battue en cours !";
        String text = lvl >= 2
                ? "Signalez votre présence à voix haute, restez visible et quittez la zone."
                : "Battue déclarée à " + fmtDist(Math.max(10.0, d)) + ". Restez visible, évitez la zone et éloignez-vous.";
        postNote(title, text);
        vibrate(lvl);
        synchronized (LOCK) { sAlertSeq++; sAlertLevel = lvl; }
    }

    @SuppressLint("MissingPermission")
    private synchronized void startLocation() {
        if (listening) return;
        try {
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, gpsEvery, 0f, listener, Looper.getMainLooper());
            listening = true;
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000L, 0f, listener, Looper.getMainLooper());
            } catch (Exception e) {
                // pas de localisation reseau : on garde le GPS
            }
        } catch (Exception e) {
            listening = false;
        }
    }

    private synchronized void restartLocation() {
        try { if (lm != null) lm.removeUpdates(listener); } catch (Exception e) { }
        listening = false;
        startLocation();
    }

    private void refreshGps() {
        boolean on = true;
        try {
            if (lm == null) lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            on = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { }
        synchronized (LOCK) { sGpsOn = on; }
    }

    private void onFix(Location l) {
        if (l == null || !running) return;
        long now = System.currentTimeMillis();
        if (now - startedAt > MAX_MS) { autoStop(); return; }
        Location p = lastLoc;
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        boolean pGps = p != null && LocationManager.GPS_PROVIDER.equals(p.getProvider());
        if (!gps && pGps && now - p.getTime() < 20000L) return;
        lastLoc = l;
        float acc = l.hasAccuracy() ? l.getAccuracy() : 999f;
        synchronized (LOCK) {
            sHasFix = true; sLat = l.getLatitude(); sLng = l.getLongitude(); sAcc = acc;
            sTs = Math.abs(now - l.getTime()) < 3600000L ? l.getTime() : now;
        }
        refreshGps();
        evaluate(l, acc);
        record(l, acc);
        maybeRefreshZones(now);
    }

    private void evaluate(Location l, float acc) {
        if (levelsReset) { levelsReset = false; levels.clear(); }
        List<Zone> zs = zones;
        double slack = Math.min(acc, 50f);
        double lat = l.getLatitude(), lng = l.getLongitude();
        double bestD = -1, bestLat = lat, bestLng = lng;
        int maxLevel = 0, alertLevel = 0;
        double alertD = 0;
        for (Zone z : zs) {
            double[] r = nearest(lat, lng, z.poly);
            double d = Math.max(0.0, r[0] - slack);
            if (bestD < 0 || d < bestD) { bestD = d; bestLat = r[1]; bestLng = r[2]; }
            int lvl = d <= 0.0 ? 2 : (d <= alertM ? 1 : 0);
            Integer last = levels.get(z.id);
            int lv = last == null ? 0 : last.intValue();
            if (lvl > lv) {
                levels.put(z.id, Integer.valueOf(lvl));
                if (lvl > alertLevel) { alertLevel = lvl; alertD = d; }
            } else if (d > alertM + RESET_M) {
                levels.put(z.id, Integer.valueOf(0));
            }
            if (lvl > maxLevel) maxLevel = lvl;
        }
        synchronized (LOCK) { sNearD = bestD; sNearLat = bestLat; sNearLng = bestLng; sLevel = maxLevel; }
        if (alertLevel > 0) raiseAlert(alertLevel, alertD);
        adapt(bestD);
        long now = System.currentTimeMillis();
        if (now - lastNote > NOTE_EVERY) {
            lastNote = now;
            String t;
            if (bestD < 0) t = "Aucune battue déclarée en cours.";
            else if (bestD > 5000.0) t = "Aucune battue déclarée à moins de 5 km.";
            else if (bestD <= 0.0) t = "Vous êtes dans une zone de battue déclarée.";
            else t = "Battue déclarée la plus proche : " + fmtDist(bestD);
            try { nm.notify(ID_RUN, runningNotification(t)); } catch (Exception e) { }
        }
    }

    private void adapt(double nearD) {
        long want = gpsEvery;
        if (nearD < 0 || nearD > FAR_M) want = 15000L;
        else if (nearD < NEAR_M) want = 4000L;
        if (want != gpsEvery) { gpsEvery = want; restartLocation(); }
    }

    private void record(Location l, float acc) {
        if (!recording || acc > 50f) return;
        long now = System.currentTimeMillis();
        long t = (Math.abs(now - l.getTime()) < 3600000L ? l.getTime() : now) / 1000L;
        double lat = l.getLatitude(), lng = l.getLongitude();
        if (hasRec) {
            if (t <= lastRecT) return;
            if (distM(lastRecLat, lastRecLng, lat, lng) < 4.0 && t - lastRecT < 30L) return;
        }
        String line = t + "," + String.format(Locale.US, "%.6f,%.6f,%d", lat, lng, Math.round(acc)) + "\n";
        try {
            FileOutputStream f = openFileOutput(TRACK_FILE, Context.MODE_APPEND);
            f.write(line.getBytes("UTF-8"));
            f.close();
            synchronized (LOCK) { sTrackN++; }
            hasRec = true; lastRecT = t; lastRecLat = lat; lastRecLng = lng;
        } catch (Exception e) {
            // disque plein ou erreur : la promenade continue sans enregistrer ce point
        }
    }

    private void maybeRefreshZones(long now) {
        if (zonesUrl.length() == 0 || now - lastZonesTry < ZONES_EVERY) return;
        if (!fetching.compareAndSet(false, true)) return;
        lastZonesTry = now;
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    fetchZones();
                } catch (Throwable t) {
                    synchronized (LOCK) { sNetOk = false; }
                } finally {
                    fetching.set(false);
                }
            }
        });
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    private void fetchZones() throws Exception {
        JSONObject req = new JSONObject();
        req.put("rev", zonesRev);
        HttpURLConnection c = (HttpURLConnection) new URL(zonesUrl).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(req.toString().getBytes("UTF-8"));
            os.close();
            int st = c.getResponseCode();
            String body = readAll(st >= 400 ? c.getErrorStream() : c.getInputStream());
            if (st != 200) {
                synchronized (LOCK) { sNetOk = false; }
                return;
            }
            JSONObject j = new JSONObject(body);
            if (!j.optBoolean("unchanged", false)) {
                JSONArray za = j.optJSONArray("zones");
                List<Zone> list = new ArrayList<Zone>();
                if (za != null) {
                    for (int i = 0; i < za.length(); i++) {
                        JSONObject zo = za.getJSONObject(i);
                        String id = zo.optString("id", "");
                        JSONArray pa = zo.optJSONArray("poly");
                        if (id.length() == 0 || pa == null || pa.length() < 3) continue;
                        double[][] poly = new double[pa.length()][2];
                        for (int k = 0; k < pa.length(); k++) {
                            JSONArray pt = pa.getJSONArray(k);
                            poly[k][0] = pt.getDouble(0);
                            poly[k][1] = pt.getDouble(1);
                        }
                        list.add(new Zone(id, poly));
                    }
                }
                zones = list;
                zonesRev = j.optString("rev", "");
                synchronized (LOCK) { sZonesJson = za == null ? "[]" : za.toString(); sZrev = zonesRev; }
                main.post(new Runnable() {
                    @Override public void run() {
                        Location l = lastLoc;
                        if (l != null && running) evaluate(l, l.hasAccuracy() ? l.getAccuracy() : 999f);
                    }
                });
            }
            synchronized (LOCK) { sNetOk = true; sZonesAt = System.currentTimeMillis(); }
        } finally {
            c.disconnect();
        }
    }

    private void autoStop() {
        postNote("Lisiera – promenade terminée", "Arrêt automatique après 8 heures. Rouvrez Lisiera pour garder ou effacer la trace.");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (lm != null) lm.removeUpdates(listener); } catch (Exception e) { }
        listening = false;
        try { if (main != null) main.removeCallbacksAndMessages(null); } catch (Exception e) { }
        try { if (io != null) io.shutdownNow(); } catch (Exception e) { }
        try { stopForeground(true); } catch (Exception e) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /* ---------- fonctions utilitaires partagees avec le plugin ---------- */

    static double distM(double la1, double lo1, double la2, double lo2) {
        double kx = Math.cos(Math.toRadians((la1 + la2) / 2.0)) * 111320.0, ky = 110540.0;
        return Math.hypot((lo2 - lo1) * kx, (la2 - la1) * ky);
    }

    static boolean inPoly(double lat, double lng, double[][] poly) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double yi = poly[i][0], xi = poly[i][1], yj = poly[j][0], xj = poly[j][1];
            if (((yi > lat) != (yj > lat)) && (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)) in = !in;
        }
        return in;
    }

    static double[] nearest(double lat, double lng, double[][] poly) {
        if (inPoly(lat, lng, poly)) return new double[]{0.0, lat, lng};
        double kx = Math.cos(Math.toRadians(lat)) * 111320.0, ky = 110540.0;
        double best = Double.MAX_VALUE, bl = lat, bg = lng;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double ax = (poly[j][1] - lng) * kx, ay = (poly[j][0] - lat) * ky;
            double bx = (poly[i][1] - lng) * kx, by = (poly[i][0] - lat) * ky;
            double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
            double t = l2 > 0 ? -(ax * dx + ay * dy) / l2 : 0.0;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = ax + t * dx, py = ay + t * dy, d = Math.hypot(px, py);
            if (d < best) { best = d; bl = lat + py / ky; bg = lng + px / kx; }
        }
        return new double[]{best, bl, bg};
    }

    static int trackCount(Context ctx) {
        File f = ctx.getFileStreamPath(TRACK_FILE);
        if (f == null || !f.exists()) return 0;
        int n = 0;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            while (r.readLine() != null) n++;
            r.close();
        } catch (Exception e) { }
        return n;
    }

    static JSONArray readTrack(Context ctx, int max) {
        JSONArray out = new JSONArray();
        File f = ctx.getFileStreamPath(TRACK_FILE);
        if (f == null || !f.exists()) return out;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null && out.length() < max) {
                String[] p = line.split(",");
                if (p.length < 4) continue;
                try {
                    JSONArray row = new JSONArray();
                    row.put(Long.parseLong(p[0]));
                    row.put(Double.parseDouble(p[1]));
                    row.put(Double.parseDouble(p[2]));
                    row.put(Integer.parseInt(p[3].trim()));
                    out.put(row);
                } catch (NumberFormatException e) { }
            }
            r.close();
        } catch (Exception e) { }
        return out;
    }

    static String zonesJson() { synchronized (LOCK) { return sZonesJson; } }
    static String zonesRevision() { synchronized (LOCK) { return sZrev; } }

    static void clearTrack(Context ctx) {
        try { ctx.deleteFile(TRACK_FILE); } catch (Exception e) { }
        synchronized (LOCK) { sTrackN = 0; }
    }

    static String snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("running", running);
            synchronized (LOCK) {
                if (sHasFix) {
                    JSONObject f = new JSONObject();
                    f.put("lat", sLat);
                    f.put("lng", sLng);
                    f.put("acc", (double) sAcc);
                    f.put("ts", sTs);
                    o.put("fix", f);
                }
                if (sNearD >= 0) {
                    JSONObject n = new JSONObject();
                    n.put("d", sNearD);
                    n.put("lat", sNearLat);
                    n.put("lng", sNearLng);
                    o.put("nearest", n);
                }
                o.put("level", sLevel);
                o.put("netOk", sNetOk);
                o.put("zonesAt", sZonesAt);
                o.put("zrev", sZrev);
                o.put("alertSeq", sAlertSeq);
                o.put("alertLevel", sAlertLevel);
                o.put("startedAt", sStartedAt);
                o.put("gps", sGpsOn);
                o.put("trackN", running ? sTrackN : trackCount(ctx));
            }
        } catch (Exception e) { }
        return o.toString();
    }
}
