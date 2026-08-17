package com.lecternscanner.client;

/**
 * Keep in sync with {@code mod_version} in {@code gradle.properties}.
 * <ul>
 *   <li>{@code x.y.z} — stable</li>
 *   <li>{@code x.y.z.w} — beta (+0.0.0.1 per small change)</li>
 * </ul>
 */
public final class ModVersion {
    public static final String VERSION = "1.4.21";

    private ModVersion() {
    }

    /** Four-part versions are betas. */
    public static boolean isBeta() {
        int dots = 0;
        for (int i = 0; i < VERSION.length(); i++) {
            if (VERSION.charAt(i) == '.') {
                dots++;
            }
        }
        return dots >= 3;
    }
}
