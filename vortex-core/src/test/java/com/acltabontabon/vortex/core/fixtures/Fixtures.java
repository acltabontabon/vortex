package com.acltabontabon.vortex.core.fixtures;

import com.acltabontabon.vortex.core.catalog.CatalogSource;
import com.acltabontabon.vortex.core.catalog.ExpectedResponse;
import com.acltabontabon.vortex.core.catalog.HttpMethod;
import com.acltabontabon.vortex.core.catalog.Operation;
import com.acltabontabon.vortex.core.catalog.OperationBinding;
import com.acltabontabon.vortex.core.catalog.ParameterLocation;
import com.acltabontabon.vortex.core.catalog.ParameterSpec;
import com.acltabontabon.vortex.core.catalog.PayloadProvenance;
import com.acltabontabon.vortex.core.catalog.RequestBodySpec;
import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.environment.EnvironmentCapabilities;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.environment.TestClassification;
import com.acltabontabon.vortex.core.intent.TestIntent;
import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.metrics.LatencyPercentiles;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricSeries;
import com.acltabontabon.vortex.core.metrics.OperationMetrics;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.OperationKeys;
import com.acltabontabon.vortex.core.data.FixedValue;
import com.acltabontabon.vortex.core.data.RequestData;
import com.acltabontabon.vortex.core.plan.PlannedOperation;
import com.acltabontabon.vortex.core.plan.RunnerKind;
import com.acltabontabon.vortex.core.plan.ScriptSource;
import com.acltabontabon.vortex.core.project.Project;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.target.ExternalEndpointTarget;
import com.acltabontabon.vortex.core.workload.Workload;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.EnvironmentId;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.shared.WorkloadId;
import com.acltabontabon.vortex.core.shared.TestPlanId;
import com.acltabontabon.vortex.core.threshold.ErrorRateThreshold;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.RateAllocation;
import com.acltabontabon.vortex.core.workload.RateAllocator;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.WeightedOperation;
import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared test data, published as a test-jar so adapter modules can build realistic domain objects
 * without each inventing their own slightly different notion of a plan.
 *
 * <p>Modelled on a neutral {@code checkout-service} rather than any real organisation's naming.
 */
public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");

    public static final OperationId GET_ACCOUNT = OperationId.of("getAccount");
    public static final OperationId CREATE_ORDER = OperationId.of("createOrder");
    public static final OperationId GET_ORDER = OperationId.of("getOrder");
    public static final OperationId CANCEL_ORDER = OperationId.of("cancelOrder");

    private static final RateAllocator ALLOCATOR = new RateAllocator();

    private Fixtures() {
    }

    // ------------------------------------------------------------------ catalog

    public static Operation getAccountOperation() {
        return new Operation(GET_ACCOUNT, "getAccount", HttpMethod.GET, "/accounts/{id}",
                "Fetch an account", List.of("Accounts"),
                List.of(new ParameterSpec("id", ParameterLocation.PATH, true, "string", "acc-1001")),
                null);
    }

    public static Operation createOrderOperation() {
        return new Operation(CREATE_ORDER, "createOrder", HttpMethod.POST, "/orders",
                "Place an order", List.of("Orders"), List.of(),
                RequestBodySpec.schemaGenerated("application/json",
                        "{\"accountId\":\"acc-1001\",\"amount\":42.5}"));
    }

    public static Operation getOrderOperation() {
        return new Operation(GET_ORDER, "getOrder", HttpMethod.GET, "/orders/{id}",
                "Fetch an order", List.of("Orders"),
                List.of(new ParameterSpec("id", ParameterLocation.PATH, true, "string", "ord-1")),
                null);
    }

    public static Operation cancelOrderOperation() {
        return new Operation(CANCEL_ORDER, "cancelOrder", HttpMethod.POST, "/orders/{id}/cancel",
                "Cancel an order", List.of("Orders"),
                List.of(new ParameterSpec("id", ParameterLocation.PATH, true, "string", "ord-1")),
                null);
    }

    public static ServiceCatalog catalog() {
        return new ServiceCatalog(CatalogSource.OPENAPI, "http://localhost:8080/openapi.yaml",
                "checkout-service", "1.0.0", NOW,
                List.of(getAccountOperation(), createOrderOperation(), getOrderOperation(),
                        cancelOrderOperation()),
                List.of());
    }

    /** Bindings that approve the mutating operations, so a plan can actually be resolved. */
    public static List<OperationBinding> reviewedBindings() {
        return List.of(
                OperationBinding.of(CREATE_ORDER).withReviewed(true),
                OperationBinding.of(CANCEL_ORDER).withReviewed(true));
    }

    // ------------------------------------------------------------------ workload

    /** A realistic aggregate composition: mostly reads, a little writing. */
    public static OperationMix operationMix() {
        return OperationMix.of(List.of(
                WeightedOperation.of(GET_ACCOUNT, 70),
                WeightedOperation.of(GET_ORDER, 30)));
    }

    public static OperationMix fourWayMix() {
        return OperationMix.of(List.of(
                WeightedOperation.of(CREATE_ORDER, 15),
                WeightedOperation.of(GET_ORDER, 25),
                WeightedOperation.of(GET_ACCOUNT, 55),
                WeightedOperation.of(CANCEL_ORDER, 5)));
    }

    // ------------------------------------------------------------------ environments

    public static Environment localEnvironment() {
        return new Environment(EnvironmentId.of("local"), "local", EnvironmentType.LOCAL_ISOLATED,
                new ExternalEndpointTarget(TargetUrl.of("http://localhost:8080")),
                EnvironmentCapabilities.localIsolated(), DependencyMode.MOCKED, Map.of());
    }

    public static Environment performanceEnvironment() {
        return new Environment(EnvironmentId.of("perf"), "performance", EnvironmentType.PERFORMANCE,
                new ExternalEndpointTarget(TargetUrl.of("https://checkout.perf.example.com")),
                new EnvironmentCapabilities(false, false, true, true, true),
                DependencyMode.REAL, Map.of("Authorization", "${VORTEX_AUTH_TOKEN}"));
    }

    // ------------------------------------------------------------------ objectives

    public static ThresholdSet thresholds() {
        return ThresholdSet.of(
                LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)),
                LatencyThreshold.of(Percentile.P99, Duration.ofMillis(1000)),
                ErrorRateThreshold.ofPercent(1));
    }

    // ------------------------------------------------------------------ workloads

    public static Workload averageLoadWorkload() {
        return workload("average-load", TestType.AVERAGE_LOAD, operationMix(),
                ConstantArrivalRateShape.of(20, Duration.ofMinutes(10)));
    }

    public static Workload breakpointWorkload() {
        return workload("capacity", TestType.BREAKPOINT, operationMix(), breakpointShape());
    }

    public static Workload singleOperationWorkload() {
        return workload("submission-load", TestType.AVERAGE_LOAD, OperationMix.single(CREATE_ORDER),
                ConstantArrivalRateShape.of(50, Duration.ofMinutes(30)));
    }

    public static Workload concurrencyWorkload() {
        return workload("batch-workers", TestType.AVERAGE_LOAD, OperationMix.single(GET_ORDER),
                ConstantConcurrencyShape.of(50, Duration.ofMinutes(10)));
    }

    public static Workload workload(String name, TestType type, OperationMix mix, LoadShape shape) {
        return new Workload(WorkloadId.of(name), name, "", "", type, mix, shape,
                ThresholdSet.empty(), WorkloadSource.manual(), Map.of());
    }

    public static RampingArrivalRateShape breakpointShape() {
        return new RampingArrivalRateShape(RequestsPerSecond.of(20), List.of(
                Stage.ofRate(50, Duration.ofMinutes(5)),
                Stage.ofRate(100, Duration.ofMinutes(5)),
                Stage.ofRate(150, Duration.ofMinutes(5)),
                Stage.ofRate(200, Duration.ofMinutes(5))));
    }

    // ------------------------------------------------------------------ project

    public static Project project() {
        return new Project(ProjectId.of("checkout"), "checkout-service",
                "Sample service", "/tmp/checkout", "2.17.0", NOW, NOW);
    }

    public static ProjectConfiguration configuration() {
        return new ProjectConfiguration(ProjectConfiguration.CURRENT_VERSION,
                "checkout-service", "Order placement and lookup", "2.17.0",
                reviewedBindings(),
                List.of(localEnvironment()),
                List.of(averageLoadWorkload(), breakpointWorkload(), concurrencyWorkload()),
                thresholds(),
                null,
                null,
                null);
    }

    /** A service whose dependencies are described by a Compose file the repository owns. */
    public static LocalLabSettings localLab() {
        return new LocalLabSettings("compose.yaml");
    }

    // ------------------------------------------------------------------ plans

    public static EffectiveTestPlan plan() {
        return plan(TestType.AVERAGE_LOAD, ConstantArrivalRateShape.of(20, Duration.ofMinutes(10)));
    }

    public static EffectiveTestPlan breakpointPlan() {
        return plan(TestType.BREAKPOINT, breakpointShape());
    }

    public static EffectiveTestPlan concurrencyPlan() {
        return plan(TestType.AVERAGE_LOAD, ConstantConcurrencyShape.of(50, Duration.ofMinutes(10)),
                OperationMix.single(GET_ORDER));
    }

    public static EffectiveTestPlan plan(TestType type, LoadShape workload) {
        return plan(type, workload, operationMix());
    }

    /**
     * Builds a plan the way {@code PlanResolver} does: keys assigned once, rates allocated by
     * dividing one total.
     *
     * <p>Deliberately not hand-written per-operation rates. A fixture that fabricated an
     * {@code AllocatedRate} would let a test pass while the production path — the only path that can
     * produce one — was broken.
     */
    public static EffectiveTestPlan plan(TestType type, LoadShape workload, OperationMix mix) {
        Map<OperationId, String> keys = OperationKeys.assign(mix.operationIds());
        Map<OperationId, com.acltabontabon.vortex.core.workload.AllocatedRate> rates = new LinkedHashMap<>();
        if (workload.model() == WorkloadModel.OPEN) {
            RateAllocation allocation =
                    ALLOCATOR.allocate((RequestsPerSecond) workload.peakLevel(), mix);
            allocation.allocations().forEach(rate -> rates.put(rate.operationId(), rate));
        }

        List<PlannedOperation> operations = new ArrayList<>();
        for (OperationId operationId : mix.operationIds()) {
            operations.add(plannedOperation(operationId, keys.get(operationId), rates.get(operationId)));
        }

        return new EffectiveTestPlan(
                TestPlanId.of("plan1"), ProjectId.of("checkout"), "checkout-service", "2.17.0",
                TestIntent.defaultFor(type), type.name().toLowerCase(java.util.Locale.ROOT), "",
                type, workload.model(), workload.peakLevel(), workload.stages(), operations,
                List.of(), WorkloadSource.manual(), thresholds(),
                "local", EnvironmentType.LOCAL_ISOLATED,
                new ExternalEndpointTarget(TargetUrl.of("http://localhost:8080")),
                TargetUrl.of("http://localhost:8080"), TargetUrl.of("http://localhost:8080"), "",
                DependencyMode.MOCKED, TestClassification.ISOLATED,
                Map.of(), Map.of(), RunnerKind.LOCAL_BINARY, ScriptSource.GENERATED, List.of(), null,
                com.acltabontabon.vortex.core.validity.ValidityPolicy.defaults(), project().workspacePath())
                .withComputedFingerprint();
    }

    /**
     * The allocated rate for an operation driven alone at {@code rate}.
     *
     * <p>Goes through the allocator because that is the only thing that can produce an
     * {@code AllocatedRate} — its constructor is package-private precisely so a test cannot
     * fabricate one and pass while the production path is broken.
     */
    public static com.acltabontabon.vortex.core.workload.AllocatedRate soleAllocation(OperationId operationId,
            double rate) {
        return ALLOCATOR.allocate(RequestsPerSecond.of(rate), OperationMix.single(operationId))
                .forOperation(operationId).orElseThrow();
    }

    /** Request data carrying one fixed path value, the shape most fixtures need. */
    private static RequestData pathData(String name, String value) {
        return new RequestData(Map.of(name, new FixedValue(value)), Map.of(), Map.of(), "", Map.of());
    }

    private static PlannedOperation plannedOperation(OperationId operationId, String key,
            com.acltabontabon.vortex.core.workload.AllocatedRate rate) {

        return switch (operationId.value()) {
            case "getAccount" -> PlannedOperation.driving(GET_ACCOUNT, "GET /accounts/{id}", key,
                    HttpMethod.GET, "/accounts/{id}", pathData("id", "acc-1001"),
                    PayloadProvenance.SCHEMA_GENERATED, ExpectedResponse.DEFAULT, rate);
            case "getOrder" -> PlannedOperation.driving(GET_ORDER, "GET /orders/{id}", key,
                    HttpMethod.GET, "/orders/{id}", pathData("id", "ord-1"),
                    PayloadProvenance.SCHEMA_GENERATED, ExpectedResponse.DEFAULT, rate);
            case "createOrder" -> PlannedOperation.driving(CREATE_ORDER, "POST /orders", key,
                    HttpMethod.POST, "/orders",
                    RequestData.ofBody("{\"accountId\":\"acc-1001\",\"amount\":42.5}"),
                    PayloadProvenance.SCHEMA_GENERATED, ExpectedResponse.ofStatuses(200, 201), rate);
            case "cancelOrder" -> PlannedOperation.driving(CANCEL_ORDER, "POST /orders/{id}/cancel", key,
                    HttpMethod.POST, "/orders/{id}/cancel", pathData("id", "ord-1"),
                    PayloadProvenance.SCHEMA_GENERATED, ExpectedResponse.DEFAULT, rate);
            default -> throw new IllegalArgumentException("No fixture for operation " + operationId);
        };
    }

    // ------------------------------------------------------------------ results

    /** Measured results with the given p95, all objectives otherwise comfortably met. */
    public static MeasuredResults results(long p95Millis, double errorFraction) {
        long requests = 30_852;
        return new MeasuredResults(
                new TimeWindow(NOW, NOW.plus(Duration.ofMinutes(10))),
                RequestsPerSecond.of(20),
                RequestsPerSecond.of(19.8),
                requests,
                Math.round(requests * errorFraction),
                LatencyPercentiles.builder()
                        .atMillis(50, p95Millis * 0.25)
                        .atMillis(95, p95Millis)
                        .atMillis(99, p95Millis * 1.6)
                        .meanMillis(p95Millis * 0.4)
                        .maximumMillis(p95Millis * 3)
                        .build(),
                Map.of(), MetricSeries.empty(), List.of());
    }

    /** Per-operation measurements, so operation-scoped objectives have something to evaluate. */
    public static Map<OperationId, OperationMetrics> perOperation(long accountP95Millis,
            long orderP95Millis) {

        Map<OperationId, OperationMetrics> metrics = new LinkedHashMap<>();
        metrics.put(GET_ACCOUNT, new OperationMetrics(GET_ACCOUNT, "GET /accounts/{id}",
                RequestsPerSecond.of(14), RequestsPerSecond.of(13.9), 8_340, 4,
                LatencyPercentiles.builder().atMillis(95, accountP95Millis).build()));
        metrics.put(GET_ORDER, new OperationMetrics(GET_ORDER, "GET /orders/{id}",
                RequestsPerSecond.of(6), RequestsPerSecond.of(5.9), 3_540, 2,
                LatencyPercentiles.builder().atMillis(95, orderP95Millis).build()));
        return metrics;
    }

    public static MeasuredResults resultsWithOperations(long p95Millis, double errorFraction,
            Map<OperationId, OperationMetrics> perOperation) {

        MeasuredResults base = results(p95Millis, errorFraction);
        return new MeasuredResults(base.window(), base.targetLoad(), base.achievedRate(),
                base.requests(), base.failures(), base.latency(), perOperation, base.series(),
                base.observations());
    }

    /**
     * A time series that degrades as offered load rises, mimicking a service whose bounded worker
     * pool starts queueing.
     */
    public static MetricSeries degradingSeries(List<Stage> stages) {
        List<SamplePoint> points = new ArrayList<>();
        Instant cursor = NOW;
        for (Stage stage : stages) {
            long buckets = stage.duration().toSeconds() / 5;
            double level = stage.target().asDouble();
            // Latency stays flat until the pool saturates around 120/sec, then climbs sharply.
            double p95 = level < 120 ? 100 + level : 100 + level + Math.pow(level - 120, 1.9);
            double errors = level < 150 ? 0.001 : Math.min(0.3, (level - 150) / 200.0);
            double achieved = level < 150 ? level * 0.99 : 150 * 0.99;
            LoadLevel target = stage.target();
            for (int i = 0; i < buckets; i++) {
                points.add(new SamplePoint(cursor, Duration.ofSeconds(5),
                        RequestsPerSecond.of(achieved),
                        ErrorRate.ofFraction(errors),
                        Duration.ofMillis(Math.round(p95)),
                        target));
                cursor = cursor.plusSeconds(5);
            }
        }
        return new MetricSeries(Duration.ofSeconds(5), points);
    }
}
