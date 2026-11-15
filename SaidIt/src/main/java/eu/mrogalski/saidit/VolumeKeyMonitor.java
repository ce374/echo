package eu.mrogalski.saidit;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Detects 5 volume-up presses within a short window and fires a trigger that
 * toggles recording.
 *
 * Reliability technique: after each detected up-press, the stream's volume is
 * decremented back to its previous value (guarded by {@code selfChange} so the
 * reset itself is not counted). This guarantees that every volume-up press
 * registers as an increase — even when the user is not at maximum volume and
 * even when the screen is locked (where volume keys adjust the ring stream).
 * Without this reset, presses at or near max volume produce no observable
 * change and the shortcut silently fails.
 *
 * Battery: this only runs while the recording service is already in the
 * foreground, and the work per change event is trivial.
 */
public final class VolumeKeyMonitor {

    private static final String TAG = "VolumeKeyMonitor";
    private static final int REQUIRED_PRESSES = 5;
    private static final long WINDOW_MS = 3000;

    public interface Trigger {
        void onVolumeShortcut();
    }

    private final Context context;
    private final Handler handler;
    private final Trigger trigger;
    private final AudioManager audio;

    private int lastMusic;
    private int lastRing;
    private int presses;
    private boolean registered;
    private boolean selfChange;

    private final Runnable resetPresses = new Runnable() {
        @Override
        public void run() {
            if (presses != 0) {
                Log.d(TAG, "press window expired, reset");
                presses = 0;
            }
        }
    };

    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            // Android passes its own selfChange flag here; ignore it and use ours.
            handleVolumeChange();
        }
    };

    public VolumeKeyMonitor(Context context, Trigger trigger) {
        this.context = context.getApplicationContext();
        this.trigger = trigger;
        this.handler = new Handler(context.getMainLooper());
        this.audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void start() {
        if (registered) return;
        lastMusic = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        lastRing = audio.getStreamVolume(AudioManager.STREAM_RING);
        presses = 0;
        selfChange = false;
        try {
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("volume_music"), false, observer);
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("volume_ring"), false, observer);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register volume observer", e);
            return;
        }
        registered = true;
        Log.d(TAG, "started (music=" + lastMusic + ", ring=" + lastRing + ")");
    }

    public void stop() {
        if (!registered) return;
        try {
            context.getContentResolver().unregisterContentObserver(observer);
        } catch (Exception ignored) {}
        handler.removeCallbacks(resetPresses);
        registered = false;
        presses = 0;
        selfChange = false;
    }

    private void handleVolumeChange() {
        if (!registered) return;

        // Ignore the change event triggered by our own setStreamVolume reset.
        if (selfChange) {
            selfChange = false;
            return;
        }

        final int music = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        final int ring = audio.getStreamVolume(AudioManager.STREAM_RING);

        final boolean musicUp = music > lastMusic;
        final boolean ringUp = ring > lastRing;

        if (musicUp) {
            registerPress();
            // Reset music back down so the next up-press registers again.
            selfChange = true;
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(music - 1, 0), 0);
            lastMusic = Math.max(music - 1, 0);
            lastRing = ring;
        } else if (ringUp) {
            registerPress();
            // Reset ring back down so the next up-press registers again.
            selfChange = true;
            audio.setStreamVolume(AudioManager.STREAM_RING, Math.max(ring - 1, 0), 0);
            lastRing = Math.max(ring - 1, 0);
            lastMusic = music;
        } else {
            // A decrease or no net change — restart the window.
            lastMusic = music;
            lastRing = ring;
            presses = 0;
        }
    }

    private void registerPress() {
        presses++;
        Log.d(TAG, "volume-up detected, count=" + presses);
        handler.removeCallbacks(resetPresses);
        handler.postDelayed(resetPresses, WINDOW_MS);
        if (presses >= REQUIRED_PRESSES) {
            presses = 0;
            handler.removeCallbacks(resetPresses);
            Log.d(TAG, "triggering shortcut");
            trigger.onVolumeShortcut();
        }
    }
}
