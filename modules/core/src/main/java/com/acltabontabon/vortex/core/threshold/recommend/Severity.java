package com.acltabontabon.vortex.core.threshold.recommend;

/**
 * How seriously a {@link SanityFinding} should be taken.
 *
 * <p>Three tiers, deliberately not more: warnings must be proportional and rare, not a wall of
 * yellow boxes for every ordinary manual decision. {@link #INVALID} is the one tier that blocks a
 * save — {@link #INFORMATION} and {@link #CAUTION} are always advisory.
 */
public enum Severity {

    /** Worth noting, not worth a visual warning. */
    INFORMATION,

    /** Worth a quiet flag — the user's decision may still be entirely reasonable. */
    CAUTION,

    /** A logical contradiction, not a judgement call. Blocks save. */
    INVALID
}
