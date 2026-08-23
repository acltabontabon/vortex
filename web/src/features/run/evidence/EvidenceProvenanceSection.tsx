import { Alert, Text, Title } from '@mantine/core';
import type {
  EvidenceProvenance,
  LoadAxis,
  ObservabilityEvidence,
  Resources,
  TimelineEvidence,
  WorkloadEvidence,
} from '../../../api/run';
import { Fact, Facts } from '../../../components/Fact';
import { ServerSvg } from '../../../components/charts/ServerSvg';
import { Unknown } from '../../../components/Unknown';
import { ResourceList } from './ResourceList';
import shared from './shared.module.css';
import classes from './EvidenceProvenanceSection.module.css';

/**
 * "Why does Vortex believe this" — every audit-level detail the old page scattered through the main
 * reading path, grouped and collapsed here instead: workload configuration, what telemetry was
 * consulted and what wasn't, the tool versions and how to reproduce the run, the load axis, the load
 * generator's own resources, and the raw sample table. Nothing here is new data; it is the same
 * fields the old page always sent, reorganized so they no longer sit between a reader and the
 * conclusion — the generator's own health is context for how much to trust this run, not part of how
 * the service under test behaved, so it lives here rather than beside {@link ResourcesSection}.
 */
export function EvidenceProvenanceSection({
  workload,
  loadAxis,
  observability,
  timeline,
  resources,
  provenance,
}: {
  workload: WorkloadEvidence;
  loadAxis: LoadAxis;
  observability: ObservabilityEvidence;
  timeline: TimelineEvidence;
  resources: Resources;
  provenance: EvidenceProvenance;
}) {
  const generatorSaturated = resources.generator.filter((s) => s.atItsLimit);
  const generatorHostUnderPressure = resources.generatorHost.filter((s) => s.atItsLimit);
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Evidence &amp; provenance
      </Title>
      <div className={shared.disclosureGroup}>
        <details className={shared.disclosure}>
          <summary>Workload</summary>
          <Facts>
            <Fact label="Model" note={workload.modelGuidance}>
              {workload.modelLabel}
            </Fact>
            <Fact label="Source">{workload.sourceDescribe}</Fact>
            <Fact label="Script">{workload.scriptSourceLabel}</Fact>
          </Facts>
        </details>

        {loadAxis.renderable && (
          <details className={shared.disclosure}>
            <summary>Load axis</summary>
            {loadAxis.svg && <ServerSvg svg={loadAxis.svg} className={classes.chart} />}
            <Facts>
              {loadAxis.highestCompliantDisplay && (
                <Fact label="Highest compliant level">{loadAxis.highestCompliantDisplay}</Fact>
              )}
              {loadAxis.firstNonCompliantDisplay && (
                <Fact label="First non-compliant level">{loadAxis.firstNonCompliantDisplay}</Fact>
              )}
            </Facts>
            {!loadAxis.drawsBoundary && loadAxis.boundaryStatement && (
              <Text size="sm" c="dimmed">
                {loadAxis.boundaryStatement}
              </Text>
            )}
            {loadAxis.drawsSaturation && loadAxis.saturationDescribe && (
              <Text size="sm" mt="xs">
                {loadAxis.saturationDescribe}
              </Text>
            )}
            {loadAxis.testedToDisplay && (
              <Text size="xs" c="dimmed" mt="xs">
                Tested to {loadAxis.testedToDisplay}.
              </Text>
            )}
          </details>
        )}

        <details className={shared.disclosure}>
          <summary>Load generator</summary>
          <div className={classes.groups}>
            <div className={classes.group}>
              <Text size="xs" c="dimmed" tt="uppercase" fw={600} mb={4}>
                Load generator
              </Text>
              {resources.generator.length > 0 ? (
                <ResourceList signals={resources.generator} />
              ) : (
                <Unknown
                  compact
                  what="The generator's own process or container was not observed"
                  reason="That is not evidence it kept up: whether the offered load was actually produced is unknown for this run."
                />
              )}
            </div>

            <div className={classes.groupMuted}>
              <Text size="xs" c="dimmed" tt="uppercase" fw={600} mb={4}>
                Load generator host
              </Text>
              {resources.generatorHost.length > 0 ? (
                <ResourceList signals={resources.generatorHost} />
              ) : (
                <Unknown
                  compact
                  what="The machine running the load generator was not observed"
                  reason="Supporting context only — its absence says nothing about whether the generator's own process or container kept up."
                />
              )}
            </div>
          </div>

          {generatorSaturated.length > 0 && (
            <Alert color="warn" title="Load generator pressure" mt="sm">
              {generatorSaturated.map((s) => s.name).join(', ')} at{' '}
              {generatorSaturated.map((s) => s.utilisationDisplay).join(', ')} of limit. High
              load-generator utilisation may reduce confidence that the requested workload can continue
              to be generated accurately.
            </Alert>
          )}

          {generatorHostUnderPressure.length > 0 && (
            <Alert color="warn" variant="light" title="Load generator host pressure" mt="sm">
              {generatorHostUnderPressure.map((s) => s.name).join(', ')} at{' '}
              {generatorHostUnderPressure.map((s) => s.utilisationDisplay).join(', ')} of limit. This
              is the whole machine running the generator, not its own process or container — it
              qualifies confidence in this run rather than proving the generator itself was
              constrained.
            </Alert>
          )}
        </details>

        {observability.present && (
          <details className={shared.disclosure}>
            <summary>Telemetry</summary>
            {observability.signals.length > 0 && (
              <div className={classes.signalTable}>
                {observability.signals.map((signal) => (
                  <div key={signal.name} className={classes.signalRow}>
                    <span>{signal.name}</span>
                    <span>
                      {signal.display}
                      {signal.movement && <span className={shared.dim}> {signal.movement}</span>}
                    </span>
                    <span className={shared.dim}>
                      {signal.sourceUrl ? (
                        <a href={signal.sourceUrl} target="_blank" rel="noreferrer">
                          {signal.sourceLabel}
                        </a>
                      ) : (
                        signal.sourceLabel
                      )}
                    </span>
                  </div>
                ))}
              </div>
            )}
            {observability.providersConsulted.length > 0 && (
              <Text size="xs" c="dimmed" mt="xs">
                Consulted: {observability.providersConsulted.join(', ')}
              </Text>
            )}
          </details>
        )}

        {observability.gaps.length > 0 && (
          <details className={shared.disclosure}>
            <summary>Missing telemetry ({observability.gaps.length})</summary>
            <ul className={shared.list}>
              {observability.gaps.map((gap) => (
                <li key={gap.what}>
                  <strong>{gap.what}</strong> — {gap.howToCollect}
                </li>
              ))}
            </ul>
          </details>
        )}

        <details className={shared.disclosure}>
          <summary>Evidence provenance</summary>
          <Facts>
            <Fact label="Vortex">{provenance.vortexVersion}</Fact>
            <Fact label="Engine">{provenance.engineVersion}</Fact>
            <Fact label="Runtime">{provenance.runtimeVersion}</Fact>
            {provenance.dockerImage && <Fact label="Image">{provenance.dockerImage}</Fact>}
            <Fact label="Configuration">{provenance.configurationHash}</Fact>
          </Facts>
          {provenance.secretReferences.length > 0 && (
            <Text size="xs" c="dimmed" mt="xs">
              Secrets referenced (not their values): {provenance.secretReferences.join(', ')}
            </Text>
          )}
          {provenance.hasArtifacts && (
            <Text size="xs" c="dimmed" mt="xs">
              Artifacts: {provenance.artifactNames.join(', ')}
            </Text>
          )}
        </details>

        <details className={shared.disclosure}>
          <summary>Reproduction</summary>
          <Text size="xs" c="dimmed">
            {provenance.reproductionCommand}
          </Text>
        </details>

        {timeline.tableRows.length > 0 && (
          <details className={shared.disclosure}>
            <summary>Raw measurements ({timeline.tableRows.length})</summary>
            <div className={classes.sampleTable}>
              <div className={`${classes.sampleRow} ${shared.head}`}>
                <span>Time</span>
                <span>Offered</span>
                <span>Achieved</span>
                <span>p95</span>
                <span>Errors</span>
              </div>
              {timeline.tableRows.map((row, i) => (
                <div key={i} className={classes.sampleRow}>
                  <span>{row.timeDisplay}</span>
                  <span>{row.offeredDisplay}</span>
                  <span>{row.achievedDisplay}</span>
                  <span>{row.p95Display}</span>
                  <span>{row.errorRateDisplay}</span>
                </div>
              ))}
            </div>
          </details>
        )}
      </div>
    </section>
  );
}
