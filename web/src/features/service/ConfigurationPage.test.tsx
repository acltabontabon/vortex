import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Configuration } from '../../api/configuration';
import { ConfigurationPage } from './ConfigurationPage';

let queryResult: { data: Configuration | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useParams: () => ({ id: 'checkout' }) };
});

vi.mock('../../api/configuration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/configuration')>();
  return { ...actual, useConfigurationQuery: () => queryResult };
});

vi.mock('../../api/tests', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/tests')>();
  return { ...actual, useApplyProductionMutation: () => ({ mutate: vi.fn(), isPending: false }) };
});

function aConfiguration(overrides: Partial<Configuration> = {}): Configuration {
  return {
    serviceVersion: '2.17.0',
    environments: [
      {
        name: 'local',
        baseUrl: 'http://localhost:8080',
        type: 'LOCAL_ISOLATED',
        typeLabel: 'Local (isolated)',
        dependencyMode: 'MOCKED',
        dependencyModeLabel: 'Mocked',
        classification: 'ISOLATED',
        classificationLabel: 'Isolated',
        classificationCaveat: '',
        hasSecretReferences: false,
        maskedHeaders: {},
        target: { kind: 'EXTERNAL_ENDPOINT', summary: 'http://localhost:8080', ownershipLabel: 'Externally managed' },
        productionLike: false,
        image: null,
        containerPort: null,
        cpuMillicores: null,
        memoryMebibytes: null,
        readinessPath: null,
        readinessExpectedStatus: null,
        readinessTimeoutSeconds: null,
        composeFile: null,
        composeService: null,
      },
    ],
    environmentTypes: [{ name: 'LOCAL_ISOLATED', label: 'Local (isolated)', description: '' }],
    dependencyModes: [{ name: 'MOCKED', label: 'Mocked', description: '' }],
    localLab: {
      configured: false,
      composeFileDisplay: null,
      status: { usable: true, dockerAvailable: true, daemonRunning: true, composeAvailable: true, version: '', remedy: '' },
      running: false,
      activity: null,
    },
    production: null,
    calibrationSuggestions: [],
    observationSource: null,
    thresholds: { p95Millis: 500, p99Millis: 1000, errorPercent: 1, describe: ['p95 latency below 500ms'] },
    catalog: { imported: true, title: 'checkout-service', sourceRef: 'https://x/openapi.yaml', operationCount: 1, mutatingCount: 0, operations: [
      { id: 'getAccount', method: 'GET', path: '/accounts/{id}', summary: '', primaryTag: 'accounts', kind: 'READ', requiresReview: false, reviewed: false },
    ] },
    file: { yaml: 'version: 1\n', path: '/repo/.vortex/vortex.yaml' },
    ...overrides,
  };
}

describe('the configuration page', () => {
  it('shows every environment and offers to add one', () => {
    queryResult = { data: aConfiguration(), isError: false };
    renderWithProviders(<ConfigurationPage />);

    expect(screen.getByText('local')).toBeInTheDocument();
    expect(screen.getAllByText(/http:\/\/localhost:8080/).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Add environment' })).toBeInTheDocument();
  });

  it('states the release plainly when recorded', () => {
    queryResult = { data: aConfiguration(), isError: false };
    renderWithProviders(<ConfigurationPage />);

    expect(screen.getAllByText('2.17.0').length).toBeGreaterThan(0);
  });

  it('offers to import when no catalog exists yet', () => {
    queryResult = {
      data: aConfiguration({
        catalog: { imported: false, title: null, sourceRef: null, operationCount: 0, mutatingCount: 0, operations: [] },
      }),
      isError: false,
    };
    renderWithProviders(<ConfigurationPage />);

    expect(screen.getByText('Import an API description')).toBeInTheDocument();
  });

  it('shows the committed YAML', () => {
    queryResult = { data: aConfiguration(), isError: false };
    renderWithProviders(<ConfigurationPage />);

    expect(screen.getByText('vortex.yaml')).toBeInTheDocument();
    expect(screen.getByText('version: 1')).toBeInTheDocument();
  });

  it('surfaces a failed load rather than a silent empty page', () => {
    queryResult = { data: undefined, isError: true };
    renderWithProviders(<ConfigurationPage />);

    expect(screen.getByText('Could not load configuration')).toBeInTheDocument();
  });

  // WorkspaceAssembler emits `configuration#production` and `#objectives` for readiness "Fix"
  // links. Both landed at the top of the page for as long as they existed, because neither
  // section had the id being pointed at.
  it.each(['operations', 'datasets', 'environments', 'production', 'objectives'])(
    'carries the #%s anchor the readiness links point at',
    (anchor) => {
      queryResult = { data: aConfiguration(), isError: false };
      const { container } = renderWithProviders(<ConfigurationPage />);

      expect(container.querySelector(`#${anchor}`)).not.toBeNull();
    },
  );
});
