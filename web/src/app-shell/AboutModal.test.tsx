import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/renderWithProviders';
import type { Settings } from '../api/settings';
import { AboutModal } from './AboutModal';

let queryResult: { data: Settings | undefined } = { data: undefined };

vi.mock('../api/settings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/settings')>();
  return {
    ...actual,
    useSettingsQuery: () => queryResult,
  };
});

function aSettings(overrides: Partial<Settings> = {}): Settings {
  return {
    vortexVersion: '0.1.0-alpha.21',
    engine: { usesDocker: false, runner: '', executable: '', dockerImage: '', compressRawMetrics: false },
    engineAvailability: { available: true, version: '', problem: '', remedy: '' },
    aiSettings: { provider: '', baseUrl: '', model: '' },
    aiAvailability: { available: true, provider: '', model: '', problem: '', remedy: '' },
    installedModels: [],
    labStatus: {
      dockerAvailable: false,
      daemonRunning: false,
      composeAvailable: false,
      usable: false,
      version: '',
      remedy: '',
    },
    workspacePath: '/tmp/vortex',
    loadGenerator: {
      configured: { mode: 'automatic', cpuMillicores: null, memoryMebibytes: null },
      effective: {
        mode: 'automatic',
        allocation: { cpuMillicores: null, memoryBytes: null },
        detectedHost: {
          operatingSystem: '',
          osVersion: '',
          architecture: '',
          availableProcessors: 0,
          totalMemoryBytes: 0,
        },
        osAndVortexReserve: { cpuMillicores: null, memoryBytes: null },
        sutReserve: { cpuMillicores: null, memoryBytes: null },
        colocatedWithManagedSut: false,
      },
      automaticPreview: {
        mode: 'automatic',
        allocation: { cpuMillicores: null, memoryBytes: null },
        detectedHost: {
          operatingSystem: '',
          osVersion: '',
          architecture: '',
          availableProcessors: 0,
          totalMemoryBytes: 0,
        },
        osAndVortexReserve: { cpuMillicores: null, memoryBytes: null },
        sutReserve: { cpuMillicores: null, memoryBytes: null },
        colocatedWithManagedSut: false,
      },
    },
    dynatraceMcp: { enabled: false, endpoint: '', defaultWindowDisplay: '', organization: '' },
    dynatraceMcpAvailability: { available: false, problem: '', remedy: '' },
    prometheusDefaults: {
      endpoint: '',
      windowDisplay: '',
      headers: {},
      serviceLabel: '',
      routeLabel: '',
      methodLabel: '',
      configured: false,
    },
    ...overrides,
  };
}

describe('AboutModal', () => {
  it('renders nothing when closed', () => {
    queryResult = { data: undefined };
    renderWithProviders(<AboutModal opened={false} onClose={vi.fn()} />);

    expect(screen.queryByText('Vortex')).not.toBeInTheDocument();
  });

  it('shows the project identity, creator, version and license', () => {
    queryResult = { data: aSettings({ vortexVersion: '0.1.0-alpha.21' }) };
    renderWithProviders(<AboutModal opened onClose={vi.fn()} />);

    expect(screen.getByRole('heading', { name: 'Vortex' })).toBeInTheDocument();
    expect(screen.getByText('Performance Engineering Workbench')).toBeInTheDocument();
    expect(screen.getByText(/turning load tests into repeatable/)).toBeInTheDocument();
    expect(screen.getByText('Created by Alvin Cris Tabontabon')).toBeInTheDocument();
    expect(screen.getByText(/0\.1\.0-alpha\.21/)).toBeInTheDocument();
    expect(screen.getByText(/Apache License 2\.0/)).toBeInTheDocument();
  });

  it('links out to GitHub, Documentation and License in a new tab', () => {
    queryResult = { data: aSettings() };
    renderWithProviders(<AboutModal opened onClose={vi.fn()} />);

    const github = screen.getByRole('link', { name: 'GitHub' });
    expect(github).toHaveAttribute('href', 'https://github.com/acltabontabon/vortex');
    expect(github).toHaveAttribute('target', '_blank');
    expect(github).toHaveAttribute('rel', 'noopener noreferrer');

    const docs = screen.getByRole('link', { name: 'Documentation' });
    expect(docs).toHaveAttribute('href', 'https://acltabontabon.com/vortex/docs.html');
    expect(docs).toHaveAttribute('target', '_blank');
    expect(docs).toHaveAttribute('rel', 'noopener noreferrer');

    const license = screen.getByRole('link', { name: 'License' });
    expect(license).toHaveAttribute('href', 'https://github.com/acltabontabon/vortex/blob/main/LICENSE');
    expect(license).toHaveAttribute('target', '_blank');
    expect(license).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('closes on Escape', async () => {
    const user = userEvent.setup();
    queryResult = { data: aSettings() };
    const onClose = vi.fn();
    renderWithProviders(<AboutModal opened onClose={onClose} />);

    await user.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledOnce();
  });
});
