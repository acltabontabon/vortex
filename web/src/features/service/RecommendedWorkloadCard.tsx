import { Button } from '@mantine/core';
import { useRecommendationQuery, type RecommendationDto } from '../../api/tests';
import classes from './TestComposer.module.css';

/**
 * "What Vortex recommends for this Intent" — a compact, always-visible summary of a
 * `WorkloadRecommendation`, never computed here: the purpose sentence, the headline and every
 * number behind "Use recommended" come straight from `GET /tests/recommendation`. Fails soft (no
 * card, not an error banner) so the composer stays usable if the recommendation can't be fetched —
 * the rest of Load still works with its own defaults.
 */
export function RecommendedWorkloadCard({
  serviceId,
  type,
  model,
  typeLabel,
  onApply,
}: {
  serviceId: string;
  type: string;
  model: 'OPEN' | 'CLOSED';
  typeLabel: string;
  onApply: (recommendation: RecommendationDto) => void;
}) {
  const query = useRecommendationQuery(serviceId, type, model);
  if (query.isError || !query.data) {
    return null;
  }
  const rec = query.data;

  return (
    <div className={classes.recommendedCard}>
      <span className={classes.recommendedLabel}>Recommended for {typeLabel}</span>
      <p className={classes.recommendedPurpose}>{rec.purpose}</p>
      <p className={classes.recommendedHeadline}>{rec.headline}</p>
      <Button size="xs" variant="light" onClick={() => onApply(rec)}>
        Use recommended
      </Button>
    </div>
  );
}
