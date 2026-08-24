import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Tests } from '../../api/workspace';
import type { CatalogOperation, RecommendationDto, TestEdit } from '../../api/tests';
import { TestEditorPage } from './TestEditorPage';

/**
 * This page is now a thin shell around `TestComposer`/`WorkloadPreviewPanel` — see the page's own
 * header comment. `TestComposer.test.tsx` owns the behavioral coverage (Intent, Load shapes,
 * recommendations, spike params, saving); these tests only cover what's specific to this page: which
 * mode/editingName it hands to the composer, that it renders the preview rail, and that navigation
 * away on close/save actually happens.
 */

let testsResult: { data: Tests | undefined; isError: boolean } = { data: undefined, isError: false };
let catalogResult: { data: CatalogOperation[] | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};
let editResult: { data: TestEdit | undefined; isError: boolean } = { data: undefined, isError: false };
let recommendationResult: { data: RecommendationDto | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};
const saveMutate = vi.fn();
const previewMutate = vi.fn();
const navigateMock = vi.fn();
let routeParams: { id: string; name?: string } = { id: 'checkout' };

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useParams: () => routeParams, useNavigate: () => navigateMock };
});

vi.mock('../../api/workspace', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/workspace')>();
  return { ...actual, useTestsQuery: () => testsResult };
});

vi.mock('../../api/tests', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/tests')>();
  return {
    ...actual,
    useCatalogOperationsQuery: () => catalogResult,
    useTestEditQuery: () => editResult,
    useSaveTestMutation: () => ({ mutate: saveMutate, isPending: false, isError: false, error: null }),
    usePreviewMutation: () => ({ mutate: previewMutate, data: undefined }),
    useRecommendationQuery: () => recommendationResult,
  };
});

const CATALOG: CatalogOperation[] = [
  { id: 'getAccount', label: 'GET /accounts/{id}', method: 'GET', path: '/accounts/{id}', mutating: false },
];

const TEST_TYPES = [
  { name: 'AVERAGE_LOAD', label: 'Average load', question: 'Does it meet objectives normally?', guidance: '', saturating: false, configuredTestCount: 1 },
];

function aTestsResult(): Tests {
  return {
    header: {
      id: 'checkout', name: 'checkout-service', description: null, target: null,
      environmentCount: 1, release: null,
      readiness: { canRun: true, satisfiedCount: 1, totalCount: 1, blockerCount: 0, items: [], nextStepText: null },
      operationCount: 1, testCount: 0, runCount: 0, running: null,
    },
    tests: [],
    testTypes: TEST_TYPES,
    environmentNames: ['local'],
  };
}

describe('the test editor page', () => {
  it('creating: titles itself generically and hands the composer create mode', () => {
    routeParams = { id: 'checkout' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestEditorPage />);

    expect(screen.getByText('Define a test')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create test' })).toBeInTheDocument();
  });

  it('editing: titles itself with the test name and hands the composer edit mode, prefilled', () => {
    routeParams = { id: 'checkout', name: 'average-load' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = {
      data: {
        name: 'average-load',
        description: 'A steady load.',
        objective: '',
        type: 'AVERAGE_LOAD',
        model: 'OPEN',
        rate: 20,
        vus: null,
        durationMinutes: 10,
        ramping: false,
        peakRate: null,
        stages: null,
        singleOperation: null,
        weights: { getAccount: 100 },
        shapeKind: 'STEADY',
        explicitStages: [],
      },
      isError: false,
    };

    renderWithProviders(<TestEditorPage />);

    expect(screen.getByText('average-load')).toBeInTheDocument();
    expect(screen.getByDisplayValue('20')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save test' })).toBeInTheDocument();
  });

  it('renders the Workload Preview rail alongside the composer', () => {
    routeParams = { id: 'checkout' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestEditorPage />);

    expect(screen.getByText('Workload')).toBeInTheDocument();
  });

  it('offers to import an API description when there are no operations to build a test from', () => {
    routeParams = { id: 'checkout' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: [], isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestEditorPage />);

    expect(screen.getByText('No operations yet.')).toBeInTheDocument();
  });

  it('navigates back to the service on Cancel', async () => {
    routeParams = { id: 'checkout' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestEditorPage />);
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(navigateMock).toHaveBeenCalledWith('/services/checkout');
  });

  it('navigates back to the service after a successful save', async () => {
    routeParams = { id: 'checkout' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };
    saveMutate.mockImplementation((_request, options) => {
      options.onSuccess({ name: 'new-test' });
    });

    renderWithProviders(<TestEditorPage />);
    await userEvent.type(screen.getByLabelText('Name'), 'new-test');
    await userEvent.click(screen.getByRole('button', { name: 'Create test' }));

    expect(navigateMock).toHaveBeenCalledWith('/services/checkout');
  });
});
