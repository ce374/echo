package eu.mrogalski.saidit;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.ComponentName;
import android.app.FragmentManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;

import java.util.Calendar;

import eu.mrogalski.StringFormat;
import eu.mrogalski.android.TimeFormat;
import eu.mrogalski.android.Views;

public class SettingsActivity extends Activity {

    static final String TAG = SettingsActivity.class.getSimpleName();
    private static final int REQ_SCHED_START = 9201;
    private static final int REQ_SCHED_STOP = 9202;

    private final MemoryOnClickListener memoryClickListener = new MemoryOnClickListener();
    private final QualityOnClickListener qualityClickListener = new QualityOnClickListener();
    final WorkingDialog dialog = new WorkingDialog();

    SaidItService service;
    final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            service = ((SaidItService.BackgroundRecorderBinder) binder).getService();
            syncUI();
        }
        @Override
        public void onServiceDisconnected(ComponentName arg0) { service = null; }
    };

    final TimeFormat.Result timeFormatResult = new TimeFormat.Result();

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, SaidItService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unbindService(connection);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeHelper.themeId(this));
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        final AssetManager assets = getAssets();
        final Typeface bold = Typeface.createFromAsset(assets, "RobotoCondensedBold.ttf");
        final Typeface regular = Typeface.createFromAsset(assets, "RobotoCondensed-Regular.ttf");

        final ViewGroup root = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_settings, null);
        Views.search(root, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if (view instanceof Button) {
                    ((Button) view).setTypeface(bold);
                } else if (view instanceof TextView) {
                    final String tag = (String) view.getTag();
                    ((TextView) view).setTypeface("bold".equals(tag) ? bold : regular);
                }
            }
        });

        final LinearLayout settingsLayout = (LinearLayout) root.findViewById(R.id.settings_layout);
        final FrameLayout frame = new FrameLayout(this) {
            @Override
            protected boolean fitSystemWindows(Rect insets) {
                settingsLayout.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return true;
            }
        };
        frame.addView(root);

        root.findViewById(R.id.settings_return).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        root.findViewById(R.id.memory_low).setOnClickListener(memoryClickListener);
        root.findViewById(R.id.memory_medium).setOnClickListener(memoryClickListener);
        root.findViewById(R.id.memory_high).setOnClickListener(memoryClickListener);

        initSampleRateButton(root, R.id.quality_8kHz, 8000, 11025);
        initSampleRateButton(root, R.id.quality_16kHz, 16000, 22050);
        initSampleRateButton(root, R.id.quality_48kHz, 48000, 44100);

        root.findViewById(R.id.storage_pick).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Storage.openTreePicker(SettingsActivity.this); }
        });

        // Theme buttons.
        root.findViewById(R.id.theme_light).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ThemeHelper.set(SettingsActivity.this, ThemeHelper.LIGHT); recreate(); }
        });
        root.findViewById(R.id.theme_dark).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ThemeHelper.set(SettingsActivity.this, ThemeHelper.DARK); recreate(); }
        });
        root.findViewById(R.id.theme_amoled).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ThemeHelper.set(SettingsActivity.this, ThemeHelper.AMOLED); recreate(); }
        });

        // Schedule pickers.
        root.findViewById(R.id.sched_start_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickTime(true); }
        });
        root.findViewById(R.id.sched_stop_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickTime(false); }
        });

        final Switch arm = (Switch) root.findViewById(R.id.sched_arm_switch);
        arm.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked) armSchedule(); else disarmSchedule();
                updateScheduleLabels();
            }
        });

        // Low-battery auto-save.
        final Switch low = (Switch) root.findViewById(R.id.low_battery_switch);
        low.setChecked(getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE)
                .getBoolean(SaidIt.LOW_BATTERY_AUTOSAVE_KEY, true));
        low.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE)
                        .edit().putBoolean(SaidIt.LOW_BATTERY_AUTOSAVE_KEY, checked).commit();
            }
        });

        dialog.setDescriptionStringId(R.string.work_preparing_memory);
        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncUI();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Storage.REQUEST_CODE_OPEN_TREE) {
            if (resultCode == RESULT_OK) Storage.takeTreeUri(this, data);
            syncUI();
        }
    }

    private void syncUI() {
        final long maxMemory = Runtime.getRuntime().maxMemory();
        ((Button) findViewById(R.id.memory_low)).setText(StringFormat.shortFileSize(maxMemory / 4));
        ((Button) findViewById(R.id.memory_medium)).setText(StringFormat.shortFileSize(maxMemory / 2));
        ((Button) findViewById(R.id.memory_high)).setText(StringFormat.shortFileSize((long) (maxMemory * 0.90)));

        if (service != null) {
            TimeFormat.naturalLanguage(getResources(),
                    service.getBytesToSeconds() * service.getMemorySize(), timeFormatResult);
            ((TextView) findViewById(R.id.history_limit)).setText(timeFormatResult.text);
        }
        highlightButtons();
        updateFolderDisplay();
        updateThemeHighlight();
        updateScheduleLabels();
        updateLowBatterySwitch();
    }

    private void updateFolderDisplay() {
        TextView tv = (TextView) findViewById(R.id.storage_path);
        Uri tree = Storage.getTreeUri(this);
        if (tree == null) { tv.setText(R.string.folder_not_set); return; }
        String name = tree.getLastPathSegment();
        tv.setText(name != null ? name : tree.toString());
    }

    private void updateThemeHighlight() {
        int t = ThemeHelper.current(this);
        findViewById(R.id.theme_light).setBackgroundResource(t == ThemeHelper.LIGHT ? R.drawable.green_button : R.drawable.gray_button);
        findViewById(R.id.theme_dark).setBackgroundResource(t == ThemeHelper.DARK ? R.drawable.green_button : R.drawable.gray_button);
        findViewById(R.id.theme_amoled).setBackgroundResource(t == ThemeHelper.AMOLED ? R.drawable.green_button : R.drawable.gray_button);
    }

    private void updateLowBatterySwitch() {
        Switch sw = (Switch) findViewById(R.id.low_battery_switch);
        if (sw == null) return;
        boolean on = getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE)
                .getBoolean(SaidIt.LOW_BATTERY_AUTOSAVE_KEY, true);
        if (sw.isChecked() != on) sw.setChecked(on);
    }

    // ---- Schedule ----

    private void updateScheduleLabels() {
        SharedPreferences p = getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE);
        int sh = p.getInt(SaidIt.SCHED_START_HOUR_KEY, SaidIt.SCHED_DISABLED);
        int sm = p.getInt(SaidIt.SCHED_START_MIN_KEY, 0);
        int eh = p.getInt(SaidIt.SCHED_STOP_HOUR_KEY, SaidIt.SCHED_DISABLED);
        int em = p.getInt(SaidIt.SCHED_STOP_MIN_KEY, 0);
        ((Button) findViewById(R.id.sched_start_btn))
                .setText(sh < 0 ? getString(R.string.schedule_start) : formatHm(sh, sm));
        ((Button) findViewById(R.id.sched_stop_btn))
                .setText(eh < 0 ? getString(R.string.schedule_stop) : formatHm(eh, em));
        Switch arm = (Switch) findViewById(R.id.sched_arm_switch);
        boolean armed = p.getBoolean(SaidIt.SCHED_ARMED_KEY, false);
        if (arm.isChecked() != armed) arm.setChecked(armed);
    }

    private String formatHm(int hour, int min) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, min);
        return (String) DateFormat.format("h:mm aa", c);
    }

    private void pickTime(final boolean start) {
        final SharedPreferences p = getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE);
        int h = p.getInt(start ? SaidIt.SCHED_START_HOUR_KEY : SaidIt.SCHED_STOP_HOUR_KEY, 8);
        int m = p.getInt(start ? SaidIt.SCHED_START_MIN_KEY : SaidIt.SCHED_STOP_MIN_KEY, 0);
        TimePickerDialog d = new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                SharedPreferences.Editor e = p.edit();
                e.putInt(start ? SaidIt.SCHED_START_HOUR_KEY : SaidIt.SCHED_STOP_HOUR_KEY, hourOfDay);
                e.putInt(start ? SaidIt.SCHED_START_MIN_KEY : SaidIt.SCHED_STOP_MIN_KEY, minute);
                e.commit();
                if (p.getBoolean(SaidIt.SCHED_ARMED_KEY, false)) armSchedule(); // re-arm with new times
                updateScheduleLabels();
            }
        }, h, m, false /* 12-hour with AM/PM */);
        d.show();
    }

    private void armSchedule() {
        SharedPreferences p = getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE);
        int sh = p.getInt(SaidIt.SCHED_START_HOUR_KEY, SaidIt.SCHED_DISABLED);
        int eh = p.getInt(SaidIt.SCHED_STOP_HOUR_KEY, SaidIt.SCHED_DISABLED);
        if (sh < 0 || eh < 0) {
            // Need both times; un-arm.
            p.edit().putBoolean(SaidIt.SCHED_ARMED_KEY, false).commit();
            return;
        }
        int sm = p.getInt(SaidIt.SCHED_START_MIN_KEY, 0);
        int em = p.getInt(SaidIt.SCHED_STOP_MIN_KEY, 0);
        long startTrig = nextOccurrence(sh, sm);
        long stopTrig = nextOccurrence(eh, em);
        if (stopTrig <= startTrig) stopTrig += 24L * 3600_000L;
        setAlarm(SaidIt.ACTION_SCHEDULED_START, startTrig, REQ_SCHED_START);
        setAlarm(SaidIt.ACTION_SCHEDULED_STOP, stopTrig, REQ_SCHED_STOP);
        p.edit().putBoolean(SaidIt.SCHED_ARMED_KEY, true).commit();
    }

    private void disarmSchedule() {
        cancelAlarm(SaidIt.ACTION_SCHEDULED_START, REQ_SCHED_START);
        cancelAlarm(SaidIt.ACTION_SCHEDULED_STOP, REQ_SCHED_STOP);
        getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(SaidIt.SCHED_ARMED_KEY, false).commit();
    }

    private static long nextOccurrence(int hour, int min) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, min);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    private void setAlarm(String action, long at, int reqCode) {
        Intent i = new Intent(this, SaidItService.class).setAction(action);
        PendingIntent pi = PendingIntent.getService(this, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            ((AlarmManager) getSystemService(ALARM_SERVICE))
                    .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            android.util.Log.e(TAG, "setAlarm failed", e);
        }
    }

    private void cancelAlarm(String action, int reqCode) {
        Intent i = new Intent(this, SaidItService.class).setAction(action);
        PendingIntent pi = PendingIntent.getService(this, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            ((AlarmManager) getSystemService(ALARM_SERVICE)).cancel(pi);
        } catch (Exception ignored) {}
    }

    // ---- Memory / quality ----

    void highlightButtons() {
        if (service == null) return;
        final long maxMemory = Runtime.getRuntime().maxMemory();
        int button = (int) (service.getMemorySize() / (maxMemory / 4));
        highlightButton(R.id.memory_low, R.id.memory_medium, R.id.memory_high, button);
        int sr = service.getSamplingRate();
        button = sr >= 44100 ? 3 : sr >= 16000 ? 2 : 1;
        highlightButton(R.id.quality_8kHz, R.id.quality_16kHz, R.id.quality_48kHz, button);
    }

    private void highlightButton(int b1, int b2, int b3, int i) {
        findViewById(b1).setBackgroundResource(1 == i ? R.drawable.green_button : R.drawable.gray_button);
        findViewById(b2).setBackgroundResource(2 == i ? R.drawable.green_button : R.drawable.gray_button);
        findViewById(b3).setBackgroundResource(3 == i ? R.drawable.green_button : R.drawable.gray_button);
    }

    private void initSampleRateButton(ViewGroup layout, int buttonId, int primary, int secondary) {
        Button button = (Button) layout.findViewById(buttonId);
        button.setOnClickListener(qualityClickListener);
        if (testSampleRateValid(primary)) {
            button.setText(String.format("%d kHz", primary / 1000));
            button.setTag(primary);
        } else if (testSampleRateValid(secondary)) {
            button.setText(String.format("%d kHz", secondary / 1000));
            button.setTag(secondary);
        } else {
            button.setVisibility(View.GONE);
        }
    }

    private boolean testSampleRateValid(int sampleRate) {
        return AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0;
    }

    private class MemoryOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            final long memory = getMultiplier(v) * Runtime.getRuntime().maxMemory() / 4;
            dialog.show(getFragmentManager(), "Preparing memory");
            new Handler().post(new Runnable() {
                @Override public void run() {
                    service.setMemorySize(memory);
                    service.getState(new SaidItService.StateCallback() {
                        @Override
                        public void state(boolean le, boolean r, float m, float t, float rec) {
                            syncUI();
                            if (dialog.isVisible()) dialog.dismiss();
                        }
                    });
                }
            });
        }
        private int getMultiplier(View button) {
            switch (button.getId()) {
                case R.id.memory_high: return 3;
                case R.id.memory_medium: return 2;
                case R.id.memory_low: return 1;
            }
            return 0;
        }
    }

    private class QualityOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (service == null) return;
            final int sampleRate = getSampleRate(v);
            dialog.show(getFragmentManager(), "Preparing memory");
            new Handler().post(new Runnable() {
                @Override public void run() {
                    service.setSampleRate(sampleRate);
                    service.getState(new SaidItService.StateCallback() {
                        @Override
                        public void state(boolean le, boolean r, float m, float t, float rec) {
                            syncUI();
                            if (dialog.isVisible()) dialog.dismiss();
                        }
                    });
                }
            });
        }
        private int getSampleRate(View button) {
            Object tag = button.getTag();
            return tag instanceof Integer ? (Integer) tag : 8000;
        }
    }
}
