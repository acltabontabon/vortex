package dev.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.environment.TargetUrl;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.target.ExternalEndpointTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LegacyExecutionTargetNormalizer} against a real, hand-captured pre-{@code executionTarget}
 * document.
 *
 * <p>{@code LEGACY_PLAN_JSON} below is not built from today's domain model — it is the literal
 * output {@code EffectiveTestPlan} produced for {@code Fixtures.plan()} before this feature added
 * {@code executionTarget}, with that one field removed by hand (every other field, including the
 * pre-existing {@code configuredTarget}/{@code effectiveTarget} pair, is exactly what was captured).
 * This is what is actually sitting in a {@code plan_json} column written by an older Vortex.
 */
class LegacyExecutionTargetNormalizerTest {

    private final ObjectMapper json = JsonDocuments.mapper();

    // Captured from JsonDocuments.mapper().writerWithDefaultPrettyPrinter()
    // .writeValueAsString(Fixtures.plan()), then the "executionTarget" field removed by hand — that
    // field is the one and only thing this feature adds to a stored plan. Every other field is
    // untouched, byte for byte.
    private static final String LEGACY_PLAN_JSON = """
            {
              "id" : {
                "value" : "plan1"
              },
              "projectId" : {
                "value" : "checkout"
              },
              "projectName" : "checkout-service",
              "serviceVersion" : "2.17.0",
              "intent" : {
                "type" : "AVERAGE_LOAD",
                "objective" : "",
                "custom" : false
              },
              "workloadName" : "average_load",
              "workloadDescription" : "",
              "testType" : "AVERAGE_LOAD",
              "workloadModel" : "OPEN",
              "peakLevel" : {
                "unit" : "requestsPerSecond",
                "value" : 20.000,
                "positive" : true
              },
              "stages" : [ {
                "target" : {
                  "unit" : "requestsPerSecond",
                  "value" : 20.000,
                  "positive" : true
                },
                "duration" : 600.000000000
              } ],
              "operations" : [ {
                "operationId" : {
                  "value" : "getAccount"
                },
                "name" : "GET /accounts/{id}",
                "k6ScenarioKey" : "getaccount",
                "method" : "GET",
                "pathTemplate" : "/accounts/{id}",
                "requestData" : {
                  "pathValues" : {
                    "id" : {
                      "source" : "fixed",
                      "literal" : "acc-1001",
                      "dynamic" : false
                    }
                  },
                  "queryValues" : { },
                  "headers" : { },
                  "body" : "",
                  "bodyValues" : { },
                  "empty" : false
                },
                "provenance" : "SCHEMA_GENERATED",
                "expect" : {
                  "statuses" : [ ],
                  "default" : true
                },
                "share" : 0.700000,
                "arrivalRate" : {
                  "unit" : "requestsPerSecond",
                  "value" : 14.000,
                  "positive" : true
                },
                "mutating" : false
              }, {
                "operationId" : {
                  "value" : "getOrder"
                },
                "name" : "GET /orders/{id}",
                "k6ScenarioKey" : "getorder",
                "method" : "GET",
                "pathTemplate" : "/orders/{id}",
                "requestData" : {
                  "pathValues" : {
                    "id" : {
                      "source" : "fixed",
                      "literal" : "ord-1",
                      "dynamic" : false
                    }
                  },
                  "queryValues" : { },
                  "headers" : { },
                  "body" : "",
                  "bodyValues" : { },
                  "empty" : false
                },
                "provenance" : "SCHEMA_GENERATED",
                "expect" : {
                  "statuses" : [ ],
                  "default" : true
                },
                "share" : 0.300000,
                "arrivalRate" : {
                  "unit" : "requestsPerSecond",
                  "value" : 6.000,
                  "positive" : true
                },
                "mutating" : false
              } ],
              "datasets" : [ ],
              "workloadSource" : {
                "kind" : "MANUAL",
                "detail" : "",
                "observation" : {
                  "window" : false,
                  "point" : false,
                  "known" : false
                },
                "derivation" : "",
                "productionInformed" : false
              },
              "thresholds" : {
                "thresholds" : [ {
                  "kind" : "latency",
                  "scope" : {
                    "overall" : true
                  },
                  "percentile" : {
                    "basisPoints" : 9500
                  },
                  "maximum" : 0.500000000
                }, {
                  "kind" : "latency",
                  "scope" : {
                    "overall" : true
                  },
                  "percentile" : {
                    "basisPoints" : 9900
                  },
                  "maximum" : 1.000000000
                }, {
                  "kind" : "errorRate",
                  "scope" : {
                    "overall" : true
                  },
                  "maximum" : {
                    "fraction" : 0.010000
                  }
                } ],
                "empty" : false
              },
              "environmentName" : "local",
              "environmentType" : "LOCAL_ISOLATED",
              "configuredTarget" : {
                "value" : "http://localhost:8080",
                "loopback" : true
              },
              "effectiveTarget" : {
                "value" : "http://localhost:8080",
                "loopback" : true
              },
              "targetRewriteReason" : "",
              "dependencyMode" : "MOCKED",
              "classification" : "ISOLATED",
              "headers" : { },
              "k6Options" : { },
              "runner" : "LOCAL_BINARY",
              "scriptSource" : "GENERATED",
              "safetyDecisions" : [ ],
              "fingerprint" : {
                "algorithm" : "SHA-256",
                "hash" : "e5bdfbeb1c673bb90801150514f09b95a79d4ec744ff6d6f8a91ebed64328409"
              },
              "validityPolicy" : {
                "minimumRunDuration" : {
                  "SPIKE" : 0.0,
                  "AVERAGE_LOAD" : 300.000000000,
                  "BREAKPOINT" : 120.000000000,
                  "SMOKE" : 0.0,
                  "SOAK" : 1800.000000000,
                  "STRESS" : 120.000000000
                },
                "sustainDuration" : {
                  "STRESS" : 120.000000000,
                  "BREAKPOINT" : 120.000000000,
                  "AVERAGE_LOAD" : 300.000000000,
                  "SOAK" : 1800.000000000
                },
                "minimumRequestsPerStage" : 100,
                "materialShortfallFraction" : 0.1,
                "telemetryWindowTolerance" : 30.000000000,
                "targetUnavailableShare" : 0.05
              },
              "singleOperation" : false
            }""";

    @Test
    @DisplayName("a plan_json written before executionTarget existed is given one, synthesised "
            + "from its legacy configuredTarget")
    void synthesisesTheMissingExecutionTarget() throws Exception {
        JsonNode raw = json.readTree(LEGACY_PLAN_JSON);
        assertThat(raw.has("executionTarget")).as("fixture must genuinely lack the field").isFalse();

        JsonNode normalized = LegacyExecutionTargetNormalizer.normalize(raw);
        EffectiveTestPlan plan = json.treeToValue(normalized, EffectiveTestPlan.class);

        assertThat(plan.executionTarget())
                .isEqualTo(new ExternalEndpointTarget(TargetUrl.of("http://localhost:8080")));
        // The legacy fields the normalizer read from are themselves untouched by it.
        assertThat(plan.configuredTargetIfPresent()).hasValue(TargetUrl.of("http://localhost:8080"));
        assertThat(plan.effectiveTargetIfPresent()).hasValue(TargetUrl.of("http://localhost:8080"));
    }

    @Test
    @DisplayName("a plan_json that already has executionTarget passes through unchanged")
    void isANoOpWhenExecutionTargetIsAlreadyPresent() throws Exception {
        String currentShapePlanJson = json.writeValueAsString(Fixtures.plan());
        JsonNode raw = json.readTree(currentShapePlanJson);
        assertThat(raw.has("executionTarget")).isTrue();

        JsonNode normalized = LegacyExecutionTargetNormalizer.normalize(raw);

        assertThat(normalized).isEqualTo(raw);
        EffectiveTestPlan plan = json.treeToValue(normalized, EffectiveTestPlan.class);
        assertThat(plan.executionTarget()).isEqualTo(Fixtures.plan().executionTarget());
    }
}
