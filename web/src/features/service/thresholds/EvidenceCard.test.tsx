import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ThresholdProvenanceDto } from '../../../api/thresholds';
import { EvidenceCard } from './EvidenceCard';

describe('EvidenceCard', () => {
  it('a manual objective is labelled honestly, with no collapsible', () => {
    renderWithProviders(<EvidenceCard value="550 ms" provenance={null} />);

    expect(screen.getByText('Manual objective — no supporting evidence recorded.')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('an explicit MANUAL_OBJECTIVE provenance reads the same as no provenance at all', () => {
    const provenance: ThresholdProvenanceDto = { source: 'MANUAL_OBJECTIVE', sourceLabel: 'Manual objective' };

    renderWithProviders(<EvidenceCard value="550 ms" provenance={provenance} />);

    expect(screen.getByText('Manual objective — no supporting evidence recorded.')).toBeInTheDocument();
  });

  it('a derived value expands to show its derivation and quality', async () => {
    const provenance: ThresholdProvenanceDto = {
      source: 'PRODUCTION_BASELINE',
      sourceLabel: 'Production baseline',
      detail: 'Prometheus (checkout-service)',
      derivation: 'Your observed production 620 ms x 1.10 = 682 ms, rounded to 700 ms.',
      evidenceQuality: 'STRONG',
    };

    renderWithProviders(<EvidenceCard value="700 ms" provenance={provenance} />);

    const trigger = screen.getByRole('button', { expanded: false });
    expect(trigger).toBeInTheDocument();

    await userEvent.click(trigger);

    expect(screen.getByRole('button', { expanded: true })).toBeInTheDocument();
    expect(screen.getByText(/rounded to 700 ms/)).toBeInTheDocument();
    expect(screen.getByText('Prometheus (checkout-service)')).toBeInTheDocument();
  });

  it('shows the baseline execution id when the source is a Vortex baseline', async () => {
    const provenance: ThresholdProvenanceDto = {
      source: 'VORTEX_BASELINE',
      sourceLabel: 'Vortex baseline',
      derivation: '10% improvement on your best valid baseline: rounded to 450 ms.',
      baselineExecutionId: 'exec-184',
      evidenceQuality: 'MODERATE',
    };

    renderWithProviders(<EvidenceCard value="450 ms" provenance={provenance} />);
    await userEvent.click(screen.getByText('Why 450 ms?'));

    expect(screen.getByText('From run exec-184')).toBeInTheDocument();
  });
});
