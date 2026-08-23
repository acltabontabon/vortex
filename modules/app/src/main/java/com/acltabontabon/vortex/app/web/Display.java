package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.core.analysis.Confidence;
import com.acltabontabon.vortex.core.analysis.EvidenceStrength;
import com.acltabontabon.vortex.core.analysis.FindingType;
import com.acltabontabon.vortex.core.capacity.BoundaryStatus;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * Formatting shared by every REST controller that renders evidence, a run, or a service's state.
 *
 * <p>Keeping presentation logic here rather than duplicated per controller means the phrasing
 * decisions — which are product decisions — sit in one reviewable place.
 *
 * <p>Timestamps are stored in UTC and rendered in the viewer's zone. A test that ran at 14:00 local
 * time should say so; storing local time instead would make history incomparable the moment someone
 * travelled or the clocks changed.
 */
@Component("display")
public class Display {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");
    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter COMPACT_DATE_WITH_YEAR =
            DateTimeFormatter.ofPattern("MMM d, yyyy");

    /**
     * Where a readiness item is satisfied, relative to the service.
     *
     * <p>{@code ProjectReadiness} states what is missing and what to do about it, and deliberately
     * knows nothing about screens — it is domain, and a URL is not. The mapping from one to the
     * other is a presentation decision, so it lives here with the other presentation decisions
     * rather than being spelled out in a template conditional that nobody would find again.
     */
    public String readinessPath(String itemLabel) {
        return switch (itemLabel) {
            case "API imported" -> "understand#operations";
            case "Environment configured" -> "understand#environments";
            case "Workload defined", "Average-load workload defined" -> "traffic";
            case "Objectives configured" -> "understand#objectives";
            case "Production traffic recorded" -> "understand#production";
            case "Test executed" -> "evaluate";
            default -> "understand";
        };
    }

    /** A timestamp in the viewer's local zone. */
    public String timestamp(Instant instant) {
        return instant == null ? "—" : TIMESTAMP.format(instant.atZone(ZoneId.systemDefault()));
    }

    public String time(Instant instant) {
        return instant == null ? "—" : TIME_ONLY.format(instant.atZone(ZoneId.systemDefault()));
    }

    /** Relative phrasing for recent activity: {@code 2 hours ago}. */
    public String relative(Instant instant) {
        if (instant == null) {
            return "—";
        }
        Duration since = Duration.between(instant, Instant.now());
        if (since.isNegative()) {
            return "just now";
        }
        long minutes = since.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        }
        long hours = since.toHours();
        if (hours < 24) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        long days = since.toDays();
        if (days < 30) {
            return days + (days == 1 ? " day ago" : " days ago");
        }
        return timestamp(instant);
    }

    /**
     * Relative phrasing for recent evidence, a compact date once it is old enough that "N days ago"
     * stops being a useful reading — {@code "19 Aug"}, or {@code "19 Aug 2025"} once the year itself
     * is no longer obvious.
     *
     * <p>Never a claim about staleness — nothing here knows whether the age of a reading matters, only
     * that a dense header cell needs a shorter way to write an old date than {@link #relative} falls
     * back to. A separate method rather than changing {@link #relative} itself, since every existing
     * screen using it depends on the full-timestamp fallback it already has.
     */
    public String freshness(Instant instant) {
        if (instant == null) {
            return "—";
        }
        if (Duration.between(instant, Instant.now()).toDays() < 30) {
            return relative(instant);
        }
        ZonedDateTime zoned = instant.atZone(ZoneId.systemDefault());
        boolean sameYear = zoned.getYear() == Instant.now().atZone(ZoneId.systemDefault()).getYear();
        return (sameYear ? COMPACT_DATE : COMPACT_DATE_WITH_YEAR).format(zoned);
    }

    public String duration(Duration duration) {
        return duration == null ? "—" : Durations.display(duration);
    }

    /** Elapsed time as a stopwatch reading, for a run in progress: {@code 08:42}. */
    public String stopwatch(Duration duration) {
        if (duration == null) {
            return "00:00";
        }
        long totalSeconds = Math.max(0, duration.toSeconds());
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }

    /** The CSS modifier for a verdict badge. */
    public String verdictClass(Verdict verdict) {
        if (verdict == null) {
            return "verdict-unknown";
        }
        return switch (verdict) {
            case PASS -> "verdict-pass";
            case FAIL -> "verdict-fail";
            case NOT_EVALUATED -> "verdict-unknown";
        };
    }

    /**
     * The word shown for a verdict.
     *
     * <p>An unevaluated objective is never rendered as a pass. "Not evaluated" is a real outcome
     * with a real meaning: something the service was supposed to meet was never checked.
     */
    public String verdictLabel(Verdict verdict) {
        return verdict == null ? "No verdict" : verdict.label();
    }

    public String stateClass(ExecutionState state) {
        if (state == null) {
            return "verdict-unknown";
        }
        return switch (state) {
            case COMPLETED -> "verdict-pass";
            case FAILED -> "verdict-fail";
            case CANCELLED -> "verdict-warn";
            case RUNNING, STARTING, COLLECTING, EVALUATING, VALIDATING -> "verdict-running";
            case CREATED, READY -> "verdict-unknown";
        };
    }

    public String confidenceLabel(Confidence confidence) {
        return confidence == null ? "Unstated" : confidence.label() + " confidence";
    }

    /** CSS class distinguishing a measured fact from a correlation, hypothesis or limitation. */
    public String findingTypeClass(FindingType type) {
        if (type == null) {
            return "finding-type-hypothesis";
        }
        return switch (type) {
            case OBSERVATION -> "finding-type-observation";
            case CORRELATION -> "finding-type-correlation";
            case HYPOTHESIS -> "finding-type-hypothesis";
            case LIMITATION -> "finding-type-limitation";
        };
    }

    public String evidenceStrengthLabel(EvidenceStrength strength) {
        return strength == null ? "" : strength.label() + " evidence";
    }

    /** A byte count for artifact listings. */
    public String bytes(Long size) {
        if (size == null) {
            return "—";
        }
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return Math.round(size / 1024.0) + " KB";
        }
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }

    /** Truncates for a summary column without cutting mid-word where avoidable. */
    public String shorten(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        int lastSpace = text.lastIndexOf(' ', maxLength);
        return text.substring(0, lastSpace > maxLength / 2 ? lastSpace : maxLength).trim() + "…";
    }

    /** Whole minutes and seconds for an estimated run length. */
    public String estimate(Duration duration) {
        if (duration == null) {
            return "—";
        }
        return Durations.display(duration.truncatedTo(ChronoUnit.SECONDS));
    }

    /** A count with the right plural, so the interface never says "1 executions". */
    public String plural(long count, String singular, String pluralForm) {
        return count + " " + (count == 1 ? singular : pluralForm);
    }

    /**
     * What a run establishes when the service has no objectives configured.
     *
     * <p>Quoted from {@link BoundaryStatus#NOT_EVALUATED} rather than written here, because it is the
     * same sentence the evidence pages use for the same situation, and a second phrasing of it would
     * eventually say something slightly different about the same fact.
     *
     * <p>Used to explain an advisory setup item: missing objectives do not stop a test running, they
     * decide what the result can conclude.
     */
    public String withoutObjectives() {
        return BoundaryStatus.NOT_EVALUATED.label();
    }

    /**
     * That the evidence on file was measured against a different release than the one configured now.
     *
     * <p>The one sentence on the home page that Vortex composes rather than quotes, so it is composed
     * exactly once. Deliberately not phrased as staleness: nothing here knows whether the change
     * mattered, only that the two versions differ and that re-testing is what would settle it.
     */
    public String releaseGap(String measuredVersion, String currentVersion) {
        return releaseGapText(measuredVersion, currentVersion);
    }

    /** The same sentence, for a controller assembling it outside a template. */
    public static String releaseGapText(String measuredVersion, String currentVersion) {
        return "The evidence on file was measured against " + measuredVersion
                + ". This service is now configured as " + currentVersion + ".";
    }
}
