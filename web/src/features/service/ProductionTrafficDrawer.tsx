import { Drawer, Text } from '@mantine/core';
import type { Catalog } from '../../api/configuration';
import { RecordedTrafficPanel } from './configuration/RecordedTrafficPanel';

/** The "Production traffic recorded" step of the guided setup pipeline, scoped to a manual
 *  ballpark entry — never the full Configuration page, and never gated on an observation source
 *  being connected first. A live source can still be configured later from Configuration. */
export function ProductionTrafficDrawer({
  serviceId,
  catalog,
  opened,
  onClose,
}: {
  serviceId: string;
  catalog: Catalog | undefined;
  opened: boolean;
  onClose: () => void;
}) {
  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="lg" padding="xl" title="Record production traffic">
      <Text size="sm" c="dimmed" mb="sm">
        A ballpark figure is fine, even before this service is really in production — Vortex just
        needs something to calibrate a workload against.
      </Text>
      {catalog && <RecordedTrafficPanel serviceId={serviceId} catalog={catalog} onSaved={onClose} />}
    </Drawer>
  );
}
