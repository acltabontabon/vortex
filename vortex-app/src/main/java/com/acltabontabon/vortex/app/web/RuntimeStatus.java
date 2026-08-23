package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.app.readiness.DoctorReport;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * What this machine can currently do, summarised for the top bar.
 *
 * <h2>Why this does not say "Ready"</h2>
 *
 * <p>The interface used to carry a permanent green "Local AI ready" badge and a Diagnostics
 * destination beside it. Both overstated their case. "Ready" has no single deterministic meaning
 * here: Java, the load generator, the workspace, Docker and a local model are not equivalent
 * capabilities, two of them are optional, and none of them says anything at all about whether the
 * service you are about to test is reachable. A single word covering all of that is a claim Vortex
 * cannot support.
 *
 * <p>So the trigger is neutral. It names what was counted — {@code 4/5} — and the popover shows
 * each capability with its own state and its own remedy. The only judgement it makes is the one
 * {@link DoctorReport#isReady(List)} already makes: whether everything Vortex <em>requires</em> is
 * present. Nothing here is coloured by an optional
 * tool being absent, because absent optional tools are not a problem.
 *
 * <p>Whether a particular service can be reached is a different question with a different answer
 * per environment, and it is answered where it belongs: in that service's workspace.
 *
 * <h2>Why it is memoised</h2>
 *
 * <p>{@link DoctorReport#run()} shells out to k6 and Docker and probes the assistant. That is
 * perfectly reasonable once, and unreasonable on every page render of an application whose pages
 * are otherwise a database read away. The answer changes when someone installs something, which is
 * not a thing that happens twice a minute.
 */
@Component
public class RuntimeStatus {

    /** Long enough that browsing costs nothing, short enough to notice an install. */
    private static final Duration TTL = Duration.ofSeconds(30);

    /**
     * @param checks             every capability that was checked, in report order
     * @param satisfied          how many of them were satisfied
     * @param total              how many were checked
     * @param requirementsMet    whether everything Vortex requires is present — the only claim this
     *                           summary makes, and the same one {@code vortex doctor} exits on
     */
    public record Summary(List<DoctorReport.Check> checks, int satisfied, int total,
            boolean requirementsMet) {

        public Summary {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }

        /** The trigger's label, e.g. {@code 4/5}. A count, not a verdict. */
        public String tally() {
            return satisfied + "/" + total;
        }

        /** Capabilities Vortex needs in order to run a test at all. */
        public List<DoctorReport.Check> required() {
            return checks.stream().filter(DoctorReport.Check::required).toList();
        }

        /** Capabilities that add something when present, and cost nothing when absent. */
        public List<DoctorReport.Check> optional() {
            return checks.stream().filter(check -> !check.required()).toList();
        }

        /**
         * The required capabilities that are missing.
         *
         * <p>Named rather than counted, because "install k6" and "make ~/.vortex writable" are
         * different afternoons.
         */
        public List<DoctorReport.Check> unmetRequirements() {
            return required().stream().filter(check -> !check.isOk()).toList();
        }
    }

    private record Cached(Summary summary, long expiresAtNanos) {
    }

    private final DoctorReport doctor;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    public RuntimeStatus(DoctorReport doctor) {
        this.doctor = Objects.requireNonNull(doctor, "doctor");
    }

    /** The current summary, recomputed at most once per {@link #TTL}. */
    public Summary current() {
        Cached snapshot = cached.get();
        if (snapshot != null && System.nanoTime() < snapshot.expiresAtNanos()) {
            return snapshot.summary();
        }
        return refresh();
    }

    /** Recomputes now, discarding whatever was cached. */
    public Summary refresh() {
        List<DoctorReport.Check> checks = doctor.run();
        Summary summary = new Summary(checks,
                (int) checks.stream().filter(DoctorReport.Check::isOk).count(),
                checks.size(),
                doctor.isReady(checks));
        cached.set(new Cached(summary, System.nanoTime() + TTL.toNanos()));
        return summary;
    }
}
