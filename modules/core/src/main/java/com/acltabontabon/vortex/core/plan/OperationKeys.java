package com.acltabontabon.vortex.core.plan;

import com.acltabontabon.vortex.core.shared.OperationId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Assigns each operation in a plan the k6 scenario key it will be executed under.
 *
 * <p>Vortex generates one k6 scenario per operation, which means k6's own built-in {@code workload}
 * tag already identifies the operation on every sample it emits — no Vortex-specific tag is needed,
 * and the generated script stays readable to anyone who knows k6 and nothing about Vortex.
 *
 * <p>That only works if the mapping is recorded rather than reconstructed. Keys are sanitised to
 * what k6 and JavaScript accept, and sanitising is lossy: {@code get-order} and {@code get_order}
 * both become {@code get_order}, and two different operations can collide. Recovering an operation
 * by re-sanitising and comparing strings would silently attribute one operation's latency to
 * another, which is exactly the kind of quietly-wrong number this product exists to prevent.
 *
 * <p>So keys are assigned once, here, during plan resolution; they are stored on each
 * {@code PlannedOperation}; the generator emits them verbatim; and the metrics aggregator resolves
 * tags back to operations through {@code EffectiveTestPlan.operationsByScenarioKey()}. A tag that is
 * not in that map is left unattributed rather than guessed at.
 */
public final class OperationKeys {

    /** Prefix applied when an id sanitises to something JavaScript would not accept. */
    private static final String FALLBACK_PREFIX = "op_";

    private OperationKeys() {
    }

    /**
     * Assigns a unique key to every operation, in the order given.
     *
     * @return operation id to k6 scenario key, in iteration order
     */
    public static Map<OperationId, String> assign(Iterable<OperationId> operations) {
        Map<OperationId, String> keys = new LinkedHashMap<>();
        Set<String> used = new java.util.LinkedHashSet<>();
        for (OperationId operation : operations) {
            if (keys.containsKey(operation)) {
                continue;
            }
            keys.put(operation, unique(sanitise(operation.value()), used));
        }
        return keys;
    }

    /** Lowercase, alphanumeric and underscores, never starting with a digit. */
    static String sanitise(String value) {
        StringBuilder key = new StringBuilder();
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                key.append(c);
            } else if (!key.isEmpty() && key.charAt(key.length() - 1) != '_') {
                key.append('_');
            }
        }
        while (!key.isEmpty() && key.charAt(key.length() - 1) == '_') {
            key.setLength(key.length() - 1);
        }
        if (key.isEmpty() || Character.isDigit(key.charAt(0))) {
            key.insert(0, FALLBACK_PREFIX);
        }
        return key.toString();
    }

    private static String unique(String candidate, Set<String> used) {
        if (used.add(candidate)) {
            return candidate;
        }
        for (int suffix = 2; ; suffix++) {
            String attempt = candidate + "_" + suffix;
            if (used.add(attempt)) {
                return attempt;
            }
        }
    }
}
