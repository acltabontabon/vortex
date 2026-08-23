package dev.vortex.core.metrics;

import java.util.Locale;

/**
 * Bytes at a scale a person can read, shared by every reading in {@link MetricUnit#BYTES} — a
 * measurement and the limit it is judged against alike. Lives here, once, because two independently
 * formatted numbers ("183.3 MB" beside "536870912 bytes") is what makes a reader do the conversion
 * arithmetic themselves before they can tell how close one is to the other.
 *
 * <p>{@code 5419040765 bytes} is technically the measurement and practically unreadable; nobody
 * comparing a heap against its limit counts digits. Powers of 1024, because that is what a JVM and an
 * operating system mean by a megabyte.
 */
public final class Bytes {

    private static final String[] UNITS = {"bytes", "KB", "MB", "GB", "TB"};

    private Bytes() {
    }

    public static String display(double value) {
        double scaled = Math.abs(value);
        int unitIndex = 0;
        while (scaled >= 1024 && unitIndex < UNITS.length - 1) {
            scaled /= 1024;
            unitIndex++;
        }
        double signed = value < 0 ? -scaled : scaled;
        String number = unitIndex == 0
                ? String.valueOf((long) signed)
                : String.format(Locale.ROOT, "%.1f", signed);
        return number + " " + UNITS[unitIndex];
    }
}
