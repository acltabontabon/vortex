package dev.vortex.core.plan;

import dev.vortex.core.data.BodyFieldPath;
import dev.vortex.core.data.DatasetValue;
import dev.vortex.core.data.EnvironmentValue;
import dev.vortex.core.data.FixedValue;
import dev.vortex.core.data.GeneratedValue;
import dev.vortex.core.data.RequestData;
import dev.vortex.core.data.RequestValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * How request data participates in experiment identity.
 *
 * <p>Two runs are only compared when they tested the same experiment, and what a request
 * <em>sends</em> is unambiguously part of what was tested. A run submitting
 * {@code productCode: CREDIT_CARD} and a run submitting {@code productCode: PERSONAL_LOAN} exercise
 * different code paths against different downstream systems; reporting a latency difference between
 * them as a regression would be exactly the confidently-wrong behaviour Vortex exists to avoid.
 *
 * <p>So identity is taken over the <em>configuration</em>, in full — not over the shape of it. It is
 * not enough to record that a field is fixed; the literal is what makes it that experiment. The
 * rule per source:
 *
 * <table border="1">
 *   <caption>What each source contributes to the fingerprint</caption>
 *   <tr><th>Source</th><th>Contributes</th><th>Excludes</th></tr>
 *   <tr><td>Fixed</td><td>the literal value</td><td>—</td></tr>
 *   <tr><td>Generated</td><td>generator, lifecycle, and any range or length it reads</td>
 *       <td>every value it produced — those differ by design, on every run</td></tr>
 *   <tr><td>Dataset</td><td>name, scope, field, and the content hash of the data</td>
 *       <td>the rows themselves</td></tr>
 *   <tr><td>Environment</td><td>the reference, as written</td>
 *       <td>the resolved value — a fingerprint must never depend on a credential, and must never
 *           be a way to test a guess at one</td></tr>
 * </table>
 *
 * <p>The two exclusions are the interesting ones and they are not symmetric. A generator's output is
 * excluded because including it would make every run its own experiment and destroy comparison
 * entirely. A secret's value is excluded because it must be — but the <em>reference</em> is
 * included, so switching {@code ${STAGING_TOKEN}} for {@code ${PROD_TOKEN}} is visible as a changed
 * experiment without either value being knowable from the hash.
 *
 * <p>A dataset's content hash is included for the same reason a service version is not: the data is
 * a condition of the experiment, not the thing under test. Editing the CSV between two runs changes
 * what was measured, and Vortex should say the runs are not comparable rather than report the
 * difference as a regression.
 */
final class RequestDataIdentity {

    private RequestDataIdentity() {
    }

    /**
     * The canonical form of one operation's request data.
     *
     * @param plan the plan, consulted for the content hash of any dataset a value reads
     */
    static Map<String, Object> canonicalForm(RequestData requestData, EffectiveTestPlan plan) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("path", canonicalValues(requestData.pathValues(), plan));
        form.put("query", canonicalValues(requestData.queryValues(), plan));
        form.put("headers", canonicalValues(requestData.headers(), plan));
        form.put("body", requestData.body().isBlank() ? null : requestData.body());
        form.put("bodyFields", canonicalBodyValues(requestData.bodyValues(), plan));
        return form;
    }

    private static Map<String, Object> canonicalValues(Map<String, RequestValue> values,
            EffectiveTestPlan plan) {
        Map<String, Object> canonical = new TreeMap<>();
        values.forEach((key, value) -> canonical.put(key, canonicalValue(value, plan)));
        return canonical;
    }

    private static Map<String, Object> canonicalBodyValues(Map<BodyFieldPath, RequestValue> values,
            EffectiveTestPlan plan) {
        Map<String, Object> canonical = new TreeMap<>();
        values.forEach((path, value) -> canonical.put(path.asText(), canonicalValue(value, plan)));
        return canonical;
    }

    /**
     * One value's contribution.
     *
     * <p>Null entries are dropped by {@link CanonicalJson}, so a generator that reads no range does
     * not carry an irrelevant default into the hash — and adding a parameter to a generator that
     * ignores it never changes an existing fingerprint.
     */
    private static Object canonicalValue(RequestValue value, EffectiveTestPlan plan) {
        return switch (value) {
            case FixedValue fixed -> CanonicalJson.map(
                    "source", "fixed",
                    "value", fixed.literal());

            case GeneratedValue generated -> CanonicalJson.map(
                    "source", "generated",
                    "generator", generated.generator().name(),
                    "lifecycle", generated.lifecycle().name(),
                    "minimum", generated.generator().usesRange() ? generated.minimum() : null,
                    "maximum", generated.generator().usesRange() ? generated.maximum() : null,
                    "length", generated.generator().usesLength() ? generated.length() : null);

            case DatasetValue dataset -> CanonicalJson.map(
                    "source", "dataset",
                    "dataset", dataset.datasetName(),
                    "scope", dataset.dataset().scope().name(),
                    "field", dataset.field(),
                    // Absent rather than empty when the plan does not carry the dataset: a plan read
                    // back from an older execution predates dataset support, and an empty string
                    // would be a claim about its contents rather than an admission of not knowing.
                    "contentHash", plan.dataset(dataset.dataset())
                            .map(PlannedDataset::contentHash)
                            .filter(hash -> !hash.isBlank())
                            .orElse(null));

            // The reference as written, never the resolved value. Same rule the plan's own headers
            // have always followed.
            case EnvironmentValue environment -> CanonicalJson.map(
                    "source", "environment",
                    "reference", environment.template());
        };
    }
}
