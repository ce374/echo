package eu.mrogalski.saidit;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Launched by the disguised "Settings" launcher icon. Saves the current
 * recording (if any) and exits immediately with no UI — stealth mode.
 */
public class StealthActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = new Intent(this, SaidItService.class).setAction(SaidIt.ACTION_AUTO_STOP);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        finish();
    }
}
