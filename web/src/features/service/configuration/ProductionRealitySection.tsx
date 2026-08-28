import { useState } from 'react';
import { Alert, Anchor, Button, Collapse, Divider, Group, Select, Stack, Table, Text, TextInput, List } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { Link } from 'react-router-dom';
import type { Catalog, ObservationSource, WorkloadSuggestion } from '../../../api/configuration';
import {
  useFetchAndSaveProductionMutation,
  useFetchProductionMutation,
  useLookupDynatraceEntityMutation,
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
import { RecordedTrafficPanel } from './RecordedTrafficPanel';
import classes from './ProductionRealitySection.module.css';

function headerArrays(rows: HeaderRow[]): { headerName?: string[]; headerValue?: string[] } {
  const named = rows.filter((row) => row.name.trim());
  if (named.length === 0) return {};
  return { headerName: named.map((row) => row.name), headerValue: named.map((row) => row.value) };
}

/** Maps a connection-test state onto the same pass/warn/fail vocabulary the rest of the settings UI
 *  already uses. `null` covers a pre-flight refusal that never reached a connection — falls back to
 *  the plain succeeded/failed split so it still reads sensibly. */
function connectionStateAppearance(state: string | null, succeeded: boolean): { color: string; title: string } {
  switch (state) {
    case 'CONNECTED':
      return { color: 'pass', title: 'Connected' };
    case 'CONNECTED_NO_DATA':
      return { color: 'warn', title: 'Connected — no data' };
    case 'AUTHENTICATION_FAILED':
      return { color: 'fail', title: 'Authentication failed' };
    case 'UNREACHABLE':
      return { color: 'fail', title: 'Unreachable' };
    case 'INVALID_RESPONSE':
      return { color: 'warn', title: 'Invalid response' };
    default:
      return { color: succeeded ? 'pass' : 'warn', title: 'Test connection' };
  }
}

/**
 * What Vortex learned about production, and where it came from — unified into one lead fact
 * instead of two separately-configured sections, since a rate without its provenance is not
 * evidence of much.
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
  const [configuringSource, setConfiguringSource] = useState(!observationSource);

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

  const toggleRecording = () =>
    setRecording((v) => {
      if (!v) setConfiguringSource(false);
      return !v;
    });
  const toggleConfiguringSource = () =>
    setConfiguringSource((v) => {
      if (!v) setRecording(false);
      return !v;
    });

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

      <div>
        <Group gap="md">
          <Button size="compact-sm" variant="subtle" color="gray" className={classes.manageAction} onClick={toggleRecording}>
            {recording ? 'Cancel' : 'Record manually'}
          </Button>
          <Button
            size="compact-sm"
            variant="subtle"
            color="gray"
            className={classes.manageAction}
            onClick={toggleConfiguringSource}
          >
            {configuringSource ? 'Cancel' : observationSource ? 'Edit source' : 'Configure source'}
          </Button>
        </Group>

        {recording && (
          <div className={classes.managePanel}>
            <RecordedTrafficPanel serviceId={serviceId} catalog={catalog} onSaved={() => setRecording(false)} />
          </div>
        )}

        {configuringSource && (
          <div className={classes.managePanel}>
            <ObservationSourcePanel
              serviceId={serviceId}
              serviceName={serviceName}
              source={observationSource}
              onSaved={() => setConfiguringSource(false)}
            />
          </div>
        )}
      </div>
    </Stack>
  );
}

function ObservationSourcePanel({
  serviceId,
  serviceName,
  source,
  onSaved,
}: {
  serviceId: string;
  serviceName: string;
  source: ObservationSource | null;
  onSaved: () => void;
}) {
  const save = useSaveObservationSourceMutation(serviceId);
  const test = useTestObservationSourceMutation(serviceId);
  const lookup = useLookupDynatraceEntityMutation(serviceId);
  const settingsQuery = useSettingsQuery();

  const [kind, setKind] = useState(source?.kind.toLowerCase() ?? 'prometheus');
  const [transport, setTransport] = useState(source?.transport?.toLowerCase() ?? 'rest');
  const [endpoint, setEndpoint] = useState(source?.endpoint ?? '');
  const [serviceIdentifier, setServiceIdentifier] = useState(source?.serviceIdentifier ?? '');
  const [window, setWindow] = useState(source?.windowDisplay ?? '30d');
  const [headerRows, setHeaderRows] = useState<HeaderRow[]>(
    source ? rowsFromMasked(source.maskedHeaders) : []
  );
  const [lookupQuery, setLookupQuery] = useState(serviceName);

  // Only ever the override — an empty string here means "use the Micrometer default", never a
  // literal blank label name, so it's fine to leave unset when a Prometheus source has never had
  // one saved.
  const [serviceLabel, setServiceLabel] = useState(source?.serviceLabel ?? '');
  const [routeLabel, setRouteLabel] = useState(source?.routeLabel ?? '');
  const [methodLabel, setMethodLabel] = useState(source?.methodLabel ?? '');
  const [showAdvanced, setShowAdvanced] = useState(false);

  const usingMcp = kind === 'dynatrace' && transport === 'mcp';
  const mcpConfigured = settingsQuery.data?.dynatraceMcp.enabled ?? false;
  const isPrometheus = kind === 'prometheus';

  function payload() {
    return {
      source: kind,
      transport: kind === 'dynatrace' ? transport : undefined,
      endpoint: usingMcp ? '' : endpoint,
      serviceIdentifier,
      window,
      ...(usingMcp ? {} : headerArrays(headerRows)),
      ...(isPrometheus
        ? {
            serviceLabel: serviceLabel.trim() || undefined,
            routeLabel: routeLabel.trim() || undefined,
            methodLabel: methodLabel.trim() || undefined,
          }
        : {}),
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
        <Alert
          color={connectionStateAppearance(test.data.state, test.data.succeeded).color}
          title={connectionStateAppearance(test.data.state, test.data.succeeded).title}
          mt="md"
          mb="md"
        >
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

        {usingMcp && (
          <Group align="flex-end" gap="xs">
            <TextInput
              style={{ flex: 1 }}
              label="Look up entity id by name"
              description="Vortex searches Dynatrace for a service whose name matches this — pick a result, or enter the id below by hand."
              value={lookupQuery}
              onChange={(e) => setLookupQuery(e.currentTarget.value)}
            />
            <Button
              variant="default"
              size="sm"
              onClick={() => lookup.mutate(lookupQuery)}
              loading={lookup.isPending}
              disabled={!lookupQuery.trim()}
            >
              Look up
            </Button>
          </Group>
        )}
        {usingMcp && lookup.data?.succeeded && lookup.data.candidates.length > 0 && (
          <Select
            label="Matches found"
            description="Pick one to fill in the entity id below."
            placeholder="Choose a match"
            data={lookup.data.candidates.map((c) => ({ value: c.id, label: `${c.name} (${c.id})` }))}
            onChange={(v) => v && setServiceIdentifier(v)}
          />
        )}
        {usingMcp && lookup.data && (!lookup.data.succeeded || lookup.data.candidates.length === 0) && (
          <Alert color="warn" title={lookup.data.succeeded ? 'No matches found' : lookup.data.problem}>
            {lookup.data.succeeded
              ? 'Nothing in Dynatrace matched that name closely enough — try a different phrase, or enter the entity id manually below.'
              : lookup.data.remedy}
          </Alert>
        )}

        <Group grow align="flex-start">
          <div>
            <TextInput
              label={kind === 'dynatrace' ? 'Entity id' : 'Service label'}
              placeholder={kind === 'dynatrace' ? 'SERVICE-1A2B3C4D5E6F7890' : 'checkout-service'}
              value={serviceIdentifier}
              onChange={(e) => setServiceIdentifier(e.currentTarget.value)}
            />
            {kind === 'dynatrace' && (
              <Text size="xs" c="dimmed" mt={4}>
                Finding this by hand: open the service in Dynatrace — its entity id (starting with{' '}
                <code>SERVICE-</code>) is in the URL and under Properties.
              </Text>
            )}
          </div>
          <TextInput label="Window" placeholder="30d" value={window} onChange={(e) => setWindow(e.currentTarget.value)} />
        </Group>

        {!usingMcp && <HeaderRows rows={headerRows} onChange={setHeaderRows} />}

        {isPrometheus && (
          <div>
            <Button
              size="compact-sm"
              variant="subtle"
              color="gray"
              className={classes.manageAction}
              onClick={() => setShowAdvanced((v) => !v)}
            >
              {showAdvanced ? 'Hide advanced' : 'Advanced'}
            </Button>
            <Collapse expanded={showAdvanced}>
              <Text size="xs" c="dimmed" mt="xs" mb="xs">
                Only needed if this service publishes Micrometer metrics under different label names.
                Leave blank to use the Spring Boot defaults.
              </Text>
              <Group grow align="flex-start">
                <TextInput
                  label="Service label name"
                  placeholder="application"
                  value={serviceLabel}
                  onChange={(e) => setServiceLabel(e.currentTarget.value)}
                />
                <TextInput
                  label="Route label name"
                  placeholder="uri"
                  value={routeLabel}
                  onChange={(e) => setRouteLabel(e.currentTarget.value)}
                />
                <TextInput
                  label="Method label name"
                  placeholder="method"
                  value={methodLabel}
                  onChange={(e) => setMethodLabel(e.currentTarget.value)}
                />
              </Group>
            </Collapse>
          </div>
        )}

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
