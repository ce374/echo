package eu.mrogalski.saidit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Handles persistent SAF (Storage Access Framework) output for recordings.
 *
 * On first launch (or via Settings) the user picks a folder with
 * ACTION_OPEN_DOCUMENT_TREE; the returned tree Uri is persisted and reused
 * for every recording. Files are named with the current date and time.
 */
public final class Storage {

    private static final String TAG = "Storage";
    public static final int REQUEST_CODE_OPEN_TREE = 7701;

    private Storage() {}

    public static boolean hasTreeUri(Context context) {
        return getTreeUri(context) != null;
    }

    public static Uri getTreeUri(Context context) {
        final String raw = context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE)
                .getString(SaidIt.SAF_TREE_URI_KEY, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Uri.parse(raw);
        } catch (Exception e) {
            Log.w(TAG, "Bad tree uri: " + raw, e);
            return null;
        }
    }

    public static void setTreeUri(Context context, Uri uri) {
        context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE)
                .edit().putString(SaidIt.SAF_TREE_URI_KEY, uri == null ? null : uri.toString()).commit();
    }

    /** Launches the system folder picker. The caller must handle the result in onActivityResult. */
    public static void openTreePicker(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        activity.startActivityForResult(intent, REQUEST_CODE_OPEN_TREE);
    }

    /** Persists the permission for the returned tree Uri so it survives reboots. */
    public static boolean takeTreeUri(Activity activity, Intent data) {
        if (data == null || data.getData() == null) return false;
        final Uri treeUri = data.getData();
        try {
            activity.getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.e(TAG, "Could not take persistable permission", e);
            return false;
        }
        setTreeUri(activity, treeUri);
        return true;
    }

    /** Builds a datetime-stamped filename like "Echo_20240115_143022.wav". */
    public static String buildFileName(long whenMs) {
        final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(whenMs));
        return "Echo_" + stamp + ".wav";
    }

    /**
     * Creates a new .wav document inside the stored tree and copies the contents of
     * {@code tempFile} into it. Returns the Uri of the created document, or null on failure.
     */
    public static Uri saveToTree(Context context, File tempFile, long whenMs) {
        final Uri treeUri = getTreeUri(context);
        if (treeUri == null) {
            Log.e(TAG, "No SAF tree uri configured; cannot save.");
            return null;
        }
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.canWrite()) {
            Log.e(TAG, "Tree uri not writable: " + treeUri);
            return null;
        }
        final String name = buildFileName(whenMs);
        DocumentFile file = tree.createFile("audio/wav", name);
        if (file == null) {
            Log.e(TAG, "createFile returned null for " + name);
            return null;
        }
        OutputStream out = null;
        FileInputStream in = null;
        try {
            out = context.getContentResolver().openOutputStream(file.getUri());
            in = new FileInputStream(tempFile);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            return file.getUri();
        } catch (IOException e) {
            Log.e(TAG, "Failed copying temp to SAF", e);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }
}
