package eu.mrogalski.saidit;

public class SaidIt {

    static final String PACKAGE_NAME = "eu.mrogalski.saidit";
    static final String AUDIO_MEMORY_ENABLED_KEY = "audio_memory_enabled";
    static final String AUDIO_MEMORY_SIZE_KEY = "audio_memory_size";
    static final String SAMPLE_RATE_KEY = "sample_rate";

    /** Persisted tree Uri (SAF) where recordings + the trace/ folder live. */
    static final String SAF_TREE_URI_KEY = "saf_tree_uri";

    /** True while a recording is in progress so it can be resumed after a restart. */
    static final String RECORDING_ACTIVE_KEY = "recording_active";
    static final String RECORDING_START_KEY = "recording_start";

    /** Low-battery auto-save (stops + saves at BATTERY_THRESHOLD percent). */
    static final String LOW_BATTERY_AUTOSAVE_KEY = "low_battery_autosave";
    static final int BATTERY_THRESHOLD = 2;

    /** Scheduled recording (clock-based start / stop), 24h hour + minute. */
    static final String SCHED_START_HOUR_KEY = "sched_start_hour";
    static final String SCHED_START_MIN_KEY = "sched_start_min";
    static final String SCHED_STOP_HOUR_KEY = "sched_stop_hour";
    static final String SCHED_STOP_MIN_KEY = "sched_stop_min";
    static final String SCHED_ARMED_KEY = "sched_armed";
    static final int SCHED_DISABLED = -1;

    /** Theme: 0 = light, 1 = dark (grey), 2 = amoled (black). */
    static final String THEME_KEY = "theme";

    /** Hard segment length: save and immediately continue in a new file. */
    static final int MAX_RECORDING_MINUTES = 70;

    /** Intent actions handled by the service / receivers. */
    static final String ACTION_AUTO_STOP = PACKAGE_NAME + ".ACTION_AUTO_STOP";
    static final String ACTION_SCHEDULED_START = PACKAGE_NAME + ".ACTION_SCHEDULED_START";
    static final String ACTION_SCHEDULED_STOP = PACKAGE_NAME + ".ACTION_SCHEDULED_STOP";
    static final String ACTION_SEGMENT_RESTART = PACKAGE_NAME + ".ACTION_SEGMENT_RESTART";

    /** Name of the trace subfolder created inside the SAF root. */
    static final String TRACE_FOLDER = "trace";
}
