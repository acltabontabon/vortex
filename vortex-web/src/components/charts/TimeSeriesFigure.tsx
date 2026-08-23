import { Text } from '@mantine/core';
import type { ObservabilityEvidence, TimelineEvidence } from '../../api/run';
import { TimelineChart } from './TimelineChart';
import classes from './TimeSeriesFigure.module.css';

/**
 * What each plot means, for a reader who has not just read the SLO breakpoint sentence above it.
 * Pure caption copy — never a substitute for the plot's own {@code label}, which stays the title.
 */
const SUBTITLE: Record<string, string> = {
  Throughput: 'Offered vs actual requests/sec',
  'p95 Latency': 'Response time under increasing load',
  'Error rate': 'Failed requests as a share of total',
};

const MARK_LABEL: Record<'breakpoint' | 'jump', string> = {
  breakpoint: 'First objective violation',
  jump: 'Traffic jump',
};

/**
 * The card-level answer for a test whose question is temporal rather than a load-level magnitude
 * — Soak asks whether performance degrades over a held duration, Smoke asks only whether the
 * workload is valid and the service reachable, and Spike asks how the service reacts to a sudden
 * jump and whether it recovers. None of the three has a real position to draw on the load-level
 * scale {@link CapacityRangeFigure}/{@code EvidenceScale} exist for: what matters here is the shape
 * of the metric across the run, which is exactly what {@code evidence.timeline} already carries,
 * whatever shape the underlying workload happened to take. See `testVisualization.ts` for which
 * kind gets which `annotation`.
 *
 * <p>Same plots {@code TimelineSection} draws on the full report, just compact for the inline card.
 *
 * <p>Every chart shares one time axis, so the marked instant — a compliance breakpoint or a traffic
 * jump, depending on `annotation` — lines up at the same x on all of them. The label naming it is
 * drawn once, on the first chart, rather than repeated on each.
 */
export function TimeSeriesFigure({
  timeline,
  annotation,
  secondaryReference,
  observability,
}: {
  timeline: TimelineEvidence;
  annotation: 'breakpoint' | 'jump';
  /** A magnitude fact worth stating once, as a caption, without turning a temporal test's chart back
   *  into a scale — e.g. Spike's peak vs. production. Omitted when there's nothing to say. */
  secondaryReference?: string | null;
  /** Soak's own "did anything grow" signal — real telemetry when a provider answered, never faked
   *  when one didn't. Vortex currently keeps this as a start→peak→end trace, not a full curve; see
   *  `ObservationTrace` in vortex-core for why, and revisit once a real time series exists for it. */
  observability?: ObservabilityEvidence | null;
}) {
  const plots = timeline.plots.filter((plot) => plot.hasData);
  if (plots.length === 0) return null;

  const markAtIso = annotation === 'jump' ? timeline.levelChangeAtIso : timeline.breakpointAtIso;
  const signals = observability?.present ? observability.signals : [];

  return (
    <div className={classes.plots}>
      {secondaryReference && (
        <Text size="xs" c="dimmed" mb={2}>
          {secondaryReference}
        </Text>
      )}

      {plots.map((plot, index) => (
        <div key={plot.label}>
          <Text size="sm" fw={600} mb={2}>
            {plot.label}
          </Text>
          {SUBTITLE[plot.label] && (
            <Text size="xs" c="dimmed" mb={6}>
              {SUBTITLE[plot.label]}
            </Text>
          )}
          <TimelineChart
            plot={plot}
            height={140}
            markAtIso={markAtIso}
            markLabel={index === 0 ? MARK_LABEL[annotation] : undefined}
          />
        </div>
      ))}

      {signals.length > 0 && (
        <div className={classes.signals}>
          <Text size="xs" fw={600} c="dimmed" mb={2}>
            Observed over the run
          </Text>
          {signals.map((signal) => (
            <Text key={signal.name} size="xs" c="dimmed">
              {signal.name}: {signal.movement ?? signal.display}
            </Text>
          ))}
        </div>
      )}
    </div>
  );
}
