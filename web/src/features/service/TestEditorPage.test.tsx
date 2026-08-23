import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Tests } from '../../api/workspace';
import type { CatalogOperation, TestEdit } from '../../api/tests';
import { TestEditorPage } from './TestEditorPage';

let testsResult: { data: Tests | undefined; isError: boolean } = { data: undefined, isError: false };
let catalogResult: { data: CatalogOperation[] | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};
let editResult: { data: TestEdit | undefined; isError: boolean } = { data: undefined, isError: false };
const saveMutate = vi.fn();
const previewMutate = vi.fn();
let routeParams: { id: string; name?: string } = { id: 'checkout' };

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useParams: () => routeParams, useNavigate: () => vi.fn() };
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
  };
});

const CATALOG: CatalogOperation[] = [
  { id: 'getAccount', label: 'GET /accounts/{id}', method: 'GET', path: '/accounts/{id}', mutating: false },
  { id: 'getOrder', label: 'GET /orders/{id}', method: 'GET', path: '/orders/{id}', mutating: false },
];

const TEST_TYPES = [
  { name: 'AVERAGE_LOAD', label: 'Average load', question: 'Does it meet objectives normally?', guidance: '', saturating: false, configuredTestCount: 1 },
  { name: 'STRESS', label: 'Stress', question: 'How does it behave at peak?', guidance: '', saturating: true, configuredTestCount: 0 },
];

function aTestsResult(): Tests {
  return {
    header: {
      id: 'checkout', name: 'checkout-service', description: null, target: null,
      environmentCount: 1, release: null,
      readiness: { canRun: true, satisfiedCount: 1, totalCount: 1, blockerCount: 0, items: [], nextStepText: null },
      operationCount: 2, testCount: 0, runCount: 0, running: null,
    },
    tests: [],
    testTypes: TEST_TYPES,
    environmentNames: ['local'],
  };
}

describe('the test editor page', () => {
  it('creating: requires a name before it will submit', async () => {
    routeParams = { id: 'checkout' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestEditorPage />);

    await userEvent.click(screen.getByRole('button', { name: 'Create test' }));

    expect(screen.getByText('A test needs a name.')).toBeInTheDocument();
    expect(saveMutate).not.toHaveBeenCalled();
  });

  it('editing: prefills from the raw editable values, not the display strings', () => {
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
        weights: { getAccount: 70, getOrder: 30 },
      },
      isError: false,
    };

    renderWithProviders(<TestEditorPage />);

    expect(screen.getByDisplayValue('20')).toBeInTheDocument();
    expect(screen.getByDisplayValue('A steady load.')).toBeInTheDocument();
  });

  it('editing: reopens a ramping test with its stages intact, not silently flattened', () => {
    routeParams = { id: 'checkout', name: 'breakpoint-check' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = {
      data: {
        name: 'breakpoint-check',
        description: 'Finds the ceiling.',
        objective: '',
        type: 'STRESS',
        model: 'OPEN',
        rate: 50,
        vus: null,
        durationMinutes: 10,
        ramping: true,
        peakRate: 300,
        stages: 5,
        singleOperation: null,
        weights: { getAccount: 70, getOrder: 30 },
      },
      isError: false,
    };

    renderWithProviders(<TestEditorPage />);

    // The staged fields are visible and carry the saved test's own numbers — not the toggle's
    // defaults — so saving without touching anything keeps the ramp exactly as it was.
    expect(screen.getByText('Total duration (minutes)')).toBeInTheDocument();
    expect(screen.getByDisplayValue('300')).toBeInTheDocument();
    expect(screen.getByDisplayValue('5')).toBeInTheDocument();
    expect(screen.getByText('Split evenly across 5 stages — 2 min each')).toBeInTheDocument();
    // "Requests per second" is hidden while ramping — the domain never reads it in that mode, and a
    // live-looking field that does nothing is exactly what caused a misconfigured breakpoint test.
    expect(screen.queryByLabelText('Requests per second')).not.toBeInTheDocument();
    expect(
      screen.getByText('Stages are evenly spaced from 60 to 300 requests/sec — there is no separate starting rate to set.'),
    ).toBeInTheDocument();
  });

  it('offers to import an API description when there are no operations to build a test from', () => {
    routeParams = { id: 'checkout' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: [], isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestEditorPage />);

    expect(screen.getByText('No operations yet.')).toBeInTheDocument();
  });

  it('relabels Duration as total and shows the per-stage split once staging is on', async () => {
    routeParams = { id: 'checkout' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestEditorPage />);

    expect(screen.getByText('Duration (minutes)')).toBeInTheDocument();
    expect(screen.queryByText(/Split evenly across/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByText('Increase the load in stages instead'));

    // Default form values: durationMinutes 10, stages 4 — 2.5 min per stage.
    expect(screen.getByText('Total duration (minutes)')).toBeInTheDocument();
    expect(screen.getByText('Split evenly across 4 stages — 2.5 min each')).toBeInTheDocument();
  });

  it('switches from the weight grid to a single-operation choice under concurrency', async () => {
    routeParams = { id: 'checkout' };
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestEditorPage />);

    expect(screen.getByText(/divided across these operations/)).toBeInTheDocument();

    await userEvent.click(screen.getByText('Concurrency'));

    expect(screen.getByText('Operation these users call')).toBeInTheDocument();
    expect(screen.queryByText(/divided across these operations/)).not.toBeInTheDocument();
  });
});
