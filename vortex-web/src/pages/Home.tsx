import { useMemo, useState } from 'react';
import {
  Container,
  Title,
  Text,
  Group,
  Stack,
  Button,
  SimpleGrid,
  Skeleton,
} from '@mantine/core';
import {
  IconTarget,
  IconShieldCheck,
  IconChartBar,
  IconActivity,
  IconPlus,
} from '@tabler/icons-react';
import { useHomeQuery, type ServiceCard } from '../api/home';
import { ActionTile } from '../components/ActionTile';
import { ServiceShelf } from '../components/ServiceShelf';
import { CommandBar } from '../components/CommandBar';
import { MeasurementRule } from '../components/MeasurementRule';
import { recencyMillis } from '../lib/workbenchState';
import { errorFallback } from '../lib/queryFallback';

export function Home() {
  const { data, isError } = useHomeQuery();
  const error = errorFallback(isError, 'Could not load the workbench',
      '/api/home did not respond. Reload the page to try again.');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const cards = data?.cards ?? [];
  const hasServices = cards.length > 0;

  // Active work first, then whichever of "last run" or "last touched" is more recent — see
  // recencyMillis. The shelf and the command bar both key off this order, not creation order.
  const sortedCards = useMemo(
    () => [...cards].sort((a, b) => recencyMillis(b) - recencyMillis(a)),
    [cards],
  );

  // No default selection — the homepage opens showing services, not already "inside" one. Picking
  // a service is a deliberate act that establishes homepage context, not something assumed for you.
  const selected = sortedCards.find((c) => c.id === selectedId) ?? null;

  return (
    <Container size={1560} px={0} py="xl">
      <Stack gap="xl">
        {error}

        {!error && !data && <Skeleton height={220} radius="md" />}

        {data && !hasServices && <FirstRun cards={cards} />}

        {data && hasServices && (
          <Stack gap="xl">
            <div>
              <Title order={2} style={{ letterSpacing: '-0.01em' }}>
                Your Workbench
              </Title>
              <Text c="dimmed" size="sm" mb="sm">
                Select a service to continue.
              </Text>
              <ServiceShelf
                services={sortedCards}
                selectedId={selectedId}
                onSelect={(id) => setSelectedId((prev) => (prev === id ? null : id))}
              />
            </div>

            {selected && <CommandBar service={selected} />}
          </Stack>
        )}
      </Stack>
    </Container>
  );
}

function startTestHref(cards: ServiceCard[]): string {
  if (cards.length === 0) return '/services/new';
  if (cards.length === 1) return `/services/${cards[0].id}`;
  return '#services';
}

function trafficHref(cards: ServiceCard[]): string {
  if (cards.length === 0) return '/services/new';
  if (cards.length === 1) return `/services/${cards[0].id}`;
  return '/';
}

/** The onboarding a first-time installation sees — unchanged once services exist, see Home(). */
function FirstRun({ cards }: { cards: ServiceCard[] }) {
  return (
    <>
      <Group justify="space-between" align="flex-start" wrap="wrap" gap="lg">
        <div>
          <Text tt="uppercase" size="xs" fw={600} c="dimmed" mb={4}>
            Performance workbench
          </Text>
          <Title order={1} fw={650} style={{ letterSpacing: '-0.02em', maxWidth: '20ch' }}>
            What do you want to prove about your service?
          </Title>
          <Text c="dimmed" mt="xs" maw={560}>
            Define a performance question. Run controlled load. Measure what happens. Keep the
            evidence.
          </Text>
        </div>

        <Stack gap="sm" align="flex-end">
          <Button size="lg" component="a" href={startTestHref(cards)}>
            Start a performance test
          </Button>
          <Button
            size="xs"
            variant="subtle"
            color="gray"
            component="a"
            href="/services/new"
            leftSection={<IconPlus size={14} />}
          >
            Add a service
          </Button>
        </Stack>
      </Group>

      <MeasurementRule />

      <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="md">
        <ActionTile
          icon={IconTarget}
          title="Find the limit"
          description="Determine where the service stops meeting its objectives."
          href={startTestHref(cards)}
        />
        <ActionTile
          icon={IconShieldCheck}
          title="Validate capacity"
          description="Establish tested SLO-compliant capacity under recorded conditions."
          href={startTestHref(cards)}
        />
        <ActionTile
          icon={IconChartBar}
          title="Compare performance"
          description="Compare runs and releases, and expose meaningful regressions."
          href="/runs"
        />
        <ActionTile
          icon={IconActivity}
          title="Production-informed testing"
          description="Use observed traffic as evidence for realistic workloads."
          href={trafficHref(cards)}
        />
      </SimpleGrid>
    </>
  );
}
