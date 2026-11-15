package eu.mrogalski.saidit;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Handles two things:
 *  - BOOT_COMPLETED (and equivalents): start the recording service so a
 *    recording in progress (flagged via {@link SaidIt#RECORDING_ACTIVE_KEY})
 *    can be resumed and the volume shortcut keeps working.
 *  - BATTERY_LOW: if the user enabled low-battery auto-save, stop and save the
 *    current recording before the phone dies.
 */
public class BroadcastReceiver extends android.content.BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Intent start = new Intent(context, SaidItService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(start);
            } else {
                context.startService(start);
            }
            return;
        }

        if (Intent.ACTION_BATTERY_LOW.equals(action)) {
            boolean enabled = context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE)
                    .getBoolean(SaidIt.LOW_BATTERY_AUTOSAVE_KEY, true);
            if (!enabled) return;
            Intent stop = new Intent(context, SaidItService.class).setAction(SaidIt.ACTION_AUTO_STOP);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(stop);
            } else {
                context.startService(stop);
            }
        }
    }
}
