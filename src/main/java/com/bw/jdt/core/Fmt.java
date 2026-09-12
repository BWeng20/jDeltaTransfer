package com.bw.jdt.core;

import java.util.Locale;

/** Small formatting helpers shared by the command line front ends. */
public final class Fmt {

    private Fmt() {
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.2f %s", v, units[i]);
    }

    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.2f%%", fraction * 100);
    }

    public static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.1fs", nanos / 1e9);
    }
}
