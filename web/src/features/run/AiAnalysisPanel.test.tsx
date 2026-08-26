import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Analysis } from '../../api/run';
import { AiAnalysisPanel } from './AiAnalysisPanel';

function anAnalysis(overrides: Partial<Analysis> = {}): Analysis {
  return {
    state: 'COMPLETED',
    conclusion: 'The service met its objectives throughout this run.',
    findings: [],
    recommendations: [],
    missingTelemetry: [],
    nextTest: null,
    provenanceDescribe: 'Generated locally using ollama · model qwen3:8b · prompt v6',
    failureMessage: null,
    ...overrides,
  };
}

const availableStatus = { available: true, problem: '', remedy: '' };

function panel({
  onStart = vi.fn(),
  starting = false,
  ...props
}: Partial<Parameters<typeof AiAnalysisPanel>[0]> = {}) {
  return renderWithProviders(
    <AiAnalysisPanel
      title="Interpretation"
      disclaimer="Adds an AI reading of the evidence above."
      runningLabel="Analysing"
      runningMessage="Analysing. The measurements above are already final."
      triggerLabel="Analyse evidence with AI"
      status={{ analysing: false, latest: null, availability: availableStatus }}
      onStart={onStart}
      starting={starting}
      {...props}
    />,
  );
}

describe('AiAnalysisPanel', () => {
  it('offers to start an analysis when none has been requested yet', () => {
    panel();

    expect(screen.getByRole('button', { name: 'Analyse evidence with AI' })).toBeInTheDocument();
  });

  it('shows a failed attempt distinctly from "not requested", with its message and a retry action', () => {
    const onStart = vi.fn();
    panel({
      status: {
        analysing: false,
        latest: anAnalysis({ state: 'FAILED', failureMessage: 'The model did not respond in time.' }),
        availability: availableStatus,
      },
      onStart,
    });

    expect(screen.getByText('Interpretation did not complete')).toBeInTheDocument();
    expect(screen.getByText('The model did not respond in time.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Analyse evidence with AI' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onStart).toHaveBeenCalledOnce();
  });

  it('shows an elapsed time while running', async () => {
    vi.useFakeTimers();
    try {
      panel({ status: { analysing: true, latest: null, availability: availableStatus } });

      await vi.advanceTimersByTimeAsync(3000);

      expect(screen.getByText('3s')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('renders each finding as its own disclosure, collapsed to its statement', () => {
    panel({
      status: {
        analysing: false,
        latest: anAnalysis({
          findings: [
            {
              statement: 'Latency correlated with pool utilisation.',
              typeKind: 'CORRELATION',
              typeLabel: 'Correlation',
              confidenceKind: 'MEDIUM',
              confidenceLabel: 'Medium',
              evidenceIds: ['metric:checkout.pool.utilization'],
            },
          ],
        }),
        availability: availableStatus,
      },
    });

    const statement = screen.getByText('Latency correlated with pool utilisation.');
    expect(statement).toBeInTheDocument();
    const row = statement.closest('details');
    expect(row).not.toBeNull();
    expect(row).not.toHaveAttribute('open');

    fireEvent.click(statement);

    expect(row).toHaveAttribute('open');
    expect(screen.getByText('Correlation · Medium confidence')).toBeInTheDocument();
    expect(screen.getByText('metric:checkout.pool.utilization')).toBeInTheDocument();
  });

  it('shows the local-AI-unavailable alert when no result exists and the provider is unreachable', () => {
    panel({
      status: {
        analysing: false,
        latest: null,
        availability: { available: false, problem: 'Ollama was not detected.', remedy: 'Install Ollama.' },
      },
    });

    expect(screen.getByText('Local AI is not available')).toBeInTheDocument();
    expect(screen.getByText('Ollama was not detected.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Analyse evidence with AI' })).not.toBeInTheDocument();
  });

  it('lists earlier analyses collapsed, one disclosure per analysis', () => {
    panel({
      status: {
        analysing: false,
        latest: anAnalysis({ conclusion: 'Latest conclusion.' }),
        availability: availableStatus,
      },
      earlierCount: 1,
      earlier: [anAnalysis({ conclusion: 'An earlier conclusion.', provenanceDescribe: 'prompt v5' })],
    });

    expect(screen.getByText('Earlier analyses (1)')).toBeInTheDocument();
    // The conclusion is the disclosure's own collapsed header text.
    expect(screen.getAllByText('An earlier conclusion.').length).toBeGreaterThan(0);
    expect(screen.getByText('prompt v5')).toBeInTheDocument();
  });
});
