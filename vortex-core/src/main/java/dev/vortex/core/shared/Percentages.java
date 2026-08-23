package dev.vortex.core.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Renders a fractional share as a display percentage: {@code 60}, {@code 33.3}, {@code 100}.
 *
 * <p>A string rather than a {@code BigDecimal}, because the two are not the same thing once
 * rendering is involved: {@code stripTrailingZeros()} applied to 100.0 yields {@code 1E+2}, which is
 * correct arithmetic and useless in a user interface.
 *
 * <p>Extracted because the rule was previously implemented identically in two places, and a display
 * rule with two homes is a display rule that will eventually disagree with itself.
 */
public final class Percentages {

    private Percentages() {
    }

    /** @param share a fraction of one, in the range (0, 1] */
    public static String display(BigDecimal share) {
        if (share == null) {
            throw new IllegalArgumentException("share must not be null");
        }
        return share.movePointRight(2)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
