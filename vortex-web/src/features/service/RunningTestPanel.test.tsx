import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { RunRef } from '../../api/workspace';
import { invalidateService } from '../../api/workspace';
import { useCancelRunMutation } from '../../api/run';
import { useRunProgress } from '../../api/runs';
import { RunningTestPanel } from './RunningTestPanel';

/**
 * The row's own live-progress view — the same vocabulary `RunPage` has always shown, reached
 * without navigating away from the service workspace to watch it. Rendering itself — the telemetry
 * layout, the commentary line, the cancel-confirmation contract — belongs to
 * `LiveExecutionPanel.test.tsx`; this file is only about wiring: what this component feeds that
 * shared surface, and what it does once a run finishes.
 */

vi.mock('../../api/run', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/run')>();
  return { ...actual, useCancelRunMutation: vi.fn() };
});

vi.mock('../../api/runs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/runs')>();
  return { ...actual, useRunProgress: vi.fn() };
});

vi.mock('../../api/workspace', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/workspace')>();
  return { ...actual, invalidateService: vi.fn() };
});

// The confirm dialog itself is LiveExecutionPanel's own concern (and its own test file); here it
// only needs to resolve immediately so the click-through-to-cancel wiring can be observed.
vi.mock('@mantine/modals', () => ({
  modals: { openConfirmModal: (opts: { onConfirm: () => void }) => opts.onConfirm() },
}));

function aRunRef(overrides: Partial<RunRef> = {}): RunRef {
  return {
    id: 'exec-1',
    testName: 'capacity-check',
    testTypeLabel: 'Average load',
    stateLabel: 'Running',
    ...overrides,
  };
}

describe('the running test panel', () => {
  it("falls back to the run ref's own state label and a zeroed clock before the first bucket arrives", () => {
    vi.mocked(useCancelRunMutation).mockReturnValue({ mutate: vi.fn(), isPending: false } as never);
    vi.mocked(useRunProgress).mockReturnValue(null);

    renderWithProviders(<RunningTestPanel serviceId="checkout" running={aRunRef()} />);

    expect(screen.getByText('Running')).toBeInTheDocument();
    expect(screen.getByText('00:00')).toBeInTheDocument();
  });

  it('shows the live stage, elapsed time and only the facts the bucket actually carries', () => {
    vi.mocked(useCancelRunMutation).mockReturnValue({ mutate: vi.fn(), isPending: false } as never);
    vi.mocked(useRunProgress).mockReturnValue({
      state: 'RUNNING',
      elapsed: '00:05',
      stage: 'Holding 100 requests/sec',
      percent: 40,
      targetRate: '100 requests/sec',
      currentRate: '100.2 requests/sec',
      p95: '',
      errorRate: '',
    });

    renderWithProviders(<RunningTestPanel serviceId="checkout" running={aRunRef()} />);

    expect(screen.getByText('Holding 100 requests/sec')).toBeInTheDocument();
    expect(screen.getByText('00:05')).toBeInTheDocument();
    expect(screen.getByText('100 requests/sec')).toBeInTheDocument();
    expect(screen.getByText('100.2 requests/sec')).toBeInTheDocument();
    // Empty strings from the bucket produce no telemetry slot at all.
    expect(screen.queryByText('p95')).not.toBeInTheDocument();
    expect(screen.queryByText('Errors')).not.toBeInTheDocument();
  });

  it('cancels the run once the confirmation is accepted', async () => {
    const cancelMutate = vi.fn();
    vi.mocked(useCancelRunMutation).mockReturnValue({
      mutate: cancelMutate,
      isPending: false,
    } as never);
    vi.mocked(useRunProgress).mockReturnValue(null);

    renderWithProviders(<RunningTestPanel serviceId="checkout" running={aRunRef()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Cancel run' }));

    expect(cancelMutate).toHaveBeenCalled();
  });

  it("invalidates the service's cached data once the run finishes", () => {
    vi.mocked(useCancelRunMutation).mockReturnValue({ mutate: vi.fn(), isPending: false } as never);
    vi.mocked(invalidateService).mockClear();
    let capturedOnFinished: (() => void) | undefined;
    vi.mocked(useRunProgress).mockImplementation((_id, options) => {
      capturedOnFinished = options.onFinished;
      return null;
    });

    renderWithProviders(<RunningTestPanel serviceId="checkout" running={aRunRef()} />);
    capturedOnFinished?.();

    expect(invalidateService).toHaveBeenCalledWith(expect.anything(), 'checkout');
  });
});
