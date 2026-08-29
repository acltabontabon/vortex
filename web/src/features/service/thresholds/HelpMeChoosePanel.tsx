import { Button, Group, Skeleton, Stack, Text } from '@mantine/core';
import { useThresholdRecommendationQuery, type ThresholdRecommendationOptionDto } from '../../../api/thresholds';
import { EvidenceQualityBadge } from './EvidenceQualityBadge';
import { Fact, Facts } from '../../../components/Fact';
import classes from './HelpMeChoosePanel.module.css';

/**
 * The evidence-backed strategy picker — everything a "Help me choose" trigger opens. Every number
 * here, and the label naming where it came from, is computed by the backend; this only lays it out.
 * Renders honestly when there's nothing to show: missing evidence is a normal outcome, and the panel
 * still lets the user pick a value by hand.
 */
export function HelpMeChoosePanel({
  serviceId,
  workload,
  metric,
  percentile,
  onApply,
}: {
  serviceId: string;
  workload: string;
  metric: 'LATENCY' | 'ERROR_RATE';
  percentile: number | null;
  onApply: (recommendation: ThresholdRecommendationOptionDto) => void;
}) {
  const query = useThresholdRecommendationQuery(serviceId, workload, metric, percentile, null);

  if (query.isLoading) {
    return <Skeleton height={120} radius="sm" />;
  }
  if (query.isError || !query.data) {
    return (
      <Text size="sm" c="dimmed">
        Could not load evidence right now — you can still type a value directly.
      </Text>
    );
  }

  const { production, baselines, recommendations } = query.data;
  const bestBaseline = baselines[0] ?? null;
  const hasEvidence = production !== null || bestBaseline !== null;

  return (
    <Stack gap="sm" className={classes.panel}>
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        Help me choose
      </Text>
      {hasEvidence ? (
        <Facts>
          {production && (
            <Fact label="Production" note={production.window || production.sourceLabel}>
              <Group gap={6}>
                <Text size="sm" fw={600} style={{ whiteSpace: 'nowrap' }}>
                  {production.displayValue}
                </Text>
                <EvidenceQualityBadge quality={production.evidenceQuality} />
                {production.stale && (
                  <Text size="xs" c="warn">
                    stale
                  </Text>
                )}
              </Group>
            </Fact>
          )}
          {bestBaseline && (
            <Fact label="Vortex baseline" note={bestBaseline.sourceLabel}>
              <Group gap={6}>
                <Text size="sm" fw={600} style={{ whiteSpace: 'nowrap' }}>
                  {bestBaseline.displayValue}
                </Text>
                <EvidenceQualityBadge quality={bestBaseline.evidenceQuality} />
                {bestBaseline.stale && (
                  <Text size="xs" c="warn">
                    stale
                  </Text>
                )}
              </Group>
            </Fact>
          )}
        </Facts>
      ) : (
        <Text size="sm" c="dimmed">
          No baseline yet. You can set a manual objective now, run a baseline test, or connect
          production telemetry to get evidence-backed suggestions here.
        </Text>
      )}

      {recommendations.length > 0 && (
        <Stack gap={6}>
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
            Suggested starting points
          </Text>
          {recommendations.map((rec) => (
            <div key={rec.label} className={classes.option}>
              <Group justify="space-between" wrap="nowrap" gap="xs">
                <div>
                  <Text size="sm" fw={600}>
                    {rec.label} — {rec.displayValue}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {rec.derivation || rec.sourceLabel}
                  </Text>
                </div>
                <Button
                  size="compact-xs"
                  variant="light"
                  onClick={() => onApply(rec)}
                >
                  Use {rec.displayValue}
                </Button>
              </Group>
            </div>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
