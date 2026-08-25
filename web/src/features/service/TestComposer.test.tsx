import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Tests } from '../../api/workspace';
import type { CatalogOperation, RecommendationDto, TestEdit } from '../../api/tests';
import { TestComposer } from './TestComposer';

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

beforeEach(() => {
  saveMutate.mockClear();
  previewMutate.mockClear();
  recommendationResult = { data: undefined, isError: false };
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

function aRecommendation(overrides: Partial<RecommendationDto> = {}): RecommendationDto {
  return {
    type: 'STRESS',
    model: 'OPEN',
    shapeKind: 'PROGRESSIVE_RAMP',
    purpose: 'Traffic heavier than normal, ramped up in view.',
    headline: 'Increase traffic across 3 stages from 40 → 120 requests/sec over 15m',
    startLevel: 40,
    durationMinutes: 15,
    explicitStages: [
      { level: 40, durationSeconds: 300 },
      { level: 80, durationSeconds: 300 },
      { level: 120, durationSeconds: 300 },
    ],
    productionInformed: true,
    safetyCeilingApplied: false,
    sourceDescription: 'Derived from observed production traffic',
    derivation: 'Your observed peak × 1.5 = 120, rounded.',
    availableShapeKinds: ['PROGRESSIVE_RAMP', 'STEADY'],
    ...overrides,
  };
}

describe('the inline test composer', () => {
  it('creating: requires a name before it will submit', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };
    const onClose = vi.fn();

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={onClose} />);

    // The name starts auto-suggested from the default evaluation — clear it back to empty to
    // exercise the "a test needs a name" guarantee itself, independent of that suggestion.
    await userEvent.clear(screen.getByLabelText('Name'));
    await userEvent.click(screen.getByRole('button', { name: 'Create test' }));

    expect(screen.getByText('A test needs a name.')).toBeInTheDocument();
    expect(saveMutate).not.toHaveBeenCalled();
  });

  it('suggests a name from the evaluation only while the field is empty', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={() => {}} />);

    // Default type is Average load — suggested immediately since the name starts empty.
    expect(screen.getByLabelText('Name')).toHaveValue('average-load-check');

    await userEvent.click(screen.getByText('Stress'));
    expect(screen.getByLabelText('Name')).toHaveValue('stress-check');
  });

  it('opens on the evaluation it was asked for, suggestion and all', () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(
      <TestComposer serviceId="checkout" mode="create" initialType="STRESS" onClose={() => {}} />,
    );

    // Not the AVERAGE_LOAD default this composer opens on when nobody asked for anything.
    expect(screen.getByRole('radio', { name: /Stress/ })).toBeChecked();
    // The seed flows through the existing suggestion effect rather than around it.
    expect(screen.getByLabelText('Name')).toHaveValue('stress-check');
  });

  it('never overwrites a name the user has typed, even after changing the evaluation', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={() => {}} />);

    const name = screen.getByLabelText('Name');
    await userEvent.clear(name);
    await userEvent.type(name, 'my-own-name');

    await userEvent.click(screen.getByText('Stress'));

    expect(screen.getByLabelText('Name')).toHaveValue('my-own-name');
  });

  it('does not suggest a name in edit mode', () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = {
      data: {
        name: 'average-load',
        description: '',
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

    renderWithProviders(
      <TestComposer serviceId="checkout" mode="edit" editingName="average-load" onClose={() => {}} />,
    );

    expect(screen.getByLabelText('Name')).toHaveValue('average-load');
  });

  it('editing: prefills from the raw editable values, not the display strings', () => {
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
        shapeKind: 'STEADY',
        explicitStages: [],
      },
      isError: false,
    };

    renderWithProviders(
      <TestComposer serviceId="checkout" mode="edit" editingName="average-load" onClose={() => {}} />,
    );

    expect(screen.getByDisplayValue('20')).toBeInTheDocument();
    expect(screen.getByDisplayValue('A steady load.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save test' })).toBeInTheDocument();
  });

  it('editing: reopens a ramping test with its stages intact, not silently flattened', () => {
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
        shapeKind: 'PROGRESSIVE_RAMP',
        explicitStages: [
          { level: 60, durationSeconds: 120 },
          { level: 120, durationSeconds: 120 },
          { level: 180, durationSeconds: 120 },
          { level: 240, durationSeconds: 120 },
          { level: 300, durationSeconds: 120 },
        ],
      },
      isError: false,
    };

    renderWithProviders(
      <TestComposer serviceId="checkout" mode="edit" editingName="breakpoint-check" onClose={() => {}} />,
    );

    // The staged fields are visible and carry the saved test's own numbers — not the toggle's
    // defaults — so saving without touching anything keeps the ramp exactly as it was.
    expect(screen.getByText('Total duration')).toBeInTheDocument();
    expect(screen.getByDisplayValue('300')).toBeInTheDocument();
    expect(screen.getByDisplayValue('5')).toBeInTheDocument();
    // The saved test's stages came in as `explicitStages`, carried through untouched — the
    // "split evenly" caption would misdescribe a ramp that isn't guaranteed to be evenly spaced,
    // so it stays hidden until the user actually edits Target/Stages/Duration (see the next test).
    expect(screen.queryByText(/Split evenly across/)).not.toBeInTheDocument();
    // "Rate" (the steady-state field) is hidden while ramping — the domain never reads it in that
    // mode, and a live-looking field that does nothing is exactly what caused a misconfigured
    // breakpoint test.
    expect(screen.queryByLabelText('Rate')).not.toBeInTheDocument();
  });

  it('editing: hand-editing Stages after reopening a ramp reverts to the equal-spacing preview', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = {
      data: {
        name: 'breakpoint-check',
        description: '',
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
        shapeKind: 'PROGRESSIVE_RAMP',
        explicitStages: [
          { level: 60, durationSeconds: 120 },
          { level: 120, durationSeconds: 120 },
          { level: 180, durationSeconds: 120 },
          { level: 240, durationSeconds: 120 },
          { level: 300, durationSeconds: 120 },
        ],
      },
      isError: false,
    };

    renderWithProviders(
      <TestComposer serviceId="checkout" mode="edit" editingName="breakpoint-check" onClose={() => {}} />,
    );

    await userEvent.clear(screen.getByLabelText('Stages'));
    await userEvent.type(screen.getByLabelText('Stages'), '4');

    expect(screen.getByText('Split evenly across 4 stages — 2.5 min each')).toBeInTheDocument();
  });

  it('offers to import an API description when there are no operations to build a test from', () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: [], isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={() => {}} />);

    expect(screen.getByText('No operations yet.')).toBeInTheDocument();
  });

  it('hides the shape selector until Customize workload is opened, for an intent with one relevant shape', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={() => {}} />);

    // Average load (the default Intent) only recommends Steady — no shape selector, no ramp
    // controls, until the user explicitly asks for more.
    expect(screen.getByText('Duration')).toBeInTheDocument();
    expect(screen.queryByText('Progressive ramp')).not.toBeInTheDocument();
    expect(screen.queryByText(/Split evenly across/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByText('Customize workload'));
    await userEvent.click(screen.getByText('Progressive ramp'));

    // Default form values: durationMinutes 10, stages 4 — 2.5 min per stage.
    expect(screen.getByText('Total duration')).toBeInTheDocument();
    expect(screen.getByText('Split evenly across 4 stages — 2.5 min each')).toBeInTheDocument();
  });

  it('offers "Use recommended" and applies the domain\'s own numbers into the form', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };
    recommendationResult = { data: aRecommendation(), isError: false };

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={() => {}} />);
    // Load starts collapsed in create mode (see the Intent-vs-Load `defaultExpanded` split) — its
    // content is still in the DOM (findable by text) but excluded from the accessibility tree
    // (Mantine's Collapse marks a collapsed body `aria-hidden`) until it's opened, same as a real
    // browser would only let a sighted user interact with it once visible.
    await userEvent.click(screen.getByText('Load'));

    expect(screen.getByText(/Recommended for/)).toBeInTheDocument();
    expect(
      screen.getByText('Increase traffic across 3 stages from 40 → 120 requests/sec over 15m'),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByText('Use recommended'));

    // The recommendation's own peak (last stage) and stage count land in the form — not a
    // client-invented default.
    expect(screen.getByDisplayValue('120')).toBeInTheDocument();
    expect(screen.getByDisplayValue('3')).toBeInTheDocument();
    expect(screen.getByDisplayValue('15')).toBeInTheDocument();
  });

  it('shows the spike parameter editor, not generic ramp controls, once Spike is selected', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };
    recommendationResult = {
      data: aRecommendation({
        type: 'SPIKE',
        shapeKind: 'SPIKE',
        availableShapeKinds: ['SPIKE'],
      }),
      isError: false,
    };

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={() => {}} />);
    await userEvent.click(screen.getByText('Load'));
    await userEvent.click(screen.getByText('Customize workload'));
    await userEvent.click(screen.getByText('Spike'));

    expect(screen.getByText('Baseline')).toBeInTheDocument();
    expect(screen.getByText('Peak')).toBeInTheDocument();
    expect(screen.queryByText('Stages')).not.toBeInTheDocument();
    expect(screen.queryByText('Target')).not.toBeInTheDocument();
  });

  it('switches from the weight grid to a single-operation choice under concurrency', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={() => {}} />);

    expect(screen.getByText(/Weighted by how much each operation gets/)).toBeInTheDocument();

    await userEvent.click(screen.getByText('Concurrency'));

    expect(screen.getByText('Operation these users call')).toBeInTheDocument();
    expect(screen.queryByText(/Weighted by how much each operation gets/)).not.toBeInTheDocument();
  });

  it('closes without saving when Cancel is clicked', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };
    const onClose = vi.fn();

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={onClose} />);

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalled();
    expect(saveMutate).not.toHaveBeenCalled();
  });

  it('closes after a successful save, rather than navigating away', async () => {
    testsResult = { data: aTestsResult(), isError: false };
    catalogResult = { data: CATALOG, isError: false };
    editResult = { data: undefined, isError: false };
    const onClose = vi.fn();
    saveMutate.mockImplementation((_request, options) => {
      options.onSuccess({ name: 'new-test' });
    });

    renderWithProviders(<TestComposer serviceId="checkout" mode="create" onClose={onClose} />);

    await userEvent.type(screen.getByLabelText('Name'), 'new-test');
    await userEvent.click(screen.getByRole('button', { name: 'Create test' }));

    expect(saveMutate).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});
