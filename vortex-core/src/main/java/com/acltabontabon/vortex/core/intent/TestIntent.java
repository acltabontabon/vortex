package com.acltabontabon.vortex.core.intent;

import com.acltabontabon.vortex.core.workload.TestType;
import java.util.Objects;

/**
 * What a test run is trying to learn.
 *
 * <p>A performance test is an experiment, not an errand. It has a hypothesis, a controlled workload,
 * a known environment, measured observations, an evaluation and a conclusion. A run without a stated
 * question tends to produce numbers nobody can act on, because there was never an agreed definition
 * of what a good answer would look like.
 *
 * <p>Vortex therefore attaches an intent to every plan, taken from the workload that produced it,
 * and the result page opens by returning to that question rather than by showing a chart.
 *
 * @param type      the kind of test, which supplies the default question
 * @param objective what this specific run is trying to establish, in the user's own words
 */
public record TestIntent(TestType type, String objective) {

    public static final int MAX_OBJECTIVE_LENGTH = 500;

    public TestIntent {
        Objects.requireNonNull(type, "type");
        objective = objective == null ? "" : objective.trim();
        if (objective.length() > MAX_OBJECTIVE_LENGTH) {
            throw new IllegalArgumentException(
                    "test objective must be at most " + MAX_OBJECTIVE_LENGTH + " characters");
        }
    }

    /** The default intent for a test type, used when the user has not written their own. */
    public static TestIntent defaultFor(TestType type) {
        return new TestIntent(type, "");
    }

    /**
     * The question this run answers: the user's own objective when they wrote one, otherwise the
     * standard question for this kind of test.
     */
    public String question() {
        return objective.isBlank() ? type.question() : objective;
    }

    public boolean isCustom() {
        return !objective.isBlank();
    }
}
