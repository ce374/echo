package eu.mrogalski.saidit;

import android.app.Activity;
import android.content.ComponentName;
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
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import eu.mrogalski.StringFormat;
import eu.mrogalski.android.TimeFormat;
import eu.mrogalski.android.Views;

public class SettingsActivity extends Activity {
    static final String TAG = SettingsActivity.class.getSimpleName();
    private final MemoryOnClickListener memoryClickListener = new MemoryOnClickListener();
    private final QualityOnClickListener qualityClickListener = new QualityOnClickListener();
    private final TimerOnClickListener timerClickListener = new TimerOnClickListener();

    final WorkingDialog dialog = new WorkingDialog();

    private int[] timerButtons;
    private int[] timerValues;

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

    SaidItService service;
    final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            service = ((SaidItService.BackgroundRecorderBinder) binder).getService();
            syncUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            service = null;
        }
    };

    final TimeFormat.Result timeFormatResult = new TimeFormat.Result();

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
        updateTimerHighlight();
        updateLowBatterySwitch();
        updateAccessibilityStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user may have just enabled/disabled the accessibility service.
        updateAccessibilityStatus();
    }

    private void updateLowBatterySwitch() {
        Switch sw = (Switch) findViewById(R.id.low_battery_switch);
        if (sw == null) return;
        boolean on = getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE)
                .getBoolean(SaidIt.LOW_BATTERY_AUTOSAVE_KEY, true);
        if (sw.isChecked() != on) sw.setChecked(on);
    }

    static boolean isAccessibilityEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null
                && enabled.contains(EchoAccessibilityService.class.getName());
    }

    private void updateAccessibilityStatus() {
        TextView tv = (TextView) findViewById(R.id.volume_shortcut_status);
        if (tv == null) return;
        tv.setText(isAccessibilityEnabled(this) ? R.string.volume_shortcut_on
                : R.string.volume_shortcut_off);
    }

    void highlightButtons() {
        if (service == null) return;
        final long maxMemory = Runtime.getRuntime().maxMemory();
        int button = (int) (service.getMemorySize() / (maxMemory / 4));
        highlightButton(R.id.memory_low, R.id.memory_medium, R.id.memory_high, button);

        int samplingRate = service.getSamplingRate();
        button = samplingRate >= 44100 ? 3 : samplingRate >= 16000 ? 2 : 1;
        highlightButton(R.id.quality_8kHz, R.id.quality_16kHz, R.id.quality_48kHz, button);
    }

    private void highlightButton(int b1, int b2, int b3, int i) {
        findViewById(b1).setBackgroundResource(1 == i ? R.drawable.green_button : R.drawable.gray_button);
        findViewById(b2).setBackgroundResource(2 == i ? R.drawable.green_button : R.drawable.gray_button);
        findViewById(b3).setBackgroundResource(3 == i ? R.drawable.green_button : R.drawable.gray_button);
    }

    private void updateFolderDisplay() {
        TextView tv = (TextView) findViewById(R.id.storage_path);
        Uri tree = Storage.getTreeUri(this);
        if (tree == null) {
            tv.setText(R.string.folder_not_set);
        } else {
            DocumentFile df = DocumentFile.fromTreeUri(this, tree);
            String name = df != null ? df.getName() : tree.getLastPathSegment();
            tv.setText(name != null ? name : tree.toString());
        }
    }

    private void updateTimerHighlight() {
        SharedPreferences prefs = getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE);
        int current = prefs.getInt(SaidIt.TIMER_MINUTES_KEY, 0);
        for (int i = 0; i < timerValues.length; i++) {
            findViewById(timerButtons[i]).setBackgroundResource(
                    current == timerValues[i] ? R.drawable.green_button : R.drawable.gray_button);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Block screenshots, screen recording and casting of this app.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        final AssetManager assets = getAssets();
        final Resources resources = getResources();
        final float density = resources.getDisplayMetrics().density;

        final Typeface robotoCondensedBold = Typeface.createFromAsset(assets, "RobotoCondensedBold.ttf");
        final Typeface robotoCondensedRegular = Typeface.createFromAsset(assets, "RobotoCondensed-Regular.ttf");

        final ViewGroup root = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_settings, null);
        Views.search(root, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if (view instanceof Button) {
                    ((Button) view).setTypeface(robotoCondensedBold);
                } else if (view instanceof TextView) {
                    final String tag = (String) view.getTag();
                    final TextView textView = (TextView) view;
                    textView.setTypeface("bold".equals(tag) ? robotoCondensedBold : robotoCondensedRegular);
                }
            }
        });

        root.findViewById(R.id.settings_return).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        final LinearLayout settingsLayout = (LinearLayout) root.findViewById(R.id.settings_layout);
        final FrameLayout myFrameLayout = new FrameLayout(this) {
            @Override
            protected boolean fitSystemWindows(Rect insets) {
                settingsLayout.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return true;
            }
        };
        myFrameLayout.addView(root);

        root.findViewById(R.id.memory_low).setOnClickListener(memoryClickListener);
        root.findViewById(R.id.memory_medium).setOnClickListener(memoryClickListener);
        root.findViewById(R.id.memory_high).setOnClickListener(memoryClickListener);

        initSampleRateButton(root, R.id.quality_8kHz, 8000, 11025);
        initSampleRateButton(root, R.id.quality_16kHz, 16000, 22050);
        initSampleRateButton(root, R.id.quality_48kHz, 48000, 44100);

        // Timer option buttons.
        timerButtons = new int[]{
                R.id.timer_off, R.id.timer_5, R.id.timer_10, R.id.timer_15,
                R.id.timer_25, R.id.timer_30, R.id.timer_45, R.id.timer_60
        };
        timerValues = new int[]{0, 5, 10, 15, 25, 30, 45, 60};
        for (int id : timerButtons) {
            root.findViewById(id).setOnClickListener(timerClickListener);
        }

        root.findViewById(R.id.storage_pick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Storage.openTreePicker(SettingsActivity.this);
            }
        });

        // Low-battery auto-save toggle.
        final Switch lowBattery = (Switch) root.findViewById(R.id.low_battery_switch);
        lowBattery.setChecked(getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE)
                .getBoolean(SaidIt.LOW_BATTERY_AUTOSAVE_KEY, true));
        lowBattery.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean isChecked) {
                getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE)
                        .edit().putBoolean(SaidIt.LOW_BATTERY_AUTOSAVE_KEY, isChecked).commit();
            }
        });

        // Volume-key shortcut: open system Accessibility settings to enable the service.
        root.findViewById(R.id.volume_shortcut_enable).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        dialog.setDescriptionStringId(R.string.work_preparing_memory);

        setContentView(myFrameLayout);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Storage.REQUEST_CODE_OPEN_TREE) {
            if (resultCode == RESULT_OK) {
                Storage.takeTreeUri(this, data);
            }
            updateFolderDisplay();
        }
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
                @Override
                public void run() {
                    service.setMemorySize(memory);
                    service.getState(new SaidItService.StateCallback() {
                        @Override
                        public void state(boolean listeningEnabled, boolean recording, float memorized,
                                          float totalMemory, float recorded) {
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
                @Override
                public void run() {
                    service.setSampleRate(sampleRate);
                    service.getState(new SaidItService.StateCallback() {
                        @Override
                        public void state(boolean listeningEnabled, boolean recording, float memorized,
                                          float totalMemory, float recorded) {
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

    private class TimerOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            int idx = -1;
            for (int i = 0; i < timerButtons.length; i++) {
                if (timerButtons[i] == v.getId()) { idx = i; break; }
            }
            if (idx < 0) return;
            getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE)
                    .edit().putInt(SaidIt.TIMER_MINUTES_KEY, timerValues[idx]).commit();
            updateTimerHighlight();
        }
    }
}
