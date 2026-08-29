import { useState } from 'react';
import { Alert, Anchor, Button, Collapse, Group, Select, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { Link } from 'react-router-dom';
import type { ObservationSource } from '../../../api/configuration';
import {
  useLookupDynatraceEntityMutation,
  useSaveObservationSourceMutation,
  useTestObservationSourceMutation,
} from '../../../api/configuration';
import { useSettingsQuery } from '../../../api/settings';
import { Fact, Facts } from '../../../components/Fact';
import { connectionStateAppearance } from '../../../lib/connectionState';
import { extractErrorMessage } from '../../../lib/queryFallback';
import { HeaderRows } from './HeaderRows';
import { rowsFromMasked, type HeaderRow } from './headerRowUtils';
import classes from './ProductionRealitySection.module.css';

function headerArrays(rows: HeaderRow[]): { headerName?: string[]; headerValue?: string[] } {
  const named = rows.filter((row) => row.name.trim());
  if (named.length === 0) return {};
  return { headerName: named.map((row) => row.name), headerValue: named.map((row) => row.value) };
}

/** Where production traffic can be fetched from: Prometheus or Dynatrace, configured once and
 *  edited in place. Lives in its own drawer (`ObservationSourceDrawer`) rather than inline, so
 *  editing it reads as "open one form" rather than sharing a toggle with an unrelated action. */
export function ObservationSourcePanel({
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

  // A brand-new (never-saved) Prometheus source prefills from Settings → Prometheus defaults, once,
  // at mount — never a live sync, and never applied when `source` already exists (its own saved
  // values always win outright below). See ADR-062. `useSettingsQuery()` is invoked when this panel
  // mounts (when the drawer opens), not from page load, so on the very first open before that fetch
  // resolves, prefill is silently skipped and fields simply start blank — a minor, accepted
  // limitation for a convenience feature, not worth engineering around.
  const prometheusDefaults = settingsQuery.data?.prometheusDefaults;
  const isNewPrometheusSource = !source && kind === 'prometheus';

  const [endpoint, setEndpoint] = useState(
    source?.endpoint ?? (isNewPrometheusSource ? prometheusDefaults?.endpoint : undefined) ?? ''
  );
  const [serviceIdentifier, setServiceIdentifier] = useState(source?.serviceIdentifier ?? '');
  const [window, setWindow] = useState(
    source?.windowDisplay ?? (isNewPrometheusSource ? prometheusDefaults?.windowDisplay : undefined) ?? '30d'
  );
  const [headerRows, setHeaderRows] = useState<HeaderRow[]>(
    source
      ? rowsFromMasked(source.maskedHeaders)
      : isNewPrometheusSource && prometheusDefaults?.headers
        ? rowsFromMasked(prometheusDefaults.headers)
        : []
  );
  const [lookupQuery, setLookupQuery] = useState(serviceName);

  // Only ever the override — an empty string here means "use the Micrometer default", never a
  // literal blank label name, so it's fine to leave unset when a Prometheus source has never had
  // one saved.
  const [serviceLabel, setServiceLabel] = useState(
    source?.serviceLabel ?? (isNewPrometheusSource ? prometheusDefaults?.serviceLabel : undefined) ?? ''
  );
  const [routeLabel, setRouteLabel] = useState(
    source?.routeLabel ?? (isNewPrometheusSource ? prometheusDefaults?.routeLabel : undefined) ?? ''
  );
  const [methodLabel, setMethodLabel] = useState(
    source?.methodLabel ?? (isNewPrometheusSource ? prometheusDefaults?.methodLabel : undefined) ?? ''
  );
  const [showAdvanced, setShowAdvanced] = useState(false);

  const usingMcp = kind === 'dynatrace' && transport === 'mcp';
  const mcpConfigured = settingsQuery.data?.dynatraceMcp.enabled ?? false;
  const isPrometheus = kind === 'prometheus';

  /**
   * The endpoint, headers and label overrides mean something different — or nothing at all — under
   * a different system, so switching System must not leave the previous one's values sitting in the
   * form looking like they still apply (a Prometheus endpoint is not a valid Dynatrace one, and
   * silently carrying it over is exactly the kind of thing a person would miss and save by
   * accident). Also drops any connection-test result, which was answering for the system just left.
   */
  function handleKindChange(rawNext: string | null) {
    const next = rawNext ?? 'prometheus';
    setKind(next);
    setTransport('rest');
    setServiceIdentifier('');
    setShowAdvanced(false);
    test.reset();

    if (next === 'prometheus' && !source) {
      // Re-apply the same Settings → Prometheus defaults prefill switching back to Prometheus would
      // have gotten at mount, rather than leaving it blank just because it was visited once already.
      setEndpoint(prometheusDefaults?.endpoint ?? '');
      setHeaderRows(prometheusDefaults?.headers ? rowsFromMasked(prometheusDefaults.headers) : []);
      setServiceLabel(prometheusDefaults?.serviceLabel ?? '');
      setRouteLabel(prometheusDefaults?.routeLabel ?? '');
      setMethodLabel(prometheusDefaults?.methodLabel ?? '');
    } else {
      setEndpoint('');
      setHeaderRows([]);
      setServiceLabel('');
      setRouteLabel('');
      setMethodLabel('');
    }
  }

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
            onChange={handleKindChange}
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
            placeholder={kind === 'dynatrace' ? 'https://abc12345.live.dynatrace.com' : 'http://prometheus.internal:9090'}
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
