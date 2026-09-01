import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/renderWithProviders';
import type { Settings } from '../api/settings';
import { ApiError } from '../api/client';

/** Scopes queries to the card titled `heading` — several cards share button labels like "Save". */
function withinCard(heading: string) {
  const card = screen.getByText(heading).closest('.mantine-Card-root');
  if (!card) throw new Error(`No card found for heading "${heading}"`);
  return within(card as HTMLElement);
}
import { SettingsPage } from './SettingsPage';

let queryResult: { data: Settings | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};
const retryMutate = vi.fn();
const chooseModelMutate = vi.fn();
const chooseAiEndpointMutate = vi.fn();
const chooseLoadGeneratorBudgetMutate = vi.fn();
const saveDynatraceMcpMutate = vi.fn();
const testDynatraceMcpMutate = vi.fn();
const savePrometheusDefaultsMutate = vi.fn();
const testPrometheusDefaultsMutate = vi.fn();

let testDynatraceMcpResult: { isError: boolean; error: unknown; data: unknown } = {
  isError: false,
  error: undefined,
  data: undefined,
};

let testPrometheusDefaultsResult: { isError: boolean; error: unknown; data: unknown } = {
  isError: false,
  error: undefined,
  data: undefined,
};

vi.mock('../api/settings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/settings')>();
  return {
    ...actual,
    useSettingsQuery: () => queryResult,
    useRetryAiMutation: () => ({ mutate: retryMutate, isPending: false }),
    useChooseModelMutation: () => ({ mutate: chooseModelMutate, isPending: false }),
    useChooseAiEndpointMutation: () => ({ mutate: chooseAiEndpointMutate, isPending: false }),
    useChooseLoadGeneratorBudgetMutation: () => ({
      mutate: chooseLoadGeneratorBudgetMutate,
      isPending: false,
    }),
    useSaveDynatraceMcpMutation: () => ({ mutate: saveDynatraceMcpMutate, isPending: false }),
    useTestDynatraceMcpMutation: () => ({ mutate: testDynatraceMcpMutate, isPending: false, ...testDynatraceMcpResult }),
    useSavePrometheusDefaultsMutation: () => ({ mutate: savePrometheusDefaultsMutate, isPending: false }),
    useTestPrometheusDefaultsMutation: () => ({
      mutate: testPrometheusDefaultsMutate,
      isPending: false,
      ...testPrometheusDefaultsResult,
    }),
  };
});

function aSettings(overrides: Partial<Settings> = {}): Settings {
  return {
    vortexVersion: '0.1.0-SNAPSHOT',
    engine: {
      usesDocker: false,
      runner: 'local',
      executable: 'k6',
      dockerImage: 'grafana/k6:1.3.0',
      compressRawMetrics: true,
    },
    engineAvailability: { available: true, version: 'k6 v1.3.0', problem: '', remedy: '' },
    aiSettings: { provider: 'ollama', baseUrl: 'http://localhost:11434', model: 'qwen3:4b' },
    aiAvailability: {
      available: true,
      provider: 'ollama',
      model: 'qwen3:4b',
      problem: '',
      remedy: '',
    },
    installedModels: ['qwen3:4b', 'llama3.2:3b'],
    labStatus: {
      dockerAvailable: true,
      daemonRunning: true,
      composeAvailable: true,
      usable: true,
      version: '24.0.0',
      remedy: '',
    },
    workspacePath: '/Users/dev/.vortex',
    loadGenerator: {
      configured: { mode: 'automatic', cpuMillicores: null, memoryMebibytes: null },
      effective: {
        mode: 'automatic',
        allocation: { cpuMillicores: 4000, memoryBytes: 4 * 1024 ** 3 },
        detectedHost: {
          operatingSystem: 'Mac OS X',
          osVersion: '15.6',
          architecture: 'aarch64',
          availableProcessors: 12,
          totalMemoryBytes: 32 * 1024 ** 3,
        },
        osAndVortexReserve: { cpuMillicores: 1800, memoryBytes: Math.round(3.2 * 1024 ** 3) },
        sutReserve: { cpuMillicores: 5100, memoryBytes: Math.round(14.4 * 1024 ** 3) },
        colocatedWithManagedSut: true,
      },
      automaticPreview: {
        mode: 'automatic',
        allocation: { cpuMillicores: 4000, memoryBytes: 4 * 1024 ** 3 },
        detectedHost: {
          operatingSystem: 'Mac OS X',
          osVersion: '15.6',
          architecture: 'aarch64',
          availableProcessors: 12,
          totalMemoryBytes: 32 * 1024 ** 3,
        },
        osAndVortexReserve: { cpuMillicores: 1800, memoryBytes: Math.round(3.2 * 1024 ** 3) },
        sutReserve: { cpuMillicores: 5100, memoryBytes: Math.round(14.4 * 1024 ** 3) },
        colocatedWithManagedSut: true,
      },
    },
    dynatraceMcp: {
      enabled: false, endpoint: '', defaultWindowDisplay: '30d', organization: '',
    },
    dynatraceMcpAvailability: {
      available: false,
      problem: 'Dynatrace MCP is not enabled.',
      remedy: 'Turn it on and set the endpoint under Settings.',
    },
    prometheusDefaults: {
      endpoint: '',
      windowDisplay: '30d',
      headers: {},
      serviceLabel: '',
      routeLabel: '',
      methodLabel: '',
      configured: false,
    },
    ...overrides,
  };
}

describe('the settings page', () => {
  it("shows the engine's own remedy text when it is not available, never a generic error", () => {
    queryResult = {
      data: aSettings({
        engineAvailability: {
          available: false,
          version: '',
          problem: 'k6 was not found on PATH.',
          remedy: 'Install it with `brew install k6`.',
        },
      }),
      isError: false,
    };
    renderWithProviders(<SettingsPage />);

    expect(screen.getByText('k6 was not found on PATH.')).toBeInTheDocument();
    expect(screen.getByText('Install it with `brew install k6`.')).toBeInTheDocument();
  });

  it('offers a model picker only when models were actually found', () => {
    queryResult = { data: aSettings({ installedModels: [] }), isError: false };
    renderWithProviders(<SettingsPage />);

    expect(screen.queryByLabelText('Model')).not.toBeInTheDocument();
    expect(screen.getByText(/No models found at/)).toBeInTheDocument();
  });

  it('retries the AI connection when asked', async () => {
    queryResult = { data: aSettings(), isError: false };
    renderWithProviders(<SettingsPage />);

    await userEvent.click(withinCard('Local AI').getByRole('button', { name: 'Test connection' }));
    expect(retryMutate).toHaveBeenCalled();
  });

  it('saves the selected model', async () => {
    queryResult = { data: aSettings(), isError: false };
    renderWithProviders(<SettingsPage />);

    await userEvent.click(withinCard('Local AI').getByRole('button', { name: 'Save' }));
    expect(chooseModelMutate).toHaveBeenCalledWith('qwen3:4b', expect.anything());
  });

  it('saves an edited endpoint', async () => {
    queryResult = { data: aSettings(), isError: false };
    renderWithProviders(<SettingsPage />);

    const card = withinCard('Local AI');
    await userEvent.clear(card.getByLabelText('Endpoint'));
    await userEvent.type(card.getByLabelText('Endpoint'), 'http://localhost:22222');
    await userEvent.click(card.getByRole('button', { name: 'Save endpoint' }));

    expect(chooseAiEndpointMutate).toHaveBeenCalledWith('http://localhost:22222', expect.anything());
  });

  it('surfaces a failed load rather than a silent empty page', () => {
    queryResult = { data: undefined, isError: true };
    renderWithProviders(<SettingsPage />);

    expect(screen.getByText('Could not load settings')).toBeInTheDocument();
  });

  describe('load generator resources', () => {
    it('shows the automatic allocation computed for this host', () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      expect(screen.getByText('4 cores / 4 GiB')).toBeInTheDocument();
    });

    it('switching to custom prefills from what automatic would currently choose', async () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      await userEvent.click(screen.getByRole('radio', { name: 'Custom' }));

      expect(screen.getByLabelText('CPU')).toHaveValue('4');
      expect(screen.getByLabelText('Memory')).toHaveValue('4096');
    });

    it('saves a custom budget as millicores and mebibytes', async () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      await userEvent.click(screen.getByRole('radio', { name: 'Custom' }));
      await userEvent.clear(screen.getByLabelText('CPU'));
      await userEvent.type(screen.getByLabelText('CPU'), '2');
      await userEvent.clear(screen.getByLabelText('Memory'));
      await userEvent.type(screen.getByLabelText('Memory'), '2048');

      const saveButtons = screen.getAllByRole('button', { name: 'Save' });
      await userEvent.click(saveButtons[0]);

      expect(chooseLoadGeneratorBudgetMutate).toHaveBeenCalledWith(
        { mode: 'custom', cpuMillicores: 2000, memoryMebibytes: 2048 },
        expect.anything(),
      );
    });

    it('warns, without blocking, when custom leaves little headroom', async () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      await userEvent.click(screen.getByRole('radio', { name: 'Custom' }));
      await userEvent.clear(screen.getByLabelText('CPU'));
      await userEvent.type(screen.getByLabelText('CPU'), '12');

      expect(
        screen.getByText('This leaves little headroom for the host and anything else running on it.'),
      ).toBeInTheDocument();
    });
  });

  describe('Dynatrace', () => {
    beforeEach(() => {
      testDynatraceMcpResult = { isError: false, error: undefined, data: undefined };
    });

    it('shows the unconfigured state with its remedy', () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Dynatrace');
      expect(card.getByText('Unavailable')).toBeInTheDocument();
      expect(card.getByText('Dynatrace MCP is not enabled.')).toBeInTheDocument();
    });

    it('shows the connected state and the configured endpoint', () => {
      queryResult = {
        data: aSettings({
          dynatraceMcp: {
            enabled: true,
            endpoint: 'https://dynatrace-mcp.internal/mcp',
            defaultWindowDisplay: '30d',
            organization: '',
          },
          dynatraceMcpAvailability: { available: true, problem: '', remedy: '' },
        }),
        isError: false,
      };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Dynatrace');
      expect(card.getByText('Connected')).toBeInTheDocument();
      expect(card.getByText('https://dynatrace-mcp.internal/mcp')).toBeInTheDocument();
    });

    it('saves the endpoint entered manually', async () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Dynatrace');
      await userEvent.type(card.getByLabelText('Endpoint'), 'https://dynatrace-mcp.internal/mcp');
      await userEvent.click(card.getByRole('button', { name: 'Save' }));

      expect(saveDynatraceMcpMutate).toHaveBeenCalledWith(
        expect.objectContaining({ endpoint: 'https://dynatrace-mcp.internal/mcp' }),
        expect.anything(),
      );
    });

    it('the test connection button is disabled with a blank endpoint', () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Dynatrace');
      expect(card.getByRole('button', { name: 'Test connection' })).toBeDisabled();
    });

    it('shows the server-supplied reason when testing the connection fails', () => {
      queryResult = { data: aSettings(), isError: false };
      testDynatraceMcpResult = {
        isError: true,
        error: new ApiError('POST', '/api/settings/dynatrace-mcp/test', 400,
          'Enter the Dynatrace MCP endpoint before testing the connection.'),
        data: undefined,
      };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Dynatrace');
      expect(card.getByText('Enter the Dynatrace MCP endpoint before testing the connection.'))
          .toBeInTheDocument();
    });

    it('always shows the local bridge mode explanation', () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Dynatrace');
      expect(card.getByText('Local bridge mode')).toBeInTheDocument();
    });

    it('offers a dropdown to pick an organization when the test finds more than one', () => {
      queryResult = { data: aSettings(), isError: false };
      testDynatraceMcpResult = {
        isError: false,
        error: undefined,
        data: {
          succeeded: false,
          stages: [
            { stage: 'Local bridge started', succeeded: true, category: null, detail: '' },
            { stage: 'Dynatrace tool discovered', succeeded: true, category: null, detail: '' },
            {
              stage: 'Resolved organization',
              succeeded: false,
              category: 'AMBIGUOUS_ORGANIZATION',
              detail: 'this account has 2 organizations — pick one below.',
            },
          ],
          organizationOptions: ['org-a', 'org-b'],
        },
      };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Dynatrace');
      expect(card.getByLabelText('Organization', { selector: 'input' })).toBeInTheDocument();
    });

    it('shows no organization dropdown when nothing has been tested yet', () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Dynatrace');
      expect(card.queryByLabelText('Organization', { selector: 'input' })).not.toBeInTheDocument();
    });
  });

  describe('Prometheus defaults', () => {
    beforeEach(() => {
      testPrometheusDefaultsResult = { isError: false, error: undefined, data: undefined };
    });

    it('shows "not configured" when no default endpoint is set', () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Prometheus defaults');
      expect(card.getByText('not configured')).toBeInTheDocument();
    });

    it('shows the configured endpoint', () => {
      queryResult = {
        data: aSettings({
          prometheusDefaults: {
            endpoint: 'http://prometheus.internal:9090',
            windowDisplay: '30d',
            headers: {},
            serviceLabel: '',
            routeLabel: '',
            methodLabel: '',
            configured: true,
          },
        }),
        isError: false,
      };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Prometheus defaults');
      expect(card.getByText('http://prometheus.internal:9090')).toBeInTheDocument();
    });

    it('renders no "Enabled" control — there is nothing to enable or disable', () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Prometheus defaults');
      expect(card.queryByLabelText('Enabled')).not.toBeInTheDocument();
    });

    it('saves the endpoint entered manually', async () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Prometheus defaults');
      await userEvent.type(card.getByLabelText('Endpoint'), 'http://prometheus.internal:9090');
      await userEvent.click(card.getByRole('button', { name: 'Save' }));

      expect(savePrometheusDefaultsMutate).toHaveBeenCalledWith(
        expect.objectContaining({ endpoint: 'http://prometheus.internal:9090' }),
        expect.anything(),
      );
    });

    it('the test connection button is disabled with a blank endpoint', () => {
      queryResult = { data: aSettings(), isError: false };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Prometheus defaults');
      expect(card.getByRole('button', { name: 'Test connection' })).toBeDisabled();
    });

    it('colours a successful test connection', () => {
      queryResult = { data: aSettings(), isError: false };
      testPrometheusDefaultsResult = {
        isError: false,
        error: undefined,
        data: { succeeded: true, state: 'CONNECTED', message: 'Connected to http://prometheus.internal:9090.' },
      };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Prometheus defaults');
      expect(card.getByText('Connected')).toBeInTheDocument();
    });

    it('shows the server-supplied reason when testing the connection fails', () => {
      queryResult = { data: aSettings(), isError: false };
      testPrometheusDefaultsResult = {
        isError: true,
        error: new ApiError('POST', '/api/settings/prometheus-defaults/test', 400,
          'Enter the Prometheus endpoint before testing the connection.'),
        data: undefined,
      };
      renderWithProviders(<SettingsPage />);

      const card = withinCard('Prometheus defaults');
      expect(card.getByText('Enter the Prometheus endpoint before testing the connection.'))
          .toBeInTheDocument();
    });
  });
});
