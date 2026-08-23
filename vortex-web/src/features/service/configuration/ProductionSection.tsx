import { useState } from 'react';
import { Alert, Button, Group, NumberInput, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { Catalog, WorkloadSuggestion } from '../../../api/configuration';
import { useFetchProductionMutation, useRecordProductionMutation } from '../../../api/configuration';
import { useApplyProductionMutation } from '../../../api/tests';
import type { Production } from '../../../api/workspace';
import { Fact, Facts } from '../../../components/Fact';
import { TrafficDistribution } from '../../../components/TrafficDistribution';
import { extractErrorMessage } from '../../../lib/queryFallback';
import { SectionDisclosure } from './SectionDisclosure';

export function ProductionSection({
  serviceId,
  production,
  calibrationSuggestions,
  catalog,
}: {
  serviceId: string;
  production: Production | null;
  calibrationSuggestions: WorkloadSuggestion[];
  catalog: Catalog;
}) {
  const fetchMutation = useFetchProductionMutation(serviceId);
  const applyMutation = useApplyProductionMutation(serviceId);
  const recordMutation = useRecordProductionMutation(serviceId);

  const [peakRate, setPeakRate] = useState<number | ''>('');
  const [averageRate, setAverageRate] = useState<number | ''>('');
  const [p95Rate, setP95Rate] = useState<number | ''>('');
  const [source, setSource] = useState('');
  const [note, setNote] = useState('');
  const [weights, setWeights] = useState<Record<string, number>>({});

  function onFetch() {
    fetchMutation.mutate();
  }

  function onApply() {
    applyMutation.mutate(undefined, {
      onSuccess: (r) => notifications.show({ message: r.message, color: r.applied ? 'pass' : 'neutral' }),
    });
  }

  function onRecord() {
    if (peakRate === '') return;
    recordMutation.mutate(
      {
        peakRate: Number(peakRate),
        averageRate: averageRate === '' ? undefined : Number(averageRate),
        p95ObservedRate: p95Rate === '' ? undefined : Number(p95Rate),
        mixOperation: catalog.operations.map((op) => op.id),
        mixWeight: catalog.operations.map((op) => weights[op.id] ?? 0),
        source: source || undefined,
        note: note || undefined,
      },
      { onSuccess: (r) => notifications.show({ message: r.message, color: 'pass' }) }
    );
  }

  const recordError = extractErrorMessage(recordMutation, 'Something went wrong recording production traffic.');

  return (
    <SectionDisclosure
      id="production"
      title="Production traffic"
      openByDefault={!production}
      state={production ? production.peakRate : 'not recorded'}
    >
      <Stack gap="lg">
        {production && (
          <div>
            <Facts>
              <Fact label="Peak">{production.peakRate}</Fact>
              {production.averageRate && <Fact label="Average">{production.averageRate}</Fact>}
              {production.p95ObservedRate && <Fact label="p95 rate">{production.p95ObservedRate}</Fact>}
              {production.source && <Fact label="Source">{production.source}</Fact>}
            </Facts>
            {production.observedMix.length > 0 && (
              <div style={{ marginTop: '0.75rem' }}>
                <Text size="xs" fw={600} c="dimmed" mb={4}>
                  Operation mix
                </Text>
                <TrafficDistribution rows={production.observedMix} />
              </div>
            )}
            <details style={{ marginTop: '0.75rem' }}>
              <summary style={{ cursor: 'pointer', fontSize: '0.8rem', color: 'var(--mantine-color-dimmed)' }}>
                How much this baseline is worth
              </summary>
              <ul style={{ fontSize: '0.82rem', marginTop: '0.4rem' }}>
                {production.qualityFacts.map((fact) => (
                  <li key={fact}>{fact}</li>
                ))}
              </ul>
            </details>
          </div>
        )}

        <Group>
          <Button variant="default" size="sm" onClick={onFetch} loading={fetchMutation.isPending}>
            Fetch from observation source
          </Button>
        </Group>

        {fetchMutation.data && !fetchMutation.data.succeeded && (
          <Alert color="warn" title="Could not fetch">
            {fetchMutation.data.error}
          </Alert>
        )}
        {fetchMutation.data?.succeeded && fetchMutation.data.preview && (
          <Alert color="live" title="Fetched — nothing saved yet">
            <Facts>
              <Fact label="Peak">{fetchMutation.data.preview.peakRate}</Fact>
              {fetchMutation.data.preview.averageRate && (
                <Fact label="Average">{fetchMutation.data.preview.averageRate}</Fact>
              )}
            </Facts>
          </Alert>
        )}

        {calibrationSuggestions.length > 0 && (
          <div>
            <Text size="sm" fw={600} mb="xs">
              Vortex can propose tests from this traffic
            </Text>
            <Table>
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
            <Button mt="sm" size="sm" onClick={onApply} loading={applyMutation.isPending}>
              Create proposed tests
            </Button>
          </div>
        )}

        <div>
          <Text size="sm" fw={600} mb="xs">
            Record observed traffic
          </Text>
          {recordError && (
            <Text size="sm" c="fail" mb="xs">
              {recordError}
            </Text>
          )}
          <Group grow>
            <NumberInput
              label="Peak rate (req/sec)"
              required
              min={0}
              step={0.1}
              value={peakRate}
              onChange={(v) => setPeakRate(typeof v === 'number' ? v : '')}
            />
            <NumberInput
              label="Average rate (req/sec)"
              min={0}
              step={0.1}
              value={averageRate}
              onChange={(v) => setAverageRate(typeof v === 'number' ? v : '')}
            />
            <NumberInput
              label="p95 rate (req/sec)"
              min={0}
              step={0.1}
              value={p95Rate}
              onChange={(v) => setP95Rate(typeof v === 'number' ? v : '')}
            />
          </Group>

          {catalog.operations.length > 0 && (
            <Stack gap={4} mt="sm">
              <Text size="xs" c="dimmed">
                Operation mix (relative weights)
              </Text>
              {catalog.operations.map((op) => (
                <Group key={op.id} justify="space-between" wrap="nowrap">
                  <Text size="sm">
                    {op.method} {op.path}
                  </Text>
                  <NumberInput
                    min={0}
                    step={1}
                    w={100}
                    value={weights[op.id] ?? 0}
                    onChange={(v) =>
                      setWeights((prev) => ({ ...prev, [op.id]: typeof v === 'number' ? v : 0 }))
                    }
                  />
                </Group>
              ))}
            </Stack>
          )}

          <Group grow mt="sm">
            <TextInput
              label="Source"
              placeholder="Grafana · checkout-service overview"
              value={source}
              onChange={(e) => setSource(e.currentTarget.value)}
            />
            <Textarea label="Note" minRows={1} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
          </Group>

          <Button mt="sm" onClick={onRecord} loading={recordMutation.isPending} disabled={peakRate === ''}>
            Record
          </Button>
        </div>
      </Stack>
    </SectionDisclosure>
  );
}
