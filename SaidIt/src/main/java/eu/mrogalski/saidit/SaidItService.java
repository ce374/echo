package eu.mrogalski.saidit;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.File;
import java.io.IOException;

import simplesound.pcm.WavAudioFormat;
import simplesound.pcm.WavFileWriter;

import static eu.mrogalski.saidit.SaidIt.*;

public class SaidItService extends Service {
    static final String TAG = SaidItService.class.getSimpleName();
    private static final int FOREGROUND_NOTIFICATION_ID = 458;
    private static final int SAVED_NOTIFICATION_ID = 43;
    private static final String CHANNEL_ID = "SaidItServiceChannel";
    private static final String CHANNEL_ID_SAVED = "SaidItSavedChannel";
    private static final int SEGMENT_REQUEST_CODE = 9101;

    volatile int SAMPLE_RATE;
    volatile int FILL_RATE;

    File wavFile;
    AudioRecord audioRecord;
    WavFileWriter wavFileWriter;
    final AudioMemory audioMemory = new AudioMemory();

    HandlerThread audioThread;
    Handler audioHandler;
    Handler mainHandler;

    private android.content.BroadcastReceiver batteryReceiver;

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        final SharedPreferences preferences = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        SAMPLE_RATE = preferences.getInt(SAMPLE_RATE_KEY,
                AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC));
        FILL_RATE = 2 * SAMPLE_RATE;

        audioThread = new HandlerThread("audioThread", Thread.MAX_PRIORITY);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());
        mainHandler = new Handler(getMainLooper());

        audioHandler.post(new Runnable() {
            @Override public void run() { recoverCrashedRecording(); }
        });

        if (preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStartListening();
        }
    }

    @Override
    public void onDestroy() {
        stopRecording();
        innerStopListening();
        cancelSegmentAlarm();
        unregisterBatteryReceiver();
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new BackgroundRecorderBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }

    public void enableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, true).commit();
        innerStartListening();
    }

    public void disableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, false).commit();
        innerStopListening();
    }

    int state;
    static final int STATE_READY = 0;
    static final int STATE_LISTENING = 1;
    static final int STATE_RECORDING = 2;

    private void innerStartListening() {
        switch (state) {
            case STATE_READY: break;
            case STATE_LISTENING:
            case STATE_RECORDING: return;
        }
        state = STATE_LISTENING;
        startService(new Intent(this, this.getClass()));

        final long memorySize = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .getLong(AUDIO_MEMORY_SIZE_KEY, Runtime.getRuntime().maxMemory() / 4);

        audioHandler.post(new Runnable() {
            @SuppressLint("MissingPermission")
            @Override public void run() {
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioMemory.CHUNK_SIZE);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release();
                    audioRecord = null;
                    state = STATE_READY;
                    return;
                }
                audioMemory.allocate(memorySize);
                audioRecord.startRecording();
                audioHandler.post(audioReader);
            }
        });
    }

    private void innerStopListening() {
        switch (state) {
            case STATE_READY:
            case STATE_RECORDING: return;
            case STATE_LISTENING: break;
        }
        state = STATE_READY;
        stopForeground(true);
        stopService(new Intent(this, this.getClass()));
        audioHandler.post(new Runnable() {
            @Override public void run() {
                if (audioRecord != null) audioRecord.release();
                audioHandler.removeCallbacks(audioReader);
                audioMemory.allocate(0);
            }
        });
    }

    private File tempDir() {
        File dir = new File(getFilesDir(), "Echo");
        if (!dir.exists()) dir.mkdir();
        return dir;
    }

    private File newTempFile() {
        return new File(tempDir(), "echo_recording.wav");
    }

    private static long availableBytes(File dir) {
        try {
            return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    public boolean isRecording() {
        return state == STATE_RECORDING;
    }

    /** Starts a continuous recording, optionally prepending ring-buffer memory. */
    public void startRecording(final float prependedMemorySeconds) {
        switch (state) {
            case STATE_READY:
                innerStartListening();
                break;
            case STATE_LISTENING:
                break;
            case STATE_RECORDING:
                return;
        }
        state = STATE_RECORDING;

        final long startMs = System.currentTimeMillis();
        final SharedPreferences prefs = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        prefs.edit()
                .putBoolean(RECORDING_ACTIVE_KEY, true)
                .putLong(RECORDING_START_KEY, startMs)
                .commit();

        scheduleSegmentAlarm(startMs);
        beginSegment(prependedMemorySeconds);
    }

    /** Creates the temp WAV + writer for a new segment (assumes mic is running). */
    private void beginSegment(final float prependedMemorySeconds) {
        audioHandler.post(new Runnable() {
            @Override public void run() {
                flushAudioRecord();
                if (availableBytes(tempDir()) < 5L * 1024 * 1024) {
                    showToast(getString(R.string.storage_full));
                    state = STATE_LISTENING;
                    return;
                }
                int prependBytes = (int) (prependedMemorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();
                int skipBytes = Math.max(0, bytesAvailable - prependBytes);

                wavFile = newTempFile();
                try { wavFile.createNewFile(); }
                catch (IOException e) {
                    Log.e(TAG, "Could not create temp file", e);
                    state = STATE_LISTENING;
                    return;
                }
                WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    wavFileWriter = new WavFileWriter(format, wavFile);
                } catch (IOException e) {
                    showToast(getString(R.string.cant_create_file) + wavFile.getAbsolutePath());
                    state = STATE_LISTENING;
                    return;
                }
                if (skipBytes < bytesAvailable) {
                    try {
                        audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                            @Override public int consume(byte[] a, int o, int c) throws IOException {
                                wavFileWriter.write(a, o, c);
                                return 0;
                            }
                        });
                    } catch (IOException e) {
                        Log.e(TAG, "Error writing prepended history", e);
                        stopRecording();
                    }
                }
            }
        });
    }

    /** Saves the last N seconds of the ring buffer to a file (no continuous recording). */
    public void dumpRecording(final float memorySeconds) {
        if (state != STATE_LISTENING) return;
        final long whenMs = System.currentTimeMillis();
        audioHandler.post(new Runnable() {
            @Override public void run() {
                flushAudioRecord();
                int prependBytes = (int) (memorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();
                int skipBytes = Math.max(0, bytesAvailable - prependBytes);
                File temp = newTempFile();
                WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    if (!temp.exists() && !temp.createNewFile()) throw new IOException("createFile failed");
                } catch (IOException e) {
                    showToast(getString(R.string.cant_create_file) + temp.getAbsolutePath());
                    return;
                }
                long written;
                try (WavFileWriter writer = new WavFileWriter(format, temp)) {
                    audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                        @Override public int consume(byte[] a, int o, int c) throws IOException {
                            writer.write(a, o, c);
                            return 0;
                        }
                    });
                    written = writer.getTotalSampleBytesWritten();
                } catch (IOException e) {
                    showToast(getString(R.string.error_during_writing_history_into) + temp.getAbsolutePath());
                    return;
                }
                long durationMs = (long) (written * getBytesToSeconds() * 1000);
                publishSaved(temp, whenMs, durationMs, SAMPLE_RATE);
            }
        });
    }

    /** Stops the current recording and saves it (WAV + trace JSON). */
    public void stopRecording() {
        switch (state) {
            case STATE_READY:
            case STATE_LISTENING: return;
            case STATE_RECORDING: break;
        }
        state = STATE_LISTENING;
        cancelSegmentAlarm();

        final long whenMs = System.currentTimeMillis();
        final File tempRef = wavFile;
        final WavFileWriter writerRef = wavFileWriter;

        audioHandler.post(new Runnable() {
            @Override public void run() {
                long durationMs = 0;
                if (writerRef != null) {
                    flushAudioRecord();
                    try { writerRef.close(); }
                    catch (IOException e) { Log.e(TAG, "CLOSING ERROR", e); }
                    durationMs = (long) (writerRef.getTotalSampleBytesWritten() * getBytesToSeconds() * 1000);
                    if (tempRef != null) publishSaved(tempRef, whenMs, durationMs, SAMPLE_RATE);
                }
                wavFileWriter = null;
                wavFile = null;
            }
        });

        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(RECORDING_ACTIVE_KEY, false).commit();
        if (!getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStopListening();
        }
        stopForeground(true);
    }

    /**
     * 70-minute boundary: save the current segment and immediately begin a new
     * one so recording continues uninterrupted across files.
     */
    public void segmentRestart() {
        if (state != STATE_RECORDING) return;
        final long whenMs = System.currentTimeMillis();
        final File tempRef = wavFile;
        final WavFileWriter writerRef = wavFileWriter;

        audioHandler.post(new Runnable() {
            @Override public void run() {
                long durationMs = 0;
                if (writerRef != null) {
                    flushAudioRecord();
                    try { writerRef.close(); }
                    catch (IOException e) { Log.e(TAG, "segment close error", e); }
                    durationMs = (long) (writerRef.getTotalSampleBytesWritten() * getBytesToSeconds() * 1000);
                    if (tempRef != null) publishSaved(tempRef, whenMs, durationMs, SAMPLE_RATE);
                }
                wavFileWriter = null;
                wavFile = null;
                // Immediately start the next segment.
                beginSegment(0);
            }
        });

        final long startMs = System.currentTimeMillis();
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putLong(RECORDING_START_KEY, startMs).commit();
        scheduleSegmentAlarm(startMs);
    }

    private void publishSaved(File temp, long whenMs, long durationMs, int sampleRate) {
        if (temp == null || !temp.exists()) return;
        Uri saved = Storage.saveRecording(this, temp, whenMs, durationMs, sampleRate);
        String displayName = Storage.buildFileName(whenMs);
        if (saved != null) {
            notifySaved(saved, displayName);
            if (!temp.delete()) Log.w(TAG, "Could not delete temp " + temp);
        } else {
            showToast(getString(R.string.pick_folder_to_save));
            notifySavedFileProvider(temp);
        }
    }

    private void recoverCrashedRecording() {
        SharedPreferences prefs = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(RECORDING_ACTIVE_KEY, false)) return;
        File temp = newTempFile();
        if (!temp.exists() || temp.length() < 44) return;
        Log.d(TAG, "Recovering crashed recording: " + temp.length() + " bytes");
        long whenMs = System.currentTimeMillis();
        Uri saved = Storage.saveOrphanedTemp(this, temp, whenMs);
        if (saved != null) {
            showToast(getString(R.string.recovered_recording_saved));
            notifySaved(saved, Storage.buildRecoveredFileName(whenMs));
        }
        if (!temp.delete()) Log.w(TAG, "Could not delete recovered temp");
    }

    public long getMemorySize() { return audioMemory.getAllocatedMemorySize(); }

    public void setMemorySize(final long memorySize) {
        final SharedPreferences p = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        p.edit().putLong(AUDIO_MEMORY_SIZE_KEY, memorySize).commit();
        if (p.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            audioHandler.post(new Runnable() {
                @Override public void run() { audioMemory.allocate(memorySize); }
            });
        }
    }

    public int getSamplingRate() { return SAMPLE_RATE; }

    public void setSampleRate(int sampleRate) {
        switch (state) {
            case STATE_READY:
            case STATE_RECORDING: return;
            case STATE_LISTENING: break;
        }
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putInt(SAMPLE_RATE_KEY, sampleRate).commit();
        innerStopListening();
        SAMPLE_RATE = sampleRate;
        FILL_RATE = 2 * SAMPLE_RATE;
        innerStartListening();
    }

    public float getBytesToSeconds() { return 1f / FILL_RATE; }

    class BackgroundRecorderBinder extends Binder {
        public SaidItService getService() { return SaidItService.this; }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed: " + t);
        }

        registerBatteryReceiver();

        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_AUTO_STOP.equals(action) || ACTION_SCHEDULED_STOP.equals(action)) {
            if (state == STATE_RECORDING) stopRecording();
            return START_STICKY;
        }
        if (ACTION_SCHEDULED_START.equals(action)) {
            if (state != STATE_RECORDING) startRecording(0);
            return START_STICKY;
        }
        if (ACTION_SEGMENT_RESTART.equals(action)) {
            segmentRestart();
            return START_STICKY;
        }

        // Resume after a system kill / reboot.
        SharedPreferences prefs = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(RECORDING_ACTIVE_KEY, false) && state != STATE_RECORDING) {
            if (state == STATE_READY && prefs.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
                innerStartListening();
            }
            startRecording(0);
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restart = new Intent(getApplicationContext(), this.getClass());
        restart.setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getService(this, 1, restart,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            ((AlarmManager) getSystemService(ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000, pi);
        } catch (Exception e) {
            Log.e(TAG, "onTaskRemoved restart failed", e);
        }
    }

    // ---- 70-minute segment alarm ----

    private void scheduleSegmentAlarm(long startMs) {
        long stopAt = startMs + MAX_RECORDING_MINUTES * 60_000L;
        Intent intent = new Intent(this, SaidItService.class).setAction(ACTION_SEGMENT_RESTART);
        PendingIntent pi = PendingIntent.getService(this, SEGMENT_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            ((AlarmManager) getSystemService(ALARM_SERVICE))
                    .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, pi);
        } catch (SecurityException e) {
            Log.e(TAG, "Could not schedule segment alarm", e);
        }
    }

    private void cancelSegmentAlarm() {
        Intent intent = new Intent(this, SaidItService.class).setAction(ACTION_SEGMENT_RESTART);
        PendingIntent pi = PendingIntent.getService(this, SEGMENT_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            ((AlarmManager) getSystemService(ALARM_SERVICE)).cancel(pi);
        } catch (Exception ignored) {}
    }

    // ---- Battery 2% auto-save ----

    private void registerBatteryReceiver() {
        if (batteryReceiver != null) return;
        batteryReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int pct = scale > 0 ? level * 100 / scale : level;
                if (pct >= 0 && pct <= BATTERY_THRESHOLD
                        && getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                            .getBoolean(LOW_BATTERY_AUTOSAVE_KEY, true)
                        && state == STATE_RECORDING) {
                    Log.d(TAG, "Battery " + pct + "%, auto-saving");
                    stopRecording();
                }
            }
        };
        try {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception e) {
            Log.e(TAG, "battery receiver register failed", e);
        }
    }

    private void unregisterBatteryReceiver() {
        if (batteryReceiver == null) return;
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        batteryReceiver = null;
    }

    // ---- Notifications ----

    private Notification buildNotification() {
        Intent intent = new Intent(this, SaidItActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        ensureChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.recording))
                .setSmallIcon(R.drawable.ic_stat_notify_recording)
                .setTicker(getString(R.string.recording))
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void notifySaved(Uri contentUri, String displayName) {
        ensureChannel(CHANNEL_ID_SAVED, "Saved recordings", NotificationManager.IMPORTANCE_DEFAULT);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(contentUri, "audio/wav");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID_SAVED)
                .setContentTitle(getString(R.string.recording_saved))
                .setContentText(displayName)
                .setSmallIcon(R.drawable.ic_stat_notify_recorded)
                .setTicker(displayName)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(SAVED_NOTIFICATION_ID, n);
        }
    }

    private void notifySavedFileProvider(File file) {
        ensureChannel(CHANNEL_ID_SAVED, "Saved recordings", NotificationManager.IMPORTANCE_DEFAULT);
        Intent view = new Intent(Intent.ACTION_VIEW);
        Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".provider", file);
        view.setDataAndType(uri, "audio/wav");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID_SAVED)
                .setContentTitle(getString(R.string.recording_saved))
                .setContentText(file.getName())
                .setSmallIcon(R.drawable.ic_stat_notify_recorded)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(SAVED_NOTIFICATION_ID, n);
        }
    }

    private void ensureChannel(String id, String name, int importance) {
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void showToast(final String message) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(SaidItService.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- Audio capture loop ----

    private void flushAudioRecord() {
        assert audioHandler.getLooper() == android.os.Looper.myLooper();
        audioHandler.removeCallbacks(audioReader);
        audioReader.run();
    }

    final AudioMemory.Consumer filler = new AudioMemory.Consumer() {
        @Override
        public int consume(byte[] array, int offset, int count) throws IOException {
            final int read = audioRecord.read(array, offset, count, AudioRecord.READ_NON_BLOCKING);
            if (read == AudioRecord.ERROR_BAD_VALUE
                    || read == AudioRecord.ERROR_INVALID_OPERATION
                    || read == AudioRecord.ERROR) {
                return 0;
            }
            if (wavFileWriter != null && read > 0) {
                wavFileWriter.write(array, offset, read);
            }
            if (read == count) {
                audioHandler.post(audioReader);
            } else {
                float bps = audioRecord.getBufferSizeInFrames() / (float) SAMPLE_RATE;
                float delay = Math.max(bps - 1, bps * 0.5f);
                delay = Math.min(delay, bps * 0.9f);
                audioHandler.postDelayed(audioReader, (long) (delay * 1000));
            }
            return read;
        }
    };

    final Runnable audioReader = new Runnable() {
        @Override public void run() {
            try {
                audioMemory.fill(filler);
            } catch (IOException e) {
                Log.e(TAG, "Write error (likely full storage)", e);
                showToast(getString(R.string.storage_error_saved));
                stopRecording();
            }
        }
    };

    public interface StateCallback {
        void state(boolean listeningEnabled, boolean recording, float memorized, float totalMemory, float recorded);
    }

    public void getState(final StateCallback stateCallback) {
        final SharedPreferences p = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        final boolean listeningEnabled = p.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true);
        final boolean recording = (state == STATE_RECORDING);
        final Handler sourceHandler = new Handler();
        audioHandler.post(new Runnable() {
            @Override public void run() {
                flushAudioRecord();
                final AudioMemory.Stats stats = audioMemory.getStats(FILL_RATE);
                int recorded = 0;
                if (wavFileWriter != null) {
                    recorded += wavFileWriter.getTotalSampleBytesWritten();
                    recorded += stats.estimation;
                }
                final float b2s = getBytesToSeconds();
                final int finalRecorded = recorded;
                sourceHandler.post(new Runnable() {
                    @Override public void run() {
                        stateCallback.state(listeningEnabled, recording,
                                (stats.overwriting ? stats.total : stats.filled + stats.estimation) * b2s,
                                stats.total * b2s,
                                finalRecorded * b2s);
                    }
                });
            }
        });
    }
}
