import { ActionIcon, Button, Container, Group, Skeleton, Stack, Text, Title, Tooltip } from '@mantine/core';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { ApiError } from '../api/client';
import { useDeleteServiceMutation, useServicesListQuery, type ServiceListItem } from '../api/services';
import { Unknown } from '../components/Unknown';
import { errorFallback } from '../lib/queryFallback';
import classes from './ServicesPage.module.css';

/**
 * Every service Vortex knows about, one row each.
 *
 * <p>Reached from the topbar's service switcher ("All services") and from the homepage when there
 * is more than one — this is the plain list behind both, not a second source of truth for what a
 * service is.
 */
export function ServicesPage() {
  const { data, isError } = useServicesListQuery();
  const error = errorFallback(isError, 'Could not load services',
      '/api/services did not respond. Reload the page to try again.');

  return (
    <Container size={900} px={0} py="xl">
      <Stack gap="lg">
        <Group justify="space-between" align="flex-start">
          <div>
            <Title order={1} size="h2">
              Services
            </Title>
            <Text c="dimmed" size="sm">
              One entry per service you performance-test.
            </Text>
          </div>
          <Button component="a" href="/services/new" leftSection={<IconPlus size={16} />}>
            Add a service
          </Button>
        </Group>

        {error}

        {!isError && !data && <Skeleton height={120} radius="md" />}

        {data && data.length === 0 && (
          <Unknown
            what="No services yet."
            reason="A service holds everything Vortex needs to measure one system: the operations it exposes, the workloads that describe how traffic reaches them, and the objectives it has to meet."
            actionLabel="Add a service"
            actionHref="/services/new"
          />
        )}

        {data && data.length > 0 && (
          <Stack gap="sm">
            {data.map((service) => (
              <ServiceRow key={service.id} service={service} />
            ))}
          </Stack>
        )}
      </Stack>
    </Container>
  );
}

function ServiceRow({ service }: { service: ServiceListItem }) {
  const remove = useDeleteServiceMutation();

  function confirmDelete() {
    modals.openConfirmModal({
      title: `Delete '${service.name}'?`,
      children: (
        <Text size="sm">
          This removes every run, analysis and piece of evidence Vortex has recorded for this
          service. There is no undo. The service's own <code>vortex.yaml</code>, if it has one, is
          left where it is — this only removes it from Vortex.
        </Text>
      ),
      labels: { confirm: 'Delete', cancel: 'Cancel' },
      confirmProps: { color: 'fail' },
      onConfirm: () =>
        remove.mutate(service.id, {
          onSuccess: () =>
            notifications.show({ message: `'${service.name}' deleted.`, color: 'pass' }),
          onError: (error) =>
            notifications.show({
              message:
                error instanceof ApiError && error.detail
                  ? error.detail
                  : `Vortex could not delete '${service.name}'.`,
              color: 'fail',
            }),
        }),
    });
  }

  return (
    <div className={classes.row}>
      <div className={classes.main}>
        <a href={`/services/${service.id}`} className={classes.name}>
          {service.name}
        </a>
        {service.description && (
          <Text size="sm" c="dimmed" className={classes.description}>
            {service.description}
          </Text>
        )}
        {service.serviceVersion && (
          <Text size="xs" c="dimmed" className={classes.release}>
            Release under test <span className={classes.mono}>{service.serviceVersion}</span>
          </Text>
        )}
      </div>
      <Group gap="xs" wrap="nowrap">
        <Button component="a" href={`/services/${service.id}`} size="xs">
          Open
        </Button>
        <Tooltip label="Delete service" openDelay={400} withArrow>
          <ActionIcon
            variant="subtle"
            color="fail"
            size="lg"
            aria-label="Delete service"
            loading={remove.isPending}
            onClick={confirmDelete}
          >
            <IconTrash size={16} stroke={1.6} />
          </ActionIcon>
        </Tooltip>
      </Group>
    </div>
  );
}
