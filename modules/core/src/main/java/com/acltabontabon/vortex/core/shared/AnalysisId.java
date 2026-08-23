package com.acltabontabon.vortex.core.shared;

/** Opaque identifier for a analysis id. */
public record AnalysisId(String value) implements Identifier {

    public AnalysisId {
        value = Ids.require("AnalysisId", value);
    }

    public static AnalysisId generate() {
        return new AnalysisId(Ids.generate());
    }

    public static AnalysisId of(String value) {
        return new AnalysisId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
