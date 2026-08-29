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
    useLookupDynatraceEntityMutation: () => ({ mutate: lookupMutate, isPending: false, data: undefined }),
  };
});

vi.mock('../../../api/tests', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/tests')>();
  return { ...actual, useApplyProductionMutation: () => ({ mutate: applyMutate, isPending: false }) };
});

vi.mock('../../../api/settings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/settings')>();
  return { ...actual, useSettingsQuery: () => ({ data: undefined, isError: false }) };
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
    serviceLabel: 'application',
    routeLabel: 'uri',
    methodLabel: 'method',
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

  it('offers to configure an observation source, and to record manually, as two independent actions', () => {
    render();

    expect(screen.getByRole('button', { name: 'Configure observation source' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record manually' })).toBeInTheDocument();
  });

  it('offers to edit, not configure, once an observation source exists', () => {
    render({ observationSource: anObservationSource() });

    expect(screen.getByRole('button', { name: 'Edit observation source' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Configure observation source' })).not.toBeInTheDocument();
  });

  it('opens the observation source drawer, with its own form, not an inline toggle panel', async () => {
    const user = userEvent.setup();
    render();

    expect(screen.queryByLabelText('System')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Configure observation source' }));

    expect(await screen.findByRole('combobox', { name: 'System' })).toBeInTheDocument();
  });

  it('opens the manual-recording drawer from its own button', async () => {
    const user = userEvent.setup();
    render();

    expect(screen.queryByLabelText(/Peak rate \(req\/sec\)/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Record manually' }));
    // The field is required, so Mantine appends a " *" to its accessible label — matched with a
    // regex rather than the plain label text for that reason.
    expect(await screen.findByLabelText(/Peak rate \(req\/sec\)/)).toBeInTheDocument();

    await user.type(screen.getByLabelText(/Peak rate \(req\/sec\)/), '35');
    await user.click(screen.getByRole('button', { name: 'Record' }));

    expect(recordMutate).toHaveBeenCalled();
    expect(recordMutate.mock.calls[0][0]).toMatchObject({ peakRate: 35 });
  });

  it('switching from one drawer to the other opens the second without getting stuck', async () => {
    // Mantine keeps a closed drawer's content mounted (for its own exit transition), so this
    // doesn't assert the first drawer's DOM is gone — only that the two actions remain independent,
    // never fighting over one another the way the old shared toggle-and-relabelled-Cancel did.
    const user = userEvent.setup();
    render({ observationSource: anObservationSource() });

    await user.click(screen.getByRole('button', { name: 'Edit observation source' }));
    expect(await screen.findByRole('combobox', { name: 'System' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Record manually' }));
    expect(await screen.findByLabelText(/Peak rate \(req\/sec\)/)).toBeInTheDocument();
  });

  it('calibration suggestions apply as proposed tests', async () => {
    const user = userEvent.setup();
    render({
      calibrationSuggestions: [{ name: 'smoke', rateDisplay: '10', derivation: 'from observed average' }],
    });

    await user.click(screen.getByRole('button', { name: 'Create proposed tests' }));

    expect(applyMutate).toHaveBeenCalled();
  });
});
