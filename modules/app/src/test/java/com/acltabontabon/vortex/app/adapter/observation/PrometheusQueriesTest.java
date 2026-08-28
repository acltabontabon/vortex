package com.acltabontabon.vortex.app.adapter.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.capacity.ObservationSource;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The exact PromQL Vortex issues — asserted directly rather than only through its effect, since the
 *  builder methods behind it are otherwise private and untestable. */
class PrometheusQueriesTest {

    private static final ObservationSource SOURCE = new ObservationSource(
            ObservationSource.Kind.PROMETHEUS, "http://prometheus.internal:9090",
            "checkout-service", Duration.ofDays(30), Map.of(), Map.of());

    private static final ObservationSource RENAMED = new ObservationSource(
            ObservationSource.Kind.PROMETHEUS, "http://prometheus.internal:9090",
            "checkout-service", Duration.ofDays(30), Map.of(),
            Map.of("service", "app", "route", "endpoint", "method", "verb"));

    @Test
    void selectorUsesTheDefaultServiceLabel() {
        assertThat(PrometheusQueries.selector(SOURCE))
                .isEqualTo("http_server_requests_seconds_count{application=\"checkout-service\"}");
    }

    @Test
    void selectorEscapesQuotesInTheServiceIdentifier() {
        var quoted = new ObservationSource(ObservationSource.Kind.PROMETHEUS,
                "http://prometheus.internal:9090", "checkout\"service", Duration.ofDays(30),
                Map.of(), Map.of());

        assertThat(PrometheusQueries.selector(quoted)).contains("checkout\\\"service");
    }

    @Test
    void selectorHonoursAnOverriddenServiceLabel() {
        assertThat(PrometheusQueries.selector(RENAMED))
                .isEqualTo("http_server_requests_seconds_count{app=\"checkout-service\"}");
    }

    @Test
    void rateExpressionSumsAcrossTheWholeSelectorAtTheGivenResolution() {
        assertThat(PrometheusQueries.rateExpression(SOURCE, Duration.ofMinutes(5)))
                .isEqualTo("sum(rate(http_server_requests_seconds_count{application=\"checkout-service\"}[300s]))");
    }

    @Test
    void averageQueryDividesTotalIncreaseByElapsedSeconds() {
        assertThat(PrometheusQueries.averageQuery(SOURCE, Duration.ofDays(1)))
                .isEqualTo("sum(increase(http_server_requests_seconds_count{application=\"checkout-service\"}[86400s])) / 86400");
    }

    @Test
    void mixQueryGroupsByTheConfiguredRouteAndMethodLabels() {
        assertThat(PrometheusQueries.mixQuery(SOURCE, Duration.ofDays(1)))
                .isEqualTo("sum by (uri, method) (increase(http_server_requests_seconds_count"
                        + "{application=\"checkout-service\"}[86400s]))");
        assertThat(PrometheusQueries.mixQuery(RENAMED, Duration.ofDays(1)))
                .contains("sum by (endpoint, verb)");
    }

    @Test
    void totalQueryIsTheUnsplitIncrease() {
        assertThat(PrometheusQueries.totalQuery(SOURCE, Duration.ofDays(1)))
                .isEqualTo("sum(increase(http_server_requests_seconds_count{application=\"checkout-service\"}[86400s]))");
    }

    @Test
    void histogramExistenceQueryAsksWhetherAnyBucketSeriesExists() {
        assertThat(PrometheusQueries.histogramExistenceQuery(SOURCE))
                .isEqualTo("count(count by (le) (http_server_requests_seconds_bucket"
                        + "{application=\"checkout-service\"}))");
    }

    @Test
    void latencyP95QueryIsOneInstantHistogramQuantileOverTheWholeWindow() {
        assertThat(PrometheusQueries.latencyP95Query(SOURCE, Duration.ofHours(1)))
                .isEqualTo("histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket"
                        + "{application=\"checkout-service\"}[3600s])))");
    }

    @Test
    void stepIsWholeSecondsEveryPrometheusVersionAccepts() {
        assertThat(PrometheusQueries.step(Duration.ofMinutes(5))).isEqualTo("300s");
        assertThat(PrometheusQueries.step(Duration.ofHours(1))).isEqualTo("3600s");
    }
}
