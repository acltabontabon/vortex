import { Button, Container, Group, Skeleton, Stack, Text, Title } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useServicesListQuery } from '../api/services';
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
              <div key={service.id} className={classes.row}>
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
                </Group>
              </div>
            ))}
          </Stack>
        )}
      </Stack>
    </Container>
  );
}
