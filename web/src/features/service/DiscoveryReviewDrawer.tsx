import { useEffect } from 'react';
import { Alert, Drawer, Loader, Stack, Text } from '@mantine/core';
import {
  useApplyServiceDiscoveryMutation,
  useServiceDiscoveryScanMutation,
} from '../../api/discovery';
import { extractErrorMessage } from '../../lib/queryFallback';
import { DiscoveryReviewPanel, type DiscoverySelections } from '../../pages/DiscoveryReviewPanel';

/**
 * "Discover from project" for an already-created service — re-reads its project directory, shows
 * what changed or was found, and applies whatever subset a person approves. Scanning happens once,
 * on open; "Scan again" is just closing and reopening this drawer, which is all re-discovery ever
 * needs to be (see docs/adr/adr-063-project-discovery-is-synchronous-and-stateless.adoc).
 */
export function DiscoveryReviewDrawer({
  serviceId,
  opened,
  onClose,
}: {
  serviceId: string;
  opened: boolean;
  onClose: () => void;
}) {
  const scan = useServiceDiscoveryScanMutation(serviceId);
  const apply = useApplyServiceDiscoveryMutation(serviceId);

  useEffect(() => {
    if (opened) {
      scan.mutate();
      apply.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened]);

  function handleApply(selections: DiscoverySelections) {
    if (!scan.data?.ok) {
      return;
    }
    apply.mutate(
      {
        applyOpenApiSource: selections.includeOpenApiSource,
        openApiSourceFile: scan.data.proposedOpenApiSourceFile ?? undefined,
        applyEnvironment: selections.includeEnvironment,
        environment: scan.data.proposedEnvironment ?? undefined,
        applyLocalLab: selections.includeLocalLab,
        localLabComposeFile: scan.data.proposedLocalLabComposeFile ?? undefined,
      },
      { onSuccess: onClose },
    );
  }

  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size="lg"
      padding="xl"
      title="Discover from project"
    >
      {scan.isPending && (
        <Stack align="center" py="xl" gap="xs">
          <Loader size="sm" />
          <Text size="sm" c="dimmed">
            Inspecting the project…
          </Text>
        </Stack>
      )}

      {scan.isError && (
        <Alert color="fail" title="Could not inspect this project">
          {extractErrorMessage(scan, 'Something went wrong. Try again.')}
        </Alert>
      )}

      {scan.data && !scan.data.ok && (
        <Alert color="warn" title="Could not inspect this project">
          {scan.data.error}
        </Alert>
      )}

      {scan.data?.ok && (
        <DiscoveryReviewPanel
          proposal={scan.data}
          onApply={handleApply}
          applying={apply.isPending}
          applyError={extractErrorMessage(apply, 'Could not apply this setup.')}
        />
      )}
    </Drawer>
  );
}
