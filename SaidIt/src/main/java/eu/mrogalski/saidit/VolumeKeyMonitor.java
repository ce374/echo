package eu.mrogalski.saidit;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Detects 5 volume-up presses within a short window and fires a trigger.
 *
 * Works while the screen is locked because a foreground service keeps the
 * observer registered: when the screen is off the volume keys adjust the
 * ring stream, and when media is playing they adjust the music stream, so
 * both are watched. Only increases are counted, so the user's volume is
 * never modified by this monitor (minimal disruption, minimal battery).
 */
public final class VolumeKeyMonitor {

    private static final String TAG = "VolumeKeyMonitor";
    private static final int REQUIRED_PRESSES = 5;
    private static final long WINDOW_MS = 2500;

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
    }

    private void handleVolumeChange() {
        if (!registered) return;
        final int music = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        final int ring = audio.getStreamVolume(AudioManager.STREAM_RING);
        final boolean musicUp = music > lastMusic;
        final boolean ringUp = ring > lastRing;
        lastMusic = music;
        lastRing = ring;
        if (!musicUp && !ringUp) {
            // a decrease or no net change; restart the window
            presses = 0;
            return;
        }
        presses++;
        Log.d(TAG, "volume-up detected, count=" + presses);
        handler.removeCallbacks(resetPresses);
        handler.postDelayed(resetPresses, WINDOW_MS);
        if (presses >= REQUIRED_PRESSES) {
            presses = 0;
            handler.removeCallbacks(resetPresses);
            trigger.onVolumeShortcut();
        }
    }
}
