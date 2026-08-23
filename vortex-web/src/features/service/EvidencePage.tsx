import { useParams } from 'react-router-dom';
import { Button, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useEvidenceQuery } from '../../api/workspace';
import type { Capacity, CapacityHistoryEntry, RunSummary } from '../../api/workspace';
import { Fact, Facts } from '../../components/Fact';
import { Unknown, UnknownInline } from '../../components/Unknown';
import { VerdictBadge } from '../../components/VerdictBadge';
import { CapacityRangeFigure } from '../../components/charts/CapacityRangeFigure';
import { runAgainHref } from '../../lib/testState';
import { errorFallback } from '../../lib/queryFallback';
import classes from './EvidencePage.module.css';

/**
 * What do we currently know?
 *
 * <p>Capacity used to be a destination of its own, sitting beside Runs — the wrong shape twice
 * over. Tested capacity is not configured state a service has; it is a conclusion drawn from runs,
 * and separating the conclusion from the evidence for it is exactly how a number ends up on a slide
 * without its conditions. So this page opens with the conclusion, states the headroom or the
 * recorded reason there is none, and only then shows the history and the runs behind it.
 *
 * <p>"Not comparable" and "not established" are answers here, not gaps — rendered with the same
 * weight as a figure, because refusing to compute something Vortex cannot support is the product
 * working as designed.
 */
export function EvidencePage() {
  const { id = '' } = useParams();
  const { data, isError } = useEvidenceQuery(id);

  const error = errorFallback(isError, "Could not load this service's evidence",
      `/api/services/${id}/evidence did not respond. Reload the page to try again.`);
  if (error) return error;

  if (!data) return <Skeleton height={420} radius="md" />;

  const { capacity, range, production } = data;

  return (
    <Stack gap="xl">
      <section>
        <div className={classes.sectionHead}>
          <Title order={2} size="h4">
            What do we currently know?
          </Title>
          {/* Not "this evidence is stale" — nothing here knows whether the change mattered, only
              that the two versions differ and that running a test is what would settle it. */}
          {data.releaseMoved && (
            <Button component="a" href={`/services/${id}`} size="xs" variant="default">
              Test current release
            </Button>
          )}
        </div>

        {!capacity ? (
          <Unknown
            what="No tested capacity has been established for this service."
            reason="A capacity is something a run establishes, not something a service has."
            actionLabel="Go to Tests"
            actionHref={`/services/${id}`}
          />
        ) : (
          <Stack gap="md">
            {range.renderable && <CapacityRangeFigure range={range} size="wide" />}

            <Facts>
              <Fact label={capacity.boundaryLabel}>{capacity.boundary}</Fact>

              {capacity.quotable && (
                <Fact label={capacity.label}>{capacity.compliantLevel}</Fact>
              )}

              {capacity.firstNonCompliant && (
                <Fact label="First observed non-compliant load">
                  {capacity.firstNonCompliant}
                </Fact>
              )}

              {production && <Fact label="Observed production peak">{production.peakRate}</Fact>}

              {/* Headroom is a number or a stated reason, and never silence. Exactly one of the two
                  exists, and the reason is worth as much as the figure. */}
              <Fact
                label="Headroom over production"
                note={capacity.headroom ? undefined : capacity.headroomRefusal}
              >
                {capacity.headroom ? (
                  `${capacity.headroom} above the observed production peak`
                ) : (
                  <UnknownInline>Not computed</UnknownInline>
                )}
              </Fact>

              <Fact
                label="How well the boundary is established"
                note={capacity.boundaryStatusLabel}
              >
                {capacity.boundaryStrength}
              </Fact>

              <Fact label="Measured">{capacity.measuredAt}</Fact>
            </Facts>

            <ConditionsDisclosure capacity={capacity} />
            <ConstraintCandidatesDisclosure capacity={capacity} />

            <Button
              component="a"
              href={`/runs/${capacity.runId}`}
              size="xs"
              variant="default"
              w="fit-content"
            >
              View supporting run
            </Button>
          </Stack>
        )}
      </section>

      <CapacityHistorySection history={data.history} />
      <RunsSection id={id} runs={data.runs} />
    </Stack>
  );
}

function ConditionsDisclosure({ capacity }: { capacity: Capacity }) {
  return (
    <details className={classes.disclosure}>
      <summary>The conditions this figure holds under</summary>
      <div className={classes.disclosureBody}>
        <Text size="xs" c="dimmed" maw="68ch">
          Detached from these, the number above is a rumour rather than evidence. Tested capacity
          moves with the version, the configuration, the infrastructure, the dependencies, the
          operation mix and the size of the data.
        </Text>
        {/* The domain's own conditions() sentences, verbatim — CapacityObservation already phrases
            these and re-composing them here would risk saying it differently in one place. */}
        <ul className={classes.conditions}>
          {capacity.conditions.map((condition) => (
            <li key={condition}>{condition}</li>
          ))}
        </ul>
      </div>
    </details>
  );
}

/**
 * What was near its limit there — correlated with the degradation, never asserted as its cause.
 *
 * <p>Shown only when the domain recorded one. {@code describe()} is deliberately the only sentence
 * here: a resource name beside a capacity figure reads as a diagnosis however carefully the
 * surrounding prose is worded, which is why boundary confidence is never shown next to this list —
 * "High" beside a CPU figure would read as "CPU is the cause, with high confidence", a claim no run
 * supports.
 */
function ConstraintCandidatesDisclosure({ capacity }: { capacity: Capacity }) {
  if (capacity.constraintCandidates.length === 0) return null;
  return (
    <details className={classes.disclosure}>
      <summary>What was near its limit there</summary>
      <div className={classes.disclosureBody}>
        <ul className={classes.candidates}>
          {capacity.constraintCandidates.map((candidate) => (
            <li key={candidate.describe}>
              {candidate.describe}
              <span className={classes.candidateSupport}> {candidate.support}</span>
            </li>
          ))}
        </ul>
        <Text size="xs" c="dimmed" maw="68ch">
          These are candidates, not causes. A resource near its limit at the level where objectives
          stopped being met has been correlated with that degradation; separating cause from
          symptom needs context a load test does not contain.
        </Text>
      </div>
    </details>
  );
}

/** One row per release Vortex has ever tested, newest first. */
function CapacityHistorySection({ history }: { history: CapacityHistoryEntry[] }) {
  if (history.length === 0) return null;

  return (
    <section>
      <Title order={2} size="h5" mb="sm">
        Capacity history
      </Title>
      <div className={classes.history}>
        {history.map((entry) => (
          <div key={entry.serviceVersion} className={classes.historyRow}>
            <div className={classes.historyVersion}>
              {entry.serviceVersion}
              {entry.current && <span className={classes.currentTag}>current</span>}
            </div>
            <div className={classes.historyObservations}>
              {entry.observations.map((observation) => (
                <a
                  key={observation.runId}
                  className={classes.historyObservation}
                  href={`/runs/${observation.runId}`}
                >
                  {observation.quotable ? observation.compliantLevel : observation.boundary}
                  <span className={classes.historyWhen}>{observation.measuredAt}</span>
                </a>
              ))}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function RunsSection({ id, runs }: { id: string; runs: RunSummary[] }) {
  return (
    <section>
      <div className={classes.sectionHead}>
        <Title order={2} size="h5">
          Runs
        </Title>
        <a className={classes.more} href={`/services/${id}/runs`}>
          All runs →
        </a>
      </div>

      {runs.length === 0 ? (
        <Text size="sm" c="dimmed">
          No runs yet.
        </Text>
      ) : (
        <div className={classes.runs}>
          {runs.map((run) => (
            <div key={run.id} className={classes.run}>
              <a className={classes.runLink} href={`/runs/${run.id}`}>
                <VerdictBadge verdict={run.verdict} label={run.verdictLabel} />
                <span className={classes.runName}>{run.testName}</span>
              </a>
              <span className={classes.runLevel}>{run.levelDisplay}</span>
              <span className={classes.runWhen}>{run.relativeTime}</span>
              <Button
                component="a"
                href={runAgainHref(id, run)}
                size="compact-xs"
                variant="default"
              >
                Run again
              </Button>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
