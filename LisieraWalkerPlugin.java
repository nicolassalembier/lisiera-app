package fr.lisiera.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import org.json.JSONArray;

@CapacitorPlugin(
        name = "LisieraWalker",
        permissions = {
                @Permission(alias = "location", strings = {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                })
        }
)
public class LisieraWalkerPlugin extends Plugin {

    private boolean hasLocation() {
        boolean fine = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    @PluginMethod
    public void walkStart(PluginCall call) {
        if (!hasLocation()) {
            requestPermissionForAlias("location", call, "locationResult");
            return;
        }
        doStart(call);
    }

    @PermissionCallback
    private void locationResult(PluginCall call) {
        if (hasLocation()) {
            doStart(call);
        } else {
            call.reject("NO_PERMISSION");
        }
    }

    private void askNotifications() {
        try {
            if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                    && ContextCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{"android.permission.POST_NOTIFICATIONS"}, 7302);
            }
        } catch (Exception e) {
            // sans importance : le service tourne, seules les notifications seraient masquees
        }
    }

    private void doStart(PluginCall call) {
        askNotifications();
        Intent i = new Intent(getContext(), LisieraWalkerService.class);
        i.putExtra("zonesUrl", call.getString("url", ""));
        Integer am = call.getInt("alertM", 200);
        i.putExtra("alertM", am == null ? 200 : am.intValue());
        Boolean rec = call.getBoolean("record", true);
        i.putExtra("record", rec == null || rec.booleanValue());
        Boolean fresh = call.getBoolean("fresh", true);
        i.putExtra("fresh", fresh == null || fresh.booleanValue());
        Boolean shr = call.getBoolean("share", false);
        i.putExtra("share", shr != null && shr.booleanValue());
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
    public void walkStop(PluginCall call) {
        try {
            getContext().stopService(new Intent(getContext(), LisieraWalkerService.class));
        } catch (Exception e) {
            // deja arrete
        }
        call.resolve();
    }

    @PluginMethod
    public void walkConfig(PluginCall call) {
        Integer am = call.getInt("alertM", null);
        if (am != null) {
            LisieraWalkerService.alertM = Math.max(50, Math.min(2000, am.intValue()));
            LisieraWalkerService.levelsReset = true;
        }
        Boolean sh = call.getBoolean("share", null);
        if (sh != null) LisieraWalkerService.setShare(sh.booleanValue());
        call.resolve();
    }

    @PluginMethod
    public void walkState(PluginCall call) {
        try {
            call.resolve(new JSObject(LisieraWalkerService.snapshot(getContext())));
        } catch (Exception e) {
            call.reject(String.valueOf(e));
        }
    }

    @PluginMethod
    public void walkZones(PluginCall call) {
        try {
            JSObject r = new JSObject();
            r.put("rev", LisieraWalkerService.zonesRevision());
            r.put("zones", new JSArray(LisieraWalkerService.zonesJson()));
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e));
        }
    }

    @PluginMethod
    public void walkTrack(PluginCall call) {
        try {
            JSArray pts = new JSArray();
            JSONArray raw = LisieraWalkerService.readTrack(getContext(), 30000);
            for (int i = 0; i < raw.length(); i++) pts.put(raw.get(i));
            JSObject r = new JSObject();
            r.put("points", pts);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e));
        }
    }

    @PluginMethod
    public void walkClear(PluginCall call) {
        LisieraWalkerService.clearTrack(getContext());
        call.resolve();
    }
}
