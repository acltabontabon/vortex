import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/renderWithProviders';
import { RUNNING_COMMENTARY_LINES } from '../lib/runningCommentary';
import { LiveExecutionPanel } from './LiveExecutionPanel';

/**
 * The one live-execution surface shared by the service row's inline view and the standalone run
 * page — this file is about the panel's own rendering and cancel-confirmation contract, not either
 * call site's data-fetching, which has its own tests.
 */

interface CapturedConfirm {
  title: string;
  labels: { confirm: string; cancel: string };
  onConfirm: () => void;
}

let captured: CapturedConfirm | null = null;

vi.mock('@mantine/modals', () => ({
  modals: {
    openConfirmModal: (opts: CapturedConfirm) => {
      captured = opts;
    },
  },
}));

let reducedMotion = false;

vi.mock('motion/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('motion/react')>();
  return { ...actual, useReducedMotion: () => reducedMotion };
});

function baseProps(overrides: Partial<Parameters<typeof LiveExecutionPanel>[0]> = {}) {
  return {
    density: 'compact' as const,
    stage: 'Holding 100 requests/sec',
    elapsed: '00:42',
    percent: 40,
    targetRate: '100 req/s',
    currentRate: '99.8 req/s',
    p95: '49 ms',
    errorRate: '0.00%',
    onConfirmCancel: vi.fn(),
    cancelPending: false,
    ...overrides,
  };
}

describe('the live execution panel', () => {
  beforeEach(() => {
    captured = null;
    reducedMotion = false;
  });

  it('shows the stage, live badge, elapsed clock and every present telemetry field', () => {
    renderWithProviders(<LiveExecutionPanel {...baseProps()} />);

    expect(screen.getByText('Holding 100 requests/sec')).toBeInTheDocument();
    expect(screen.getByText('LIVE')).toBeInTheDocument();
    expect(screen.getByText('00:42')).toBeInTheDocument();
    expect(screen.getByText('100 req/s')).toBeInTheDocument();
    expect(screen.getByText('99.8 req/s')).toBeInTheDocument();
    expect(screen.getByText('49 ms')).toBeInTheDocument();
    expect(screen.getByText('0.00%')).toBeInTheDocument();
  });

  it('omits a telemetry slot entirely when its value is absent, rather than showing a placeholder', () => {
    renderWithProviders(<LiveExecutionPanel {...baseProps({ p95: null, errorRate: null })} />);

    expect(screen.queryByText('p95')).not.toBeInTheDocument();
    expect(screen.queryByText('Errors')).not.toBeInTheDocument();
    expect(screen.getByText('Target')).toBeInTheDocument();
  });

  it('marks its density for the two call sites that share this one surface', () => {
    const { container: compact } = renderWithProviders(<LiveExecutionPanel {...baseProps()} />);
    expect(compact.querySelector('[data-density="compact"]')).toBeInTheDocument();

    const { container: full } = renderWithProviders(
      <LiveExecutionPanel {...baseProps({ density: 'full' })} />,
    );
    expect(full.querySelector('[data-density="full"]')).toBeInTheDocument();
  });

  it('shows a commentary line drawn from the curated pool', () => {
    renderWithProviders(<LiveExecutionPanel {...baseProps()} />);

    const line = screen.getByText(/^↳ /).textContent?.replace(/^↳ /, '');
    expect(RUNNING_COMMENTARY_LINES).toContain(line);
  });

  it('asks for confirmation before cancelling, and does not cancel until confirmed', async () => {
    const onConfirmCancel = vi.fn();
    renderWithProviders(<LiveExecutionPanel {...baseProps({ onConfirmCancel })} />);

    await userEvent.click(screen.getByRole('button', { name: 'Cancel run' }));

    expect(onConfirmCancel).not.toHaveBeenCalled();
    expect(captured?.title).toBe('Cancel this run?');
    expect(captured?.labels).toEqual({ confirm: 'Cancel run', cancel: 'Keep running' });

    captured?.onConfirm();

    expect(onConfirmCancel).toHaveBeenCalled();
  });

  it('shows the cancel button as loading, and disabled, while cancellation is pending', () => {
    renderWithProviders(<LiveExecutionPanel {...baseProps({ cancelPending: true })} />);

    expect(screen.getByRole('button', { name: 'Cancel run' })).toBeDisabled();
  });

  it('marks itself for reduced motion, and still shows a commentary line without animating it', () => {
    reducedMotion = true;
    renderWithProviders(<LiveExecutionPanel {...baseProps()} />);

    expect(
      screen.getByText('Holding 100 requests/sec').closest('[data-reduced-motion]'),
    ).toHaveAttribute('data-reduced-motion', 'true');
    expect(screen.getByText(/^↳ /)).toBeInTheDocument();
  });
});
