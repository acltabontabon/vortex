package com.acltabontabon.vortex.core.resource;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Bounds one signal's raw series to a chart-sized number of points without discarding what makes it
 * evidence.
 *
 * <p>The artifact behind this may hold tens of thousands of samples for a long soak; a chart does
 * not need all of them, and blindly deserializing and returning the full series would push a
 * multi-megabyte payload into every browser tab that opens a run. Naive every-Nth-point thinning is
 * not an acceptable answer either — a brief saturation spike can fall entirely between two kept
 * points and vanish, which is exactly the evidence this feature exists to keep.
 *
 * <p>Instead: the run is divided into time buckets, and each bucket contributes both its lowest and
 * its highest reading, so a spike survives even if it lasted one sample. On top of that, the first
 * and last samples and whichever sample is nearest each supplied anchor instant (a stage boundary, a
 * breakpoint) are forced in regardless of which bucket they would have landed in — the neighbourhoods
 * a reader is most likely to zoom in on.
 *
 * <p>Operates on one signal's samples at a time. Mixing signals into one call would bucket unrelated
 * values together, which is meaningless.
 */
public final class ResourceSeriesProjection {

    /** Target chart resolution. A bucket contributes at most two points — its min and its max — so
     *  the number of buckets is half this. */
    public static final int TARGET_POINTS = 800;

    private ResourceSeriesProjection() {
    }

    public static List<ResourceSample> project(List<ResourceSample> samples, List<Instant> anchors) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        List<ResourceSample> sorted = samples.stream()
                .sorted(Comparator.comparing(ResourceSample::at))
                .toList();
        if (sorted.size() <= TARGET_POINTS) {
            return sorted;
        }

        Instant start = sorted.get(0).at();
        Instant end = sorted.get(sorted.size() - 1).at();
        long spanMillis = Math.max(1, Duration.between(start, end).toMillis());
        int bucketCount = Math.max(1, TARGET_POINTS / 2);

        ResourceSample[] mins = new ResourceSample[bucketCount];
        ResourceSample[] maxes = new ResourceSample[bucketCount];
        for (ResourceSample sample : sorted) {
            long offsetMillis = Duration.between(start, sample.at()).toMillis();
            int bucket = (int) Math.min(bucketCount - 1, offsetMillis * bucketCount / spanMillis);
            if (mins[bucket] == null || sample.value() < mins[bucket].value()) {
                mins[bucket] = sample;
            }
            if (maxes[bucket] == null || sample.value() > maxes[bucket].value()) {
                maxes[bucket] = sample;
            }
        }

        TreeMap<Instant, ResourceSample> kept = new TreeMap<>();
        for (int i = 0; i < bucketCount; i++) {
            if (mins[i] != null) {
                kept.put(mins[i].at(), mins[i]);
            }
            if (maxes[i] != null) {
                kept.put(maxes[i].at(), maxes[i]);
            }
        }

        kept.put(sorted.get(0).at(), sorted.get(0));
        kept.put(sorted.get(sorted.size() - 1).at(), sorted.get(sorted.size() - 1));
        if (anchors != null) {
            for (Instant anchor : anchors) {
                if (anchor == null) {
                    continue;
                }
                ResourceSample nearest = nearestTo(sorted, anchor);
                if (nearest != null) {
                    kept.put(nearest.at(), nearest);
                }
            }
        }

        return List.copyOf(kept.values());
    }

    private static ResourceSample nearestTo(List<ResourceSample> sorted, Instant anchor) {
        ResourceSample best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ResourceSample sample : sorted) {
            long distance = Math.abs(Duration.between(anchor, sample.at()).toMillis());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = sample;
            }
        }
        return best;
    }
}
