package eu.mrogalski.saidit;

import android.content.Context;

/**
 * Resolves the user's theme choice (light / dark / amoled) to a style resource.
 * Red is the accent in all three.
 */
public final class ThemeHelper {

    public static final int LIGHT = 0;
    public static final int DARK = 1;
    public static final int AMOLED = 2;

    private ThemeHelper() {}

    public static int current(Context context) {
        return context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE)
                .getInt(SaidIt.THEME_KEY, LIGHT);
    }

    public static void set(Context context, int theme) {
        context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE)
                .edit().putInt(SaidIt.THEME_KEY, theme).commit();
    }

    public static int themeId(Context context) {
        switch (current(context)) {
            case DARK:   return R.style.ThemeDark;
            case AMOLED: return R.style.ThemeAmoled;
            default:     return R.style.ThemeLight;
        }
    }
}
