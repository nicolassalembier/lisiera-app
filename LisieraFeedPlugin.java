package fr.lisiera.app;

import android.app.NotificationManager;
import android.content.Context;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Réglage « son ou vibration seule » des notifications du fil, et retrait de la notification au retour dans l'application. */
@CapacitorPlugin(name = "LisieraFeed")
public class LisieraFeedPlugin extends Plugin {
    @Override
    protected void handleOnResume() {
        try {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(LisieraFeedNotifier.ID);
        } catch (Exception e) {
            // sans importance
        }
    }

    @PluginMethod
    public void prefs(PluginCall call) {
        Boolean s = call.getBoolean("sound", true);
        LisieraFeedNotifier.sound = s == null || s.booleanValue();
        call.resolve();
    }
}
