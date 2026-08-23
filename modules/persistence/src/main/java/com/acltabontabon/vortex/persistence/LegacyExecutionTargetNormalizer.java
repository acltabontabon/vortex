package com.acltabontabon.vortex.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Fills in the one field a historical {@code plan_json} blob does not have.
 *
 * <p>{@code EffectiveTestPlan.executionTarget} did not exist before this feature, so every plan
 * stored before it carries no such field — only the legacy {@code configuredTarget} address, which
 * has always been present, because {@link com.acltabontabon.vortex.core.target.ExternalEndpointTarget} was the
 * only target type that ever existed until now. This class states that translation explicitly, as
 * code a reader can follow start to finish, rather than leaning on Jackson's default-value tolerance
 * or a {@code @JsonCreator} fallback to "just happen" to produce the right answer.
 *
 * <p>A {@code plan_json} written after {@code executionTarget} shipped already has the field, so this
 * is a strict no-op for it: the whole job here is filling one specific historical gap, never touching
 * current data.
 */
final class LegacyExecutionTargetNormalizer {

    // Mirrors VortexJacksonModule.ExecutionTargetMixin exactly: @JsonTypeInfo(property = "kind") +
    // @JsonSubTypes.Type(value = ExternalEndpointTarget.class, name = "externalEndpoint"). If that
    // mixin's discriminator ever changes, this synthesis has to move with it.
    private static final String EXECUTION_TARGET_FIELD = "executionTarget";
    private static final String KIND_PROPERTY = "kind";
    private static final String EXTERNAL_ENDPOINT_KIND = "externalEndpoint";
    private static final String ENDPOINT_FIELD = "endpoint";
    private static final String CONFIGURED_TARGET_FIELD = "configuredTarget";
    private static final String TARGET_URL_VALUE_FIELD = "value";

    private LegacyExecutionTargetNormalizer() {
    }

    /**
     * Returns a tree guaranteed to carry an {@code executionTarget} field, synthesising one from the
     * legacy {@code configuredTarget} address when the tree has none.
     *
     * <p>Never mutates {@code planTree} in place — the caller always binds the node this returns, and
     * the tree passed in is left exactly as it was read.
     */
    static JsonNode normalize(JsonNode planTree) {
        if (!(planTree instanceof ObjectNode plan)) {
            // Not an object at all — nothing this class understands how to fix; let EffectiveTestPlan
            // binding fail with Jackson's own diagnostic rather than guessing here.
            return planTree;
        }
        JsonNode existingTarget = plan.get(EXECUTION_TARGET_FIELD);
        if (existingTarget != null && !existingTarget.isNull()) {
            // Written after this feature shipped — pass through completely unchanged.
            return plan;
        }

        JsonNode configuredTarget = plan.get(CONFIGURED_TARGET_FIELD);
        JsonNode configuredTargetValue =
                configuredTarget == null ? null : configuredTarget.get(TARGET_URL_VALUE_FIELD);
        if (configuredTargetValue == null || configuredTargetValue.isNull()) {
            // No legacy address to synthesise from either. Leave the tree as-is: binding will fail
            // with Jackson's own "missing required field" message, which is more honest than
            // fabricating a target here.
            return plan;
        }

        ObjectNode endpoint = JsonNodeFactory.instance.objectNode();
        endpoint.put(TARGET_URL_VALUE_FIELD, configuredTargetValue.asText());

        ObjectNode executionTarget = JsonNodeFactory.instance.objectNode();
        executionTarget.put(KIND_PROPERTY, EXTERNAL_ENDPOINT_KIND);
        executionTarget.set(ENDPOINT_FIELD, endpoint);

        ObjectNode normalized = plan.deepCopy();
        normalized.set(EXECUTION_TARGET_FIELD, executionTarget);
        return normalized;
    }
}
