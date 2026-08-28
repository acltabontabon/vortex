import { Drawer } from '@mantine/core';
import { ImportForm } from './configuration/ImportForm';

/** The "Import OpenAPI" step of the guided setup pipeline, scoped to just what it needs — never
 *  the full Configuration page, which is what {@link ImportForm} is otherwise embedded in. */
export function ImportOpenApiDrawer({
  serviceId,
  opened,
  onClose,
}: {
  serviceId: string;
  opened: boolean;
  onClose: () => void;
}) {
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size="xl"
      padding="xl"
      title="Import an API description"
    >
      <ImportForm serviceId={serviceId} onImported={onClose} showHeading={false} />
    </Drawer>
  );
}
