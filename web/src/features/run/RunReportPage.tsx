import { useParams } from 'react-router-dom';
import { Anchor, Skeleton, Stack, Text } from '@mantine/core';
import { useRunQuery } from '../../api/run';
import { RunEvidenceView } from './RunEvidenceView';
import { errorFallback } from '../../lib/queryFallback';

/**
 * The same evidence {@link RunPage} shows, without the app around it — meant to be sent to someone
 * who was not in the room, or printed. No AI panel: that is exploratory interpretation of the
 * measurements, and a report standing in for the measurements themselves should not carry it.
 *
 * <p>Lives under {@link PrintLayout}, which deliberately has no topbar or breadcrumb — so unlike
 * every other page, this one has no other way back into the app. The link below is that page's own
 * content, not shared chrome, which is why it belongs here rather than in the layout.
 */
export function RunReportPage() {
  const { id = '' } = useParams();
  const { data, isError } = useRunQuery(id);

  const error = errorFallback(isError, 'Could not load this run');
  if (error) return error;

  if (!data) return <Skeleton height={480} radius="md" />;

  const backToService = (
    <Anchor href={`/services/${data.plan.projectId}`} size="sm">
      ← {data.plan.projectName}
    </Anchor>
  );

  if (!data.terminal || !data.evidence) {
    return (
      <Stack gap="md">
        {backToService}
        <Text size="sm" c="dimmed">
          This run has not finished yet, so there is no report to show.
        </Text>
      </Stack>
    );
  }

  return (
    <Stack gap="md">
      {backToService}
      <RunEvidenceView
        evidence={data.evidence}
        serviceId={data.plan.projectId}
        executionId={id}
        runAgainHref={null}
        variant="report"
      />
    </Stack>
  );
}
