package dev.vortex.app.web;

import java.util.List;
import java.util.Map;

/**
 * What the interface needs in order to let somebody say where a request's values come from.
 *
 * <p>Field-for-field against {@code vortex-web/src/api/requestData.ts}.
 *
 * <p>One flat shape per value rather than a discriminated union per source. The interface renders a
 * source selector and then exactly one control beside it, and a flat record is what a form binds to
 * without the client having to reconstruct a type it is about to take apart again. The domain's
 * sealed hierarchy is the authority; this is the wire.
 */
public final class RequestDataDtos {

    private RequestDataDtos() {
    }

    /** The sources a value can have, as the interface names them. */
    public static final String FIXED = "fixed";
    public static final String GENERATED = "generated";
    public static final String DATASET = "dataset";
    public static final String ENVIRONMENT = "environment";

    /**
     * Everything needed to edit one operation's request data.
     *
     * @param values      every configurable value, in the order a request carries them
     * @param datasets    what this service has to draw from, so the selector is never empty-handed
     * @param generators  what Vortex can produce, with what each one means
     */
    public record RequestDataDto(
            String operationId,
            String label,
            String method,
            String path,
            boolean mutating,
            boolean reviewed,
            String body,
            List<ValueSlotDto> values,
            List<DatasetDto> datasets,
            List<GeneratorDto> generators) {
    }

    /**
     * One configurable value.
     *
     * @param target        HEADER, PATH, QUERY or BODY_FIELD
     * @param name          the header, parameter or dotted body field
     * @param required      whether the specification marks it required
     * @param source        which source is configured; empty when nothing is
     * @param environmentSet whether the named variable exists on this machine right now. Reported
     *                      rather than guessed at, and never the variable's value
     * @param suggestion    what the specification's schema supports, or null. A suggestion, never a
     *                      default — see {@code RequestDataSuggestions}
     */
    public record ValueSlotDto(
            String target,
            String name,
            boolean required,
            String source,
            String literal,
            String generator,
            String lifecycle,
            Long minimum,
            Long maximum,
            Integer length,
            String dataset,
            String datasetScope,
            String field,
            String environmentVariable,
            boolean environmentSet,
            SuggestionDto suggestion) {
    }

    /**
     * What the API description implies about a value's shape.
     *
     * @param reason in the specification's own terms, so a person can judge it rather than trust it
     */
    public record SuggestionDto(String source, String generator, List<String> choices, String reason) {
    }

    /**
     * A dataset, with enough of it to recognise and none of it to browse.
     *
     * @param preview          the first few records only. A dataset worth having is too big to render
     * @param promotionTarget  the exact file making this portable would write, so the interface can
     *                         say so before it happens rather than after
     * @param problem          why this dataset cannot currently be read, or empty
     */
    public record DatasetDto(
            String name,
            String scope,
            String format,
            int records,
            List<String> fields,
            String location,
            List<Map<String, Object>> preview,
            String promotionTarget,
            String problem) {
    }

    /** @param meaning what it produces, in a sentence — the tooltip, so the label can stay short */
    public record GeneratorDto(String key, String label, String meaning, boolean usesRange,
            boolean usesLength) {
    }

    /** A saved edit. Values not mentioned are removed, so clearing one is expressible. */
    public record SaveRequestDataRequest(String body, List<ValueUpdateDto> values) {
    }

    /**
     * One value, as the interface sets it.
     *
     * <p>Deliberately smaller than {@link ValueSlotDto}. Three of that record's fields are things
     * Vortex tells the interface and the interface never tells Vortex back — whether the
     * specification marks a value required, whether a variable is currently set on this machine, and
     * what the schema suggests. Sending them back would invite a client to think it could change
     * them.
     *
     * <p>Every field is nullable, because a value has one source and the fields the other three
     * sources use are absent. Which fields matter is decided by {@code source}.
     */
    public record ValueUpdateDto(
            String target,
            String name,
            String source,
            String literal,
            String generator,
            String lifecycle,
            Long minimum,
            Long maximum,
            Integer length,
            String dataset,
            String datasetScope,
            String field,
            String environmentVariable) {
    }

    /**
     * A dataset, uploaded as content.
     *
     * <p>Content rather than a path, deliberately. Vortex is reachable over HTTP, and an endpoint
     * that reads an arbitrary filesystem path on request is a local-file-read primitive with a
     * friendly name — the interface reads the file the user chose, in their browser, and sends the
     * bytes.
     */
    public record UploadDatasetRequest(String name, String format, String scope, String content) {
    }
}
