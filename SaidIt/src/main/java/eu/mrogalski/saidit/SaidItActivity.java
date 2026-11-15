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

/**
 * Hosts the main recording fragment. Handles runtime permission requests and
 * forces the user to pick a SAF output folder on first launch (or if missing).
 */
public class SaidItActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 5465;
    private boolean isFragmentSet = false;
    private AlertDialog permissionDeniedDialog;
    private AlertDialog folderDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_recorder);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (permissionDeniedDialog != null) permissionDeniedDialog.dismiss();
        if (folderDialog != null) folderDialog.dismiss();
        requestPermissions();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (permissionDeniedDialog != null) permissionDeniedDialog.dismiss();
        if (folderDialog != null) folderDialog.dismiss();
        requestPermissions();
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.POST_NOTIFICATIONS
        };
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;

        boolean allGranted = true;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            showFragment();
            ensureSafFolder();
        } else {
            showPermissionDeniedDialog();
        }
    }

    private void showFragment() {
        if (!isFragmentSet) {
            isFragmentSet = true;
            getFragmentManager().beginTransaction()
                    .replace(R.id.container, new SaidItFragment(), "main-fragment")
                    .commit();
        }
    }

    private void ensureSafFolder() {
        if (Storage.hasTreeUri(this)) return;
        Storage.openTreePicker(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Storage.REQUEST_CODE_OPEN_TREE) {
            boolean ok = resultCode == RESULT_OK && Storage.takeTreeUri(this, data);
            if (!ok) {
                showFolderRequiredDialog();
            }
        }
    }

    private void showFolderRequiredDialog() {
        folderDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.folder_required)
                .setMessage(R.string.folder_required_message)
                .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Storage.openTreePicker(SaidItActivity.this);
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
