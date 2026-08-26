package com.acltabontabon.vortex.dynatrace;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * The raw answer to a {@link DynatraceTelemetryQuery}, before Vortex has decided whether to trust it.
 *
 * <p>Deliberately untyped beyond "structured JSON" — an MCP tool result is external data, and what
 * shape it takes is exactly what {@code TelemetryNormalizer} exists to check, not this record.
 *
 * @param payload   the tool result's structured content, parsed as JSON
 * @param wasStructured whether the tool returned genuinely structured data (a JSON object/array) as
 *                  opposed to a single block of prose text — the first thing normalization checks,
 *                  since an MCP tool that summarizes instead of answering is exactly the failure mode
 *                  that must never become evidence
 */
public record DynatraceTelemetryResult(JsonNode payload, boolean wasStructured) {

    public DynatraceTelemetryResult {
        Objects.requireNonNull(payload, "payload");
    }
}
