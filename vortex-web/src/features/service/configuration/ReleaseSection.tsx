import { useState } from 'react';
import { Button, Group, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useSetReleaseMutation } from '../../../api/configuration';
import { SectionDisclosure } from './SectionDisclosure';

export function ReleaseSection({
  serviceId,
  serviceVersion,
}: {
  serviceId: string;
  serviceVersion: string | null;
}) {
  const [value, setValue] = useState(serviceVersion ?? '');
  const mutation = useSetReleaseMutation(serviceId);

  function save() {
    mutation.mutate(
      { serviceVersion: value },
      { onSuccess: (r) => notifications.show({ message: r.message, color: 'pass' }) }
    );
  }

  return (
    <SectionDisclosure
      title="Release under test"
      openByDefault={!serviceVersion}
      state={serviceVersion ?? 'not recorded'}
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
      </Group>
    </SectionDisclosure>
  );
}
