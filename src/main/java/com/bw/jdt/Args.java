package com.bw.jdt;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {@code --name value} / {@code --flag} command line parsing, shared by all entry
 * points so that server, client and generator take arguments in the same shape.
 */
public final class Args {

    private static final Pattern SIZE = Pattern.compile(
            "(?i)^\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(b|k|kb|kib|m|mb|mib|g|gb|gib|t|tb|tib)?\\s*$");

    private final Map<String, String> values = new LinkedHashMap<>();

    private Args() {
    }

    public static Args parse(String[] argv) {
        Args args = new Args();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + a);
            }
            String name = a.substring(2);
            String value;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                value = argv[++i];
            } else {
                value = "true";
            }
            args.values.put(name.toLowerCase(Locale.ROOT), value);
        }
        return args;
    }

    public boolean has(String name) {
        return values.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public String get(String name, String fallback) {
        return values.getOrDefault(name.toLowerCase(Locale.ROOT), fallback);
    }

    public String require(String name) {
        String v = values.get(name.toLowerCase(Locale.ROOT));
        if (v == null) {
            throw new IllegalArgumentException("missing required argument --" + name);
        }
        return v;
    }

    public int getInt(String name, int fallback) {
        String v = get(name, null);
        return v == null ? fallback : Integer.parseInt(v.trim());
    }

    public double getDouble(String name, double fallback) {
        String v = get(name, null);
        return v == null ? fallback : Double.parseDouble(v.trim().replace(',', '.'));
    }

    public boolean getBool(String name, boolean fallback) {
        String v = get(name, null);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    /** Parses sizes like {@code 1GB}, {@code 1.5GiB}, {@code 64K} or a plain byte count. */
    public long getBytes(String name, long fallback) {
        String v = get(name, null);
        return v == null ? fallback : parseBytes(v);
    }

    public static long parseBytes(String text) {
        Matcher m = SIZE.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("cannot parse size: " + text);
        }
        double value = Double.parseDouble(m.group(1).replace(',', '.'));
        String unit = m.group(2) == null ? "b" : m.group(2).toLowerCase(Locale.ROOT);
        long factor = switch (unit.charAt(0)) {
            case 'b' -> 1L;
            case 'k' -> 1L << 10;
            case 'm' -> 1L << 20;
            case 'g' -> 1L << 30;
            case 't' -> 1L << 40;
            default -> throw new IllegalArgumentException("unknown unit in " + text);
        };
        return (long) (value * factor);
    }
}
