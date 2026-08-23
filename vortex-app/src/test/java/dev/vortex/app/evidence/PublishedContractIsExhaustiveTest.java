package dev.vortex.app.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.metrics.TelemetryAvailability;
import dev.vortex.core.resource.LimitBasis;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.validity.RunQuality;
import dev.vortex.core.validity.ValidityEffect;
import dev.vortex.core.validity.ValidityReason;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every enum the evidence contract publishes maps to a distinct, stable wire string.
 *
 * <p>{@link EvidenceEnvelope} publishes each of them through an exhaustive switch with no default,
 * so adding a constant already stops this module compiling. That covers the case a compiler can see.
 * This covers the two it cannot: a constant mapped to the same string as another — which silently
 * merges two states in every export — and one mapped to blank.
 *
 * <p>The wire strings are deliberately independent of the Java names, so that renaming a constant
 * does not change what a consumer pinned against. Which means nothing but a test can notice when two
 * of them collide.
 */
class PublishedContractIsExhaustiveTest {

    /** Every enum the envelope publishes, and the private mapper that publishes it. */
    private static final List<Class<? extends Enum<?>>> PUBLISHED = List.of(
            RunQuality.class, ValidityReason.class, ValidityEffect.class,
            ResourceKind.class, ResourceScope.class, LimitBasis.class,
            TelemetryAvailability.class);

    @Test
    @DisplayName("every constant of every published enum maps to a distinct, non-blank string")
    void everyConstantIsPublishedDistinctly() throws Exception {
        for (Class<? extends Enum<?>> type : PUBLISHED) {
            Method mapper = mapperFor(type);
            mapper.setAccessible(true);

            Set<String> published = new HashSet<>();
            for (Object constant : type.getEnumConstants()) {
                String wire = (String) mapper.invoke(null, constant);

                assertThat(wire)
                        .as("%s.%s publishes a name", type.getSimpleName(), constant)
                        .isNotBlank();
                assertThat(published.add(wire))
                        .as("%s.%s publishes '%s', which another constant already uses — two "
                                + "states would be indistinguishable in every export",
                                type.getSimpleName(), constant, wire)
                        .isTrue();
            }
            assertThat(published).hasSize(type.getEnumConstants().length);
        }
    }

    /**
     * The envelope's mapper for one enum, found by parameter type.
     *
     * <p>By type rather than by name so that renaming a mapper does not quietly stop this test
     * checking anything — it fails instead, which is the intent.
     */
    private Method mapperFor(Class<? extends Enum<?>> type) {
        return Arrays.stream(EvidenceEnvelope.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == String.class)
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getParameterTypes()[0] == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "EvidenceEnvelope publishes no mapper for " + type.getSimpleName()
                                + ". Every enum in the contract needs one, through an exhaustive "
                                + "switch, so a new constant cannot reach an export unnamed."));
    }
}
