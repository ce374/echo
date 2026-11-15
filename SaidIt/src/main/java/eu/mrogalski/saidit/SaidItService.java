package eu.mrogalski.saidit;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
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
    private static final int AUTO_STOP_REQUEST_CODE = 9101;

    volatile int SAMPLE_RATE;
    volatile int FILL_RATE;

    /** Temp WAV written while recording; copied to SAF on stop. */
    File wavFile;
    AudioRecord audioRecord; // used only in the audio thread
    WavFileWriter wavFileWriter; // used only in the audio thread
    final AudioMemory audioMemory = new AudioMemory(); // used only in the audio thread

    HandlerThread audioThread;
    Handler audioHandler; // used to post messages to audio thread
    Handler mainHandler;

    VolumeKeyMonitor volumeMonitor;

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        SAMPLE_RATE = preferences.getInt(SAMPLE_RATE_KEY, AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC));
        Log.d(TAG, "Sample rate: " + SAMPLE_RATE);
        FILL_RATE = 2 * SAMPLE_RATE;

        audioThread = new HandlerThread("audioThread", Thread.MAX_PRIORITY);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());
        mainHandler = new Handler(getMainLooper());

        volumeMonitor = new VolumeKeyMonitor(this, new VolumeKeyMonitor.Trigger() {
            @Override
            public void onVolumeShortcut() {
                toggleRecording();
            }
        });

        if (preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStartListening();
        }
    }

    @Override
    public void onDestroy() {
        if (volumeMonitor != null) volumeMonitor.stop();
        stopRecording();
        innerStopListening();
        cancelAutoStop();
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
            case STATE_READY:
                break;
            case STATE_LISTENING:
            case STATE_RECORDING:
                return;
        }
        state = STATE_LISTENING;

        Log.d(TAG, "Queueing: START LISTENING");
        startService(new Intent(this, this.getClass()));

        final long memorySize = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .getLong(AUDIO_MEMORY_SIZE_KEY, Runtime.getRuntime().maxMemory() / 4);

        audioHandler.post(new Runnable() {
            @SuppressLint("MissingPermission")
            @Override
            public void run() {
                Log.d(TAG, "Executing: START LISTENING");
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioMemory.CHUNK_SIZE);

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Audio: INITIALIZATION ERROR - releasing resources");
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
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }
        state = STATE_READY;
        Log.d(TAG, "Queueing: STOP LISTENING");

        stopForeground(true);
        stopService(new Intent(this, this.getClass()));

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: STOP LISTENING");
                if (audioRecord != null) audioRecord.release();
                audioHandler.removeCallbacks(audioReader);
                audioMemory.allocate(0);
            }
        });
    }

    /** Folder for the in-progress temp WAV file (internal storage, no permission needed). */
    private File tempDir() {
        File dir = new File(getFilesDir(), "Echo");
        if (!dir.exists()) dir.mkdir();
        return dir;
    }

    private File newTempFile() {
        return new File(tempDir(), "echo_recording.wav");
    }

    /**
     * Starts a continuous recording, optionally prepending the last {@code prependedMemorySeconds}
     * from the ring buffer. Output goes to a temp file; on stop it is copied to the SAF folder.
     */
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

        scheduleAutoStop(startMs, prefs.getInt(TIMER_MINUTES_KEY, 0));

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int prependBytes = (int) (prependedMemorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();
                int skipBytes = Math.max(0, bytesAvailable - prependBytes);

                wavFile = newTempFile();
                try {
                    wavFile.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Could not create temp file", e);
                    state = STATE_LISTENING;
                    return;
                }
                WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    wavFileWriter = new WavFileWriter(format, wavFile);
                } catch (IOException e) {
                    Toast.makeText(SaidItService.this,
                            getString(R.string.cant_create_file) + wavFile.getAbsolutePath(),
                            Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Can't create wav writer", e);
                    state = STATE_LISTENING;
                    return;
                }

                if (skipBytes < bytesAvailable) {
                    try {
                        audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                            @Override
                            public int consume(byte[] array, int offset, int count) throws IOException {
                                wavFileWriter.write(array, offset, count);
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

    /**
     * Saves the last {@code memorySeconds} of the ring buffer to a file (without keeping recording).
     */
    public void dumpRecording(final float memorySeconds) {
        if (state != STATE_LISTENING) {
            Log.w(TAG, "dumpRecording ignored, not listening");
            return;
        }
        final long whenMs = System.currentTimeMillis();
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int prependBytes = (int) (memorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();
                int skipBytes = Math.max(0, bytesAvailable - prependBytes);

                File temp = newTempFile();
                WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    if (!temp.exists() && !temp.createNewFile()) {
                        throw new IOException("createFile failed");
                    }
                } catch (IOException e) {
                    showToast(getString(R.string.cant_create_file) + temp.getAbsolutePath());
                    return;
                }
                try (WavFileWriter writer = new WavFileWriter(format, temp)) {
                    audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                        @Override
                        public int consume(byte[] array, int offset, int count) throws IOException {
                            writer.write(array, offset, count);
                            return 0;
                        }
                    });
                } catch (IOException e) {
                    showToast(getString(R.string.error_during_writing_history_into) + temp.getAbsolutePath());
                    Log.e(TAG, "Error writing dump", e);
                    return;
                }
                publishSaved(temp, whenMs);
            }
        });
    }

    /** Stops the current recording and saves the temp file to the SAF folder. */
    public void stopRecording() {
        switch (state) {
            case STATE_READY:
            case STATE_LISTENING:
                return;
            case STATE_RECORDING:
                break;
        }
        state = STATE_LISTENING;
        cancelAutoStop();

        final long whenMs = System.currentTimeMillis();
        final File tempRef = wavFile;
        final WavFileWriter writerRef = wavFileWriter;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                if (writerRef != null) {
                    flushAudioRecord();
                    try {
                        writerRef.close();
                    } catch (IOException e) {
                        Log.e(TAG, "CLOSING ERROR", e);
                    }
                    if (tempRef != null) {
                        publishSaved(tempRef, whenMs);
                    }
                }
                wavFileWriter = null;
                wavFile = null;
            }
        });

        final SharedPreferences preferences = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putBoolean(RECORDING_ACTIVE_KEY, false).commit();
        if (!preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStopListening();
        }
        stopForeground(true);
    }

    public boolean isRecording() {
        return state == STATE_RECORDING;
    }

    /** Toggles between recording and not-recording (used by the volume shortcut). */
    public void toggleRecording() {
        if (state == STATE_RECORDING) {
            stopRecording();
        } else {
            startRecording(0);
        }
    }

    /** Copies the (already-closed) temp file to the SAF folder, posts a notification, deletes temp. */
    private void publishSaved(final File temp, final long whenMs) {
        if (temp == null || !temp.exists()) {
            Log.w(TAG, "publishSaved: temp missing");
            return;
        }
        final Uri savedUri = Storage.saveToTree(SaidItService.this, temp, whenMs);
        final String displayName = Storage.buildFileName(whenMs);
        if (savedUri != null) {
            notifySaved(savedUri, displayName);
            if (!temp.delete()) {
                Log.w(TAG, "Could not delete temp file " + temp);
            }
        } else {
            // No SAF folder (user hasn't picked one). Keep the temp file so audio isn't lost.
            showToast(getString(R.string.pick_folder_to_save));
            notifySavedFileProvider(temp);
        }
    }

    public long getMemorySize() {
        return audioMemory.getAllocatedMemorySize();
    }

    public void setMemorySize(final long memorySize) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putLong(AUDIO_MEMORY_SIZE_KEY, memorySize).commit();

        if (preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            audioHandler.post(new Runnable() {
                @Override
                public void run() {
                    audioMemory.allocate(memorySize);
                }
            });
        }
    }

    public int getSamplingRate() {
        return SAMPLE_RATE;
    }

    public void setSampleRate(int sampleRate) {
        switch (state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putInt(SAMPLE_RATE_KEY, sampleRate).commit();

        innerStopListening();
        SAMPLE_RATE = sampleRate;
        FILL_RATE = 2 * SAMPLE_RATE;
        innerStartListening();
    }

    public float getBytesToSeconds() {
        return 1f / FILL_RATE;
    }

    class BackgroundRecorderBinder extends Binder {
        public SaidItService getService() {
            return SaidItService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Promote to a foreground microphone service. On Android 14+ this can
        // throw ForegroundServiceStartNotAllowedException when started from the
        // background (e.g. the onTaskRemoved restart alarm or a cold boot). We
        // catch it so the process never crashes; START_STICKY will let the
        // system restart us from an exempted context.
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed: " + t);
        }

        if (volumeMonitor != null) volumeMonitor.start();

        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_AUTO_STOP.equals(action)) {
            Log.d(TAG, "AUTO_STOP received");
            if (state == STATE_RECORDING) {
                stopRecording();
            }
            return START_STICKY;
        }

        // Resume a recording that was interrupted by the system killing / rebooting the device.
        final SharedPreferences prefs = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(RECORDING_ACTIVE_KEY, false) && state != STATE_RECORDING) {
            Log.d(TAG, "Resuming recording after restart");
            if (state == STATE_READY && prefs.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
                innerStartListening();
            }
            startRecording(0);
        }

        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Restart the service shortly after the user swipes it away from recents,
        // so background recording survives.
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        PendingIntent restartServicePendingIntent = PendingIntent.getService(this, 1,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmService = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmService.set(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent);
    }

    // ---- Auto-stop timer / hard limit ----

    /**
     * Schedules a single exact alarm at the earlier of (user timer, hard 70-min limit).
     * If the user timer is off, only the hard limit is scheduled.
     */
    private void scheduleAutoStop(long startMs, int timerMinutes) {
        final long hardLimitMs = MAX_RECORDING_MINUTES * 60_000L;
        final long timerMs = timerMinutes > 0 ? timerMinutes * 60_000L : hardLimitMs;
        final long stopAt = startMs + Math.min(timerMs, hardLimitMs);

        Intent intent = new Intent(this, SaidItService.class).setAction(ACTION_AUTO_STOP);
        PendingIntent pi = PendingIntent.getService(this, AUTO_STOP_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, pi);
            Log.d(TAG, "Auto-stop scheduled at " + stopAt + " (timer=" + timerMinutes + "min)");
        } catch (SecurityException e) {
            Log.e(TAG, "Could not schedule exact alarm", e);
        }
    }

    private void cancelAutoStop() {
        Intent intent = new Intent(this, SaidItService.class).setAction(ACTION_AUTO_STOP);
        PendingIntent pi = PendingIntent.getService(this, AUTO_STOP_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.cancel(pi);
    }

    // ---- Notifications ----

    private Notification buildNotification() {
        Intent intent = new Intent(this, SaidItActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        ensureChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.recording))
                .setSmallIcon(R.drawable.ic_stat_notify_recording)
                .setTicker(getString(R.string.recording))
                .setContentIntent(pendingIntent)
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

    /** Fallback notification when no SAF folder is set: open the temp file via FileProvider. */
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
            @Override
            public void run() {
                Toast.makeText(SaidItService.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- Audio capture loop (unchanged logic) ----

    private void flushAudioRecord() {
        assert audioHandler.getLooper() == android.os.Looper.myLooper();
        audioHandler.removeCallbacks(audioReader);
        audioReader.run();
    }

    final AudioMemory.Consumer filler = new AudioMemory.Consumer() {
        @Override
        public int consume(final byte[] array, final int offset, final int count) throws IOException {
            final int read = audioRecord.read(array, offset, count, AudioRecord.READ_NON_BLOCKING);
            if (read == AudioRecord.ERROR_BAD_VALUE
                    || read == AudioRecord.ERROR_INVALID_OPERATION
                    || read == AudioRecord.ERROR) {
                Log.e(TAG, "AUDIO RECORD ERROR: " + read);
                return 0;
            }
            if (wavFileWriter != null && read > 0) {
                wavFileWriter.write(array, offset, read);
            }
            if (read == count) {
                audioHandler.post(audioReader);
            } else {
                float bufferSizeInSeconds = audioRecord.getBufferSizeInFrames() / (float) SAMPLE_RATE;
                float delaySeconds = Math.max(bufferSizeInSeconds - 1, bufferSizeInSeconds * 0.5f);
                delaySeconds = Math.min(delaySeconds, bufferSizeInSeconds * 0.9f);
                audioHandler.postDelayed(audioReader, (long) (delaySeconds * 1000));
            }
            return read;
        }
    };

    final Runnable audioReader = new Runnable() {
        @Override
        public void run() {
            try {
                audioMemory.fill(filler);
            } catch (IOException e) {
                showToast(getString(R.string.error_during_recording_into) + (wavFile != null ? wavFile.getName() : "?"));
                Log.e(TAG, "Error during recording", e);
                stopRecording();
            }
        }
    };

    public interface StateCallback {
        void state(boolean listeningEnabled, boolean recording, float memorized, float totalMemory, float recorded);
    }

    public void getState(final StateCallback stateCallback) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        final boolean listeningEnabled = preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true);
        final boolean recording = (state == STATE_RECORDING);
        final Handler sourceHandler = new Handler();
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                final AudioMemory.Stats stats = audioMemory.getStats(FILL_RATE);

                int recorded = 0;
                if (wavFileWriter != null) {
                    recorded += wavFileWriter.getTotalSampleBytesWritten();
                    recorded += stats.estimation;
                }
                final float bytesToSeconds = getBytesToSeconds();
                final int finalRecorded = recorded;
                sourceHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stateCallback.state(listeningEnabled, recording,
                                (stats.overwriting ? stats.total : stats.filled + stats.estimation) * bytesToSeconds,
                                stats.total * bytesToSeconds,
                                finalRecorded * bytesToSeconds);
                    }
                });
            }
        });
    }
}
