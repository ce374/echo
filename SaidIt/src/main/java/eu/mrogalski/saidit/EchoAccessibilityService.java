package eu.mrogalski.saidit;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Reliable volume-key shortcut for starting / stopping a recording.
 *
 * Once the user enables this service in system Accessibility settings, it
 * receives KEYCODE_VOLUME_UP events regardless of which app is in the
 * foreground (and with the screen on, including the lock screen). Five presses
 * within 3 seconds toggles recording by dispatching {@link SaidIt#ACTION_TOGGLE}
 * to {@link SaidItService}.
 *
 * Key events are observed but never consumed (onKeyEvent returns false), so the
 * volume rocker still works normally and there is no UI flicker.
 *
 * Requires Echo's listening service to already be running (the default once the
 * app has been opened) so toggling does not need to start a microphone
 * foreground service from a background context.
 */
public class EchoAccessibilityService extends AccessibilityService {

    private static final String TAG = "EchoA11y";
    private static final int REQUIRED_PRESSES = 5;
    private static final long WINDOW_MS = 3000;

    private int presses = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reset = new Runnable() {
        @Override
        public void run() {
            presses = 0;
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        setServiceInfo(info);
        Log.d(TAG, "connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used; we only care about key events.
    }

    @Override
    public void onInterrupt() {
        // No-op.
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            presses++;
            handler.removeCallbacks(reset);
            handler.postDelayed(reset, WINDOW_MS);
            if (presses >= REQUIRED_PRESSES) {
                presses = 0;
                handler.removeCallbacks(reset);
                Log.d(TAG, "5x volume-up -> toggle");
                try {
                    Intent i = new Intent(this, SaidItService.class).setAction(SaidIt.ACTION_TOGGLE);
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(i);
                    } else {
                        startService(i);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Could not dispatch toggle", e);
                }
            }
        }
        // Never consume: the volume rocker keeps working normally (no flicker).
        return false;
    }
}
