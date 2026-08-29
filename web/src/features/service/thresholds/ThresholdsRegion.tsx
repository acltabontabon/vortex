import { useEffect } from 'react';
import { useDebouncedValue } from '@mantine/hooks';
import { Group, Stack, Text } from '@mantine/core';
import {
  useThresholdRecommendationQuery,
  useThresholdSanityCheckMutation,
  type SanityFindingDto,
  type ThresholdDto,
  type ThresholdProvenanceDto,
  type ThresholdRecommendationOptionDto,
} from '../../../api/thresholds';
import { ThresholdRow } from './ThresholdRow';
import { ThresholdScaleBar, type ScaleMarker } from '../../../components/charts/ThresholdScaleBar';

export interface ThresholdsRegionValues {
  p95Millis: number | '';
  p99Millis: number | '';
  errorPercent: number | '';
}

/**
 * The Threshold Assistant's three overall objectives (p95/p99 latency, error rate) — evidence
 * context, live comparison, "Help me choose" and the evidence card for each. Reused wherever
 * thresholds are configured: the service-level Objectives section is the normal home (see
 * `ObjectivesSection`), with `workloadName` left empty so evidence aggregates across the whole
 * project; passing a specific workload name narrows evidence to that workload's own history instead.
 * Per-operation thresholds are a separate, later surface — see `ThresholdSet.hasOperationScopedThresholds`.
 *
 * <p>Purely presentational and evidence-fetching: it live-checks whatever is currently typed and
 * reports values up so the caller decides when and how to persist them (see `ObjectivesSection`'s own
 * save call) — saving stays the caller's explicit act, the same "retrieval and adoption are separate
 * steps" discipline production evidence already follows elsewhere in this app.
 */
export function ThresholdsRegion({
  serviceId,
  workloadName,
  values,
  onChange,
  provenance,
  onProvenanceChange,
}: {
  serviceId: string;
  /** Empty for the normal, service-level case (evidence aggregates across the whole project); a
   *  specific workload name narrows baseline candidacy to that workload's own history. */
  workloadName: string;
  values: ThresholdsRegionValues;
  onChange: (values: ThresholdsRegionValues) => void;
  provenance: Record<string, ThresholdProvenanceDto>;
  onProvenanceChange: (thresholdId: string, provenance: ThresholdProvenanceDto) => void;
}) {
  const [debounced] = useDebouncedValue(values, 300);
  const sanityCheck = useThresholdSanityCheckMutation(serviceId);

  const p95Evidence = useThresholdRecommendationQuery(serviceId, workloadName, 'LATENCY', 95, null, !!workloadName);
  const p99Evidence = useThresholdRecommendationQuery(serviceId, workloadName, 'LATENCY', 99, null, !!workloadName);
  const errorEvidence = useThresholdRecommendationQuery(serviceId, workloadName, 'ERROR_RATE', null, null, !!workloadName);

  useEffect(() => {
    const thresholds: ThresholdDto[] = [];
    const productionByThresholdId: Record<string, number> = {};
    const baselineByThresholdId: Record<string, number> = {};

    if (debounced.p95Millis !== '') {
      thresholds.push({ kind: 'LATENCY', percentile: 95, maxMillis: debounced.p95Millis });
      if (p95Evidence.data?.production) productionByThresholdId['latency.p95'] = p95Evidence.data.production.rawValue;
      if (p95Evidence.data?.baselines[0]) baselineByThresholdId['latency.p95'] = p95Evidence.data.baselines[0].rawValue;
    }
    if (debounced.p99Millis !== '') {
      thresholds.push({ kind: 'LATENCY', percentile: 99, maxMillis: debounced.p99Millis });
      if (p99Evidence.data?.production) productionByThresholdId['latency.p99'] = p99Evidence.data.production.rawValue;
      if (p99Evidence.data?.baselines[0]) baselineByThresholdId['latency.p99'] = p99Evidence.data.baselines[0].rawValue;
    }
    if (debounced.errorPercent !== '') {
      thresholds.push({ kind: 'ERROR_RATE', maxErrorPercent: debounced.errorPercent });
      if (errorEvidence.data?.production) productionByThresholdId.errorRate = errorEvidence.data.production.rawValue;
      if (errorEvidence.data?.baselines[0]) baselineByThresholdId.errorRate = errorEvidence.data.baselines[0].rawValue;
    }
    if (thresholds.length === 0) return;

    sanityCheck.mutate({ thresholds, workload: workloadName, productionByThresholdId, baselineByThresholdId });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced, p95Evidence.data, p99Evidence.data, errorEvidence.data]);

  const findingsFor = (thresholdId: string): SanityFindingDto[] =>
    sanityCheck.data?.findings.filter((f) => f.thresholdId === thresholdId) ?? [];

  function applyRecommendation(
    thresholdId: string,
    field: keyof ThresholdsRegionValues,
    recommendation: ThresholdRecommendationOptionDto
  ) {
    const rawValue = recommendation.rawValue;
    onChange({ ...values, [field]: field === 'errorPercent' ? rawValue : Math.round(rawValue) });
    onProvenanceChange(thresholdId, {
      source: recommendation.source,
      sourceLabel: recommendation.sourceLabel,
      detail: recommendation.label,
      derivation: recommendation.derivation,
      evidenceQuality: recommendation.evidenceQuality,
      baselineExecutionId: '',
    });
  }

  const p95Markers: ScaleMarker[] = [
    p95Evidence.data?.baselines[0] && { value: p95Evidence.data.baselines[0].rawValue, label: p95Evidence.data.baselines[0].displayValue, kind: 'baseline' as const },
    p95Evidence.data?.production && { value: p95Evidence.data.production.rawValue, label: p95Evidence.data.production.displayValue, kind: 'production' as const },
    values.p95Millis !== '' && { value: values.p95Millis, label: `${values.p95Millis} ms`, kind: 'objective' as const },
  ].filter((m): m is ScaleMarker => !!m);

  return (
    <Stack gap="lg">
      <Text size="sm" c="dimmed">
        What counts as a passing run. Type a value directly, or use Help me choose to see what
        evidence Vortex already has.
      </Text>

      <ThresholdRow
        label="P95 latency"
        unit="ms"
        value={values.p95Millis}
        onChange={(v) => onChange({ ...values, p95Millis: v })}
        comparisonText={sanityCheck.data?.comparisons['latency.p95'] ?? null}
        findings={findingsFor('latency.p95')}
        serviceId={serviceId}
        workload={workloadName}
        metric="LATENCY"
        percentile={95}
        provenance={provenance['latency.p95'] ?? null}
        onApplyRecommendation={(rec) => applyRecommendation('latency.p95', 'p95Millis', rec)}
      />
      {p95Markers.length >= 2 && (
        <Group justify="center">
          <ThresholdScaleBar markers={p95Markers} />
        </Group>
      )}

      <ThresholdRow
        label="P99 latency"
        unit="ms"
        value={values.p99Millis}
        onChange={(v) => onChange({ ...values, p99Millis: v })}
        comparisonText={sanityCheck.data?.comparisons['latency.p99'] ?? null}
        findings={findingsFor('latency.p99')}
        serviceId={serviceId}
        workload={workloadName}
        metric="LATENCY"
        percentile={99}
        provenance={provenance['latency.p99'] ?? null}
        onApplyRecommendation={(rec) => applyRecommendation('latency.p99', 'p99Millis', rec)}
      />

      <ThresholdRow
        label="Error rate"
        unit="%"
        value={values.errorPercent}
        onChange={(v) => onChange({ ...values, errorPercent: v })}
        min={0}
        step={0.01}
        comparisonText={sanityCheck.data?.comparisons.errorRate ?? null}
        findings={findingsFor('errorRate')}
        serviceId={serviceId}
        workload={workloadName}
        metric="ERROR_RATE"
        percentile={null}
        provenance={provenance.errorRate ?? null}
        onApplyRecommendation={(rec) => applyRecommendation('errorRate', 'errorPercent', rec)}
      />
    </Stack>
  );
}
