package fr.lisiera.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "LisieraTracker")
public class LisieraTrackerPlugin extends Plugin {
    private static boolean notifAsked = false;

    @Override
    protected void handleOnResume() {
        LisieraTrackerService.appVisible = true;
    }

    @Override
    protected void handleOnPause() {
        LisieraTrackerService.appVisible = false;
    }

    private void askNotifications() {
        if (notifAsked) return;
        notifAsked = true;
        try {
            if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                    && ContextCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            ActivityCompat.requestPermissions(getActivity(), new String[]{"android.permission.POST_NOTIFICATIONS"}, 7303);
                        } catch (Exception e) {
                            // sans importance
                        }
                    }
                });
            }
        } catch (Exception e) {
            // sans importance : le suivi continue, seules les alertes en notification seraient masquees
        }
    }

    @PluginMethod
    public void alertPrefs(PluginCall call) {
        Boolean s = call.getBoolean("sound", true);
        LisieraTrackerService.alertSound = s == null || s.booleanValue();
        call.resolve();
    }

    @PluginMethod
    public void start(PluginCall call) {
        boolean fine = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            call.reject("NO_PERMISSION");
            return;
        }
        LisieraTrackerService.appVisible = true;
        askNotifications();
        Intent i = new Intent(getContext(), LisieraTrackerService.class);
        i.putExtra("url", call.getString("url", ""));
        i.putExtra("code", call.getString("code", ""));
        i.putExtra("token", call.getString("token", ""));
        Integer iv = call.getInt("intervalSec", 15);
        i.putExtra("interval", iv == null ? 15 : iv.intValue());
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                getContext().startForegroundService(i);
            } else {
                getContext().startService(i);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e));
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            getContext().stopService(new Intent(getContext(), LisieraTrackerService.class));
        } catch (Exception e) {
            // ignore
        }
        call.resolve();
    }
}
