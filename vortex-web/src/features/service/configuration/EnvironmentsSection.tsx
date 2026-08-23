import { useState } from 'react';
import { Button, Text } from '@mantine/core';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { IconPlus } from '@tabler/icons-react';
import type { Environment, EnvironmentOption } from '../../../api/configuration';
import { useDeleteEnvironmentMutation } from '../../../api/configuration';
import { ApiError } from '../../../api/client';
import { EnvironmentCard } from './EnvironmentCard';
import { EnvironmentDrawer, type EnvironmentDrawerState } from './EnvironmentDrawer';
import classes from './EnvironmentsSection.module.css';

/**
 * Where Vortex can run a test — a stack of environments, each a first-class object with its own
 * Edit and Delete, rather than a list of facts followed by one shared add-form. "+ Add environment"
 * opens the same editor Edit does, just empty.
 */
export function EnvironmentsSection({
  serviceId,
  environments,
  environmentTypes,
  dependencyModes,
}: {
  serviceId: string;
  environments: Environment[];
  environmentTypes: EnvironmentOption[];
  dependencyModes: EnvironmentOption[];
}) {
  const [drawerState, setDrawerState] = useState<EnvironmentDrawerState | null>(null);
  const remove = useDeleteEnvironmentMutation(serviceId);

  function confirmDelete(environment: Environment) {
    modals.openConfirmModal({
      title: `Delete environment '${environment.name}'?`,
      children: (
        <Text size="sm">
          This removes only the configuration entry. A run stores the environment&apos;s name as a
          snapshot, not a live reference — evidence already recorded keeps its own copy and is not
          affected.
        </Text>
      ),
      labels: { confirm: 'Delete environment', cancel: 'Cancel' },
      confirmProps: { color: 'fail' },
      onConfirm: () =>
        remove.mutate(environment.name, {
          onSuccess: (response) => notifications.show({ message: response.message, color: 'pass' }),
          onError: (error) =>
            notifications.show({
              message:
                error instanceof ApiError && error.detail
                  ? error.detail
                  : `Vortex could not delete '${environment.name}'.`,
              color: 'fail',
            }),
        }),
    });
  }

  return (
    <div>
      {environments.length === 0 ? (
        <Text size="sm" c="dimmed" className={classes.empty}>
          No environments configured yet — Vortex has nowhere to run a test.
        </Text>
      ) : (
        <div className={classes.list}>
          {environments.map((environment) => (
            <EnvironmentCard
              key={environment.name}
              environment={environment}
              onEdit={() => setDrawerState({ mode: 'edit', environment })}
              onDelete={() => confirmDelete(environment)}
            />
          ))}
        </div>
      )}

      <Button
        mt="sm"
        size="xs"
        variant="default"
        leftSection={<IconPlus size={14} />}
        onClick={() => setDrawerState({ mode: 'create' })}
      >
        Add environment
      </Button>

      <EnvironmentDrawer
        state={drawerState}
        existingNames={environments.map((e) => e.name)}
        environmentTypes={environmentTypes}
        dependencyModes={dependencyModes}
        serviceId={serviceId}
        onClose={() => setDrawerState(null)}
      />
    </div>
  );
}
