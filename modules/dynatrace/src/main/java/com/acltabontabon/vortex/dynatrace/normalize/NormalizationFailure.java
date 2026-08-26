package com.acltabontabon.vortex.dynatrace.normalize;

import java.util.Objects;

/** Why a tool result was rejected rather than turned into evidence. Vortex never guesses instead. */
public sealed interface NormalizationFailure
        permits NormalizationFailure.SchemaInvalid, NormalizationFailure.UnitUnrecognized,
        NormalizationFailure.WindowMismatch, NormalizationFailure.EntityMismatch,
        NormalizationFailure.EmptyResult {

    String detail();

    /** The tool returned prose, an unexpected shape, or no numeric value where one was expected. */
    record SchemaInvalid(String detail) implements NormalizationFailure {
        public SchemaInvalid {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** A unit was reported and it does not match what this query expects. */
    record UnitUnrecognized(String detail) implements NormalizationFailure {
        public UnitUnrecognized {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** The response's own timestamps fall entirely outside the requested window. */
    record WindowMismatch(String detail) implements NormalizationFailure {
        public WindowMismatch {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** The response echoes an entity identifier that does not match the one asked about. */
    record EntityMismatch(String detail) implements NormalizationFailure {
        public EntityMismatch {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** The values found were empty, all zero, or otherwise not a real observation. */
    record EmptyResult(String detail) implements NormalizationFailure {
        public EmptyResult {
            Objects.requireNonNull(detail, "detail");
        }
    }
}
