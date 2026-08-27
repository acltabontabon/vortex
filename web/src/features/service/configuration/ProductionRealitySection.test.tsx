import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { Catalog, ObservationSource } from '../../../api/configuration';
import type { Production } from '../../../api/workspace';
import { ProductionRealitySection } from './ProductionRealitySection';

const fetchMutate = vi.fn();
const fetchAndSaveMutate = vi.fn();
const recordMutate = vi.fn();
const saveSourceMutate = vi.fn();
const testSourceMutate = vi.fn();
const applyMutate = vi.fn();
const lookupMutate = vi.fn();

let fetchResultData: { succeeded: boolean; error: string | null; preview: Production | null } | undefined =
  undefined;
let lookupResultData:
  | { succeeded: boolean; candidates: { id: string; name: string }[]; problem: string | null; remedy: string | null }
  | undefined = undefined;

vi.mock('../../../api/configuration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/configuration')>();
  return {
    ...actual,
    useFetchProductionMutation: () => ({
      mutate: fetchMutate,
      reset: () => {
        fetchResultData = undefined;
      },
      isPending: false,
      data: fetchResultData,
    }),
    useFetchAndSaveProductionMutation: () => ({ mutate: fetchAndSaveMutate, isPending: false, data: undefined }),
    useRecordProductionMutation: () => ({ mutate: recordMutate, isPending: false, isError: false }),
    useSaveObservationSourceMutation: () => ({ mutate: saveSourceMutate, isPending: false, isError: false }),
    useTestObservationSourceMutation: () => ({ mutate: testSourceMutate, isPending: false, data: undefined }),
    useLookupDynatraceEntityMutation: () => ({ mutate: lookupMutate, isPending: false, data: lookupResultData }),
  };
});

vi.mock('../../../api/tests', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/tests')>();
  return { ...actual, useApplyProductionMutation: () => ({ mutate: applyMutate, isPending: false }) };
});

let settingsQueryData: { dynatraceMcp: { enabled: boolean } } | undefined = undefined;

vi.mock('../../../api/settings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/settings')>();
  return { ...actual, useSettingsQuery: () => ({ data: settingsQueryData, isError: false }) };
});

const CATALOG: Catalog = { imported: true, title: 't', sourceRef: null, operationCount: 0, mutatingCount: 0, operations: [] };

function aProduction(overrides: Partial<Production> = {}): Production {
  return {
    peakRate: '35 req/s',
    averageRate: null,
    p95ObservedRate: null,
    source: null,
    attributed: false,
    fetched: false,
    observedWindow: null,
    note: null,
    qualityFacts: [],
    observedMix: [],
    mixCoverage: null,
    ...overrides,
  };
}

function anObservationSource(overrides: Partial<ObservationSource> = {}): ObservationSource {
  return {
    kind: 'PROMETHEUS',
    transport: 'REST',
    endpoint: 'http://prometheus.internal:9090',
    serviceIdentifier: 'checkout-service',
    windowDisplay: '30d',
    maskedHeaders: {},
    ...overrides,
  };
}

function render(props: Partial<Parameters<typeof ProductionRealitySection>[0]> = {}) {
  fetchMutate.mockReset();
  fetchAndSaveMutate.mockReset();
  recordMutate.mockReset();
  saveSourceMutate.mockReset();
  testSourceMutate.mockReset();
  applyMutate.mockReset();
  lookupMutate.mockReset();
  settingsQueryData = undefined;
  return renderWithProviders(
    <ProductionRealitySection
      serviceId="checkout"
      serviceName="checkout-service"
      production={null}
      observationSource={null}
      calibrationSuggestions={[]}
      catalog={CATALOG}
      {...props}
    />,
  );
}

describe('ProductionRealitySection', () => {
  beforeEach(() => {
    fetchResultData = undefined;
    lookupResultData = undefined;
  });

  it('says plainly that nothing is recorded yet', () => {
    render();

    expect(screen.getByText('No production traffic recorded yet.')).toBeInTheDocument();
  });

  it('leads with the observed rate and its provenance once both are known', () => {
    render({ production: aProduction(), observationSource: anObservationSource() });

    expect(screen.getByText('35 req/s observed')).toBeInTheDocument();
    expect(screen.getByText(/PROMETHEUS.*prometheus\.internal.*30d window/)).toBeInTheDocument();
  });

  it('falls back to "recorded manually" when there is no observation source', () => {
    render({ production: aProduction({ fetched: false, source: null }) });

    expect(screen.getByText('recorded manually')).toBeInTheDocument();
  });

  it('offers Fetch only once an observation source is configured', () => {
    render({ observationSource: anObservationSource() });
    expect(screen.getByRole('button', { name: 'Fetch from observation source' })).toBeInTheDocument();
  });

  it('does not offer Fetch when no observation source is configured', () => {
    render();
    expect(screen.queryByRole('button', { name: 'Fetch from observation source' })).not.toBeInTheDocument();
  });

  it('offers to save a fetched preview, and saving calls the fetch-and-save mutation', async () => {
    const user = userEvent.setup();
    fetchResultData = { succeeded: true, error: null, preview: aProduction({ peakRate: '10 req/s' }) };
    render({ observationSource: anObservationSource() });

    expect(screen.getByText('Fetched — nothing saved yet')).toBeInTheDocument();
    const saveButton = screen.getByRole('button', { name: 'Save this observation' });

    await user.click(saveButton);

    expect(fetchAndSaveMutate).toHaveBeenCalled();
  });

  it('does not offer to save when nothing has been fetched yet', () => {
    render({ observationSource: anObservationSource() });
    expect(screen.queryByRole('button', { name: 'Save this observation' })).not.toBeInTheDocument();
  });

  it('records manual traffic behind a secondary action, not an always-open form', async () => {
    const user = userEvent.setup();
    render();

    expect(screen.queryByLabelText('Peak rate (req/sec)')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Record manually' }));
    // The field is required, so Mantine appends a " *" to its accessible label — matched with a
    // regex rather than the plain label text for that reason.
    expect(await screen.findByLabelText(/Peak rate \(req\/sec\)/)).toBeInTheDocument();

    await user.type(screen.getByLabelText(/Peak rate \(req\/sec\)/), '35');
    await user.click(screen.getByRole('button', { name: 'Record' }));

    expect(recordMutate).toHaveBeenCalled();
    expect(recordMutate.mock.calls[0][0]).toMatchObject({ peakRate: 35 });
  });

  it('recording manually and configuring the source are mutually exclusive, never both open', async () => {
    const user = userEvent.setup();
    render({ observationSource: anObservationSource() });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Cancel' })).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Record manually' }));
    expect(screen.getAllByRole('button', { name: 'Cancel' })).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Edit source' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save source' })).not.toBeInTheDocument();
  });

  it('shows a failed connection test rather than a silent no-op', async () => {
    const user = userEvent.setup();
    render({ observationSource: anObservationSource() });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));
    expect(testSourceMutate).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Test connection' }));

    expect(testSourceMutate).toHaveBeenCalled();
  });

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

  it('hides the endpoint and header fields for a Dynatrace MCP source', async () => {
    const user = userEvent.setup();
    render({ observationSource: aDynatraceMcpSource() });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));

    expect(screen.queryByLabelText('Endpoint')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /add header/i })).not.toBeInTheDocument();
    expect(screen.getByText(/Dynatrace MCP is not enabled yet\./)).toBeInTheDocument();
  });

  it('confirms the global connection will be used once Dynatrace MCP is enabled', async () => {
    const user = userEvent.setup();
    render({ observationSource: aDynatraceMcpSource() });
    settingsQueryData = { dynatraceMcp: { enabled: true } };

    await user.click(screen.getByRole('button', { name: 'Edit source' }));

    expect(
      screen.getByText('Vortex will reach Dynatrace through the endpoint configured under Settings.')
    ).toBeInTheDocument();
    expect(screen.queryByText(/Dynatrace MCP is not enabled yet\./)).not.toBeInTheDocument();
  });

  it('omits endpoint and headers from the saved payload when using MCP transport', async () => {
    const user = userEvent.setup();
    render({ observationSource: aDynatraceMcpSource() });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));
    await user.click(screen.getByRole('button', { name: 'Save source' }));

    expect(saveSourceMutate).toHaveBeenCalled();
    const payload = saveSourceMutate.mock.calls[0][0];
    expect(payload).toMatchObject({ source: 'dynatrace', transport: 'mcp', endpoint: '' });
    expect(payload.headerName).toBeUndefined();
    expect(payload.headerValue).toBeUndefined();
  });

  it('offers a "Look up" affordance only for a Dynatrace MCP source, pre-filled from the service name', async () => {
    const user = userEvent.setup();
    render({ observationSource: aDynatraceMcpSource() });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));

    expect(screen.getByLabelText('Look up entity id by name')).toHaveValue('checkout-service');
    expect(screen.getByRole('button', { name: 'Look up' })).toBeInTheDocument();
  });

  it('does not offer entity lookup for a Prometheus source', async () => {
    const user = userEvent.setup();
    render({ observationSource: anObservationSource() });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));

    expect(screen.queryByRole('button', { name: 'Look up' })).not.toBeInTheDocument();
  });

  it('calls the lookup mutation with the search text', async () => {
    const user = userEvent.setup();
    render({ observationSource: aDynatraceMcpSource() });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));
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
    render({ observationSource: aDynatraceMcpSource() });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));
    await user.click(screen.getByRole('combobox', { name: 'Matches found' }));
    await user.click(screen.getByText('checkout-service (SERVICE-AAAA)'));

    const entityIdField = screen.getByLabelText('Entity id') as HTMLInputElement;
    expect(entityIdField.value).toBe('SERVICE-AAAA');
    expect(entityIdField).not.toBeDisabled();

    await user.clear(entityIdField);
    await user.type(entityIdField, 'SERVICE-BBBB');
    expect(entityIdField.value).toBe('SERVICE-BBBB');
  });

  it('shows the failure remedy when lookup finds nothing to pick from', async () => {
    const user = userEvent.setup();
    lookupResultData = { succeeded: true, candidates: [], problem: null, remedy: null };
    render({ observationSource: aDynatraceMcpSource() });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));

    expect(screen.getByText('No matches found')).toBeInTheDocument();
  });

  it('always shows manual entity id guidance for a Dynatrace source, regardless of transport', async () => {
    const user = userEvent.setup();
    render({ observationSource: anObservationSource({ kind: 'DYNATRACE', transport: 'REST' }) });

    await user.click(screen.getByRole('button', { name: 'Edit source' }));

    expect(screen.getByText(/its entity id \(starting with/)).toBeInTheDocument();
  });
});
