package dev.vortex.core.application;

import dev.vortex.core.catalog.Operation;
import dev.vortex.core.catalog.ParameterLocation;
import dev.vortex.core.catalog.ParameterSpec;
import dev.vortex.core.catalog.SchemaHint;
import dev.vortex.core.data.Generator;
import dev.vortex.core.data.RequestValueTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the specification hints a value should look like — offered, never applied.
 *
 * <p>A schema that declares {@code format: uuid} has told Vortex something real, and pretending
 * otherwise makes a user configure by hand what the machine already knew. But it has told Vortex
 * about <em>shape</em>, and a request needs a value that the service will actually accept.
 *
 * <p>The two are not the same, and the gap is where confidently-wrong behaviour lives. A UUID-shaped
 * {@code customerId} could be:
 *
 * <ul>
 *   <li>generated fresh, if the endpoint creates the customer;</li>
 *   <li>drawn from a dataset, if it must be a customer who already exists;</li>
 *   <li>held constant, if the test is about one customer's behaviour under load.</li>
 * </ul>
 *
 * <p>Those are three different tests and the schema distinguishes none of them. So every suggestion
 * carries the reason it was made, in the specification's own terms, and a person decides. Nothing
 * here is ever written into a configuration without somebody accepting it.
 *
 * @param target   where the value is carried
 * @param field    the parameter name or body field it applies to
 * @param generator the generator suggested, or null when the suggestion is a constrained choice
 * @param choices  the values the specification permits, when it constrains them
 * @param reason   why, quoting what the specification actually said
 */
public record RequestDataSuggestions(
        RequestValueTarget target,
        String field,
        Generator generator,
        List<String> choices,
        String reason) {

    public RequestDataSuggestions {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public Optional<Generator> generatorIfPresent() {
        return Optional.ofNullable(generator);
    }

    public boolean isConstrainedChoice() {
        return !choices.isEmpty();
    }

    /** Every suggestion an operation's specification supports. */
    public static List<RequestDataSuggestions> forOperation(Operation operation) {
        List<RequestDataSuggestions> suggestions = new ArrayList<>();
        for (ParameterSpec parameter : operation.parameters()) {
            RequestValueTarget target = targetOf(parameter.location());
            if (target == null) {
                continue;
            }
            from(target, parameter.hint()).ifPresent(suggestions::add);
        }
        operation.body().ifPresent(body -> body.fields().forEach(hint ->
                from(RequestValueTarget.BODY_FIELD, hint).ifPresent(suggestions::add)));
        return List.copyOf(suggestions);
    }

    /**
     * The suggestion one declared shape supports, if any.
     *
     * <p>Absence is the common and correct answer. A field declared only {@code type: string} says
     * nothing worth acting on, and offering "Random string" for it would be Vortex making noise
     * rather than a suggestion — the user knows better than the schema does, and a suggestion nobody
     * should accept devalues the ones they should.
     */
    static Optional<RequestDataSuggestions> from(RequestValueTarget target, SchemaHint hint) {
        if (hint.isConstrained()) {
            return Optional.of(new RequestDataSuggestions(target, hint.field(), null,
                    hint.enumValues(),
                    "the specification permits only these values"));
        }
        return generatorFor(hint).map(generator -> new RequestDataSuggestions(target, hint.field(),
                generator, List.of(),
                "the specification declares format: " + hint.format()));
    }

    private static Optional<Generator> generatorFor(SchemaHint hint) {
        if (!hint.hasFormat()) {
            return Optional.empty();
        }
        return switch (hint.format().toLowerCase(Locale.ROOT)) {
            case "uuid" -> Optional.of(Generator.UUID);
            case "date-time" -> Optional.of(Generator.TIMESTAMP);
            case "date" -> Optional.of(Generator.DATE);
            case "email", "idn-email" -> Optional.of(Generator.EMAIL);
            // Everything else — uri, hostname, ipv4, byte, binary, password — is deliberately not
            // suggested. Vortex has no generator that produces one, and a suggestion it cannot
            // fulfil is worse than silence.
            default -> Optional.empty();
        };
    }

    private static RequestValueTarget targetOf(ParameterLocation location) {
        return switch (location) {
            case PATH -> RequestValueTarget.PATH;
            case QUERY -> RequestValueTarget.QUERY;
            case HEADER -> RequestValueTarget.HEADER;
            // A cookie is carried in a header Vortex does not model separately, and offering a
            // suggestion for a position it cannot bind would be an empty gesture.
            case COOKIE -> null;
        };
    }
}
