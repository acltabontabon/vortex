package dev.vortex.core.evidence;

import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.validity.RunQualityAssessment;
import dev.vortex.core.plan.ToolVersions;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What produced this document, and from what.
 *
 * <p>Answers one question: <em>what exactly produced this result?</em> A performance claim is worth
 * what its reproducibility is worth, and a report that has travelled to a ticket or a release review
 * has left the machine that could otherwise answer that.
 *
 * <p>Contains no secrets, no tokens and no arbitrary environment. Everything here is either a
 * version, an identifier, a timestamp or a query — sanitised before it arrives.
 *
 * @param schemaVersion    the evidence contract this document conforms to
 * @param configurationHash the plan fingerprint, so two runs of the same experiment are recognisable
 * @param evidenceQueries  the observability queries that were issued, for a reader who wants to
 *                         re-run them
 * @param generatedAt      when this document was produced, which is not when the run happened
 * @param reproductionCommand what running this test again requires — the workload and environment
 * @param secretReferences  the environment variables the run depended on, by name only.
 *                          Masking a header costs a reader the one thing they need to run the
 *                          test again; naming the variable gives it back, and a name is not a
 *                          secret
 */
public record EvidenceProvenance(
        String schemaVersion,
        ToolVersions toolVersions,
        String configurationHash,
        Instant startedAt,
        Instant finishedAt,
        TimeWindow observabilityWindow,
        List<String> evidenceQueries,
        String artifactDirectory,
        List<String> artifactNames,
        String reproductionCommand,
        List<String> secretReferences,
        Instant generatedAt,
        HostShape host,
        TelemetryCoverage telemetry,
        RunQualityAssessment quality) {

    /**
     * The version of the evidence contract. Exports carry it; consumers pin against it.
     *
     * <p>Bumped to {@code /2} by Phase 4, which added the host's shape, which providers were
     * consulted and what they could supply, and whether the experiment was carried out as
     * specified. Pre-1.0 there is no v1 reader and no dual support: the version is bumped, and files
     * already written stay tagged with the contract they were written under.
     */
    public static final String SCHEMA_VERSION = "vortex.evidence/2";

    public EvidenceProvenance {
        Objects.requireNonNull(generatedAt, "generatedAt");
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        toolVersions = toolVersions == null ? ToolVersions.unknown() : toolVersions;
        configurationHash = configurationHash == null ? "" : configurationHash;
        evidenceQueries = evidenceQueries == null ? List.of() : List.copyOf(evidenceQueries);
        artifactNames = artifactNames == null ? List.of() : List.copyOf(artifactNames);
        artifactDirectory = artifactDirectory == null ? "" : artifactDirectory;
        reproductionCommand = reproductionCommand == null ? "" : reproductionCommand;
        secretReferences = secretReferences == null ? List.of() : List.copyOf(secretReferences);
        host = host == null ? HostShape.unknown() : host;
        telemetry = telemetry == null ? TelemetryCoverage.none() : telemetry;
        quality = quality == null ? RunQualityAssessment.notAssessed() : quality;
    }

    /**
     * Provenance from before the host, coverage and validity were recorded.
     *
     * <p>Retained at the previous arity so widening did not mean editing every construction site.
     * Each new component defaults to its unknown form rather than to a plausible value: a document
     * that does not say what machine produced it must not appear to.
     */
    public EvidenceProvenance(String schemaVersion, ToolVersions toolVersions,
            String configurationHash, Instant startedAt, Instant finishedAt,
            TimeWindow observabilityWindow, List<String> evidenceQueries, String artifactDirectory,
            List<String> artifactNames, String reproductionCommand, List<String> secretReferences,
            Instant generatedAt) {
        this(schemaVersion, toolVersions, configurationHash, startedAt, finishedAt,
                observabilityWindow, evidenceQueries, artifactDirectory, artifactNames,
                reproductionCommand, secretReferences, generatedAt, HostShape.unknown(),
                TelemetryCoverage.none(), RunQualityAssessment.notAssessed());
    }

    public Optional<TimeWindow> observabilityWindowIfPresent() {
        return Optional.ofNullable(observabilityWindow);
    }

    /**
     * The short form of the configuration hash, for a header or a footer.
     *
     * <p>{@code configurationHash} is stored in full, as {@code SHA-256:<hex>}, because a truncated
     * hash in the only place a document records its experiment identity is not enough to match two
     * runs. This is the display form, and it is never the stored one.
     */
    public String shortHash() {
        int separator = configurationHash.indexOf(':');
        String hex = separator < 0 ? configurationHash : configurationHash.substring(separator + 1);
        return hex.substring(0, Math.min(8, hex.length()));
    }

    public boolean hasArtifacts() {
        return !artifactNames.isEmpty();
    }
}
