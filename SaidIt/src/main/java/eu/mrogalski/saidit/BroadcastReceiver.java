package eu.mrogalski.saidit;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Starts the recording service after boot so that a recording in progress
 * (flagged via {@link SaidIt#RECORDING_ACTIVE_KEY}) can be resumed, and so the
 * volume-up shortcut keeps working.
 */
public class BroadcastReceiver extends android.content.BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Intent start = new Intent(context, SaidItService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(start);
            } else {
                context.startService(start);
            }
        }
    }
}
