package dev.vortex.core.fixtures;

import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.plan.ExperimentIdentity;
import dev.vortex.core.port.Repositories;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An execution repository that keeps everything in a list.
 *
 * <p>Published from the test jar so every module's tests share one, rather than each growing a
 * private stub that implements the port slightly differently. The last time these diverged, one
 * stub returned every execution from {@code findByProject} regardless of project, which is exactly
 * the kind of difference that makes a test pass while the real query is wrong.
 *
 * <p>The identity index is modelled honestly: {@link #save} records the fingerprint derived from
 * the plan, and {@link #reindexExperimentFingerprint} changes it separately — so a test can put the
 * index out of date on purpose and check that reconciliation notices.
 */
public final class InMemoryExecutions implements Repositories.ExecutionRepository {

    private final List<TestExecution> stored = new ArrayList<>();

    /** Execution id to indexed experiment fingerprint, mirroring the database column. */
    private final Map<String, String> index = new LinkedHashMap<>();

    @Override
    public TestExecution save(TestExecution execution) {
        stored.removeIf(existing -> existing.id().equals(execution.id()));
        stored.add(execution);
        index.put(execution.id().value(),
                ExperimentIdentity.fingerprintOf(execution.plan()).hash());
        return execution;
    }

    @Override
    public List<TestExecution> saveAll(List<TestExecution> executions) {
        executions.forEach(this::save);
        return executions;
    }

    /** Stores a run whose identity index is stale, as a row written before a contract change is. */
    public TestExecution saveUnindexed(TestExecution execution) {
        save(execution);
        index.put(execution.id().value(), "an-older-contract");
        return execution;
    }

    /** The indexed fingerprint, for asserting that reconciliation wrote what it should have. */
    public String indexedFingerprint(ExecutionId id) {
        return index.getOrDefault(id.value(), "");
    }

    @Override
    public Optional<TestExecution> findById(ExecutionId id) {
        return stored.stream().filter(execution -> execution.id().equals(id)).findFirst();
    }

    @Override
    public List<TestExecution> findByProject(ProjectId projectId, int limit) {
        return stored.stream()
                .filter(execution -> execution.projectId().equals(projectId))
                .sorted(Comparator.comparing(TestExecution::requestedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<TestExecution> findRecent(int limit) {
        return stored.stream()
                .sorted(Comparator.comparing(TestExecution::requestedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<TestExecution> findUnfinished() {
        return stored.stream().filter(execution -> !execution.isTerminal()).toList();
    }

    @Override
    public List<TestExecution> findCompatible(ProjectId projectId, String experimentFingerprint,
            Instant before, int limit) {

        if (experimentFingerprint == null || experimentFingerprint.isBlank()) {
            return List.of();
        }
        return stored.stream()
                .filter(execution -> execution.projectId().equals(projectId))
                .filter(execution -> execution.state()
                        == dev.vortex.core.execution.ExecutionState.COMPLETED)
                .filter(execution -> experimentFingerprint
                        .equals(index.get(execution.id().value())))
                .filter(execution -> before == null || execution.requestedAt().isBefore(before))
                .sorted(Comparator.comparing(TestExecution::requestedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<Repositories.ExecutionRepository.ExperimentIndex> findExperimentIndexes() {
        return stored.stream()
                .map(execution -> new Repositories.ExecutionRepository.ExperimentIndex(
                        execution.id(), execution.plan(),
                        index.getOrDefault(execution.id().value(), "")))
                .toList();
    }

    @Override
    public boolean reindexExperimentFingerprint(ExecutionId id, String experimentFingerprint) {
        String fingerprint = experimentFingerprint == null ? "" : experimentFingerprint;
        String previous = index.put(id.value(), fingerprint);
        return !fingerprint.equals(previous);
    }

    @Override
    public int reindexExperimentFingerprints(Map<ExecutionId, String> newFingerprints) {
        int changed = 0;
        for (Map.Entry<ExecutionId, String> entry : newFingerprints.entrySet()) {
            if (reindexExperimentFingerprint(entry.getKey(), entry.getValue())) {
                changed++;
            }
        }
        return changed;
    }

    @Override
    public long countByProject(ProjectId projectId) {
        return stored.stream().filter(execution -> execution.projectId().equals(projectId)).count();
    }
}
