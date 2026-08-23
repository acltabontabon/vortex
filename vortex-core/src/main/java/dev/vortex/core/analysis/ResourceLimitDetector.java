package dev.vortex.core.analysis;

import dev.vortex.core.resource.ResourceSignal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the lowest level at which a typed resource reached the limit it was measured against.
 *
 * <p>Only signals scoped to the system under test, and only against a limit somebody declared. The
 * load generator's own CPU at 100% is a real measurement and is not a constraint on the service; a
 * queue depth of four thousand with no published maximum is a real measurement and is not evidence
 * that a limit was reached.
 *
 * <p>CPU is not privileged. Whichever declared signal crossed first is the answer, and where none
 * did, the finding names what <em>was</em> observed — because "no resource limit was identified"
 * without that list is not something an engineer can act on.
 */
public final class ResourceLimitDetector {

    public ResourceLimitFinding detect(List<StageObservation> stages) {
        if (stages == null || stages.isEmpty()) {
            return ResourceLimitFinding.notObserved(
                    ResourceLimitFinding.Status.NO_TYPED_RESOURCE_TELEMETRY, List.of());
        }

        List<StageObservation> byLevel = stages.stream()
                .sorted(Comparator.comparingDouble(stage -> stage.targetLoad().asDouble()))
                .toList();

        Set<String> classified = new LinkedHashSet<>();
        boolean anyLimitPublished = false;

        for (StageObservation stage : byLevel) {
            for (ResourceSignal signal : stage.serviceResourceSignals()) {
                classified.add(signal.name());
                if (signal.limitIfPresent().isPresent()) {
                    anyLimitPublished = true;
                }
                if (signal.isAtItsLimit()) {
                    return new ResourceLimitFinding(ResourceLimitFinding.Status.REACHED,
                            stage.targetLoad(), signal.kind(), signal.signalId(),
                            signal.describe(),
                            // A crossing placed on a boundary Vortex computed cannot be held as
                            // firmly as one placed on a boundary the run measured.
                            stage.supportsStrongerEvidence()
                                    ? EvidenceStrength.HIGH : EvidenceStrength.MEDIUM,
                            List.copyOf(classified));
                }
            }
        }

        if (classified.isEmpty()) {
            return ResourceLimitFinding.notObserved(
                    ResourceLimitFinding.Status.NO_TYPED_RESOURCE_TELEMETRY, List.of());
        }
        if (!anyLimitPublished) {
            // Classified, and nothing said what any of them was a fraction of. A heap in bytes with
            // no maximum is not a heap that stayed clear of its maximum.
            return ResourceLimitFinding.notObserved(
                    ResourceLimitFinding.Status.NO_LIMITS_PUBLISHED, List.copyOf(classified));
        }
        return ResourceLimitFinding.notObserved(
                ResourceLimitFinding.Status.NONE_REACHED_ITS_LIMIT, List.copyOf(classified));
    }
}
