import { Outlet, useParams } from 'react-router-dom';
import { Skeleton, Stack } from '@mantine/core';
import { useServiceHeaderQuery } from '../../api/workspace';
import { ServiceHeader } from './ServiceHeader';
import { errorFallback } from '../../lib/queryFallback';

/**
 * The frame every screen about one service renders inside.
 *
 * <p>No subnav any more. Overview absorbed what the Tests, Runs and Evidence tabs used to own —
 * they were separate destinations for information Overview already shows, which made "go look at
 * the tests" a navigation instead of a glance down the page. Runs, Evidence and Configuration still
 * exist as routes (a deep link to one still works, and the odd escape hatch — "View evidence" from
 * an attention alert, "View all" from the recent-runs rail — still points at them), but nothing on
 * the page advertises them as places to go browsing. Tests went further: it has no route of its own
 * any more (`/services/:id/tests` redirects here) since nothing it showed wasn't already on this
 * page. The breadcrumb's service-switcher dropdown doubles as the way back to this page from any of
 * the pages that remain.
 */
export function ServiceLayout() {
  const { id = '' } = useParams();
  const header = useServiceHeaderQuery(id);

  const error = errorFallback(header.isError, 'Could not load this service',
      `/api/services/${id} did not respond. Reload the page to try again.`);
  if (error) return error;

  if (!header.data) {
    return (
      <Stack gap="lg">
        <Skeleton height={92} radius="md" />
        <Skeleton height={280} radius="md" />
      </Stack>
    );
  }

  return (
    <Stack gap="xl">
      <ServiceHeader header={header.data} />
      <Outlet />
    </Stack>
  );
}
