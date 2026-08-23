import { Text } from '@mantine/core';
import type {
  AcceptanceEvidence,
  LoadSummary,
  PerformanceEvidence,
  Reliability,
  Resources,
  VerdictSection as VerdictSectionData,
} from '../../../api/run';
import { VerdictBadge } from '../../../components/VerdictBadge';
import type { TestTypeEmphasis } from './testTypeEmphasis';
import classes from './KeyMetrics.module.css';

type TileKey = 'load' | 'latency' | 'errors' | 'resources' | 'capacity';

interface Tile {
  key: TileKey;
  label: string;
  value: string;
  note?: string;
  tone?: 'pass' | 'fail' | 'warn' | 'neutral';
}

/**
 * The five figures that answer "did it pass, how fast, how much broke, how close to a limit" without
 * scrolling — a dense strip of facts, not five dashboard cards. Ordered by {@code emphasis}, so a
 * stress run leads with capacity while a smoke run leads with whether the workload ran at all.
 *
 * <p>Every value here already exists on {@code RunEvidence}; this only selects and orders it —
 * finding the worst resource signal, matching a latency row to its objective — never computing a
 * number the domain didn't already produce.
 */
export function KeyMetrics({
  verdict,
  load,
  performance,
  acceptance,
  reliability,
  resources,
  capacitySummary,
  emphasis,
}: {
  verdict: VerdictSectionData;
  load: LoadSummary;
  performance: PerformanceEvidence;
  acceptance: AcceptanceEvidence;
  reliability: Reliability;
  resources: Resources;
  capacitySummary: string | null;
  emphasis: TestTypeEmphasis;
}) {
  const latencyObjective = acceptance.results.find((r) => r.kind === 'LATENCY');
  const errorObjective = acceptance.results.find((r) => r.kind === 'ERROR_RATE');
  const p95 = performance.latencyRows.find((row) => row.percentileLabel.toLowerCase() === 'p95')
    ?? performance.latencyRows[0];
  const worstResource = [...resources.service].sort(
    (a, b) => (b.utilisationFraction ?? -1) - (a.utilisationFraction ?? -1),
  )[0];

  const tiles: Record<TileKey, Tile> = {
    load: {
      key: 'load',
      label: 'Load',
      value: load.achievedDisplay || load.requestedDisplay || '—',
      note: load.requestedDisplay ? `requested ${load.requestedDisplay}` : undefined,
    },
    latency: {
      key: 'latency',
      label: p95 ? p95.percentileLabel : 'Latency',
      value: p95 ? p95.durationDisplay : '—',
      note: latencyObjective ? latencyObjective.describe : undefined,
      tone: latencyObjective
        ? (latencyObjective.verdict === 'FAIL' ? 'fail' : latencyObjective.verdict === 'PASS' ? 'pass' : 'neutral')
        : undefined,
    },
    errors: {
      key: 'errors',
      label: 'Errors',
      value: reliability.errorRateDisplay || '—',
      note: errorObjective ? errorObjective.describe : undefined,
      tone: errorObjective
        ? (errorObjective.verdict === 'FAIL' ? 'fail' : errorObjective.verdict === 'PASS' ? 'pass' : 'neutral')
        : undefined,
    },
    resources: {
      key: 'resources',
      label: 'Resources',
      value: worstResource ? worstResource.utilisationDisplay || worstResource.display : '—',
      note: worstResource ? `peak ${worstResource.name}` : 'not observed',
      tone: worstResource?.atItsLimit ? 'fail' : undefined,
    },
    capacity: {
      key: 'capacity',
      label: 'Capacity',
      value: capacitySummary ?? 'Not established',
      tone: capacitySummary ? 'pass' : 'neutral',
    },
  };

  return (
    <div className={classes.strip}>
      <div className={classes.tile}>
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
          Result
        </Text>
        <div className={classes.value}>
          <VerdictBadge verdict={verdict.verdict as 'PASS' | 'FAIL' | 'NOT_EVALUATED'} label={verdict.verdictLabel} size="lg" subtleText />
        </div>
      </div>
      {emphasis.keyMetricsPriority.map((key) => {
        const tile = tiles[key];
        return (
          <div key={tile.key} className={classes.tile}>
            <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
              {tile.label}
            </Text>
            <Text size="lg" fw={650} className={tile.tone ? classes[`tone_${tile.tone}`] : undefined}>
              {tile.value}
            </Text>
            {tile.note && (
              <Text size="xs" c="dimmed">
                {tile.note}
              </Text>
            )}
          </div>
        );
      })}
    </div>
  );
}
