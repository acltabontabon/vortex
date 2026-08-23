package dev.vortex.core.data;

import dev.vortex.core.environment.SecretReferences;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Where one request value came from, said out loud and safe to publish.
 *
 * <p>Vortex records what a run measured <em>and the conditions it was measured under</em>, and what
 * the requests carried is one of those conditions. A capacity figure produced by replaying one
 * customer id ten thousand times and one produced from a dataset of ten thousand distinct customers
 * are different results, and a report that cannot tell them apart is not evidence.
 *
 * <p>The purpose is reproducibility and explanation, not an audit trail of traffic. This says a
 * value <em>came from</em> {@code customers.customerId}; it does not say which customer id the
 * fourteen-thousandth request used, and it never will. Keeping every generated value of every run
 * forever would be a different product with a different privacy story.
 *
 * <h2>Values, and which of them survive</h2>
 *
 * <ul>
 *   <li><strong>Fixed</strong> — the literal, through the same masking the export path applies to
 *       everything else, so a credential somebody pasted into a fixed field does not become the one
 *       secret Vortex published.</li>
 *   <li><strong>Generated</strong> — the generator and its lifecycle. What it produced is not
 *       recorded, and could not usefully be: it differs on every request, by design.</li>
 *   <li><strong>Dataset</strong> — the dataset and field. Not the rows.</li>
 *   <li><strong>Environment</strong> — the reference. Never the value; that exists only inside the
 *       engine's process, for as long as it runs.</li>
 * </ul>
 *
 * @param target where the value is carried
 * @param name   the header, parameter or body field it is bound to
 * @param source a sanitised description of where it comes from
 */
public record RequestValueOrigin(RequestValueTarget target, String name, String source) {

    /**
     * Every configured value in one request, in a stable order.
     *
     * <p>The literal a user typed is shown as they typed it. That is the useful behaviour and the
     * safe one for the interface, which is showing somebody their own configuration back.
     */
    public static List<RequestValueOrigin> of(RequestData requestData) {
        return of(requestData, UnaryOperator.identity());
    }

    /**
     * The same, with fixed literals passed through a sanitiser first.
     *
     * <p>Used on the way out — into a report, an export, anything that leaves the machine. A fixed
     * value is the one source whose content is arbitrary user text, so it is the one that could
     * contain a credential somebody pasted into the wrong field. The other three cannot: a generator
     * describes itself, a dataset value names a column, and an environment value is a reference
     * whose content exists only inside the engine's process.
     *
     * <p>Deliberately a function rather than a dependency on the sanitiser itself, so this stays a
     * value type and the export path keeps a single choke point.
     */
    public static List<RequestValueOrigin> of(RequestData requestData,
            UnaryOperator<String> sanitiseLiteral) {
        List<RequestValueOrigin> origins = new ArrayList<>();
        requestData.byTarget().forEach((target, values) ->
                values.forEach((name, value) ->
                        origins.add(new RequestValueOrigin(target, name,
                                describe(value, sanitiseLiteral)))));
        return List.copyOf(origins);
    }

    private static String describe(RequestValue value, UnaryOperator<String> sanitiseLiteral) {
        return switch (value) {
            // A pure ${NAME} inside a fixed literal is shown as written — it reveals nothing and
            // helps somebody read their own configuration. Anything else goes through the caller's
            // sanitiser, which on the export path masks credential-shaped text.
            case FixedValue fixed -> "fixed: " + (SecretReferences.containsReference(fixed.literal())
                    ? SecretReferences.mask(fixed.literal())
                    : sanitiseLiteral.apply(fixed.literal()));
            case GeneratedValue generated -> "generated: " + generated.generator().label()
                    + " (" + generated.lifecycle().label() + ")";
            case DatasetValue dataset -> "dataset: " + dataset.datasetName() + " / "
                    + dataset.field();
            case EnvironmentValue environment ->
                    "environment: " + String.join(", ", environment.referencedNames());
        };
    }

    /** One line, e.g. {@code x-idempotency-key (header) — generated: UUID (every request)}. */
    public String describe() {
        return name + " (" + target.label() + ") — " + source;
    }
}
