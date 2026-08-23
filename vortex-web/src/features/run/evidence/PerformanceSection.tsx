import { Alert, Text, Title } from '@mantine/core';
import type { LoadSummary, PerformanceEvidence, Reliability, WorkloadEvidence } from '../../../api/run';
import { Fact, Facts } from '../../../components/Fact';
import { Unknown } from '../../../components/Unknown';
import shared from './shared.module.css';
import classes from './PerformanceSection.module.css';

/**
 * How the service behaved under the traffic it received — the requested and achieved load, what the
 * workload actually delivered, latency, and reliability, as one section instead of the old page's two
 * overlapping "Load" and "Service" cards.
 */
export function PerformanceSection({
  load,
  workload,
  performance,
  reliability,
  stageCount,
}: {
  load: LoadSummary;
  workload: WorkloadEvidence;
  performance: PerformanceEvidence;
  reliability: Reliability;
  stageCount: number;
}) {
  const isRamping = stageCount > 1;

  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Performance
      </Title>

      {load.droppedWork && (
        <Alert color="fail" title="The offered load was not generated" mb="sm">
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

      {performance.latencyRows.length > 0 && (
        <div className={classes.latencyRow}>
          {performance.latencyRows.map((row) => (
            <div key={row.percentileLabel} className={classes.latencyTile}>
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
                {row.percentileLabel}
              </Text>
              <Text size="md" fw={650}>
                {row.durationDisplay}
              </Text>
            </div>
          ))}
        </div>
      )}

      {performance.hasLimitsCard && (
        <Facts>
          {performance.sloBreakpointDisplay && (
            <Fact label="SLO breakpoint" note={performance.sloBreakpointStagesText}>
              {performance.sloBreakpointDisplay}
              {performance.sloBreakpointStrengthLabel && (
                <span className={shared.dim}> · {performance.sloBreakpointStrengthLabel}</span>
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
        </Facts>
      )}

      {workload.operationMix.length > 0 && (
        <ul className={shared.list}>
          {workload.operationMix.map((row) => (
            <li key={row}>{row}</li>
          ))}
        </ul>
      )}

      <ReliabilityDetail reliability={reliability} />
    </section>
  );
}

function ReliabilityDetail({ reliability }: { reliability: Reliability }) {
  if (!reliability.reported) {
    return (
      <Unknown
        compact
        what="How requests failed was not classified"
        reason="The engine reported no status information for this run. That is not the same as every request having succeeded."
      />
    );
  }
  if (reliability.byResponseClass.length === 0 && reliability.byFailureClass.length === 0) {
    return null;
  }
  return (
    <details className={shared.disclosure}>
      <summary>Outcome breakdown</summary>
      <table className={classes.outcomeTable}>
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
    </details>
  );
}
