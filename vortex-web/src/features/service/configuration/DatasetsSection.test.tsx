import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { DatasetSummary } from '../../../api/requestData';
import { DatasetsSection } from './DatasetsSection';

const uploadMutate = vi.fn();
const promoteMutate = vi.fn();
const deleteMutate = vi.fn();
let datasets: DatasetSummary[];

vi.mock('../../../api/requestData', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/requestData')>();
  return {
    ...actual,
    useDatasetsQuery: () => ({ data: datasets, isPending: false }),
    useUploadDatasetMutation: () => ({ mutate: uploadMutate, isPending: false }),
    usePromoteDatasetMutation: () => ({ mutate: promoteMutate, isPending: false }),
    useDeleteDatasetMutation: () => ({ mutate: deleteMutate, isPending: false }),
  };
});

// The confirm dialog needs a provider this test does not wrap with. What matters here is that
// committing a file into somebody's repository asks first, and names the file it will write.
const confirmChildren = vi.fn();
vi.mock('@mantine/modals', () => ({
  modals: {
    openConfirmModal: (options: { children: unknown; onConfirm: () => void }) => {
      confirmChildren(options.children);
      options.onConfirm();
    },
  },
}));

function aDataset(overrides: Partial<DatasetSummary> = {}): DatasetSummary {
  return {
    name: 'customers',
    scope: 'local',
    format: 'csv',
    records: 5000,
    fields: ['customerId', 'mobile'],
    location: '/home/me/.vortex/datasets/checkout/customers.csv',
    preview: [{ customerId: 'C001', mobile: '09171234567' }],
    promotionTarget: '/repo/.vortex/datasets/customers.csv',
    problem: '',
    ...overrides,
  };
}

function render(rows: DatasetSummary[]) {
  datasets = rows;
  uploadMutate.mockReset();
  promoteMutate.mockReset();
  confirmChildren.mockReset();
  return renderWithProviders(<DatasetsSection serviceId="checkout" />);
}

/**
 * Datasets have to read as a product feature rather than a filesystem, and the one genuinely risky
 * action here — writing a file into somebody's repository — has to be impossible to do by accident.
 */
describe('DatasetsSection', () => {
  it('says what a dataset is without rendering it', async () => {
    render([aDataset()]);

    expect(screen.getByText(/5,000 records/)).toBeInTheDocument();
    expect(screen.getByText(/customerId, mobile/)).toBeInTheDocument();
  });

  it('shows an uploaded dataset as held on this machine, not committed', async () => {
    render([aDataset()]);

    expect(screen.getByText('this machine')).toBeInTheDocument();
  });

  it('names the exact file before writing anything into a repository', async () => {
    const user = userEvent.setup();
    render([aDataset()]);

    await user.click(screen.getByRole('button', { name: 'Commit with service' }));

    expect(confirmChildren).toHaveBeenCalled();
    expect(promoteMutate).toHaveBeenCalledWith('customers', expect.anything());
  });

  it('offers no commit action for a dataset that is already committed', async () => {
    render([aDataset({ scope: 'portable', promotionTarget: '' })]);

    expect(screen.getByText('committed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Commit with service' })).not.toBeInTheDocument();
  });

  it('lists a dataset that no longer parses, with the reason', async () => {
    // Omitting it would leave a request value pointing at something invisible.
    render([aDataset({ records: 0, fields: [], preview: [], problem: "column 'id' appears more than once." })]);

    expect(screen.getByText(/appears more than once/)).toBeInTheDocument();
  });

  it('says what a dataset is for when there are none, rather than showing an empty list', async () => {
    render([]);

    expect(screen.getByText(/No datasets yet/)).toBeInTheDocument();
  });

  it('refuses a file it cannot read, before sending anything', async () => {
    // The input's accept attribute already filters the file dialog, so userEvent.upload declines
    // this file the way a browser would. The change event is dispatched directly to exercise the
    // guard behind it — a person can override the dialog's filter, and the answer then has to be a
    // sentence rather than a parse error.
    const { container } = render([]);
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['nope'], 'notes.txt', { type: 'text/plain' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });

    fireEvent.change(input);

    expect(await screen.findByText(/Vortex reads CSV and JSON/)).toBeInTheDocument();
    expect(uploadMutate).not.toHaveBeenCalled();
  });

  it('uploads a chosen file as local, with its contents', async () => {
    const user = userEvent.setup();
    const { container } = render([]);

    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(input, new File(['customerId\nC001\n'], 'Customers.csv', { type: 'text/csv' }));

    expect(uploadMutate).toHaveBeenCalled();
    const sent = uploadMutate.mock.calls[0][0];
    expect(sent).toMatchObject({ name: 'customers', format: 'csv', scope: 'local' });
    expect(sent.content).toContain('C001');
  });
});
