package fr.lisiera.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Pont page web -> voix native et guidage ecran eteint. */
@CapacitorPlugin(name = "LisieraGuide")
public class LisieraGuidePlugin extends Plugin {

    @PluginMethod
    public void speak(PluginCall call) {
        String t = call.getString("text", "");
        if (t != null && t.length() > 0) GuideVoice.speak(getContext(), t);
        call.resolve();
    }

    @PluginMethod
    public void stopSpeak(PluginCall call) {
        GuideVoice.stop();
        call.resolve();
    }

    /** on, sound (all | alerts | off), data (JSON : poste et chemins, facultatif si inchange) */
    @PluginMethod
    public void config(PluginCall call) {
        Boolean on = call.getBoolean("on", false);
        LisieraTrackerService.setGuide(on != null && on, call.getString("sound", null), call.getString("data", null));
        call.resolve();
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        r.put("guide", true);
        r.put("tts", GuideVoice.isReady());
        call.resolve(r);
    }
}
