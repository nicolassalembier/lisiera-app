package fr.lisiera.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "LisieraTracker")
public class LisieraTrackerPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        boolean fine = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            call.reject("NO_PERMISSION");
            return;
        }
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
