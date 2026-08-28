import { Drawer } from '@mantine/core';
import type { ThresholdEdit } from '../../api/configuration';
import { ObjectivesSection } from './configuration/ObjectivesSection';

/** The "Objectives configured" step of the guided setup pipeline, scoped to just the three
 *  threshold fields rather than the full Configuration page. */
export function ObjectivesDrawer({
  serviceId,
  thresholds,
  opened,
  onClose,
}: {
  serviceId: string;
  thresholds: ThresholdEdit | undefined;
  opened: boolean;
  onClose: () => void;
}) {
  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="lg" padding="xl" title="Set objectives">
      {thresholds && <ObjectivesSection serviceId={serviceId} thresholds={thresholds} onSaved={onClose} />}
    </Drawer>
  );
}
