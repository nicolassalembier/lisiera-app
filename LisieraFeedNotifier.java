package fr.lisiera.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Notifications du fil de battue quand l'application n'est pas affichée (écran éteint, autre application).
 * Appelé par LisieraTrackerService après chaque réponse du serveur à l'envoi de position : quand le numéro
 * de version du fil (feed_rev) change, on demande les nouveaux éléments (feed_peek) et on notifie.
 */
final class LisieraFeedNotifier {
    static volatile boolean sound = true;
    static final int ID = 4741;
    private static final String CH = "lisiera_feed";
    private static final String CH_Q = "lisiera_feed_q";

    private final Service svc;
    private String curCode = "";
    private long lastRev = Long.MIN_VALUE;
    private long lastId = -1;

    LisieraFeedNotifier(Service svc) {
        this.svc = svc;
    }

    /** Appelé depuis le thread réseau du service, avec la réponse d'un envoi de position. */
    void onPing(JSONObject jr, String pingUrl, String code, String token, boolean visible) {
        try {
            if (code == null || token == null || pingUrl == null) return;
            if (!code.equals(curCode)) {
                curCode = code;
                lastRev = Long.MIN_VALUE;
                lastId = -1;
            }
            JSONObject b = jr.optJSONObject("battue");
            if (b == null || b.optInt("feed_on", 0) < 1) return;
            long rev = b.optLong("feed_rev", -1L);
            if (rev < 0) return;
            if (lastId >= 0 && rev == lastRev) return;
            JSONObject r = peek(pingUrl, code, token, lastId);
            if (r == null) return;
            long max = r.optLong("max", 0L);
            JSONArray notes = r.optJSONArray("notes");
            lastRev = rev;
            lastId = max;
            if (visible || notes == null || notes.length() == 0) return;
            show(notes);
        } catch (Throwable t) {
            // jamais d'erreur visible : la position continue d'être envoyée
        }
    }

    private JSONObject peek(String pingUrl, String code, String token, long since) throws Exception {
        String base = pingUrl;
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        if (base.length() == 0) return null;
        JSONObject p = new JSONObject();
        p.put("code", code);
        p.put("token", token);
        p.put("since", since);
        HttpURLConnection c = (HttpURLConnection) new URL(base + "?a=feed_peek").openConnection();
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
            if (c.getResponseCode() != 200) return null;
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            JSONObject r = new JSONObject(bos.toString("UTF-8"));
            return r.has("error") ? null : r;
        } finally {
            c.disconnect();
        }
    }

    private void show(JSONArray notes) throws Exception {
        int top = 0;
        JSONObject best = null;
        for (int i = 0; i < notes.length(); i++) {
            JSONObject n = notes.getJSONObject(i);
            int l = n.optInt("lvl", 1);
            if (l >= top) {
                top = l;
                best = n;
            }
        }
        if (best == null) return;
        String title = best.optString("title", "Lisiera");
        String text = best.optString("text", "");
        int others = notes.length() - 1;
        if (others > 0) text = text + " (+" + others + " autre" + (others > 1 ? "s" : "") + ")";
        notifyUser(top, title, text);
    }

    @SuppressWarnings("deprecation")
    private void notifyUser(int lvl, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) svc.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean snd = sound;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel a = new NotificationChannel(CH, "Lisiera – fil de battue (son)", NotificationManager.IMPORTANCE_HIGH);
                a.enableVibration(false);
                nm.createNotificationChannel(a);
                NotificationChannel q = new NotificationChannel(CH_Q, "Lisiera – fil de battue (silencieux)", NotificationManager.IMPORTANCE_HIGH);
                q.enableVibration(false);
                q.setSound(null, null);
                nm.createNotificationChannel(q);
            }
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(svc, snd ? CH : CH_Q) : new Notification.Builder(svc);
            nb.setContentTitle(title);
            nb.setContentText(text);
            nb.setStyle(new Notification.BigTextStyle().bigText(text));
            nb.setSmallIcon(android.R.drawable.stat_notify_chat);
            nb.setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) {
                nb.setPriority(Notification.PRIORITY_HIGH);
                if (snd) nb.setDefaults(Notification.DEFAULT_SOUND);
            }
            Intent li = svc.getPackageManager().getLaunchIntentForPackage(svc.getPackageName());
            if (li != null) {
                nb.setContentIntent(PendingIntent.getActivity(svc, 2, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            nm.notify(ID, nb.build());
        } catch (Exception e) {
            // notification refusée : la vibration ci-dessous reste possible
        }
        try {
            Vibrator v = (Vibrator) svc.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            long[] p = lvl >= 2 ? new long[]{0, 400, 150, 400} : new long[]{0, 150};
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(p, -1));
            } else {
                v.vibrate(p, -1);
            }
        } catch (Exception e) {
            // pas de vibreur ou permission absente
        }
    }
}
