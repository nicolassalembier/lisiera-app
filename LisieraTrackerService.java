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
                String st = new JSONObject(body).getJSONObject("battue").optString("status");
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
