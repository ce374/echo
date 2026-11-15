package eu.mrogalski.saidit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Hosts the main recording fragment. Handles runtime permission requests and
 * offers a SAF output folder pick on first launch.
 *
 * Permissions are requested exactly once (in onCreate or after the user returns
 * from settings). The SAF picker is offered once automatically and is never
 * blocking — if the user cancels, the app still works and the folder can be set
 * later in Settings.
 */
public class SaidItActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 5465;

    private boolean isFragmentSet = false;
    private boolean folderPrompted = false;
    private boolean permissionRequested = false;
    private AlertDialog permissionDeniedDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_recorder);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasRecordAudioPermission()) {
            showFragment();
            ensureSafFolder();
        } else if (!permissionRequested) {
            permissionRequested = true;
            requestPermissions();
        } else if (permissionDeniedDialog == null || !permissionDeniedDialog.isShowing()) {
            // Returned from settings without granting — re-prompt once.
            showPermissionDeniedDialog();
        }
    }

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.POST_NOTIFICATIONS
        }, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;
        if (hasRecordAudioPermission()) {
            showFragment();
            ensureSafFolder();
        } else {
            showPermissionDeniedDialog();
        }
    }

    private void showFragment() {
        if (isFragmentSet) return;
        isFragmentSet = true;
        getFragmentManager().beginTransaction()
                .replace(R.id.container, new SaidItFragment(), "main-fragment")
                .commit();
    }

    private void ensureSafFolder() {
        if (folderPrompted) return;
        if (Storage.hasTreeUri(this)) return;
        folderPrompted = true;
        Storage.openTreePicker(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Storage.REQUEST_CODE_OPEN_TREE) {
            if (resultCode == RESULT_OK) {
                Storage.takeTreeUri(this, data);
            }
            // If cancelled, the app still works (falls back to internal storage);
            // the user can pick a folder later in Settings.
        }
    }

    private void showPermissionDeniedDialog() {
        permissionDeniedDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.permission_required)
                .setMessage(R.string.permission_required_message)
                .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent();
                        intent.setAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }
}
