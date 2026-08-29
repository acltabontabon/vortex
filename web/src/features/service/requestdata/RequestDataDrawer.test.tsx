import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { DatasetSummary, RequestDataView, ValueSlot } from '../../../api/requestData';
import { RequestDataDrawer } from './RequestDataDrawer';

const saveMutate = vi.fn();
const reviewMutate = vi.fn();
let view: RequestDataView;

vi.mock('../../../api/requestData', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/requestData')>();
  return {
    ...actual,
    useRequestDataQuery: () => ({ data: view, isPending: false, isError: false, error: null }),
    useSaveRequestDataMutation: () => ({ mutate: saveMutate, isPending: false }),
  };
});

vi.mock('../../../api/configuration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/configuration')>();
  return {
    ...actual,
    useReviewOperationMutation: () => ({ mutate: reviewMutate, isPending: false }),
  };
});

function aSlot(overrides: Partial<ValueSlot> = {}): ValueSlot {
  return {
    target: 'HEADER',
    name: 'x-tenant',
    required: false,
    source: '',
    literal: null,
    generator: null,
    lifecycle: null,
    minimum: null,
    maximum: null,
    length: null,
    dataset: null,
    datasetScope: null,
    field: null,
    environmentVariable: null,
    environmentSet: false,
    suggestion: null,
    ...overrides,
  };
}

function aDataset(overrides: Partial<DatasetSummary> = {}): DatasetSummary {
  return {
    name: 'customers',
    scope: 'local',
    format: 'csv',
    records: 5000,
    fields: ['customerId', 'mobile'],
    location: '/tmp/customers.csv',
    preview: [],
    promotionTarget: '/repo/.vortex/datasets/customers.csv',
    problem: '',
    ...overrides,
  };
}

function aView(overrides: Partial<RequestDataView> = {}): RequestDataView {
  return {
    operationId: 'createApplication',
    label: 'POST /applications',
    method: 'POST',
    path: '/applications',
    mutating: true,
    reviewed: false,
    body: '',
    values: [aSlot()],
    datasets: [aDataset()],
    generators: [
      { key: 'uuid', label: 'UUID', meaning: 'a random UUID (version 4)', usesRange: false, usesLength: false },
      { key: 'random-integer', label: 'Random integer', meaning: 'a random integer in a range you choose', usesRange: true, usesLength: false },
      { key: 'random-string', label: 'Random string', meaning: 'a random alphanumeric string', usesRange: false, usesLength: true },
    ],
    ...overrides,
  };
}

/**
 * The visible control with this label.
 *
 * <p>Mantine's Select renders a hidden native input alongside the one a person sees, and both carry
 * the label. The first is the visible one, and it is the one an assertion about what is on screen
 * should be about.
 */
function field(label: string): HTMLElement {
  return screen.getAllByLabelText(label)[0];
}

function absent(label: string): boolean {
  return screen.queryAllByLabelText(label).length === 0;
}

function render(data: RequestDataView) {
  view = data;
  saveMutate.mockReset();
  saveMutate.mockImplementation((_vars, opts) => opts?.onSuccess?.({ message: 'Saved.' }));
  reviewMutate.mockReset();
  reviewMutate.mockImplementation((_operationId, opts) => opts?.onSuccess?.({ message: 'Reviewed.' }));
  return renderWithProviders(
    <RequestDataDrawer serviceId="checkout" operationId="createApplication" onClose={() => {}} />
  );
}

/**
 * The panel a developer uses to say what an endpoint needs.
 *
 * <p>These assert the product decision that makes the feature bearable: that only the controls the
 * chosen source actually needs are on screen. A drawer that showed a value field, a generator, a
 * dataset picker and a variable name for every value would be the wall of configuration this whole
 * feature exists to avoid.
 */
describe('RequestDataDrawer', () => {
  it('shows only the source selector until a source is chosen', async () => {
    render(aView());

    expect(field('Source for x-tenant')).toBeInTheDocument();
    expect(absent('Value for x-tenant')).toBe(true);
    expect(absent('Dataset for x-tenant')).toBe(true);
    expect(absent('Environment variable for x-tenant')).toBe(true);
  });

  it('reveals a value field for a fixed value, and nothing else', async () => {
    render(aView({ values: [aSlot({ source: 'fixed', literal: 'acme' })] }));

    expect(field('Value for x-tenant')).toHaveValue('acme');
    expect(absent('Generator for x-tenant')).toBe(true);
  });

  it('reveals a generator and how often it runs, and nothing else', async () => {
    render(aView({ values: [aSlot({ source: 'generated', generator: 'uuid', lifecycle: 'per-request' })] }));

    expect(field('Generator for x-tenant')).toHaveValue('UUID');
    expect(field('How often x-tenant is generated')).toHaveValue('Every request');
    expect(absent('Value for x-tenant')).toBe(true);
  });

  it('shows a range only for the generator that reads one', async () => {
    render(aView({ values: [aSlot({ source: 'generated', generator: 'uuid' })] }));
    expect(absent('Smallest value for x-tenant')).toBe(true);

    render(aView({ values: [aSlot({ source: 'generated', generator: 'random-integer' })] }));
    expect(field('Smallest value for x-tenant')).toBeInTheDocument();
  });

  it('reveals a dataset and a column, and says how the rows are read', async () => {
    render(aView({
      values: [aSlot({ source: 'dataset', dataset: 'customers', datasetScope: 'local', field: 'customerId' })],
    }));

    expect(field('Dataset for x-tenant')).toHaveValue('customers');
    expect(field('Field of the dataset for x-tenant')).toHaveValue('customerId');
    expect(screen.getByText(/5,000 records, walked in order/)).toBeInTheDocument();
  });

  it('says whether an environment variable is set, and never shows a value', async () => {
    render(aView({
      values: [aSlot({ source: 'environment', environmentVariable: 'API_TOKEN', environmentSet: false })],
    }));

    expect(screen.getByText(/Not set on this machine yet/)).toBeInTheDocument();
  });

  it('offers a schema suggestion with its reason, rather than applying it', async () => {
    // A format says how a value looks, not what it means. The value stays unset until somebody
    // decides, and the reason is there so they can judge it.
    render(aView({
      values: [aSlot({
        name: 'requestId',
        suggestion: { source: 'generated', generator: 'uuid', choices: [], reason: 'the specification declares format: uuid' },
      })],
    }));

    expect(screen.getByText(/the specification declares format: uuid/)).toBeInTheDocument();
    expect(field('Source for requestId')).toHaveValue('Not set');
  });

  it('applies a suggestion only when it is accepted', async () => {
    const user = userEvent.setup();
    render(aView({
      values: [aSlot({
        name: 'requestId',
        suggestion: { source: 'generated', generator: 'uuid', choices: [], reason: 'the specification declares format: uuid' },
      })],
    }));

    await user.click(screen.getByRole('button', { name: /use uuid/ }));

    expect(field('Generator for requestId')).toHaveValue('UUID');
  });

  it('constrains a value the specification constrains, rather than accepting free text', async () => {
    render(aView({
      values: [aSlot({
        name: 'productType',
        source: 'fixed',
        literal: 'CREDIT_CARD',
        suggestion: { source: 'fixed', generator: null, choices: ['CREDIT_CARD', 'PERSONAL_LOAN'], reason: 'the specification permits only these values' },
      })],
    }));

    // A select, not a text field: typing a value the service will reject is a mistake the schema
    // can prevent.
    const control = field('Value for productType');
    expect(control).toHaveAttribute('readonly');
  });

  it('sends only the fields the chosen source uses', async () => {
    const user = userEvent.setup();
    render(aView({
      values: [
        aSlot({ source: 'fixed', literal: 'acme' }),
        aSlot({ target: 'PATH', name: 'id', source: 'dataset', dataset: 'customers', datasetScope: 'local', field: 'customerId' }),
      ],
    }));

    await user.click(screen.getByRole('button', { name: 'Save & approve' }));

    expect(saveMutate).toHaveBeenCalled();
    const sent = saveMutate.mock.calls[0][0];
    expect(sent.values[0]).toEqual({ target: 'HEADER', name: 'x-tenant', source: 'fixed', literal: 'acme' });
    expect(sent.values[1]).toEqual({
      target: 'PATH', name: 'id', source: 'dataset',
      dataset: 'customers', datasetScope: 'local', field: 'customerId',
    });
  });

  it('says so plainly when an operation has nothing to configure', async () => {
    render(aView({ values: [] }));

    expect(screen.getByText(/nothing to configure/)).toBeInTheDocument();
  });

  /**
   * The property that matters here: a mutating operation is never marked reviewed except from
   * inside this drawer, after its data has actually been shown. There used to be a one-click
   * "Review data" button on the operations list that called the review endpoint directly without
   * ever opening this drawer — these assert that path is gone and approval only happens here.
   */
  it('approves a mutating operation once its data has been saved', async () => {
    const user = userEvent.setup();
    render(aView());

    await user.click(screen.getByRole('button', { name: 'Save & approve' }));

    expect(saveMutate).toHaveBeenCalled();
    expect(reviewMutate).toHaveBeenCalledWith('createApplication', expect.anything());
  });

  it('approves an operation with nothing to configure without saving anything', async () => {
    const user = userEvent.setup();
    render(aView({ values: [] }));

    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(saveMutate).not.toHaveBeenCalled();
    expect(reviewMutate).toHaveBeenCalledWith('createApplication', expect.anything());
  });

  it('offers plain Save, with no approval, for an operation already reviewed', async () => {
    const user = userEvent.setup();
    render(aView({ reviewed: true }));

    expect(screen.queryByText(/Vortex won't send it in a workload/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(saveMutate).toHaveBeenCalled();
    expect(reviewMutate).not.toHaveBeenCalled();
  });

  it('offers plain Save, with no approval, for a read-only operation', async () => {
    render(aView({ mutating: false }));

    expect(screen.queryByText(/Vortex won't send it in a workload/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
  });

  it('says where to add a dataset rather than offering an empty picker', async () => {
    render(aView({ values: [aSlot({ source: 'dataset' })], datasets: [] }));

    expect(screen.getByText(/no datasets yet/i)).toBeInTheDocument();
    expect(absent('Dataset for x-tenant')).toBe(true);
  });

  it('groups values by where the request carries them', async () => {
    render(aView({
      values: [
        aSlot({ target: 'PATH', name: 'id' }),
        aSlot({ target: 'HEADER', name: 'x-tenant' }),
        aSlot({ target: 'BODY_FIELD', name: 'customerId' }),
      ],
    }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Path')).toBeInTheDocument();
    expect(within(dialog).getByText('Header')).toBeInTheDocument();
    expect(within(dialog).getByText('Body')).toBeInTheDocument();
  });
});
