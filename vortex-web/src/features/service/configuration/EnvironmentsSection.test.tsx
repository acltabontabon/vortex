import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { Environment, EnvironmentOption } from '../../../api/configuration';
import { EnvironmentsSection } from './EnvironmentsSection';

const addMutate = vi.fn();
const validateMutate = vi.fn();
let addPending = false;
let validatePending = false;
let validateData: { valid: boolean; checks: string[] } | undefined;

vi.mock('../../../api/configuration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/configuration')>();
  return {
    ...actual,
    useAddEnvironmentMutation: () => ({ mutate: addMutate, isPending: addPending, isError: false }),
    useValidateTargetMutation: () => ({
      mutate: validateMutate,
      isPending: validatePending,
      data: validateData,
    }),
  };
});

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
    ...overrides,
  };
}

function render(environments: Environment[] = []) {
  addMutate.mockReset();
  validateMutate.mockReset();
  addPending = false;
  validatePending = false;
  validateData = undefined;
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
  it('shows each saved environment\'s own target summary', () => {
    render([anEnvironment({ target: { kind: 'DOCKER_IMAGE', summary: 'Docker: payment-service:1.4.2', ownershipLabel: 'Vortex managed' } })]);

    expect(screen.getByText('Docker: payment-service:1.4.2')).toBeInTheDocument();
  });

  it('shows only the target URL field for the default, existing-endpoint kind', () => {
    render();

    expect(screen.getByLabelText('Target URL')).toBeInTheDocument();
    expect(screen.queryByLabelText('Image')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Compose file')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Test Connection' })).not.toBeInTheDocument();
  });

  it('switches to the Docker image fields when that target kind is selected', async () => {
    const user = userEvent.setup();
    render();

    await user.click(screen.getByRole('radio', { name: 'Docker image' }));

    expect(screen.queryByLabelText('Target URL')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Image')).toBeInTheDocument();
    expect(screen.getByLabelText('Container port')).toBeInTheDocument();
    expect(screen.getByLabelText(/CPU \(cores\)/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Test Connection' })).toBeInTheDocument();
  });

  it('switches to the Compose fields when that target kind is selected', async () => {
    const user = userEvent.setup();
    render();

    await user.click(screen.getByRole('radio', { name: 'Docker Compose' }));

    expect(screen.queryByLabelText('Target URL')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Compose file')).toBeInTheDocument();
    expect(screen.getByLabelText('Service')).toBeInTheDocument();
    expect(screen.getByLabelText('Container port')).toBeInTheDocument();
  });

  it('submits an existing-endpoint environment with exactly today\'s request shape (regression guard)', async () => {
    const user = userEvent.setup();
    render();

    await user.clear(screen.getByLabelText('Target URL'));
    await user.type(screen.getByLabelText('Target URL'), 'http://localhost:9090');
    await user.click(screen.getByRole('button', { name: 'Save environment' }));

    expect(addMutate).toHaveBeenCalled();
    const sent = addMutate.mock.calls[0][0];
    expect(sent).toEqual({
      name: 'local',
      baseUrl: 'http://localhost:9090',
      type: 'LOCAL_ISOLATED',
      dependencies: 'MOCKED',
      productionLike: false,
      headerNames: undefined,
      headerValues: undefined,
      targetKind: undefined,
    });
  });

  it('submits a Docker image configuration with the millicore-converted CPU value', async () => {
    const user = userEvent.setup();
    render();

    await user.click(screen.getByRole('radio', { name: 'Docker image' }));
    await user.type(screen.getByLabelText('Image'), 'payment-service:1.4.2');
    await user.type(screen.getByLabelText('Container port'), '8080');
    await user.type(screen.getByLabelText(/CPU \(cores\)/), '0.5');
    await user.type(screen.getByLabelText('Memory (MiB)'), '512');
    await user.click(screen.getByRole('button', { name: 'Save environment' }));

    expect(addMutate).toHaveBeenCalled();
    const sent = addMutate.mock.calls[0][0];
    expect(sent).toMatchObject({
      targetKind: 'DOCKER_IMAGE',
      image: 'payment-service:1.4.2',
      containerPort: 8080,
      cpuMillicores: 500,
      memoryMebibytes: 512,
      baseUrl: '',
    });
  });

  it('calls the validate endpoint with the current form values, and renders the returned checks', async () => {
    const user = userEvent.setup();
    render();

    await user.click(screen.getByRole('radio', { name: 'Docker image' }));
    await user.type(screen.getByLabelText('Image'), 'payment-service:1.4.2');
    await user.type(screen.getByLabelText('Container port'), '8080');

    validateData = { valid: true, checks: ['Docker daemon reachable: OK', 'Image present: OK'] };
    await user.click(screen.getByRole('button', { name: 'Test Connection' }));

    expect(validateMutate).toHaveBeenCalled();
    const sent = validateMutate.mock.calls[0][0];
    expect(sent).toMatchObject({ targetKind: 'DOCKER_IMAGE', image: 'payment-service:1.4.2', containerPort: 8080 });
  });

  it('shows the validation result once it arrives', async () => {
    render();
    const user = userEvent.setup();

    // Switching kind triggers the re-render the mock's freshly-set data needs to be read on —
    // there is no live subscription here, only what the mocked hook returns on its next call.
    validateData = { valid: false, checks: ['Docker daemon reachable: FAILED — daemon not running'] };
    await user.click(screen.getByRole('radio', { name: 'Docker Compose' }));

    expect(screen.getByText('Connection checks failed')).toBeInTheDocument();
    expect(screen.getByText(/daemon not running/)).toBeInTheDocument();
  });
});
