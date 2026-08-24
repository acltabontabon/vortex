import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/renderWithProviders';
import { ApiError } from '../api/client';
import { NewServicePage } from './NewServicePage';

const navigate = vi.fn();
const mutate = vi.fn();
let mutationState: {
  isPending: boolean;
  isError: boolean;
  isSuccess: boolean;
  error: unknown;
  data: unknown;
} = { isPending: false, isError: false, isSuccess: false, error: null, data: undefined };

const openApiPreviewMutate = vi.fn();
const openApiPreviewReset = vi.fn();
let openApiPreviewState: { isPending: boolean; isError: boolean; data: unknown } = {
  isPending: false,
  isError: false,
  data: undefined,
};

const workspaceCheckMutate = vi.fn();
const workspaceCheckReset = vi.fn();
let workspaceCheckState: { isPending: boolean; isError: boolean; data: unknown } = {
  isPending: false,
  isError: false,
  data: undefined,
};

const detectConfigMutate = vi.fn();
const detectConfigReset = vi.fn();
let detectConfigState: { isPending: boolean; isError: boolean; data: unknown } = {
  isPending: false,
  isError: false,
  data: undefined,
};

const adoptMutate = vi.fn();
let adoptState: {
  isPending: boolean;
  isError: boolean;
  isSuccess: boolean;
  error: unknown;
  data: unknown;
} = { isPending: false, isError: false, isSuccess: false, error: null, data: undefined };

vi.mock('../api/services', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/services')>();
  return {
    ...actual,
    useCreateServiceMutation: () => ({ mutate, ...mutationState }),
    useAdoptServiceMutation: () => ({ mutate: adoptMutate, ...adoptState }),
    useOpenApiPreviewMutation: () => ({
      mutate: openApiPreviewMutate,
      reset: openApiPreviewReset,
      ...openApiPreviewState,
    }),
    useWorkspaceCheckMutation: () => ({
      mutate: workspaceCheckMutate,
      reset: workspaceCheckReset,
      ...workspaceCheckState,
    }),
    useDetectConfigMutation: () => ({
      mutate: detectConfigMutate,
      reset: detectConfigReset,
      ...detectConfigState,
    }),
  };
});

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigate };
});

function resetAll() {
  mutationState = { isPending: false, isError: false, isSuccess: false, error: null, data: undefined };
  adoptState = { isPending: false, isError: false, isSuccess: false, error: null, data: undefined };
  openApiPreviewState = { isPending: false, isError: false, data: undefined };
  workspaceCheckState = { isPending: false, isError: false, data: undefined };
  detectConfigState = { isPending: false, isError: false, data: undefined };
  openApiPreviewMutate.mockClear();
  workspaceCheckMutate.mockClear();
  detectConfigMutate.mockClear();
  adoptMutate.mockClear();
}

describe('the new-service page', () => {
  it('submits the trimmed fields, treating blank optional fields as absent', async () => {
    resetAll();
    renderWithProviders(<NewServicePage />);

    await userEvent.type(screen.getByLabelText('Service name', { exact: false }), '  checkout-service  ');
    await userEvent.click(screen.getByRole('button', { name: 'Add service' }));

    expect(mutate).toHaveBeenCalledWith(
      {
        name: 'checkout-service',
        description: undefined,
        workspacePath: undefined,
        openApiUrl: undefined,
      },
      expect.anything(),
    );
  });

  it("surfaces the server's own explanation for a rejected name, not a generic failure", () => {
    resetAll();
    mutationState = {
      isPending: false,
      isError: true,
      isSuccess: false,
      error: new ApiError('POST', '/api/services', 400, "A project named 'checkout-service' already exists."),
      data: undefined,
    };
    renderWithProviders(<NewServicePage />);

    expect(
      screen.getByText("A project named 'checkout-service' already exists."),
    ).toBeInTheDocument();
  });

  it('reports a failed import without implying the service was not created', () => {
    resetAll();
    mutationState = {
      isPending: false,
      isError: false,
      isSuccess: true,
      error: null,
      data: {
        service: { id: 'checkout', name: 'checkout-service', description: null, serviceVersion: null },
        importOutcome: {
          attempted: true,
          succeeded: false,
          message: null,
          info: null,
          error: 'Vortex does not recognise this kind of API description.',
          errorDetails: ['Supported formats: OpenAPI 3.x as .yaml, .yml or .json.'],
        },
      },
    };
    renderWithProviders(<NewServicePage />);

    expect(
      screen.getByText('The service was created, but the import did not finish'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('Vortex does not recognise this kind of API description.'),
    ).toBeInTheDocument();
  });

  it('disables submit until a name is entered', async () => {
    resetAll();
    renderWithProviders(<NewServicePage />);

    const button = screen.getByRole('button', { name: 'Add service' });
    expect(button).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Service name', { exact: false }), 'checkout-service');

    expect(button).not.toBeDisabled();
  });

  it('previews an OpenAPI address as it is typed', async () => {
    resetAll();
    renderWithProviders(<NewServicePage />);

    await userEvent.type(
      screen.getByPlaceholderText('https://localhost:8080/openapi.yaml'),
      'http://localhost:8080/openapi.yaml',
    );

    await waitFor(
      () => expect(openApiPreviewMutate).toHaveBeenCalledWith({ url: 'http://localhost:8080/openapi.yaml' }),
      { timeout: 2000 },
    );
  });

  it('shows discovered operations once the preview succeeds', async () => {
    resetAll();
    openApiPreviewState = {
      isPending: false,
      isError: false,
      data: {
        ok: true,
        title: 'checkout-service',
        operationCount: 12,
        sample: [{ label: 'GET /orders' }, { label: 'POST /orders' }, { label: 'GET /orders/{id}' }],
        error: null,
        errorDetails: [],
      },
    };
    renderWithProviders(<NewServicePage />);

    // The hint only shows evidence for a field that actually holds a value — typing is what
    // makes the (test-injected) preview result relevant, independent of the real debounce/mutate.
    await userEvent.type(
      screen.getByPlaceholderText('https://localhost:8080/openapi.yaml'),
      'http://localhost:8080/openapi.yaml',
    );

    expect(screen.getByText('checkout-service · 12 operations discovered')).toBeInTheDocument();
    expect(screen.getByText('GET /orders')).toBeInTheDocument();
    expect(screen.getByText('+9 more')).toBeInTheDocument();
  });

  it('shows an inline error, and no evidence, when the preview fails', async () => {
    resetAll();
    openApiPreviewState = {
      isPending: false,
      isError: false,
      data: {
        ok: false,
        title: null,
        operationCount: 0,
        sample: [],
        error: 'Vortex does not recognise this kind of API description.',
        errorDetails: [],
      },
    };
    renderWithProviders(<NewServicePage />);

    await userEvent.type(
      screen.getByPlaceholderText('https://localhost:8080/openapi.yaml'),
      'http://localhost:8080/nope.yaml',
    );

    expect(
      screen.getByText('Vortex does not recognise this kind of API description.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/operations discovered/)).not.toBeInTheDocument();
  });

  it('shows the service location field prominently, not behind a disclosure', async () => {
    resetAll();
    renderWithProviders(<NewServicePage />);

    await userEvent.type(
      screen.getByLabelText('Service location', { exact: false }),
      '/Users/me/code/checkout-service',
    );
    expect(screen.getByLabelText('Service location', { exact: false })).toHaveValue(
      '/Users/me/code/checkout-service',
    );
  });

  it('checks a repository path once it is typed, and shows what it found', async () => {
    resetAll();
    workspaceCheckState = {
      isPending: false,
      isError: false,
      data: { exists: true, isDirectory: true, writable: true, gitRepository: true, error: null },
    };
    renderWithProviders(<NewServicePage />);

    await userEvent.type(
      screen.getByLabelText('Service location', { exact: false }),
      '/Users/me/code/checkout-service',
    );

    await waitFor(
      () =>
        expect(workspaceCheckMutate).toHaveBeenCalledWith({
          path: '/Users/me/code/checkout-service',
        }),
      { timeout: 2000 },
    );
    await waitFor(() =>
      expect(detectConfigMutate).toHaveBeenCalledWith({ path: '/Users/me/code/checkout-service' }),
    );
    expect(screen.getByText('Git repository')).toBeInTheDocument();
    expect(screen.getByText('Writable')).toBeInTheDocument();
  });

  it('tells the user a repository is already onboarded, and offers to open it', async () => {
    resetAll();
    detectConfigState = {
      isPending: false,
      isError: false,
      data: {
        alreadyOnboarded: true,
        existingService: { id: 'checkout', name: 'checkout-service', description: null, serviceVersion: null },
        found: false,
        valid: false,
        summary: null,
        problems: [],
        rawYaml: null,
        sourcePath: null,
      },
    };
    renderWithProviders(<NewServicePage />);

    await userEvent.type(
      screen.getByLabelText('Service location', { exact: false }),
      '/Users/me/code/checkout-service',
    );

    expect(screen.getByText('This repository is already in Vortex')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open it' })).toHaveAttribute('href', '/services/checkout');
    expect(screen.getByRole('button', { name: 'Add service' })).toBeDisabled();
  });

  it('summarises a found, valid configuration and lets the name be changed before adopting', async () => {
    resetAll();
    detectConfigState = {
      isPending: false,
      isError: false,
      data: {
        alreadyOnboarded: false,
        existingService: null,
        found: true,
        valid: true,
        summary: {
          serviceName: 'checkout-service',
          serviceDescription: 'Places and manages customer orders.',
          workloadCount: 2,
          workloadNames: ['average-load', 'breakpoint'],
          environmentCount: 1,
          operationBindingCount: 3,
          hasProductionObservation: true,
          hasLocalLab: false,
          openApiSourceDescription: 'file: openapi/checkout.yaml',
        },
        problems: [],
        rawYaml: null,
        sourcePath: '/Users/me/code/checkout-service/.vortex/vortex.yaml',
      },
    };
    renderWithProviders(<NewServicePage />);

    await userEvent.type(
      screen.getByLabelText('Service location', { exact: false }),
      '/Users/me/code/checkout-service',
    );

    expect(screen.getByText('Vortex configuration found')).toBeInTheDocument();
    expect(screen.getByLabelText('Register as')).toHaveValue('checkout-service');
    // Fields recreated from a fresh onboarding no longer show once a configuration is restored.
    expect(screen.queryByLabelText('Service name', { exact: false })).not.toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText('Register as'));
    await userEvent.type(screen.getByLabelText('Register as'), 'checkout');
    await userEvent.click(screen.getByRole('button', { name: 'Add service' }));

    expect(adoptMutate).toHaveBeenCalledWith(
      { workspacePath: '/Users/me/code/checkout-service', name: 'checkout' },
      expect.anything(),
    );
  });

  it('explains an invalid configuration and falls back to normal onboarding on request', async () => {
    resetAll();
    detectConfigState = {
      isPending: false,
      isError: false,
      data: {
        alreadyOnboarded: false,
        existingService: null,
        found: true,
        valid: false,
        summary: null,
        problems: ["'journeys:' is no longer understood"],
        rawYaml: 'journeys:\n  placeOrder: {}\n',
        sourcePath: '/Users/me/code/checkout-service/.vortex/vortex.yaml',
      },
    };
    renderWithProviders(<NewServicePage />);

    await userEvent.type(
      screen.getByLabelText('Service location', { exact: false }),
      '/Users/me/code/checkout-service',
    );

    expect(screen.getByText('Vortex configuration found, but it could not be loaded')).toBeInTheDocument();
    expect(screen.getByText("'journeys:' is no longer understood")).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Continue without importing' }));

    expect(screen.getByLabelText('Service name', { exact: false })).toBeInTheDocument();
  });
});
