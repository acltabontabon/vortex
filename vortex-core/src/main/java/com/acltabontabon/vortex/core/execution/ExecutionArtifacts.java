package com.acltabontabon.vortex.core.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * References to the raw evidence an execution produced.
 *
 * <p>Vortex keeps source evidence rather than only summaries. A summary answers the questions
 * anticipated when it was written; the raw artifacts answer the question asked six months later.
 *
 * <p>Paths are relative to the execution's artifact directory so a workspace can be moved or
 * archived without invalidating stored references.
 */
public record ExecutionArtifacts(Map<String, String> paths) {

    /**
     * The resolved plan, written by core before anything else happens.
     *
     * <p>The only artifact name core owns. Everything else in the directory is produced by the
     * engine and named by it — {@code generated-test.js}, {@code k6-summary.json} — and naming
     * those here would put an engine's filenames in the domain model of a product whose whole
     * claim is that the engine is replaceable.
     */
    public static final String PLAN = "plan.json";

    /** Engine output streams. Named here because core writes the failure path that reads them. */
    public static final String STDOUT = "stdout.log";
    public static final String STDERR = "stderr.log";

    /**
     * The run's evidence, written by Vortex once the measurements are settled.
     *
     * <p>Named here, like the plan, because Vortex produces them rather than the engine. They exist
     * so that a copied or archived execution directory is self-describing: until they were written,
     * everything the verdict rested on — the objective-by-objective results, the tool versions, the
     * timestamps — lived only in the local database, and a directory carried away from it said what
     * was asked for but not what happened or what carried it out.
     *
     * <p>These constants were once declared without anything writing them, and were removed for it.
     * They are back because {@code TestRunner} writes them on every completed run.
     */
    public static final String EVIDENCE = "evidence.json";
    public static final String REPORT = "report.md";

    /**
     * Written only when releasing a run's target failed after the run itself had already finished.
     *
     * <p>Deliberately not a {@code TestExecution.FailureReason}: a run that measured its target
     * correctly did not fail because Vortex could not tear the target back down afterward, and
     * reporting it as failed would misstate what actually happened. This artifact — plus a WARN log
     * line — is how the failure stays visible without retroactively changing a completed run's
     * outcome.
     */
    public static final String TARGET_CLEANUP = "target-cleanup.log";

    public ExecutionArtifacts {
        paths = paths == null ? Map.of() : Map.copyOf(paths);
    }

    public static ExecutionArtifacts empty() {
        return new ExecutionArtifacts(Map.of());
    }

    public Optional<String> path(String name) {
        return Optional.ofNullable(paths.get(name));
    }

    public boolean has(String name) {
        return paths.containsKey(name);
    }

    public ExecutionArtifacts with(String name, String path) {
        Map<String, String> updated = new LinkedHashMap<>(paths);
        updated.put(name, path);
        return new ExecutionArtifacts(updated);
    }
}
