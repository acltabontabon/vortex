import { Text } from '@mantine/core';
import type { Configuration } from '../../../api/configuration';

/**
 * One line: what Vortex already knows about this service, at a glance — not a checklist or a
 * completion percentage, just the same facts the sections below state in more detail.
 */
export function ConfigurationCompleteness({ configuration }: { configuration: Configuration }) {
  const parts = [
    `${configuration.catalog.operationCount} operation${configuration.catalog.operationCount === 1 ? '' : 's'}`,
    `${configuration.environments.length} environment${configuration.environments.length === 1 ? '' : 's'}`,
    configuration.production ? 'production calibrated' : 'production not recorded',
    configuration.thresholds.describe.length > 0 ? 'objectives configured' : 'objectives not set',
  ];

  return (
    <Text size="sm" c="dimmed">
      {parts.join(' · ')}
    </Text>
  );
}
