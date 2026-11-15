package eu.mrogalski.saidit;

public class SaidIt {

    static final String PACKAGE_NAME = "eu.mrogalski.saidit";
    static final String AUDIO_MEMORY_ENABLED_KEY = "audio_memory_enabled";
    static final String AUDIO_MEMORY_SIZE_KEY = "audio_memory_size";
    static final String SAMPLE_RATE_KEY = "sample_rate";

    /** Persisted tree Uri (SAF) of the folder where recordings are saved. */
    static final String SAF_TREE_URI_KEY = "saf_tree_uri";

    /** True while a recording is in progress so it can be resumed after a restart/reboot. */
    static final String RECORDING_ACTIVE_KEY = "recording_active";

    /** Wall-clock millis at which the current recording started (for countdown / limit math). */
    static final String RECORDING_START_KEY = "recording_start";

    /** User-settable auto-stop timer in minutes (0 = off). */
    static final String TIMER_MINUTES_KEY = "timer_minutes";

    /** Hard cap for any single recording, in minutes. */
    static final int MAX_RECORDING_MINUTES = 70;

    /** Intent actions handled by the service / boot receiver. */
    static final String ACTION_AUTO_STOP = PACKAGE_NAME + ".ACTION_AUTO_STOP";
    static final String ACTION_TOGGLE = PACKAGE_NAME + ".ACTION_TOGGLE";
}
