package fr.lisiera.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

/** Voix du guidage (TextToSpeech Android) : marche ecran eteint, appelee par le service ou par la page. */
final class GuideVoice {
    private static TextToSpeech tts;
    private static volatile boolean ready = false;
    private static String pending = null;
    private static Context app;
    private static AudioFocusRequest focusReq;
    private static int seq = 0;
    private static final AudioManager.OnAudioFocusChangeListener FOCUS = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
        }
    };

    private GuideVoice() { }

    static boolean isReady() { return ready; }

    static synchronized void speak(Context ctx, String text) {
        if (text == null || text.length() == 0) return;
        app = ctx.getApplicationContext();
        if (tts == null) {
            pending = text;
            try {
                tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                    @Override
                    public void onInit(int status) {
                        onReady(status);
                    }
                });
            } catch (Exception e) {
                tts = null;
            }
            return;
        }
        if (!ready) { pending = text; return; }
        say(text);
    }

    static synchronized void stop() {
        pending = null;
        try {
            if (tts != null) tts.stop();
        } catch (Exception e) {
            // ignore
        }
        abandon();
    }

    private static synchronized void onReady(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) { tts = null; ready = false; return; }
        try {
            tts.setAudioAttributes(attrs());
        } catch (Exception e) {
            // ignore
        }
        try {
            int r = tts.setLanguage(Locale.FRANCE);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault());
        } catch (Exception e) {
            // ignore
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { abandon(); }
            @Override @SuppressWarnings("deprecation") public void onError(String id) { abandon(); }
        });
        ready = true;
        if (pending != null) { String t = pending; pending = null; say(t); }
    }

    private static AudioAttributes attrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    private static void say(String text) {
        focus();
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "g" + (++seq));
        } catch (Exception e) {
            // ignore
        }
    }

    @SuppressWarnings("deprecation")
    private static void focus() {
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq == null) {
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(attrs()).setOnAudioFocusChangeListener(FOCUS).build();
                }
                am.requestAudioFocus(focusReq);
            } else {
                am.requestAudioFocus(FOCUS, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    @SuppressWarnings("deprecation")
    private static void abandon() {
        try {
            if (app == null) return;
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq != null) am.abandonAudioFocusRequest(focusReq);
            } else {
                am.abandonAudioFocus(FOCUS);
            }
        } catch (Exception e) {
            // ignore
        }
    }
}
