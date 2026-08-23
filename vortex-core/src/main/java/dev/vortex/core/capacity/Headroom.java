package dev.vortex.core.capacity;

import dev.vortex.core.shared.RequestsPerSecond;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * How much more traffic a service has been shown to handle than it currently receives.
 *
 * <p>Expressed as a multiple: tested compliant capacity divided by observed production peak. It is
 * only meaningful when the two numbers are commensurable — same operation mix, comparable
 * environment class — which is why {@link HeadroomCalculator} refuses to produce one otherwise. A
 * headroom figure computed across incomparable measurements looks authoritative and means nothing.
 *
 * @param testedCapacity         highest tested rate that still met every objective
 * @param observedProductionPeak the highest rate observed in production
 * @param multiple               tested capacity ÷ observed peak
 */
public record Headroom(
        RequestsPerSecond testedCapacity,
        RequestsPerSecond observedProductionPeak,
        BigDecimal multiple) {

    public Headroom {
        Objects.requireNonNull(testedCapacity, "testedCapacity");
        Objects.requireNonNull(observedProductionPeak, "observedProductionPeak");
        Objects.requireNonNull(multiple, "multiple");
    }

    /** Display form, e.g. {@code 1.76×}. */
    public String display() {
        return multiple.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "×";
    }

    /** Whether the service has been shown to handle more than it currently receives. */
    public boolean isPositive() {
        return multiple.compareTo(BigDecimal.ONE) > 0;
    }
}
