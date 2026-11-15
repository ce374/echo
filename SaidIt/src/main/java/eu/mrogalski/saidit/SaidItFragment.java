package eu.mrogalski.saidit;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import eu.mrogalski.android.TimeFormat;
import eu.mrogalski.android.Views;

public class SaidItFragment extends Fragment {

    private static final String TAG = SaidItFragment.class.getSimpleName();

    private Button listenButton;
    private Button stopButton;

    private boolean isListening = true;
    private boolean isRecording = false;
    private boolean autoStarted = false;

    private View ready_section;
    private Button recordLastFiveMinutesButton;
    private Button recordMaxButton;
    private Button recordLastMinuteButton;
    private Button recordLastThirtyMinuteButton;
    private Button recordLastTwoHrsButton;
    private Button recordLastSixHrsButton;
    private TextView history_size;

    private View rec_section;
    private TextView rec_time;
    private TextView rec_remaining;

    SaidItService echo;
    private final ServiceConnection echoConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            SaidItService.BackgroundRecorderBinder typedBinder = (SaidItService.BackgroundRecorderBinder) binder;
            if (echo != null && echo == typedBinder.getService()) return;
            echo = typedBinder.getService();
            // Opening the app automatically starts a recording (once per launch).
            if (!autoStarted) {
                autoStarted = true;
                if (!echo.isRecording()) echo.startRecording(0);
            }
            getView().postOnAnimation(updater);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            echo = null;
        }
    };

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            final View view = getView();
            if (view == null || echo == null) return;
            echo.getState(serviceStateCallback);
        }
    };

    public int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    @Override
    public void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        final Activity activity = getActivity();
        assert activity != null;
        activity.bindService(new Intent(activity, SaidItService.class), echoConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
        final Activity activity = getActivity();
        assert activity != null;
        activity.unbindService(echoConnection);
        echo = null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_background_recorder, container, false);
        if (rootView == null) return null;

        final Activity activity = getActivity();
        final AssetManager assets = activity.getAssets();
        final Typeface robotoCondensedBold = Typeface.createFromAsset(assets, "RobotoCondensedBold.ttf");
        final Typeface robotoCondensedRegular = Typeface.createFromAsset(assets, "RobotoCondensed-Regular.ttf");
        final float density = activity.getResources().getDisplayMetrics().density;

        Views.search((ViewGroup) rootView, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if (view instanceof Button) {
                    final Button button = (Button) view;
                    button.setTypeface(robotoCondensedBold);
                    final int shadowColor = button.getShadowColor();
                    button.setShadowLayer(0.01f, 0, density * 2, shadowColor);
                } else if (view instanceof TextView) {
                    ((TextView) view).setTypeface(robotoCondensedRegular);
                }
            }
        });

        history_size = (TextView) rootView.findViewById(R.id.history_size);

        listenButton = (Button) rootView.findViewById(R.id.listen_button);
        listenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleListening();
            }
        });
        final int statusBarHeight = getStatusBarHeight();
        listenButton.setPadding(listenButton.getPaddingLeft(),
                listenButton.getPaddingTop() + statusBarHeight,
                listenButton.getPaddingRight(),
                listenButton.getPaddingBottom());
        final ViewGroup.LayoutParams lp = listenButton.getLayoutParams();
        lp.height += statusBarHeight;
        listenButton.setLayoutParams(lp);

        final View.OnClickListener dumpClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (echo == null) return;
                echo.dumpRecording(getPrependedSeconds(v));
            }
        };
        final View.OnLongClickListener recordLong = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (echo == null) return true;
                if (echo.isRecording()) {
                    echo.stopRecording();
                } else {
                    echo.startRecording(getPrependedSeconds(v));
                }
                return true;
            }
        };

        recordLastMinuteButton = (Button) rootView.findViewById(R.id.record_last_minute);
        recordLastFiveMinutesButton = (Button) rootView.findViewById(R.id.record_last_5_minutes);
        recordLastThirtyMinuteButton = (Button) rootView.findViewById(R.id.record_last_30_minutes);
        recordLastTwoHrsButton = (Button) rootView.findViewById(R.id.record_last_2_hrs);
        recordLastSixHrsButton = (Button) rootView.findViewById(R.id.record_last_6_hrs);
        recordMaxButton = (Button) rootView.findViewById(R.id.record_last_max);
        for (Button b : new Button[]{recordLastMinuteButton, recordLastFiveMinutesButton,
                recordLastThirtyMinuteButton, recordLastTwoHrsButton, recordLastSixHrsButton, recordMaxButton}) {
            b.setOnClickListener(dumpClick);
            b.setOnLongClickListener(recordLong);
        }

        ready_section = rootView.findViewById(R.id.ready_section);
        rec_section = rootView.findViewById(R.id.rec_section);
        rec_time = (TextView) rootView.findViewById(R.id.rec_time);
        rec_remaining = (TextView) rootView.findViewById(R.id.rec_remaining);

        stopButton = (Button) rootView.findViewById(R.id.rec_stop_button);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (echo != null) echo.stopRecording();
            }
        });

        rootView.findViewById(R.id.settings_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getActivity(), SettingsActivity.class));
            }
        });

        rootView.findViewById(R.id.library_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getActivity(), LibraryActivity.class));
            }
        });

        serviceStateCallback.state(isListening, isRecording, 0, 0, 0);
        return rootView;
    }

    @SuppressLint("ValidFragment")
    final WorkingDialog dialog = new WorkingDialog();

    private void toggleListening() {
        if (echo == null) return;
        echo.getState(new SaidItService.StateCallback() {
            @Override
            public void state(final boolean listeningEnabled, boolean recording, float memorized,
                              float totalMemory, float recorded) {
                if (listeningEnabled) {
                    echo.disableListening();
                } else {
                    dialog.setDescriptionStringId(R.string.work_preparing_memory);
                    dialog.show(getFragmentManager(), "Preparing memory");
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            echo.enableListening();
                            echo.getState(new SaidItService.StateCallback() {
                                @Override
                                public void state(boolean listeningEnabled, boolean recording,
                                                  float memorized, float totalMemory, float recorded) {
                                    dialog.dismiss();
                                }
                            });
                        }
                    });
                }
            }
        });
    }

    float getPrependedSeconds(View button) {
        switch (button.getId()) {
            case R.id.record_last_minute:
                return 60;
            case R.id.record_last_5_minutes:
                return 60 * 5;
            case R.id.record_last_30_minutes:
                return 60 * 30;
            case R.id.record_last_2_hrs:
                return 60 * 60 * 2;
            case R.id.record_last_6_hrs:
                return 60 * 60 * 6;
            case R.id.record_last_max:
                return 60 * 60 * 24 * 365;
        }
        return 0;
    }

    final TimeFormat.Result timeFormatResult = new TimeFormat.Result();

    private final SaidItService.StateCallback serviceStateCallback = new SaidItService.StateCallback() {
        @Override
        public void state(final boolean listeningEnabled, final boolean recording,
                          final float memorized, final float totalMemory, final float recorded) {
            final Activity activity = getActivity();
            if (activity == null) return;
            final Resources resources = activity.getResources();

            if (recording != isRecording) {
                isRecording = recording;
                rec_section.setVisibility(recording ? View.VISIBLE : View.GONE);
            }
            if (listeningEnabled != isListening) {
                isListening = listeningEnabled;
                if (listeningEnabled) {
                    listenButton.setText(R.string.listening_enabled_disable);
                    listenButton.setBackgroundResource(R.drawable.top_green_button);
                    listenButton.setShadowLayer(0.01f, 0,
                            resources.getDimensionPixelOffset(R.dimen.shadow_offset),
                            resources.getColor(R.color.dark_green));
                } else {
                    listenButton.setText(R.string.listening_disabled_enable);
                    listenButton.setBackgroundResource(R.drawable.top_gray_button);
                    listenButton.setShadowLayer(0.01f, 0,
                            resources.getDimensionPixelOffset(R.dimen.shadow_offset), 0xff666666);
                }
            }

            ready_section.setVisibility((listeningEnabled && !recording) ? View.VISIBLE : View.GONE);

            TimeFormat.naturalLanguage(resources, memorized, timeFormatResult);
            final String memText = timeFormatResult.text;
            if (!history_size.getText().equals(memText)) {
                history_size.setText(memText);
                recordMaxButton.setText(TimeFormat.shortTimer(memorized));
            }

            TimeFormat.naturalLanguage(resources, recorded, timeFormatResult);
            if (!rec_time.getText().equals(timeFormatResult.text)) {
                rec_time.setText(timeFormatResult.text);
            }

            updateRemaining();

            history_size.postOnAnimationDelayed(updater, 100);
        }
    };

    /** Shows the remaining time until auto-stop (user timer or the hard 70-minute limit). */
    private void updateRemaining() {
        if (rec_remaining == null) return;
        final SharedPreferences prefs = getActivity()
                .getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE);
        final long startMs = prefs.getLong(SaidIt.RECORDING_START_KEY, 0);
        if (!isRecording || startMs <= 0) {
            rec_remaining.setText("");
            return;
        }
        // Countdown to the 70-minute segment boundary (then it auto-saves and continues).
        final long limitMs = SaidIt.MAX_RECORDING_MINUTES * 60_000L;
        final long remainingMs = (startMs + limitMs) - System.currentTimeMillis();
        if (remainingMs <= 0) {
            rec_remaining.setText(R.string.saving);
            return;
        }
        rec_remaining.setText(formatHms(remainingMs));
    }

    static String formatHms(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }
}
