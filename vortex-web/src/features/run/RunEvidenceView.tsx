import { Alert, Button, Card, Stack, Text, Title } from '@mantine/core';
import type {
  AcceptanceEvidence,
  Capacity,
  LoadSummary,
  Reliability,
  ResourceSignal,
  Resources,
  Validity,
  ComparisonEvidence,
  EvidenceProvenance,
  LoadAxis,
  ObservabilityEvidence,
  OperationEvidence,
  PerformanceEvidence,
  ResourceTimelineEvidence,
  RunEvidence,
  RunIdentity,
  TimelineEvidence,
  VerdictSection as VerdictSectionData,
  WorkloadEvidence,
} from '../../api/run';
import { Fact, Facts } from '../../components/Fact';
import { Unknown } from '../../components/Unknown';
import { VerdictBadge } from '../../components/VerdictBadge';
import { ResourceKindChart } from '../../components/charts/ResourceKindChart';
import { ServerSvg } from '../../components/charts/ServerSvg';
import { type ChartMarker } from '../../components/charts/chartTime';
import { TimelineChart } from '../../components/charts/TimelineChart';
import classes from './RunEvidenceView.module.css';

/**
 * Every section of a completed run's evidence, in the order `evidence.html`'s twelve fragments
 * established: what ran, the answer, the workload delivered, performance, whether objectives were
 * met, per-operation detail, the load axis, the timeline, what was observed, findings, comparison,
 * provenance.
 *
 * <p>Shared between the result view ({@link RunPage}) and the shareable report
 * ({@link RunReportPage}) — one renderer of run evidence, not two, per the confirmed decision to
 * migrate the report as a route over the same data rather than a second server-rendered page.
 */
export function RunEvidenceView({
  evidence,
  serviceId,
}: {
  evidence: RunEvidence;
  serviceId: string;
}) {
  return (
    <Stack gap="xl">
      <IdentitySection identity={evidence.identity} releaseMoved={evidence.releaseMoved} serviceId={serviceId} />
      <VerdictSection verdict={evidence.verdict} />

      {/* Load first: every measurement below is about the traffic described here, and a run that
          never generated its offered load makes everything after it a statement about a smaller
          test than the one somebody asked for. */}
      <LoadBlock
        load={evidence.load}
        workload={evidence.workload}
        axis={evidence.loadAxis}
        stageCount={evidence.timeline.stages.length}
      />

      {/* Service: how it answered the traffic it did receive. */}
      <ServiceBlock
        performance={evidence.performance}
        acceptance={evidence.acceptance}
        reliability={evidence.reliability}
        operations={evidence.hasOperationBreakdown ? evidence.operations : []}
        timeline={evidence.timeline}
      />

      {/* Resources: what was observed, whose it was, against which limit. */}
      <ResourcesBlock
        resources={evidence.resources}
        observability={evidence.observability}
        resourceTimeline={evidence.resourceTimeline}
        breakpointAtIso={evidence.timeline.breakpointAtIso}
        levelChangeAtIso={evidence.timeline.levelChangeAtIso}
      />

      {/* Capacity: the conclusions, and the refusals where there are none. */}
      <CapacityBlock capacity={evidence.capacity} />

      {/* Experiment: whether any of the above is worth what it appears to be worth. */}
      <ExperimentBlock
        validity={evidence.validity}
        findings={evidence.findings}
        provenance={evidence.provenance}
      />

      {evidence.comparison && (
        <ComparisonSection
          comparison={evidence.comparison}
          previousId={evidence.previousCompatibleExecutionId}
        />
      )}
    </Stack>
  );
}

/**
 * Load - requested versus achieved, and what the generator itself managed.
 *
 * <p>Dropped work is rendered only when the engine reported it. An absent count means nobody
 * measured it; rendering a zero would say the generator kept up, which is a claim a run without
 * that counter never made.
 */
function LoadBlock({
  load,
  workload,
  axis,
  stageCount,
}: {
  load: LoadSummary;
  workload: WorkloadEvidence;
  axis: LoadAxis;
  stageCount: number;
}) {
  // Whether "requested" and "achieved" describe the same level at all. A ramping workload's
  // achieved rate is averaged across every stage while its requested rate is only the peak, so
  // dividing one into the other always looks like a shortfall on a healthy ramp — see the note
  // below rather than a bare percentage.
  const isRamping = stageCount > 1;
  return (
    <Card withBorder padding="lg">
      <Stack gap="md">
        <Title order={3}>Load</Title>

        {load.droppedWork && (
          <Alert color="red" title="The offered load was not generated">
            The load generator could not start {load.droppedDisplay} units of work it was asked to
            start. Everything below describes the traffic that was produced, which is less than the
            traffic requested.
          </Alert>
        )}

        <Facts>
          <Fact label="Requested" note={isRamping ? 'peak of a ramping workload' : undefined}>
            {load.requestedDisplay || workload.configuredPeakDisplay}
          </Fact>
          <Fact label="Achieved" note={isRamping ? 'averaged across the whole ramp' : undefined}>
            {load.achievedDisplay || workload.achievedRateDisplay || 'not measured'}
          </Fact>
          {/* Deliberately not shown here: achieved ÷ requested, bare. For a ramping workload
              "requested" is the peak and "achieved" is a whole-run average, so the ratio always
              looks like a shortfall on a healthy ramp. WorkloadSection below states the same
              figure with the domain's own caveat (fellShort / deliveredCaveat) attached, which a
              bare percentage under the invalid-run banner cannot carry. */}
          {load.iterationRateDisplay && (
            <Fact label="Iteration rate">{load.iterationRateDisplay}</Fact>
          )}
          {load.observedConcurrency && (
            <Fact label="Observed concurrency">{load.observedConcurrency} VUs</Fact>
          )}
          <Fact label="Dropped work">
            {load.droppedDisplay || (
              <Unknown
                compact
                what="Not reported by the load generator"
                reason="This engine did not publish how much work it could not start, so whether the offered load was actually generated is unknown rather than confirmed."
              />
            )}
          </Fact>
        </Facts>

        <WorkloadSection workload={workload} />
        {axis.renderable && <LoadAxisSection axis={axis} />}
      </Stack>
    </Card>
  );
}

/**
 * Service - throughput, latency and reliability, with the objectives they were judged against.
 *
 * <p>Reliability is a distribution rather than a count: a 503 and a connection reset at the same
 * rate are different findings, and only one of them is the service refusing work.
 */
function ServiceBlock({
  performance,
  acceptance,
  reliability,
  operations,
  timeline,
}: {
  performance: PerformanceEvidence;
  acceptance: AcceptanceEvidence;
  reliability: Reliability;
  operations: OperationEvidence[];
  timeline: TimelineEvidence;
}) {
  return (
    <Card withBorder padding="lg">
      <Stack gap="lg">
        <Title order={3}>Service</Title>
        <PerformanceSection performance={performance} />
        <ReliabilitySection reliability={reliability} />
        <AcceptanceSection acceptance={acceptance} />
        {operations.length > 0 && <OperationsSection operations={operations} />}
        {timeline.present && <TimelineSection timeline={timeline} />}
      </Stack>
    </Card>
  );
}

/** What kind of outcomes the run produced, beside the count of how many failed. */
function ReliabilitySection({ reliability }: { reliability: Reliability }) {
  if (!reliability.reported) {
    return (
      <Stack gap="xs">
        <Title order={4}>Reliability</Title>
        <Facts>
          <Fact label="Error rate">{reliability.errorRateDisplay}</Fact>
        </Facts>
        <Unknown
          what="How requests failed was not classified"
          reason="The engine reported no status information for this run. That is not the same as every request having succeeded."
        />
      </Stack>
    );
  }

  return (
    <Stack gap="xs">
      <Title order={4}>Reliability</Title>
      <Facts>
        <Fact label="Error rate">{reliability.errorRateDisplay}</Fact>
      </Facts>
      <table className={classes.table}>
        <thead>
          <tr>
            <th>Outcome</th>
            <th>Count</th>
            <th>Share</th>
          </tr>
        </thead>
        <tbody>
          {reliability.byResponseClass.map((row) => (
            <tr key={`response-${row.label}`}>
              <td>{row.label}</td>
              <td>{row.count}</td>
              <td>{row.share}</td>
            </tr>
          ))}
          {reliability.byFailureClass.map((row) => (
            <tr key={`failure-${row.label}`}>
              <td>{row.label}</td>
              <td>{row.count}</td>
              <td>{row.share}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Stack>
  );
}

/**
 * Resources - what was observed, whose it was, and against which limit.
 *
 * <p>The generator's own resources get their own table, deliberately apart from the service's.
 * Reading one as the other is the failure this phase exists to prevent, and a single table with a
 * scope column would make avoiding it a matter of the reader's attention.
 */
function ResourcesBlock({
  resources,
  observability,
  resourceTimeline,
  breakpointAtIso,
  levelChangeAtIso,
}: {
  resources: Resources;
  observability: ObservabilityEvidence;
  resourceTimeline: ResourceTimelineEvidence;
  breakpointAtIso: string | null;
  levelChangeAtIso: string | null;
}) {
  return (
    <Card withBorder padding="lg">
      <Stack gap="lg">
        <Title order={3}>Resources</Title>

        {resources.service.length > 0 ? (
          <ResourceTable title="System under test" signals={resources.service} />
        ) : (
          <Unknown
            what="No resource signal describing the service was classified"
            reason="Nothing here can say whether the service ran out of anything. Configure an observability provider to find out."
          />
        )}

        {resources.generator.length > 0 ? (
          <ResourceTable title="Load generator" signals={resources.generator} />
        ) : (
          <Unknown
            what="The machine generating the traffic was not observed"
            reason="That is not evidence it kept up: whether the offered load was actually produced is unknown for this run."
          />
        )}

        {resourceTimeline.present && (
          <ResourceChartsSection
            resourceTimeline={resourceTimeline}
            breakpointAtIso={breakpointAtIso}
            levelChangeAtIso={levelChangeAtIso}
          />
        )}

        {observability.present && <ObservabilitySection observability={observability} />}
      </Stack>
    </Card>
  );
}

/**
 * CPU, memory and the rest of a run's resource behaviour over time — one chart per kind, every scope
 * that reported it overlaid on the same axis. Compact by design: a section, not a dashboard wall.
 *
 * <p>{@code completenessStatus} is checked before rendering anything as though it were the whole
 * run — a writer that failed partway through still leaves an artifact that opens cleanly, and a
 * reader must be able to tell that apart from a fully-recorded run.
 */
function ResourceChartsSection({
  resourceTimeline,
  breakpointAtIso,
  levelChangeAtIso,
}: {
  resourceTimeline: ResourceTimelineEvidence;
  breakpointAtIso: string | null;
  levelChangeAtIso: string | null;
}) {
  const markers: ChartMarker[] = [
    ...(levelChangeAtIso ? [{ atIso: levelChangeAtIso, label: 'Traffic jump' }] : []),
    ...(breakpointAtIso ? [{ atIso: breakpointAtIso, label: 'First objective violation' }] : []),
  ];

  return (
    <Stack gap="md">
      <Title order={4}>Over the run</Title>

      {resourceTimeline.completenessStatus === 'PARTIAL' && (
        <Alert color="warn" variant="light">
          This series is partial{resourceTimeline.completenessReason
            ? `: ${resourceTimeline.completenessReason}`
            : '.'}{' '}
          It does not describe the whole run.
        </Alert>
      )}

      {resourceTimeline.plots.map((plot) => (
        <Stack key={plot.kind} gap={4}>
          <Text size="sm" fw={600}>
            {plot.kindLabel}
          </Text>
          <ResourceKindChart plot={plot} markers={markers} />
          <Stack gap={2}>
            {plot.series.map((series) => (
              <Text key={`${series.providerId}:${series.signalId}`} size="xs" c="dimmed">
                {series.scopeLabel} — {series.seriesLabel}: peak {series.display}
                {series.limitDisplay && ` · limit ${series.limitDisplay}`}
                {series.utilisationDisplay && ` · ${series.utilisationDisplay} of limit`}
                {series.atItsLimit && (
                  <Text span c="red" fw={600}>
                    {' '}
                    at limit
                  </Text>
                )}
              </Text>
            ))}
          </Stack>
        </Stack>
      ))}
    </Stack>
  );
}

function ResourceTable({ title, signals }: { title: string; signals: ResourceSignal[] }) {
  return (
    <Stack gap="xs">
      <Title order={4}>{title}</Title>
      <table className={classes.table}>
        <thead>
          <tr>
            <th>Resource</th>
            <th>Kind</th>
            <th>Observed</th>
            <th>Limit</th>
            <th>Of limit</th>
          </tr>
        </thead>
        <tbody>
          {signals.map((signal) => (
            <tr key={signal.id}>
              <td>{signal.name}</td>
              <td>{signal.kindLabel}</td>
              <td>{signal.display}</td>
              {/* An absent limit is stated rather than left blank: a resource with no published
                  limit is not a resource that stayed clear of one. */}
              <td>
                {signal.limitDisplay || (
                  <Text span c="dimmed">
                    none published
                  </Text>
                )}
              </td>
              <td>
                {signal.utilisationDisplay || (
                  <Text span c="dimmed">
                    not computable
                  </Text>
                )}
                {signal.atItsLimit && (
                  <Text span c="red" fw={600}>
                    {' '}
                    at its limit
                  </Text>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Stack>
  );
}

/**
 * Capacity - the four limits, the five conditions, and the headline or its refusal.
 *
 * <p>Sustainable capacity is the headline. Where there is none the refusal takes its place rather
 * than the section going quiet, and the highest level that passed sits beneath it, explicitly
 * labelled as not a capacity claim.
 */
function CapacityBlock({ capacity }: { capacity: Capacity }) {
  return (
    <Card withBorder padding="lg">
      <Stack gap="lg">
        <Title order={3}>Capacity</Title>

        {capacity.sustainableDisplay ? (
          <Stack gap={4}>
            <Text size="sm" c="dimmed">
              Sustainable capacity
            </Text>
            <Title order={2}>{capacity.sustainableDisplay}</Title>
            <Text size="sm" c="dimmed">
              Evidence strength: {capacity.strengthLabel}
            </Text>
          </Stack>
        ) : (
          <Alert color="yellow" title="No sustainable capacity was established">
            {capacity.refusal}
          </Alert>
        )}

        <Facts>
          {capacity.highestPassing && (
            <Fact
              label="Highest level that passed"
              note="Not a capacity claim: it is the highest level at which every objective held."
            >
              {capacity.highestPassing}
            </Fact>
          )}
          <Fact label="Headroom">
            {capacity.headroomDisplay || (
              <Unknown compact what="Not stated" reason={capacity.headroomRefusal} />
            )}
          </Fact>
        </Facts>

        {capacity.conditions.length > 0 && (
          <Stack gap="xs">
            <Title order={4}>Conditions for a sustainable capacity</Title>
            <table className={classes.table}>
              <tbody>
                {capacity.conditions.map((condition) => (
                  <tr key={condition.condition}>
                    <td>{condition.label}</td>
                    <td>{condition.outcomeLabel}</td>
                    <td>{condition.statement}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Stack>
        )}

        <Stack gap="xs">
          <Title order={4}>Limits</Title>
          <Text size="sm" c={capacity.noLimitEstablished ? 'dimmed' : undefined}>
            {capacity.firstLimit}
          </Text>
          {capacity.limits.length > 0 && (
            <table className={classes.table}>
              <tbody>
                {capacity.limits.map((limit) => (
                  <tr key={limit.kind}>
                    <td>{limit.label}</td>
                    <td>
                      {limit.level || (
                        <Text span c="dimmed">
                          not established
                        </Text>
                      )}
                    </td>
                    <td>{limit.describe}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Stack>
      </Stack>
    </Card>
  );
}

/**
 * Experiment - whether this run measured what it claims to, and what it therefore cannot support.
 *
 * <p>An invalid run renders every measurement a valid one does. What changes is what is stated
 * above it, and this block saying why. Vortex does not go quiet where a number was expected; it
 * replaces the number with the sentence explaining its absence.
 */
function ExperimentBlock({
  validity,
  findings,
  provenance,
}: {
  validity: Validity;
  findings: RunEvidence['findings'];
  provenance: EvidenceProvenance;
}) {
  const tone =
    validity.grade === 'INVALID' ? 'red' : validity.grade === 'DEGRADED' ? 'yellow' : 'green';

  return (
    <Card withBorder padding="lg">
      <Stack gap="lg">
        <Title order={3}>Experiment</Title>

        {validity.assessed ? (
          <Alert color={tone} title={`Run quality: ${validity.label}`}>
            <Stack gap="xs">
              <Text size="sm">{validity.explanation}</Text>
              {validity.findings.map((finding) => (
                <Text key={finding.code} size="sm">
                  <strong>{finding.label}.</strong> {finding.statement}
                </Text>
              ))}
            </Stack>
          </Alert>
        ) : (
          <Unknown
            what="This run's validity was never assessed"
            reason="It was recorded before Vortex graded experiments. Nothing here is withheld on that account."
          />
        )}

        {findings.length > 0 && <FindingsSection findings={findings} />}
        <ProvenanceSection provenance={provenance} />
      </Stack>
    </Card>
  );
}

function IdentitySection({
  identity,
  releaseMoved,
  serviceId,
}: {
  identity: RunIdentity;
  releaseMoved: boolean;
  serviceId: string;
}) {
  return (
    <section>
      <Title order={1} size="h2" mb={4}>
        {identity.workloadName}
      </Title>
      <Text c="dimmed" size="sm" mb="md">
        {identity.testTypeLabel} against {identity.environmentName} · {identity.finishedAtDisplay}
      </Text>

      {releaseMoved && (
        <Alert color="live" title="This service has changed since this run" mb="md">
          <Text size="sm" mb="xs">
            This evidence describes a different release than what is configured now.
          </Text>
          <Button component="a" href={`/services/${serviceId}`} size="xs" variant="light">
            Test current release
          </Button>
        </Alert>
      )}

      <Facts>
        <Fact label="Target" note={identity.targetWasRewritten ? identity.targetRewriteReason : undefined}>
          {identity.targetUrl}
        </Fact>
        {/* Omitted for an external endpoint: its address is already the "Target" fact above, and
            showing "EXTERNAL_ENDPOINT"'s own summary there too would say the same thing twice — the
            same rule ServiceHeader's meta line already follows. */}
        {identity.targetKind !== 'EXTERNAL_ENDPOINT' && (
          <Fact label="Target kind">{identity.targetSummary}</Fact>
        )}
        <Fact label="Target ownership">{identity.targetOwnershipLabel}</Fact>
        {/* Absent rather than an empty string — a historical run predating this feature, or any run
            whose target never confirmed a resource envelope (an external endpoint, a Compose
            target), simply has nothing to say here. */}
        {/* "Target resources", not "Resources" — that label already names one of the five report
            sections below (the service's own measured CPU/memory), a different concept from the
            envelope Vortex declared and confirmed for this target before the run started. */}
        {identity.resourceSummary && <Fact label="Target resources">{identity.resourceSummary}</Fact>}
        <Fact label="Environment">
          {identity.environmentName} — {identity.environmentTypeLabel}
        </Fact>
        <Fact label="Classification">{identity.classificationLabel}</Fact>
        {identity.serviceVersion && <Fact label="Release">{identity.serviceVersion}</Fact>}
        {identity.durationDisplay && <Fact label="Duration">{identity.durationDisplay}</Fact>}
      </Facts>
    </section>
  );
}

function VerdictSection({ verdict }: { verdict: VerdictSectionData }) {
  return (
    <section>
      <Text size="sm" c="dimmed" mb={4}>
        {verdict.question}
      </Text>
      <div className={classes.verdictRow}>
        <VerdictBadge verdict={verdict.verdict as 'PASS' | 'FAIL' | 'NOT_EVALUATED'} label={verdict.verdictLabel} size="lg" />
        <Text size="lg">{verdict.answer}</Text>
      </div>
      {verdict.qualifications.length > 0 && (
        <ul className={classes.qualifications}>
          {verdict.qualifications.map((q) => (
            <li key={q}>{q}</li>
          ))}
        </ul>
      )}
    </section>
  );
}

function WorkloadSection({ workload }: { workload: WorkloadEvidence }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Workload delivered
      </Title>
      <Facts>
        <Fact label="Model" note={workload.modelGuidance}>
          {workload.modelLabel}
        </Fact>
        <Fact label={workload.open ? 'Configured rate' : 'Configured concurrency'}>
          {workload.configuredPeakDisplay}
        </Fact>
        <Fact label="Source">{workload.sourceDescribe}</Fact>
        {workload.achievedRateDisplay && (
          <Fact
            label="Achieved"
            note={
              workload.fellShort
                ? (workload.deliveredCaveat ?? undefined)
                : workload.deliveredPercent
                  ? `${workload.deliveredPercent} of configured`
                  : undefined
            }
          >
            {workload.achievedRateDisplay}
          </Fact>
        )}
        <Fact label="Requests" note={workload.estimatedRequestsDisplay ? `estimated ${workload.estimatedRequestsDisplay}` : undefined}>
          {workload.requestsDisplay}
        </Fact>
        <Fact label="Errors">
          {workload.errorRateDisplay} ({workload.failuresDisplay})
        </Fact>
        <Fact label="Duration">
          {workload.actualDurationDisplay}
          {workload.configuredDurationDisplay !== workload.actualDurationDisplay &&
            ` (configured ${workload.configuredDurationDisplay})`}
        </Fact>
        <Fact label="Script">{workload.scriptSourceLabel}</Fact>
      </Facts>
      {workload.operationMix.length > 0 && (
        <ul className={classes.list}>
          {workload.operationMix.map((row) => (
            <li key={row}>{row}</li>
          ))}
        </ul>
      )}
    </section>
  );
}

function PerformanceSection({ performance }: { performance: PerformanceEvidence }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Performance
      </Title>
      {performance.latencyRows.length > 0 && (
        <Facts>
          {performance.latencyRows.map((row) => (
            <Fact key={row.percentileLabel} label={row.percentileLabel}>
              {row.durationDisplay}
            </Fact>
          ))}
        </Facts>
      )}

      {performance.hasLimitsCard && (
        <div className={classes.limits}>
          {performance.sloBreakpointDisplay && (
            <Fact label="SLO breakpoint" note={performance.sloBreakpointStagesText}>
              {performance.sloBreakpointDisplay}
              {performance.sloBreakpointStrengthLabel && (
                <span className={classes.dim}> · {performance.sloBreakpointStrengthLabel}</span>
              )}
            </Fact>
          )}
          {performance.systemSaturationDescribe && (
            <Fact label="System saturation" note={performance.systemSaturationExplanation}>
              {performance.systemSaturationDescribe}
            </Fact>
          )}
          <Fact label="Headroom" note={performance.headroomDisplay ? undefined : (performance.headroomRefusal ?? undefined)}>
            {performance.headroomDisplay ?? 'Not computed'}
          </Fact>
        </div>
      )}

      {performance.baselineQuality.length > 0 && (
        <ul className={classes.list}>
          {performance.baselineQuality.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      )}
    </section>
  );
}

function AcceptanceSection({ acceptance }: { acceptance: AcceptanceEvidence }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Objectives
      </Title>
      {!acceptance.hasObjectives ? (
        <Text size="sm" c="dimmed">
          {acceptance.absenceExplanation}
        </Text>
      ) : (
        <div className={classes.table}>
          {acceptance.results.map((result) => (
            <div key={result.describe} className={classes.acceptanceRow}>
              <VerdictBadge verdict={result.verdict as 'PASS' | 'FAIL' | 'NOT_EVALUATED'} label={result.verdictLabel} />
              <div>
                <Text size="sm">{result.describe}</Text>
                <Text size="xs" c="dimmed">
                  {result.observed}
                  {result.note && ` — ${result.note}`}
                </Text>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function OperationsSection({ operations }: { operations: OperationEvidence[] }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        By operation
      </Title>
      <div className={classes.table}>
        <div className={classes.opHead}>
          <span>Operation</span>
          <span>Requests</span>
          <span>Rate</span>
          <span>p95</span>
          <span>p99</span>
          <span>Errors</span>
        </div>
        {operations.map((op) => (
          <div key={op.name} className={classes.opRow}>
            <span>{op.name}</span>
            {op.hasTraffic ? (
              <>
                <span>{op.requestsDisplay}</span>
                <span>{op.rateDisplay}</span>
                <span>{op.p95Display}</span>
                <span>{op.p99Display}</span>
                <span>{op.errorRateDisplay}</span>
              </>
            ) : (
              <span className={classes.dim}>no traffic</span>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}

function LoadAxisSection({ axis }: { axis: LoadAxis }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Load axis
      </Title>
      {axis.svg && <ServerSvg svg={axis.svg} className={classes.chart} />}
      {axis.drawsBoundary ? (
        <Facts>
          {axis.highestCompliantDisplay && (
            <Fact label="Highest compliant level">{axis.highestCompliantDisplay}</Fact>
          )}
          {axis.firstNonCompliantDisplay && (
            <Fact label="First non-compliant level">{axis.firstNonCompliantDisplay}</Fact>
          )}
        </Facts>
      ) : (
        axis.boundaryStatement && (
          <Text size="sm" c="dimmed">
            {axis.boundaryStatement}
          </Text>
        )
      )}
      {axis.drawsSaturation && axis.saturationDescribe && (
        <Text size="sm" mt="xs">
          {axis.saturationDescribe}
        </Text>
      )}
      {axis.testedToDisplay && (
        <Text size="xs" c="dimmed" mt="xs">
          Tested to {axis.testedToDisplay}.
        </Text>
      )}
    </section>
  );
}

function TimelineSection({ timeline }: { timeline: TimelineEvidence }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Over time
      </Title>
      <div className={classes.plots}>
        {timeline.plots
          .filter((plot) => plot.hasData)
          .map((plot) => (
            <div key={plot.label}>
              <Text size="xs" c="dimmed" mb={4}>
                {plot.label}
              </Text>
              <TimelineChart plot={plot} />
            </div>
          ))}
      </div>

      {timeline.stages.length > 0 && (
        <div className={classes.table}>
          <div className={classes.stageHead}>
            <span>Level</span>
            <span>Achieved</span>
            <span>p95</span>
            <span>Errors</span>
            <span>Result</span>
          </div>
          {timeline.stages.map((stage, i) => (
            <div key={i} className={classes.stageRow}>
              <span>{stage.levelDisplay}</span>
              <span>{stage.achievedDisplay}</span>
              <span>{stage.p95Display}</span>
              <span>{stage.errorRateDisplay}</span>
              <span className={stage.resultKind === 'violated' ? classes.violated : classes.met}>
                {stage.resultKind === 'violated' ? stage.violatedThresholds.join(', ') : 'met'}
              </span>
            </div>
          ))}
        </div>
      )}

      {timeline.showsDerivedCaveat && (
        <Text size="xs" c="dimmed" mt="xs">
          Stage boundaries above are derived from the timeline rather than measured directly, so
          they are weaker evidence than a run whose stages the executor itself reported.
        </Text>
      )}

      {timeline.tableRows.length > 0 && (
        <details className={classes.disclosure}>
          <summary>Samples ({timeline.tableRows.length})</summary>
          <div className={classes.table}>
            <div className={classes.sampleHead}>
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
    </section>
  );
}

function ObservabilitySection({ observability }: { observability: ObservabilityEvidence }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        What was observed
      </Title>
      {observability.signals.length > 0 && (
        <div className={classes.table}>
          {observability.signals.map((signal) => (
            <div key={signal.name} className={classes.signalRow}>
              <span>{signal.name}</span>
              <span>
                {signal.display}
                {signal.movement && <span className={classes.dim}> {signal.movement}</span>}
              </span>
              <span className={classes.dim}>
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

      {observability.gaps.length > 0 && (
        <details className={classes.disclosure}>
          <summary>Not collected ({observability.gaps.length})</summary>
          <ul className={classes.list}>
            {observability.gaps.map((gap) => (
              <li key={gap.what}>
                <strong>{gap.what}</strong> — {gap.howToCollect}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}

function FindingsSection({ findings }: { findings: RunEvidence['findings'] }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Findings
      </Title>
      <Stack gap="sm">
        {findings.map((finding) => (
          <Card key={finding.headline} withBorder radius="md" className={classes.findingCard}>
            <div className={classes.findingHead}>
              <span className={`${classes.findingLevel} ${classes[`level_${finding.levelKind.toLowerCase()}`] ?? ''}`}>
                {finding.levelLabel}
              </span>
              <span className={classes.dim}>{finding.strengthLabel}</span>
            </div>
            <Text size="sm" fw={600}>
              {finding.headline}
            </Text>
            {finding.hasDetail && finding.detail && (
              <Text size="sm" c="dimmed">
                {finding.detail}
              </Text>
            )}
            {finding.evidenceIds.length > 0 && (
              <Text size="xs" c="dimmed" mt={4}>
                Evidence: {finding.evidenceIds.join(', ')}
              </Text>
            )}
          </Card>
        ))}
      </Stack>
    </section>
  );
}

function ComparisonSection({
  comparison,
  previousId,
}: {
  comparison: ComparisonEvidence;
  previousId: string | null;
}) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Compared to {comparison.baselineLabel}
      </Title>
      <Text size="xs" c="dimmed" mb="sm">
        {comparison.baselineFinishedAtDisplay}
        {previousId && (
          <>
            {' · '}
            <a href={`/runs/${previousId}`}>view run</a>
          </>
        )}
      </Text>

      {comparison.supportsVerdict ? (
        <>
          {comparison.deltas.length > 0 && (
            <div className={classes.table}>
              <div className={classes.deltaHead}>
                <span>Metric</span>
                <span>Baseline → this run</span>
                <span>Change</span>
              </div>
              {comparison.deltas.map((delta) => (
                <div key={delta.metric} className={classes.deltaRow}>
                  <span>{delta.metric}</span>
                  <span>{delta.display}</span>
                  <span>{delta.percentChangeDisplay}</span>
                </div>
              ))}
            </div>
          )}
          {comparison.verdictLabel && (
            <Text size="sm" mt="sm">
              <strong>{comparison.verdictLabel}</strong>
              {comparison.verdictDescription && ` — ${comparison.verdictDescription}`}
            </Text>
          )}
        </>
      ) : (
        <Text size="sm" c="dimmed">
          {comparison.notComparableExplanation}
        </Text>
      )}

      {comparison.differences.length > 0 && (
        <ul className={classes.list}>
          {comparison.differences.map((difference) => (
            <li key={difference}>{difference}</li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ProvenanceSection({ provenance }: { provenance: EvidenceProvenance }) {
  return (
    <details className={classes.disclosure}>
      <summary>Provenance</summary>
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
      <Text size="xs" c="dimmed" mt="xs" style={{ fontFamily: 'monospace' }}>
        {provenance.reproductionCommand}
      </Text>
      {provenance.hasArtifacts && (
        <Text size="xs" c="dimmed" mt="xs">
          Artifacts: {provenance.artifactNames.join(', ')}
        </Text>
      )}
    </details>
  );
}
