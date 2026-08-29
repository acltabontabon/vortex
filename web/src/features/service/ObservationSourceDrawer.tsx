import { Drawer } from '@mantine/core';
import type { ObservationSource } from '../../api/configuration';
import { ObservationSourcePanel } from './configuration/ObservationSourcePanel';

/** Where production traffic can be fetched from — Prometheus or Dynatrace — scoped to its own
 *  drawer so opening it reads as "edit this one thing" rather than an inline panel sharing space
 *  (and a repurposed "Cancel" label) with the unrelated "record manually" action. */
export function ObservationSourceDrawer({
  serviceId,
  serviceName,
  source,
  opened,
  onClose,
}: {
  serviceId: string;
  serviceName: string;
  source: ObservationSource | null;
  opened: boolean;
  onClose: () => void;
}) {
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size="lg"
      padding="xl"
      title={source ? 'Edit observation source' : 'Configure observation source'}
    >
      <ObservationSourcePanel serviceId={serviceId} serviceName={serviceName} source={source} onSaved={onClose} />
    </Drawer>
  );
}
