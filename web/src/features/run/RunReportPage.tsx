import { useParams } from 'react-router-dom';
import { Skeleton, Text } from '@mantine/core';
import { useRunQuery } from '../../api/run';
import { RunEvidenceView } from './RunEvidenceView';
import { errorFallback } from '../../lib/queryFallback';

/**
 * The same evidence {@link RunPage} shows, without the app around it — meant to be sent to someone
 * who was not in the room, or printed. No AI panel: that is exploratory interpretation of the
 * measurements, and a report standing in for the measurements themselves should not carry it.
 */
export function RunReportPage() {
  const { id = '' } = useParams();
  const { data, isError } = useRunQuery(id);

  const error = errorFallback(isError, 'Could not load this run');
  if (error) return error;

  if (!data) return <Skeleton height={480} radius="md" />;

  if (!data.terminal || !data.evidence) {
    return (
      <Text size="sm" c="dimmed">
        This run has not finished yet, so there is no report to show.
      </Text>
    );
  }

  return (
    <RunEvidenceView
      evidence={data.evidence}
      serviceId={data.plan.projectId}
      executionId={id}
      runAgainHref={null}
      variant="report"
    />
  );
}
