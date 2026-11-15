package eu.mrogalski.saidit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persistent SAF storage for recordings + their trace metadata.
 *
 * Layout inside the picked SAF tree:
 *   <root>/
 *     Echo_yyyyMMdd_HHmmss.wav        <- audio
 *     Echo_yyyyMMdd_HHmmss.json (in trace/)
 *     Echo_yyyyMMdd_HHmmss.md   (in trace/, optional export)
 */
public final class Storage {

    private static final String TAG = "Storage";
    public static final int REQUEST_CODE_OPEN_TREE = 7701;
    private static final SimpleDateFormat NAME_FMT = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private Storage() {}

    // ---- Tree Uri ----

    /** Returns the content Uri of a WAV file by name inside the SAF root, or null. */
    public static Uri findWavUri(Context context, String fileName) {
        DocumentFile root = rootTree(context);
        if (root == null) return null;
        DocumentFile f = root.findFile(fileName);
        return f != null ? f.getUri() : null;
    }

    public static boolean hasTreeUri(Context context) {
        return getTreeUri(context) != null;
    }

    public static Uri getTreeUri(Context context) {
        String raw = context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE)
                .getString(SaidIt.SAF_TREE_URI_KEY, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Uri.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setTreeUri(Context context, Uri uri) {
        context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE)
                .edit().putString(SaidIt.SAF_TREE_URI_KEY, uri == null ? null : uri.toString()).commit();
    }

    public static void openTreePicker(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        activity.startActivityForResult(intent, REQUEST_CODE_OPEN_TREE);
    }

    public static boolean takeTreeUri(Activity activity, Intent data) {
        if (data == null || data.getData() == null) return false;
        Uri treeUri = data.getData();
        try {
            activity.getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.e(TAG, "Could not take persistable permission", e);
            return false;
        }
        setTreeUri(activity, treeUri);
        ensureTraceFolder(activity);
        return true;
    }

    private static DocumentFile rootTree(Context context) {
        Uri treeUri = getTreeUri(context);
        if (treeUri == null) return null;
        return DocumentFile.fromTreeUri(context, treeUri);
    }

    /** Returns (creating if needed) the trace/ subfolder inside the SAF root. */
    public static DocumentFile ensureTraceFolder(Context context) {
        DocumentFile root = rootTree(context);
        if (root == null) return null;
        DocumentFile trace = root.findFile(SaidIt.TRACE_FOLDER);
        if (trace == null) trace = root.createDirectory(SaidIt.TRACE_FOLDER);
        return trace;
    }

    // ---- File names ----

    public static String buildFileName(long whenMs) {
        return "Echo_" + NAME_FMT.format(new Date(whenMs)) + ".wav";
    }

    public static String buildRecoveredFileName(long whenMs) {
        return "Echo_recovered_" + NAME_FMT.format(new Date(whenMs)) + ".wav";
    }

    private static String basename(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // ---- Save a recording (temp WAV -> SAF root + trace JSON) ----

    /**
     * Copies the temp WAV to the SAF root and writes a trace JSON for it.
     * Returns the saved WAV Uri, or null on failure.
     */
    public static Uri saveRecording(Context context, File temp, long whenMs,
                                    long durationMs, int sampleRate) {
        DocumentFile root = rootTree(context);
        if (root == null || !root.canWrite()) {
            Log.e(TAG, "No writable SAF tree");
            return null;
        }
        String wavName = buildFileName(whenMs);
        DocumentFile wavDoc = root.createFile("audio/wav", wavName);
        if (wavDoc == null) {
            Log.e(TAG, "createFile failed for " + wavName);
            return null;
        }
        if (!copyFile(context, temp, wavDoc.getUri())) return null;

        // Write the trace JSON.
        Trace t = new Trace();
        t.file = wavName;
        t.name = "";
        t.created = whenMs;
        t.durationMs = durationMs;
        t.sampleRate = sampleRate;
        saveTrace(context, t);
        return wavDoc.getUri();
    }

    /** Writes (or overwrites) the trace JSON for the given trace's file. */
    public static void saveTrace(Context context, Trace t) {
        DocumentFile trace = ensureTraceFolder(context);
        if (trace == null || t.file == null) return;
        String jsonName = basename(t.file) + ".json";
        DocumentFile f = trace.findFile(jsonName);
        if (f == null) f = trace.createFile("application/json", jsonName);
        if (f == null) return;
        OutputStream out = null;
        try {
            out = context.getContentResolver().openOutputStream(f.getUri());
            out.write(t.toJson().toString().getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "saveTrace failed", e);
        } finally {
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }

    public static Trace loadTrace(Context context, String wavFileName) {
        DocumentFile trace = ensureTraceFolder(context);
        if (trace == null) return null;
        DocumentFile f = trace.findFile(basename(wavFileName) + ".json");
        if (f == null) return null;
        try {
            java.io.InputStream in = context.getContentResolver().openInputStream(f.getUri());
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            return Trace.fromJson(new JSONObject(sb.toString()));
        } catch (Exception e) {
            Log.e(TAG, "loadTrace failed", e);
            return null;
        }
    }

    // ---- Listing / scanning ----

    /**
     * Scans the SAF root for .wav files and loads (or creates) a trace for each.
     * Returns a list sorted newest-first. Never throws.
     */
    public static List<Trace> listRecordings(Context context) {
        List<Trace> out = new ArrayList<>();
        DocumentFile root = rootTree(context);
        if (root == null) return out;
        DocumentFile[] files = root.listFiles();
        if (files == null) return out;
        for (DocumentFile f : files) {
            if (f == null || !f.isFile() || !f.getName().toLowerCase().endsWith(".wav")) continue;
            String name = f.getName();
            Trace t = loadTrace(context, name);
            if (t == null) {
                t = new Trace();
                t.file = name;
                t.name = "";
                t.created = parseCreatedFromName(name);
                t.durationMs = 0;
                t.sampleRate = 0;
            } else if (t.file == null) {
                t.file = name;
            }
            out.add(t);
        }
        Collections.sort(out, new Comparator<Trace>() {
            @Override
            public int compare(Trace a, Trace b) {
                return Long.compare(b.created, a.created);
            }
        });
        return out;
    }

    private static long parseCreatedFromName(String name) {
        try {
            // Echo_yyyyMMdd_HHmmss.wav  or  Echo_recovered_yyyyMMdd_HHmmss.wav
            int us = name.indexOf('_');
            String rest = name.substring(us + 1);
            if (rest.startsWith("recovered_")) rest = rest.substring("recovered_".length());
            return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(rest).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- Fuzzy search ----

    /** Multi-token substring search across name + description + bookmark notes. */
    public static List<Trace> search(List<Trace> all, String query) {
        if (query == null || query.trim().isEmpty()) return all;
        String[] tokens = query.trim().toLowerCase().split("\\s+");
        List<Trace> out = new ArrayList<>();
        for (Trace t : all) {
            String text = t.searchableText();
            boolean ok = true;
            for (String tok : tokens) {
                if (!text.contains(tok)) { ok = false; break; }
            }
            if (ok) out.add(t);
        }
        return out;
    }

    // ---- Markdown export ----

    public static Uri exportMarkdown(Context context, Trace t) {
        DocumentFile trace = ensureTraceFolder(context);
        if (trace == null || t.file == null) return null;
        String mdName = basename(t.file) + ".md";
        DocumentFile f = trace.findFile(mdName);
        if (f == null) f = trace.createFile("text/markdown", mdName);
        if (f == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(t.title()).append("\n\n");
        sb.append("- File: ").append(t.file).append("\n");
        sb.append("- Date: ").append(formatTime(t.created)).append("\n");
        sb.append("- Duration: ").append(formatDuration(t.durationMs)).append("\n");
        if (t.sampleRate > 0) sb.append("- Sample rate: ").append(t.sampleRate).append(" Hz\n");
        sb.append("\n## Description\n").append(t.description == null ? "" : t.description).append("\n\n");
        sb.append("## Bookmarks\n");
        for (Trace.Bookmark b : t.bookmarks) {
            sb.append("- [").append(formatDuration(b.timeMs)).append("] ")
              .append(b.note).append("\n");
        }
        OutputStream out = null;
        try {
            out = context.getContentResolver().openOutputStream(f.getUri());
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
            return f.getUri();
        } catch (Exception e) {
            Log.e(TAG, "exportMarkdown failed", e);
            return null;
        } finally {
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }

    // ---- Helpers ----

    static String formatDuration(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    static String formatTime(long ms) {
        if (ms <= 0) return "—";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    private static boolean copyFile(Context context, File src, Uri dst) {
        OutputStream out = null;
        FileInputStream in = null;
        try {
            out = context.getContentResolver().openOutputStream(dst);
            in = new FileInputStream(src);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "copyFile failed", e);
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }

    // ---- Orphan recovery (crashed/killed recording) ----

    public static Uri saveOrphanedTemp(Context context, File temp, long whenMs) {
        if (temp == null || !temp.exists() || temp.length() < 44) return null;
        try {
            simplesound.pcm.PcmAudioHelper.modifyRiffSizeData(temp, (int) (temp.length() - 44));
        } catch (IOException e) {
            Log.w(TAG, "Could not patch orphaned wav header", e);
        }
        return saveRecording(context, temp, whenMs, 0, 0);
    }
}
