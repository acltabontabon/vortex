import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ObservationSource } from '../../../api/configuration';
import { ObservationSourcePanel } from './ObservationSourcePanel';

const saveSourceMutate = vi.fn();
const testSourceMutate = vi.fn();
const lookupMutate = vi.fn();
const onSaved = vi.fn();

let testResultData: { succeeded: boolean; state: string | null; message: string } | undefined = undefined;
let lookupResultData:
  | { succeeded: boolean; candidates: { id: string; name: string }[]; problem: string | null; remedy: string | null }
  | undefined = undefined;

vi.mock('../../../api/configuration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/configuration')>();
  return {
    ...actual,
    useSaveObservationSourceMutation: () => ({ mutate: saveSourceMutate, isPending: false, isError: false }),
    useTestObservationSourceMutation: () => ({
      mutate: testSourceMutate,
      isPending: false,
      data: testResultData,
      reset: () => {
        testResultData = undefined;
      },
    }),
    useLookupDynatraceEntityMutation: () => ({ mutate: lookupMutate, isPending: false, data: lookupResultData }),
  };
});

let settingsQueryData:
  | {
      dynatraceMcp: { enabled: boolean };
      prometheusDefaults?: {
        endpoint: string;
        windowDisplay: string;
        headers: Record<string, string>;
        serviceLabel: string;
        routeLabel: string;
        methodLabel: string;
        configured: boolean;
      };
    }
  | undefined = undefined;

vi.mock('../../../api/settings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/settings')>();
  return { ...actual, useSettingsQuery: () => ({ data: settingsQueryData, isError: false }) };
});

function anObservationSource(overrides: Partial<ObservationSource> = {}): ObservationSource {
  return {
    kind: 'PROMETHEUS',
    transport: 'REST',
    endpoint: 'http://prometheus.internal:9090',
    serviceIdentifier: 'checkout-service',
    windowDisplay: '30d',
    maskedHeaders: {},
    serviceLabel: 'application',
    routeLabel: 'uri',
    methodLabel: 'method',
    ...overrides,
  };
}

function aDynatraceMcpSource(overrides: Partial<ObservationSource> = {}): ObservationSource {
  return anObservationSource({
    kind: 'DYNATRACE',
    transport: 'MCP',
    endpoint: '',
    serviceIdentifier: 'SERVICE-1A2B3C4D5E6F7890',
    maskedHeaders: {},
    ...overrides,
  });
}

/** `settingsQueryData` is set before render, unlike a real drawer where the settings fetch may lag
 *  the panel's own mount — fine here, since prefill's own "settings not loaded yet" case is a
 *  one-line, self-evidently-correct fallback (`?? ''`), not something worth a dedicated test. */
function render(source: ObservationSource | null, settings?: NonNullable<typeof settingsQueryData>) {
  saveSourceMutate.mockReset();
  testSourceMutate.mockReset();
  lookupMutate.mockReset();
  onSaved.mockReset();
  settingsQueryData = settings;
  return renderWithProviders(
    <ObservationSourcePanel serviceId="checkout" serviceName="checkout-service" source={source} onSaved={onSaved} />,
  );
}

describe('ObservationSourcePanel', () => {
  beforeEach(() => {
    testResultData = undefined;
    lookupResultData = undefined;
  });

  it('shows a failed connection test rather than a silent no-op', async () => {
    const user = userEvent.setup();
    render(anObservationSource());

    expect(testSourceMutate).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Test connection' }));

    expect(testSourceMutate).toHaveBeenCalled();
  });

  it('colours a connection test by its classified state, not just pass/fail', () => {
    testResultData = {
      succeeded: false,
      state: 'CONNECTED_NO_DATA',
      message: 'Prometheus answered, but not about this service.',
    };
    render(anObservationSource());

    expect(screen.getByText('Connected — no data')).toBeInTheDocument();
    expect(screen.getByText('Prometheus answered, but not about this service.')).toBeInTheDocument();
  });

  it('keeps the Prometheus label overrides collapsed until Advanced is opened, and saves them', async () => {
    const user = userEvent.setup();
    render(anObservationSource());

    await user.click(screen.getByRole('button', { name: 'Advanced' }));
    const routeLabelInput = screen.getByLabelText('Route label name');
    expect(routeLabelInput).toHaveValue('uri');

    await user.clear(routeLabelInput);
    await user.type(routeLabelInput, 'endpoint');
    await user.click(screen.getByRole('button', { name: 'Save source' }));

    expect(saveSourceMutate).toHaveBeenCalled();
    expect(saveSourceMutate.mock.calls[0][0]).toMatchObject({ routeLabel: 'endpoint' });
  });

  it('does not show the label-override Advanced disclosure for a Dynatrace source', () => {
    render(anObservationSource({ kind: 'DYNATRACE', transport: 'REST' }));

    expect(screen.queryByRole('button', { name: 'Advanced' })).not.toBeInTheDocument();
  });

  const SETTINGS_PROMETHEUS_DEFAULTS = {
    endpoint: 'http://prometheus.defaults:9090',
    windowDisplay: '14d',
    headers: {},
    serviceLabel: 'app',
    routeLabel: 'endpoint',
    methodLabel: 'verb',
    configured: true,
  };

  it('prefills a brand-new Prometheus source from Settings → Prometheus defaults', async () => {
    const user = userEvent.setup();
    render(null, { dynatraceMcp: { enabled: false }, prometheusDefaults: SETTINGS_PROMETHEUS_DEFAULTS });

    expect(screen.getByLabelText('Endpoint')).toHaveValue('http://prometheus.defaults:9090');
    expect(screen.getByLabelText('Window')).toHaveValue('14d');

    await user.click(screen.getByRole('button', { name: 'Advanced' }));
    expect(screen.getByLabelText('Service label name')).toHaveValue('app');
    expect(screen.getByLabelText('Route label name')).toHaveValue('endpoint');
    expect(screen.getByLabelText('Method label name')).toHaveValue('verb');
  });

  it('never prefills the service identifier from defaults — a default names no service', () => {
    render(null, { dynatraceMcp: { enabled: false }, prometheusDefaults: SETTINGS_PROMETHEUS_DEFAULTS });

    expect(screen.getByLabelText('Service label')).toHaveValue('');
  });

  it("shows an existing Prometheus source's own saved values, never the defaults", () => {
    render(
      anObservationSource({
        endpoint: 'http://prometheus.actual:9090',
        windowDisplay: '30d',
        serviceLabel: 'application',
      }),
      { dynatraceMcp: { enabled: false }, prometheusDefaults: SETTINGS_PROMETHEUS_DEFAULTS },
    );

    expect(screen.getByLabelText('Endpoint')).toHaveValue('http://prometheus.actual:9090');
    expect(screen.getByLabelText('Window')).toHaveValue('30d');
  });

  it('does not reset a prefilled field the user has edited, after a later re-render', async () => {
    const user = userEvent.setup();
    render(null, { dynatraceMcp: { enabled: false }, prometheusDefaults: SETTINGS_PROMETHEUS_DEFAULTS });

    const endpointInput = screen.getByLabelText('Endpoint');
    expect(endpointInput).toHaveValue('http://prometheus.defaults:9090');

    await user.clear(endpointInput);
    await user.type(endpointInput, 'http://prometheus.edited:9090');
    // Trigger a re-render of the panel (typing in an unrelated field) — the prefilled-then-edited
    // endpoint must not snap back to the default.
    await user.type(screen.getByLabelText('Service label'), 'checkout-service');

    expect(screen.getByLabelText('Endpoint')).toHaveValue('http://prometheus.edited:9090');
  });

  it("never prefills Prometheus defaults into an existing Dynatrace source's fields", () => {
    render(aDynatraceMcpSource(), {
      dynatraceMcp: { enabled: false },
      prometheusDefaults: SETTINGS_PROMETHEUS_DEFAULTS,
    });

    expect(screen.getByLabelText('Entity id')).toHaveValue('SERVICE-1A2B3C4D5E6F7890');
  });

  describe('switching System', () => {
    it('clears a Prometheus endpoint when switching to Dynatrace — it is not a valid Dynatrace one', async () => {
      const user = userEvent.setup();
      render(null, { dynatraceMcp: { enabled: false }, prometheusDefaults: SETTINGS_PROMETHEUS_DEFAULTS });
      expect(screen.getByLabelText('Endpoint')).toHaveValue('http://prometheus.defaults:9090');

      await user.click(screen.getByRole('combobox', { name: 'System' }));
      await user.click(await screen.findByText('Dynatrace'));

      expect(screen.getByLabelText('Endpoint')).toHaveValue('');
    });

    it('clears the Prometheus label overrides and headers when switching to Dynatrace', async () => {
      const user = userEvent.setup();
      render(anObservationSource());

      await user.click(screen.getByRole('combobox', { name: 'System' }));
      await user.click(await screen.findByText('Dynatrace'));
      await user.click(screen.getByRole('combobox', { name: 'Connect via' }));
      await user.click(await screen.findByText('REST API (token)'));

      // Prometheus's label-override disclosure is gone entirely under Dynatrace, so there is
      // nothing left for a stale "uri"/"method" override to hide inside.
      expect(screen.queryByRole('button', { name: 'Advanced' })).not.toBeInTheDocument();
    });

    it('shows a Dynatrace-shaped endpoint placeholder under Dynatrace, not a leftover Prometheus one', async () => {
      const user = userEvent.setup();
      render(null);
      expect(screen.getByLabelText('Endpoint')).toHaveAttribute('placeholder', 'http://prometheus.internal:9090');

      // REST is the default "Connect via" a kind switch lands on, so the endpoint field (only
      // shown for REST) is already visible without picking it explicitly.
      await user.click(screen.getByRole('combobox', { name: 'System' }));
      await user.click(await screen.findByText('Dynatrace'));

      const placeholder = screen.getByLabelText('Endpoint').getAttribute('placeholder');
      expect(placeholder).not.toContain('prometheus');
      expect(placeholder).toContain('dynatrace');
    });

    it('re-prefills from Prometheus defaults switching back to Prometheus on a brand-new source', async () => {
      const user = userEvent.setup();
      render(null, { dynatraceMcp: { enabled: false }, prometheusDefaults: SETTINGS_PROMETHEUS_DEFAULTS });

      await user.click(screen.getByRole('combobox', { name: 'System' }));
      await user.click(await screen.findByText('Dynatrace'));
      await user.click(screen.getByRole('combobox', { name: 'System' }));
      await user.click(await screen.findByText('Prometheus'));

      expect(screen.getByLabelText('Endpoint')).toHaveValue('http://prometheus.defaults:9090');
    });

    it('drops a stale connection-test result — it answered for the system just left', async () => {
      const user = userEvent.setup();
      testResultData = { succeeded: true, state: 'CONNECTED', message: 'Connected.' };
      render(anObservationSource());
      expect(screen.getByText('Connected.')).toBeInTheDocument();

      await user.click(screen.getByRole('combobox', { name: 'System' }));
      await user.click(await screen.findByText('Dynatrace'));

      expect(screen.queryByText('Connected.')).not.toBeInTheDocument();
    });

    it('clears the entity id / service label — it means something different under each system', async () => {
      const user = userEvent.setup();
      render(anObservationSource());
      expect(screen.getByLabelText('Service label')).toHaveValue('checkout-service');

      await user.click(screen.getByRole('combobox', { name: 'System' }));
      await user.click(await screen.findByText('Dynatrace'));

      expect(screen.getByLabelText('Entity id')).toHaveValue('');
    });
  });

  it('hides the endpoint and header fields for a Dynatrace MCP source', () => {
    render(aDynatraceMcpSource());

    expect(screen.queryByLabelText('Endpoint')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /add header/i })).not.toBeInTheDocument();
    expect(screen.getByText(/Dynatrace MCP is not enabled yet\./)).toBeInTheDocument();
  });

  it('confirms the global connection will be used once Dynatrace MCP is enabled', () => {
    render(aDynatraceMcpSource(), { dynatraceMcp: { enabled: true } });

    expect(
      screen.getByText('Vortex will reach Dynatrace through the endpoint configured under Settings.')
    ).toBeInTheDocument();
    expect(screen.queryByText(/Dynatrace MCP is not enabled yet\./)).not.toBeInTheDocument();
  });

  it('omits endpoint and headers from the saved payload when using MCP transport', async () => {
    const user = userEvent.setup();
    render(aDynatraceMcpSource());

    await user.click(screen.getByRole('button', { name: 'Save source' }));

    expect(saveSourceMutate).toHaveBeenCalled();
    const payload = saveSourceMutate.mock.calls[0][0];
    expect(payload).toMatchObject({ source: 'dynatrace', transport: 'mcp', endpoint: '' });
    expect(payload.headerName).toBeUndefined();
    expect(payload.headerValue).toBeUndefined();
  });

  it('offers a "Look up" affordance only for a Dynatrace MCP source, pre-filled from the service name', () => {
    render(aDynatraceMcpSource());

    expect(screen.getByLabelText('Look up entity id by name')).toHaveValue('checkout-service');
    expect(screen.getByRole('button', { name: 'Look up' })).toBeInTheDocument();
  });

  it('does not offer entity lookup for a Prometheus source', () => {
    render(anObservationSource());

    expect(screen.queryByRole('button', { name: 'Look up' })).not.toBeInTheDocument();
  });

  it('calls the lookup mutation with the search text', async () => {
    const user = userEvent.setup();
    render(aDynatraceMcpSource());

    await user.click(screen.getByRole('button', { name: 'Look up' }));

    expect(lookupMutate).toHaveBeenCalledWith('checkout-service');
  });

  it('picking a lookup match fills in the entity id, without locking the field', async () => {
    const user = userEvent.setup();
    lookupResultData = {
      succeeded: true,
      candidates: [{ id: 'SERVICE-AAAA', name: 'checkout-service' }],
      problem: null,
      remedy: null,
    };
    render(aDynatraceMcpSource());

    await user.click(screen.getByRole('combobox', { name: 'Matches found' }));
    await user.click(screen.getByText('checkout-service (SERVICE-AAAA)'));

    const entityIdField = screen.getByLabelText('Entity id') as HTMLInputElement;
    expect(entityIdField.value).toBe('SERVICE-AAAA');
    expect(entityIdField).not.toBeDisabled();

    await user.clear(entityIdField);
    await user.type(entityIdField, 'SERVICE-BBBB');
    expect(entityIdField.value).toBe('SERVICE-BBBB');
  });

  it('shows the failure remedy when lookup finds nothing to pick from', () => {
    lookupResultData = { succeeded: true, candidates: [], problem: null, remedy: null };
    render(aDynatraceMcpSource());

    expect(screen.getByText('No matches found')).toBeInTheDocument();
  });

  it('always shows manual entity id guidance for a Dynatrace source, regardless of transport', () => {
    render(anObservationSource({ kind: 'DYNATRACE', transport: 'REST' }));

    expect(screen.getByText(/its entity id \(starting with/)).toBeInTheDocument();
  });
});
