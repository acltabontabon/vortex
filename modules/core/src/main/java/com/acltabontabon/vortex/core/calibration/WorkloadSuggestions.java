package com.acltabontabon.vortex.core.calibration;

import com.acltabontabon.vortex.core.shared.WorkloadId;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.Workload;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a proposal into the workload it proposes.
 *
 * <p>Separated from {@link CalibrationPolicy} because proposing and adopting are different acts:
 * a suggestion is shown and argued with, a workload is committed to {@code vortex.yaml} and run.
 * Keeping the conversion here means the command line and the web interface adopt a proposal
 * identically — a CI mode that disagreed with the interactive mode about what a proposal meant would
 * eventually produce two different tests from the same screen.
 */
public final class WorkloadSuggestions {

    private WorkloadSuggestions() {
    }

    /**
     * Builds the workload a suggestion describes.
     *
     * <p>The observed composition is required rather than optional. A rate says how much traffic
     * arrives and nothing about what it consists of, and Vortex will not invent the distribution:
     * a workload built on a guessed mix carries the authority of production evidence without any of
     * the evidence.
     *
     * <p>The derivation is not copied into the description. It lives on the {@link Workload}'s
     * source, where somebody rewording the description cannot destroy the record of where the number
     * came from.
     */
    public static Workload toWorkload(WorkloadSuggestion suggestion, OperationMix observedMix) {
        Objects.requireNonNull(suggestion, "suggestion");
        Objects.requireNonNull(observedMix, "observedMix");

        LoadShape shape = suggestion.isRamp()
                ? new RampingArrivalRateShape(suggestion.stages().getFirst(),
                        suggestion.stages().stream()
                                .map(rate -> new Stage(rate,
                                        suggestion.duration().dividedBy(suggestion.stages().size())))
                                .toList())
                : new ConstantArrivalRateShape(suggestion.rate(), suggestion.duration());

        return new Workload(
                WorkloadId.of(suggestion.name()),
                suggestion.name(),
                suggestion.description(),
                "",
                suggestion.type(),
                observedMix,
                shape,
                ThresholdSet.empty(),
                suggestion.source(),
                Map.of());
    }
}
