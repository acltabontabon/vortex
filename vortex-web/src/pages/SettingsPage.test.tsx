import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/renderWithProviders';
import type { Settings } from '../api/settings';
import { SettingsPage } from './SettingsPage';

let queryResult: { data: Settings | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};
const retryMutate = vi.fn();
const chooseModelMutate = vi.fn();

vi.mock('../api/settings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/settings')>();
  return {
    ...actual,
    useSettingsQuery: () => queryResult,
    useRetryAiMutation: () => ({ mutate: retryMutate, isPending: false }),
    useChooseModelMutation: () => ({ mutate: chooseModelMutate, isPending: false }),
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

    await userEvent.click(screen.getByRole('button', { name: 'Test connection' }));
    expect(retryMutate).toHaveBeenCalled();
  });

  it('saves the selected model', async () => {
    queryResult = { data: aSettings(), isError: false };
    renderWithProviders(<SettingsPage />);

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(chooseModelMutate).toHaveBeenCalledWith('qwen3:4b', expect.anything());
  });

  it('surfaces a failed load rather than a silent empty page', () => {
    queryResult = { data: undefined, isError: true };
    renderWithProviders(<SettingsPage />);

    expect(screen.getByText('Could not load settings')).toBeInTheDocument();
  });
});
