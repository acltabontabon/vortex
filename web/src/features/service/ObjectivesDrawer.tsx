import { Drawer } from '@mantine/core';
import { ObjectivesSection } from './configuration/ObjectivesSection';

/** The "Objectives configured" step of the guided setup pipeline, scoped to just the three
 *  threshold fields rather than the full Configuration page. */
export function ObjectivesDrawer({
  serviceId,
  opened,
  onClose,
}: {
  serviceId: string;
  opened: boolean;
  onClose: () => void;
}) {
  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="lg" padding="xl" title="Set objectives">
      <ObjectivesSection serviceId={serviceId} onSaved={onClose} />
    </Drawer>
  );
}
