import { Group, NumberInput, Text } from '@mantine/core';
import type {
  SanityFindingDto,
  ThresholdProvenanceDto,
  ThresholdRecommendationOptionDto,
} from '../../../api/thresholds';
import { InfoPopover } from '../../../components/InfoPopover';
import { HelpMeChoosePanel } from './HelpMeChoosePanel';
import { EvidenceCard } from './EvidenceCard';
import classes from './ThresholdRow.module.css';

const SEVERITY_COLOR: Record<string, string> = { CAUTION: 'warn', INVALID: 'fail', INFORMATION: 'dimmed' };

/**
 * One threshold objective — richer than a bare numeric field, still compact. Metric, value,
 * evidence context and consequence in one row, "Help me choose" understated beside the field rather
 * than branded as an AI feature. All comparison text and findings are computed by the backend; this
 * only lays out what it's given.
 */
export function ThresholdRow({
  label,
  unit,
  value,
  onChange,
  min = 1,
  step = 1,
  comparisonText,
  findings,
  serviceId,
  workload,
  metric,
  percentile,
  provenance,
  onApplyRecommendation,
}: {
  label: string;
  unit: string;
  value: number | '';
  onChange: (value: number | '') => void;
  min?: number;
  step?: number;
  comparisonText: string | null;
  findings: SanityFindingDto[];
  serviceId: string;
  workload: string;
  metric: 'LATENCY' | 'ERROR_RATE';
  percentile: number | null;
  provenance: ThresholdProvenanceDto | null;
  onApplyRecommendation: (recommendation: ThresholdRecommendationOptionDto) => void;
}) {
  const worstFinding = findings.find((f) => f.severity === 'INVALID') ?? findings[0] ?? null;
  const displayValue = value === '' ? '' : `${value}${unit === 'ms' ? ' ms' : '%'}`;

  return (
    <div className={classes.row}>
      <Group justify="space-between" align="flex-end" wrap="nowrap">
        <NumberInput
          label={label}
          suffix={unit === 'ms' ? ' ms' : '%'}
          min={min}
          step={step}
          value={value}
          onChange={(v) => onChange(v === '' ? '' : Number(v))}
          className={classes.input}
        />
        <InfoPopover icon width={340} ariaLabel="Help me choose">
          <HelpMeChoosePanel
            serviceId={serviceId}
            workload={workload}
            metric={metric}
            percentile={percentile}
            onApply={onApplyRecommendation}
          />
        </InfoPopover>
      </Group>

      {comparisonText && (
        <Text size="xs" c="dimmed" mt={4}>
          {comparisonText}
        </Text>
      )}

      {worstFinding && (
        <Text size="xs" c={SEVERITY_COLOR[worstFinding.severity] ?? 'dimmed'} mt={2}>
          {worstFinding.message}
        </Text>
      )}

      {value !== '' && (
        <div className={classes.evidence}>
          <EvidenceCard value={displayValue} provenance={provenance} />
        </div>
      )}
    </div>
  );
}
