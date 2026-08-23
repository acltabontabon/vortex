import { useState } from 'react';
import { Button, Group, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useSetReleaseMutation } from '../../../api/configuration';
import { SummaryField } from './SummaryField';

export function ReleaseSection({
  serviceId,
  serviceVersion,
}: {
  serviceId: string;
  serviceVersion: string | null;
}) {
  const [editing, setEditing] = useState(!serviceVersion);
  const [value, setValue] = useState(serviceVersion ?? '');
  const mutation = useSetReleaseMutation(serviceId);

  function save() {
    mutation.mutate(
      { serviceVersion: value },
      {
        onSuccess: (r) => {
          notifications.show({ message: r.message, color: 'pass' });
          setEditing(false);
        },
      }
    );
  }

  return (
    <SummaryField
      label="Release under test"
      display={serviceVersion ?? 'not recorded'}
      editing={editing}
      onEdit={() => setEditing(true)}
    >
      <Group align="flex-end" gap="sm">
        <TextInput
          label="Release identifier"
          placeholder="2.18.0"
          value={value}
          onChange={(e) => setValue(e.currentTarget.value)}
          maxLength={200}
          style={{ flex: 1 }}
        />
        <Button onClick={save} loading={mutation.isPending}>
          Save
        </Button>
        {serviceVersion && (
          <Button variant="default" onClick={() => setEditing(false)}>
            Cancel
          </Button>
        )}
      </Group>
    </SummaryField>
  );
}
