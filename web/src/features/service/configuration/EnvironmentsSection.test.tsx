import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { Environment, EnvironmentOption } from '../../../api/configuration';
import { EnvironmentsSection } from './EnvironmentsSection';

const deleteMutate = vi.fn();

vi.mock('../../../api/configuration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/configuration')>();
  return {
    ...actual,
    useDeleteEnvironmentMutation: () => ({ mutate: deleteMutate, isPending: false }),
    // EnvironmentDrawer's own mutations — not under test here, only that the drawer opens.
    useAddEnvironmentMutation: () => ({ mutate: vi.fn(), isPending: false, isError: false }),
    useValidateTargetMutation: () => ({ mutate: vi.fn(), isPending: false, data: undefined }),
  };
});

// The confirm dialog needs a provider this test does not wrap with — see DatasetsSection.test.tsx
// for the same reasoning. What matters here is that deletion asks first and states plainly that
// recorded evidence is unaffected.
const confirmChildren = vi.fn();
vi.mock('@mantine/modals', () => ({
  modals: {
    openConfirmModal: (options: { children: unknown; onConfirm: () => void }) => {
      confirmChildren(options.children);
      options.onConfirm();
    },
  },
}));

const ENVIRONMENT_TYPES: EnvironmentOption[] = [
  { name: 'LOCAL_ISOLATED', label: 'Local (isolated)', description: '' },
];
const DEPENDENCY_MODES: EnvironmentOption[] = [{ name: 'MOCKED', label: 'Mocked', description: '' }];

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

function render(environments: Environment[] = []) {
  deleteMutate.mockReset();
  confirmChildren.mockReset();
  return renderWithProviders(
    <EnvironmentsSection
      serviceId="checkout"
      environments={environments}
      environmentTypes={ENVIRONMENT_TYPES}
      dependencyModes={DEPENDENCY_MODES}
    />,
  );
}

describe('EnvironmentsSection', () => {
  it('says plainly that nothing is configured yet, rather than an empty list', () => {
    render([]);

    expect(screen.getByText(/No environments configured yet/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add environment' })).toBeInTheDocument();
  });

  it('shows each environment as a compact row with its target and dependency mode', () => {
    render([
      anEnvironment({
        target: { kind: 'DOCKER_IMAGE', summary: 'Docker: payment-service:1.4.2', ownershipLabel: 'Vortex managed' },
      }),
    ]);

    expect(screen.getByText('local')).toBeInTheDocument();
    expect(screen.getByText(/Docker: payment-service:1\.4\.2/)).toBeInTheDocument();
    expect(screen.getByText(/Mocked/)).toBeInTheDocument();
  });

  it('shows a production-sized indicator only when the environment is marked as one', () => {
    render([anEnvironment({ productionLike: true })]);

    expect(screen.getByText(/production-sized/)).toBeInTheDocument();
  });

  it('opens the same editor, empty, from "Add environment"', async () => {
    const user = userEvent.setup();
    render([anEnvironment()]);

    await user.click(screen.getByRole('button', { name: 'Add environment' }));

    expect(await screen.findByLabelText('Name')).toHaveValue('');
  });

  it('opens the same editor, prefilled, from a row\'s Edit action', async () => {
    const user = userEvent.setup();
    render([anEnvironment({ name: 'staging', baseUrl: 'https://staging.example.com' })]);

    await user.click(screen.getByRole('button', { name: 'Edit staging' }));

    expect(await screen.findByLabelText('Name')).toHaveValue('staging');
    expect(screen.getByLabelText('Target URL')).toHaveValue('https://staging.example.com');
  });

  it('confirms before deleting, and states that recorded evidence is unaffected', async () => {
    const user = userEvent.setup();
    render([anEnvironment({ name: 'staging' })]);

    await user.click(screen.getByRole('button', { name: 'More actions for staging' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Delete environment' }));

    expect(confirmChildren).toHaveBeenCalled();
    expect(deleteMutate).toHaveBeenCalledWith('staging', expect.anything());
  });
});
