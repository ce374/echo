package eu.mrogalski.saidit;

import android.app.Activity;
import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists every recording in the SAF folder, with a fuzzy search bar across
 * name + description + bookmark notes. Scans on a background thread with a
 * progress bar so large folders never freeze or crash the app.
 */
public class LibraryActivity extends ListActivity {

    private TraceAdapter adapter;
    private List<Trace> all = new ArrayList<>();
    private ProgressBar progress;
    private TextView empty;
    private EditText search;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeHelper.themeId(this));
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_library);

        progress = (ProgressBar) findViewById(R.id.scan_progress);
        empty = (TextView) findViewById(R.id.empty);
        search = (EditText) findViewById(R.id.search_box);

        adapter = new TraceAdapter();
        setListAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(s.toString());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rescan();
    }

    private void rescan() {
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        final LibraryActivity self = this;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Trace> result;
                try {
                    result = Storage.listRecordings(self);
                } catch (Throwable t) {
                    android.util.Log.e("Library", "scan failed", t);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.setVisibility(View.GONE);
                            empty.setText(R.string.library_scan_failed);
                            empty.setVisibility(View.VISIBLE);
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        all = result;
                        applyFilter(search.getText().toString());
                        progress.setVisibility(View.GONE);
                    }
                });
            }
        }).start();
    }

    private void applyFilter(String q) {
        List<Trace> filtered = Storage.search(all, q);
        adapter.setData(filtered);
        empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Trace t = adapter.getItem(position);
        if (t == null || t.file == null) return;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_FILE, t.file);
        startActivity(i);
    }

    private class TraceAdapter extends ArrayAdapter<Trace> {
        private final List<Trace> items = new ArrayList<>();

        TraceAdapter() {
            super(LibraryActivity.this, 0);
        }

        void setData(List<Trace> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return items.size(); }

        @Override
        public Trace getItem(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(LibraryActivity.this)
                        .inflate(R.layout.item_library, parent, false);
            }
            Trace t = getItem(position);
            if (t != null) {
                ((TextView) convertView.findViewById(R.id.item_title)).setText(t.title());
                String sub = Storage.formatTime(t.created);
                if (t.durationMs > 0) sub += "  ·  " + Storage.formatDuration(t.durationMs);
                if (t.description != null && !t.description.isEmpty()) {
                    String d = t.description.length() > 40
                            ? t.description.substring(0, 40) + "…" : t.description;
                    sub += "\n" + d;
                }
                ((TextView) convertView.findViewById(R.id.item_subtitle)).setText(sub);
            }
            return convertView;
        }
    }
}
