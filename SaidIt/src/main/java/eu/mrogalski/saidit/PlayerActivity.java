package eu.mrogalski.saidit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Plays a recording and lets the user tag it: edit name / description, drop
 * timestamped bookmark notes while listening, and export the whole trace to
 * markdown in the trace/ folder.
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_FILE = "file";

    private Trace trace;
    private Uri wavUri;
    private MediaPlayer player;
    private boolean prepared = false;
    private final Handler posHandler = new Handler();

    private EditText nameEdit;
    private EditText descEdit;
    private Button playBtn;
    private TextView posText;
    private ListView bookmarksList;
    private BookmarkAdapter bookmarkAdapter;

    private final Runnable posUpdater = new Runnable() {
        @Override public void run() {
            if (player != null && prepared) {
                int cur = player.getCurrentPosition();
                posText.setText(Storage.formatDuration(cur));
            }
            posHandler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeHelper.themeId(this));
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_player);

        nameEdit = (EditText) findViewById(R.id.player_name);
        descEdit = (EditText) findViewById(R.id.player_desc);
        playBtn = (Button) findViewById(R.id.player_play);
        posText = (TextView) findViewById(R.id.player_pos);
        bookmarksList = (ListView) findViewById(R.id.player_bookmarks);

        String file = getIntent().getStringExtra(EXTRA_FILE);
        if (file == null) { toast(getString(R.string.player_no_file)); finish(); return; }

        trace = Storage.loadTrace(this, file);
        if (trace == null) {
            trace = new Trace();
            trace.file = file;
        }
        nameEdit.setText(trace.name == null ? "" : trace.name);
        descEdit.setText(trace.description == null ? "" : trace.description);

        bookmarkAdapter = new BookmarkAdapter();
        bookmarksList.setAdapter(bookmarkAdapter);
        refreshBookmarks();

        wavUri = Storage.findWavUri(this, file);
        if (wavUri == null) {
            toast(getString(R.string.player_file_missing));
            playBtn.setEnabled(false);
        } else {
            player = new MediaPlayer();
            try {
                player.setDataSource(this, wavUri);
                player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(MediaPlayer mp) {
                        prepared = true;
                        if (trace.durationMs <= 0) {
                            trace.durationMs = mp.getDuration();
                        }
                    }
                });
                player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer mp) {
                        playBtn.setText(R.string.player_play);
                    }
                });
                player.prepareAsync();
            } catch (Exception e) {
                android.util.Log.e("Player", "prepare failed", e);
                toast(getString(R.string.player_file_missing));
                playBtn.setEnabled(false);
            }
        }

        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });

        findViewById(R.id.player_addnote).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptAddNote(); }
        });

        findViewById(R.id.player_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveTrace(); }
        });

        findViewById(R.id.player_export).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exportMd(); }
        });

        posHandler.post(posUpdater);
    }

    private void togglePlay() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
            playBtn.setText(R.string.player_play);
        } else {
            player.start();
            playBtn.setText(R.string.player_pause);
        }
    }

    private void promptAddNote() {
        int pos = (player != null && prepared) ? player.getCurrentPosition() : 0;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint(Storage.formatDuration(pos));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.player_add_note) + " @ " + Storage.formatDuration(pos))
                .setView(input)
                .setPositiveButton(R.string.player_add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String note = input.getText().toString();
                        int p = (player != null && prepared) ? player.getCurrentPosition() : 0;
                        trace.bookmarks.add(new Trace.Bookmark(p, note));
                        Collections.sort(trace.bookmarks, new Comparator<Trace.Bookmark>() {
                            @Override public int compare(Trace.Bookmark a, Trace.Bookmark b) {
                                return Long.compare(a.timeMs, b.timeMs);
                            }
                        });
                        Storage.saveTrace(PlayerActivity.this, trace);
                        refreshBookmarks();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saveTrace() {
        trace.name = nameEdit.getText().toString();
        trace.description = descEdit.getText().toString();
        Storage.saveTrace(this, trace);
        toast(getString(R.string.player_saved));
    }

    private void exportMd() {
        trace.name = nameEdit.getText().toString();
        trace.description = descEdit.getText().toString();
        Storage.saveTrace(this, trace);
        Uri md = Storage.exportMarkdown(this, trace);
        toast(md != null ? getString(R.string.player_exported) : getString(R.string.player_export_failed));
    }

    private void refreshBookmarks() {
        bookmarkAdapter.setData(trace.bookmarks);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        posHandler.removeCallbacks(posUpdater);
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    private class BookmarkAdapter extends ArrayAdapter<Trace.Bookmark> {
        private final List<Trace.Bookmark> items = new ArrayList<>();

        BookmarkAdapter() { super(PlayerActivity.this, 0); }

        void setData(List<Trace.Bookmark> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }

        @Override public Trace.Bookmark getItem(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(PlayerActivity.this)
                        .inflate(android.R.layout.simple_list_item_1, parent, false);
            }
            Trace.Bookmark b = getItem(position);
            if (b != null) {
                ((TextView) convertView).setText("[" + Storage.formatDuration(b.timeMs) + "]  " + b.note);
            }
            return convertView;
        }
    }
}
