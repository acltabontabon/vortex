import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { Catalog, ObservationSource } from '../../../api/configuration';
import type { Production } from '../../../api/workspace';
import { ProductionRealitySection } from './ProductionRealitySection';

const fetchMutate = vi.fn();
const recordMutate = vi.fn();
const saveSourceMutate = vi.fn();
const testSourceMutate = vi.fn();
const applyMutate = vi.fn();

vi.mock('../../../api/configuration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/configuration')>();
  return {
    ...actual,
    useFetchProductionMutation: () => ({ mutate: fetchMutate, isPending: false, data: undefined }),
    useRecordProductionMutation: () => ({ mutate: recordMutate, isPending: false, isError: false }),
    useSaveObservationSourceMutation: () => ({ mutate: saveSourceMutate, isPending: false, isError: false }),
    useTestObservationSourceMutation: () => ({ mutate: testSourceMutate, isPending: false, data: undefined }),
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
  recordMutate.mockReset();
  saveSourceMutate.mockReset();
  testSourceMutate.mockReset();
  applyMutate.mockReset();
  settingsQueryData = undefined;
  return renderWithProviders(
    <ProductionRealitySection
      serviceId="checkout"
      production={null}
      observationSource={null}
      calibrationSuggestions={[]}
      catalog={CATALOG}
      {...props}
    />,
  );
}

describe('ProductionRealitySection', () => {
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
});
