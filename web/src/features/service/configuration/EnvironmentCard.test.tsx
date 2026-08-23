import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { Environment } from '../../../api/configuration';
import { EnvironmentCard } from './EnvironmentCard';

function anEnvironment(overrides: Partial<Environment> = {}): Environment {
  return {
    name: 'local',
    baseUrl: 'http://localhost:8080',
    type: 'LOCAL_ISOLATED',
    typeLabel: 'Local (isolated)',
    dependencyMode: 'MOCKED',
    dependencyModeLabel: 'Mocked',
    classification: 'ISOLATED',
    classificationLabel: 'Isolated',
    classificationCaveat: 'Dependencies are simulated or controlled.',
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
    ...overrides,
  };
}

describe('EnvironmentCard', () => {
  it('shows the name, classification and target summary', () => {
    renderWithProviders(<EnvironmentCard environment={anEnvironment()} onEdit={vi.fn()} onDelete={vi.fn()} />);

    expect(screen.getByText('local')).toBeInTheDocument();
    expect(screen.getByText('Isolated')).toBeInTheDocument();
    expect(screen.getByText(/http:\/\/localhost:8080/)).toBeInTheDocument();
  });

  it('calls onEdit when Edit is pressed', async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    renderWithProviders(<EnvironmentCard environment={anEnvironment()} onEdit={onEdit} onDelete={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: 'Edit local' }));

    expect(onEdit).toHaveBeenCalled();
  });

  it('calls onDelete from the overflow menu', async () => {
    const user = userEvent.setup();
    const onDelete = vi.fn();
    renderWithProviders(<EnvironmentCard environment={anEnvironment()} onEdit={vi.fn()} onDelete={onDelete} />);

    await user.click(screen.getByRole('button', { name: 'More actions for local' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Delete environment' }));

    expect(onDelete).toHaveBeenCalled();
  });
});
