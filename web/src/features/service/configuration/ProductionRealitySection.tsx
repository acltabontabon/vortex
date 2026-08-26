import { useState } from 'react';
import { Alert, Anchor, Button, Group, NumberInput, Select, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { Link } from 'react-router-dom';
import type { Catalog, ObservationSource, WorkloadSuggestion } from '../../../api/configuration';
import {
  useFetchProductionMutation,
  useRecordProductionMutation,
  useSaveObservationSourceMutation,
  useTestObservationSourceMutation,
} from '../../../api/configuration';
import { useSettingsQuery } from '../../../api/settings';
import { useApplyProductionMutation } from '../../../api/tests';
import type { Production } from '../../../api/workspace';
import { Fact, Facts } from '../../../components/Fact';
import { TrafficDistribution } from '../../../components/TrafficDistribution';
import { extractErrorMessage } from '../../../lib/queryFallback';
import { HeaderRows } from './HeaderRows';
import { rowsFromMasked, type HeaderRow } from './headerRowUtils';

function headerArrays(rows: HeaderRow[]): { headerName?: string[]; headerValue?: string[] } {
  const named = rows.filter((row) => row.name.trim());
  if (named.length === 0) return {};
  return { headerName: named.map((row) => row.name), headerValue: named.map((row) => row.value) };
}

/**
 * What Vortex learned about production, and where it came from — unified into one lead fact
 * instead of two separately-configured sections, since a rate without its provenance is not
 * evidence of much.
 */
export function ProductionRealitySection({
  serviceId,
  production,
  observationSource,
  calibrationSuggestions,
  catalog,
}: {
  serviceId: string;
  production: Production | null;
  observationSource: ObservationSource | null;
  calibrationSuggestions: WorkloadSuggestion[];
  catalog: Catalog;
}) {
  const fetchMutation = useFetchProductionMutation(serviceId);
  const applyMutation = useApplyProductionMutation(serviceId);

  const [recording, setRecording] = useState(false);
  const [configuringSource, setConfiguringSource] = useState(!observationSource);

  function onFetch() {
    fetchMutation.mutate();
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
    <Stack gap="md">
      <div>
        <Text size="md" fw={600}>
          {production ? `${production.peakRate} observed` : 'No production traffic recorded yet.'}
        </Text>
        {provenance && (
          <Text size="sm" c="dimmed">
            {provenance}
          </Text>
        )}
      </div>

      {production && (production.averageRate || production.p95ObservedRate || production.observedMix.length > 0) && (
        <div>
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
            <details style={{ marginTop: '0.5rem' }}>
              <summary style={{ cursor: 'pointer', fontSize: '0.8rem', color: 'var(--mantine-color-dimmed)' }}>
                How much this baseline is worth
              </summary>
              <ul style={{ fontSize: '0.82rem', marginTop: '0.4rem' }}>
                {production.qualityFacts.map((fact) => (
                  <li key={fact}>{fact}</li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}

      {observationSource && (
        <Group>
          <Button variant="default" size="xs" onClick={onFetch} loading={fetchMutation.isPending}>
            Fetch from observation source
          </Button>
        </Group>
      )}

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
          <Button mt="sm" size="xs" onClick={onApply} loading={applyMutation.isPending}>
            Create proposed tests
          </Button>
        </div>
      )}

      <Group gap="sm">
        <Button size="xs" variant="subtle" onClick={() => setRecording((v) => !v)}>
          {recording ? 'Cancel' : 'Record manually'}
        </Button>
        <Button size="xs" variant="subtle" onClick={() => setConfiguringSource((v) => !v)}>
          {configuringSource ? 'Cancel' : observationSource ? 'Edit source' : 'Configure source'}
        </Button>
      </Group>

      {recording && (
        <RecordedTrafficPanel serviceId={serviceId} catalog={catalog} onSaved={() => setRecording(false)} />
      )}

      {configuringSource && (
        <ObservationSourcePanel
          serviceId={serviceId}
          source={observationSource}
          onSaved={() => setConfiguringSource(false)}
        />
      )}
    </Stack>
  );
}

function RecordedTrafficPanel({
  serviceId,
  catalog,
  onSaved,
}: {
  serviceId: string;
  catalog: Catalog;
  onSaved: () => void;
}) {
  const recordMutation = useRecordProductionMutation(serviceId);
  const [peakRate, setPeakRate] = useState<number | ''>('');
  const [averageRate, setAverageRate] = useState<number | ''>('');
  const [p95Rate, setP95Rate] = useState<number | ''>('');
  const [source, setSource] = useState('');
  const [note, setNote] = useState('');
  const [weights, setWeights] = useState<Record<string, number>>({});

  const recordError = extractErrorMessage(recordMutation, 'Something went wrong recording production traffic.');

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
      {
        onSuccess: (r) => {
          notifications.show({ message: r.message, color: 'pass' });
          onSaved();
        },
      }
    );
  }

  return (
    <div>
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

      <Button mt="sm" size="sm" onClick={onRecord} loading={recordMutation.isPending} disabled={peakRate === ''}>
        Record
      </Button>
    </div>
  );
}

function ObservationSourcePanel({
  serviceId,
  source,
  onSaved,
}: {
  serviceId: string;
  source: ObservationSource | null;
  onSaved: () => void;
}) {
  const save = useSaveObservationSourceMutation(serviceId);
  const test = useTestObservationSourceMutation(serviceId);
  const settingsQuery = useSettingsQuery();

  const [kind, setKind] = useState(source?.kind.toLowerCase() ?? 'prometheus');
  const [transport, setTransport] = useState(source?.transport?.toLowerCase() ?? 'rest');
  const [endpoint, setEndpoint] = useState(source?.endpoint ?? '');
  const [serviceIdentifier, setServiceIdentifier] = useState(source?.serviceIdentifier ?? '');
  const [window, setWindow] = useState(source?.windowDisplay ?? '30d');
  const [headerRows, setHeaderRows] = useState<HeaderRow[]>(
    source ? rowsFromMasked(source.maskedHeaders) : []
  );

  const usingMcp = kind === 'dynatrace' && transport === 'mcp';
  const mcpConfigured = settingsQuery.data?.dynatraceMcp.enabled ?? false;

  function payload() {
    return {
      source: kind,
      transport: kind === 'dynatrace' ? transport : undefined,
      endpoint: usingMcp ? '' : endpoint,
      serviceIdentifier,
      window,
      ...(usingMcp ? {} : headerArrays(headerRows)),
    };
  }

  function onSave() {
    save.mutate(payload(), {
      onSuccess: (r) => {
        notifications.show({ message: r.message, color: 'pass' });
        onSaved();
      },
    });
  }

  const saveError = extractErrorMessage(save, 'Something went wrong saving the observation source.');

  return (
    <div>
      {source && (
        <Facts>
          <Fact label="System">
            {source.kind}
            {source.transport === 'MCP' ? ' (via MCP)' : ''}
          </Fact>
          {source.transport !== 'MCP' && <Fact label="Endpoint">{source.endpoint}</Fact>}
          <Fact label={source.kind === 'DYNATRACE' ? 'Entity' : 'Service label'}>
            {source.serviceIdentifier}
          </Fact>
          <Fact label="Window">{source.windowDisplay}</Fact>
        </Facts>
      )}

      {test.data && (
        <Alert color={test.data.succeeded ? 'pass' : 'warn'} title="Test connection" mt="md" mb="md">
          {test.data.message}
        </Alert>
      )}
      {saveError && (
        <Text size="sm" c="fail" mb="xs">
          {saveError}
        </Text>
      )}

      <Stack gap="sm" mt="sm">
        <Group grow>
          <Select
            label="System"
            data={[
              { value: 'prometheus', label: 'Prometheus' },
              { value: 'dynatrace', label: 'Dynatrace' },
            ]}
            value={kind}
            onChange={(v) => setKind(v ?? 'prometheus')}
          />
          {kind === 'dynatrace' && (
            <Select
              label="Connect via"
              data={[
                { value: 'rest', label: 'REST API (token)' },
                { value: 'mcp', label: 'MCP (uses global Dynatrace settings)' },
              ]}
              value={transport}
              onChange={(v) => setTransport(v ?? 'rest')}
            />
          )}
        </Group>

        {usingMcp ? (
          <Alert color={mcpConfigured ? 'live' : 'warn'} title="Using the global Dynatrace MCP connection">
            {mcpConfigured ? (
              'Vortex will reach Dynatrace through the endpoint configured under Settings.'
            ) : (
              <>
                Dynatrace MCP is not enabled yet.{' '}
                <Anchor component={Link} to="/settings" size="sm">
                  Configure it under Settings
                </Anchor>{' '}
                first.
              </>
            )}
          </Alert>
        ) : (
          <TextInput
            label="Endpoint"
            placeholder="http://prometheus.internal:9090"
            value={endpoint}
            onChange={(e) => setEndpoint(e.currentTarget.value)}
          />
        )}

        <Group grow>
          <TextInput
            label={kind === 'dynatrace' ? 'Entity id' : 'Service label'}
            placeholder={kind === 'dynatrace' ? 'SERVICE-1A2B3C4D5E6F7890' : 'checkout-service'}
            value={serviceIdentifier}
            onChange={(e) => setServiceIdentifier(e.currentTarget.value)}
          />
          <TextInput label="Window" placeholder="30d" value={window} onChange={(e) => setWindow(e.currentTarget.value)} />
        </Group>

        {!usingMcp && <HeaderRows rows={headerRows} onChange={setHeaderRows} />}

        <Group mt="sm">
          <Button onClick={onSave} loading={save.isPending}>
            Save source
          </Button>
          <Button variant="default" onClick={() => test.mutate(payload())} loading={test.isPending}>
            Test connection
          </Button>
        </Group>
      </Stack>
    </div>
  );
}
