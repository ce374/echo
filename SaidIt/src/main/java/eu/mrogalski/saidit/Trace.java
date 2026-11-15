package eu.mrogalski.saidit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata for one recording, persisted as JSON in the trace/ folder.
 * Drives the library, the player bookmarks and the fuzzy search.
 */
public class Trace {

    public static class Bookmark {
        public long timeMs;
        public String note;

        public Bookmark(long timeMs, String note) {
            this.timeMs = timeMs;
            this.note = note == null ? "" : note;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("t", timeMs);
            o.put("n", note);
            return o;
        }

        static Bookmark fromJson(JSONObject o) throws JSONException {
            return new Bookmark(o.optLong("t", 0), o.optString("n", ""));
        }
    }

    /** The WAV file name (e.g. Echo_20240115_143022.wav). */
    public String file;
    /** User-editable display name (empty by default). */
    public String name;
    public long created;
    public long durationMs;
    public int sampleRate;
    public String description;
    public final List<Bookmark> bookmarks = new ArrayList<>();

    public Trace() {
        this.name = "";
        this.description = "";
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("file", file == null ? "" : file);
        o.put("name", name == null ? "" : name);
        o.put("created", created);
        o.put("durationMs", durationMs);
        o.put("sampleRate", sampleRate);
        o.put("description", description == null ? "" : description);
        JSONArray arr = new JSONArray();
        for (Bookmark b : bookmarks) arr.put(b.toJson());
        o.put("bookmarks", arr);
        return o;
    }

    static Trace fromJson(JSONObject o) {
        Trace t = new Trace();
        t.file = o.optString("file", "");
        t.name = o.optString("name", "");
        t.created = o.optLong("created", 0);
        t.durationMs = o.optLong("durationMs", 0);
        t.sampleRate = o.optInt("sampleRate", 0);
        t.description = o.optString("description", "");
        JSONArray arr = o.optJSONArray("bookmarks");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                try {
                    t.bookmarks.add(Bookmark.fromJson(arr.getJSONObject(i)));
                } catch (JSONException ignored) {}
            }
        }
        return t;
    }

    /** Concatenated searchable text (name + description + bookmark notes). */
    String searchableText() {
        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append(name).append(' ');
        if (description != null) sb.append(description).append(' ');
        for (Bookmark b : bookmarks) sb.append(b.note).append(' ');
        return sb.toString().toLowerCase();
    }

    /** Display title: the user name if set, else the file name, else "Untitled". */
    public String title() {
        if (name != null && !name.isEmpty()) return name;
        if (file != null && !file.isEmpty()) return file;
        return "Untitled";
    }
}
