import { useState } from 'react';
import { Alert, Button, Divider, Group, Stack, Table, Text, List } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { Catalog, ObservationSource, WorkloadSuggestion } from '../../../api/configuration';
import { useFetchAndSaveProductionMutation, useFetchProductionMutation } from '../../../api/configuration';
import { useApplyProductionMutation } from '../../../api/tests';
import type { Production } from '../../../api/workspace';
import { Fact, Facts } from '../../../components/Fact';
import { TrafficDistribution } from '../../../components/TrafficDistribution';
import { ObservationSourceDrawer } from '../ObservationSourceDrawer';
import { ProductionTrafficDrawer } from '../ProductionTrafficDrawer';
import classes from './ProductionRealitySection.module.css';

/**
 * What Vortex learned about production, and where it came from — unified into one lead fact
 * instead of two separately-configured sections, since a rate without its provenance is not
 * evidence of much. Recording a number by hand and configuring an observation source are two
 * independent actions, not two states of one toggle — each opens its own drawer, exactly like
 * editing an environment or setting objectives already does elsewhere on this page.
 */
export function ProductionRealitySection({
  serviceId,
  serviceName,
  production,
  observationSource,
  calibrationSuggestions,
  catalog,
}: {
  serviceId: string;
  serviceName: string;
  production: Production | null;
  observationSource: ObservationSource | null;
  calibrationSuggestions: WorkloadSuggestion[];
  catalog: Catalog;
}) {
  const fetchMutation = useFetchProductionMutation(serviceId);
  const fetchAndSaveMutation = useFetchAndSaveProductionMutation(serviceId);
  const applyMutation = useApplyProductionMutation(serviceId);

  const [recording, setRecording] = useState(false);
  const [configuringSource, setConfiguringSource] = useState(false);

  function onFetch() {
    fetchMutation.mutate();
  }

  function onSaveFetched() {
    fetchAndSaveMutation.mutate(undefined, {
      onSuccess: (r) => {
        if (r.succeeded) {
          notifications.show({ message: 'Saved the observation Vortex just fetched.', color: 'pass' });
          fetchMutation.reset();
        }
      },
    });
  }

  function onApply() {
    applyMutation.mutate(undefined, {
      onSuccess: (r) => notifications.show({ message: r.message, color: r.applied ? 'pass' : 'neutral' }),
    });
  }

  const provenance = production
    ? observationSource
      ? `${observationSource.kind} · ${observationSource.transport === 'MCP' ? 'via MCP' : observationSource.endpoint} · ${observationSource.windowDisplay} window`
      : (production.source ?? (production.fetched ? 'observation source' : 'recorded manually'))
    : null;

  return (
    <Stack gap="sm">
      <div>
        <Text size="md" fw={600}>
          {production ? `${production.peakRate} observed` : 'No production traffic recorded yet.'}
        </Text>
        {provenance && (
          <Text size="sm" c="dimmed">
            {provenance}
          </Text>
        )}

        {production && (production.averageRate || production.p95ObservedRate || production.observedMix.length > 0) && (
          <>
            <Facts>
              {production.averageRate && <Fact label="Average">{production.averageRate}</Fact>}
              {production.p95ObservedRate && <Fact label="p95 rate">{production.p95ObservedRate}</Fact>}
            </Facts>
            {production.observedMix.length > 0 && (
              <div style={{ marginTop: '0.5rem' }}>
                <Text size="xs" fw={600} c="dimmed" mb={4}>
                  Operation mix
                </Text>
                <TrafficDistribution rows={production.observedMix} />
              </div>
            )}
            {production.qualityFacts.length > 0 && (
              <details className={classes.advanced}>
                <summary>How much this baseline is worth</summary>
                <List className={classes.advancedBody} size="sm" spacing={2}>
                  {production.qualityFacts.map((fact) => (
                    <List.Item key={fact}>{fact}</List.Item>
                  ))}
                </List>
              </details>
            )}
          </>
        )}
      </div>

      {observationSource && (
        <div>
          <Button variant="default" size="xs" onClick={onFetch} loading={fetchMutation.isPending}>
            Fetch from observation source
          </Button>

          {fetchMutation.data && !fetchMutation.data.succeeded && (
            <Alert color="warn" title="Could not fetch" mt="xs">
              {fetchMutation.data.error}
            </Alert>
          )}
          {fetchMutation.data?.succeeded && fetchMutation.data.preview && (
            <Alert color="live" title="Fetched — nothing saved yet" mt="xs">
              <Facts>
                <Fact label="Peak">{fetchMutation.data.preview.peakRate}</Fact>
                {fetchMutation.data.preview.averageRate && (
                  <Fact label="Average">{fetchMutation.data.preview.averageRate}</Fact>
                )}
              </Facts>
              <Button mt="sm" size="xs" onClick={onSaveFetched} loading={fetchAndSaveMutation.isPending}>
                Save this observation
              </Button>
            </Alert>
          )}
          {fetchAndSaveMutation.data && !fetchAndSaveMutation.data.succeeded && (
            <Alert color="warn" title="Could not save" mt="xs">
              {fetchAndSaveMutation.data.error}
            </Alert>
          )}
        </div>
      )}

      {calibrationSuggestions.length > 0 && (
        <>
          <Divider />
          <div>
            <Text size="sm" fw={600} mb="xs">
              Vortex can propose tests from this traffic
            </Text>
            <Table verticalSpacing="xs">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Name</Table.Th>
                  <Table.Th>Rate</Table.Th>
                  <Table.Th>Derivation</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {calibrationSuggestions.map((s) => (
                  <Table.Tr key={s.name}>
                    <Table.Td>{s.name}</Table.Td>
                    <Table.Td>{s.rateDisplay} requests/sec</Table.Td>
                    <Table.Td>
                      <Text size="xs" c="dimmed">
                        {s.derivation}
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
            <Button mt="sm" size="xs" onClick={onApply} loading={applyMutation.isPending}>
              Create proposed tests
            </Button>
          </div>
        </>
      )}

      <Divider />

      <Group gap="md">
        <Button
          size="compact-sm"
          variant="subtle"
          color="gray"
          className={classes.manageAction}
          onClick={() => {
            setConfiguringSource(true);
            setRecording(false);
          }}
        >
          {observationSource ? 'Edit observation source' : 'Configure observation source'}
        </Button>
        <Button
          size="compact-sm"
          variant="subtle"
          color="gray"
          className={classes.manageAction}
          onClick={() => {
            setRecording(true);
            setConfiguringSource(false);
          }}
        >
          Record manually
        </Button>
      </Group>

      <ObservationSourceDrawer
        serviceId={serviceId}
        serviceName={serviceName}
        source={observationSource}
        opened={configuringSource}
        onClose={() => setConfiguringSource(false)}
      />

      <ProductionTrafficDrawer
        serviceId={serviceId}
        catalog={catalog}
        opened={recording}
        onClose={() => setRecording(false)}
      />
    </Stack>
  );
}
