import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { TestTypeEvidence } from '../../api/workspace';
import { EvidenceStrip } from './EvidenceStrip';

/**
 * These assert the semantic rule the redesign exists to enforce: each test type's own cell shows
 * the fact that type actually answers (a word, a rate, a duration), never a manufactured universal
 * number — and that a run in flight is never confused with, or allowed to erase, prior evidence.
 */

function anEvidence(overrides: Partial<TestTypeEvidence> = {}): TestTypeEvidence {
  return {
    testType: 'BREAKPOINT',
    testTypeLabel: 'Breakpoint',
    hasEvidence: false,
    outcome: null,
    outcomeLabel: null,
    primaryValueKind: null,
    primaryValue: null,
    secondaryValue: null,
    workloadName: null,
    environmentName: null,
    release: null,
    executionId: null,
    relativeTime: null,
    isoTimestamp: null,
    answer: null,
    running: false,
    runningWorkloadName: null,
    ...overrides,
  };
}

describe('EvidenceStrip', () => {
  it('shows a test type with no run yet as a muted "Not tested", never N/A', () => {
    renderWithProviders(
      <EvidenceStrip evidence={[anEvidence({ testType: 'SOAK', testTypeLabel: 'Soak' })]} onSelect={vi.fn()} />,
    );

    expect(screen.getByText('Not tested')).toBeInTheDocument();
    expect(screen.queryByText('N/A')).not.toBeInTheDocument();
  });

  it('abbreviates a rate value the same way the rest of the page does', () => {
    renderWithProviders(
      <EvidenceStrip
        evidence={[
          anEvidence({
            testType: 'AVERAGE_LOAD',
            testTypeLabel: 'Average load',
            hasEvidence: true,
            outcome: 'PASS',
            outcomeLabel: 'Pass',
            primaryValueKind: 'RATE',
            primaryValue: '42 requests/sec',
            workloadName: 'average-check',
          }),
        ]}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText('42 req/s')).toBeInTheDocument();
  });

  it("shows an outcome word, not a fabricated number, for a test type whose evidence is a pass/fail", () => {
    renderWithProviders(
      <EvidenceStrip
        evidence={[
          anEvidence({
            testType: 'SMOKE',
            testTypeLabel: 'Smoke',
            hasEvidence: true,
            outcome: 'PASS',
            outcomeLabel: 'Passed',
            primaryValueKind: 'OUTCOME',
            primaryValue: 'Passed',
            workloadName: 'smoke-check',
          }),
        ]}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText('Passed')).toBeInTheDocument();
  });

  it('shows a headroom multiple against production where the domain supplied one', () => {
    renderWithProviders(
      <EvidenceStrip
        evidence={[
          anEvidence({
            hasEvidence: true,
            outcome: 'PASS',
            outcomeLabel: 'Pass',
            primaryValueKind: 'RATE',
            primaryValue: '112 requests/sec',
            secondaryValue: '3.2×',
            workloadName: 'breakpoint-check',
          }),
        ]}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText('3.2× production')).toBeInTheDocument();
  });

  it('falls back to a compact freshness reading when there is no headroom to show', () => {
    renderWithProviders(
      <EvidenceStrip
        evidence={[
          anEvidence({
            hasEvidence: true,
            outcome: 'PASS',
            outcomeLabel: 'Pass',
            primaryValueKind: 'RATE',
            primaryValue: '112 requests/sec',
            secondaryValue: null,
            relativeTime: '9 hours ago',
            workloadName: 'breakpoint-check',
          }),
        ]}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText('9h')).toBeInTheDocument();
  });

  it('shows "Running…" for a test type that has never completed but is in flight now', () => {
    renderWithProviders(
      <EvidenceStrip
        evidence={[anEvidence({ hasEvidence: false, running: true, runningWorkloadName: 'first-breakpoint' })]}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText('Running…')).toBeInTheDocument();
  });

  it('never replaces prior completed evidence with "Running…" once a later run starts', () => {
    renderWithProviders(
      <EvidenceStrip
        evidence={[
          anEvidence({
            hasEvidence: true,
            outcome: 'PASS',
            outcomeLabel: 'Pass',
            primaryValueKind: 'RATE',
            primaryValue: '112 requests/sec',
            workloadName: 'breakpoint-check',
            running: true,
            runningWorkloadName: 'breakpoint-check',
          }),
        ]}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText('112 req/s')).toBeInTheDocument();
    expect(screen.queryByText('Running…')).not.toBeInTheDocument();
    // Still communicated, just not by overwriting the number — a screen-reader-only cue alongside it.
    expect(screen.getByText(/test running now/)).toBeInTheDocument();
  });

  it('selects that test type\'s own workload when its cell is clicked', async () => {
    const onSelect = vi.fn();
    renderWithProviders(
      <EvidenceStrip
        evidence={[
          anEvidence({
            hasEvidence: true,
            outcome: 'PASS',
            outcomeLabel: 'Pass',
            primaryValueKind: 'RATE',
            primaryValue: '112 requests/sec',
            workloadName: 'breakpoint-check',
          }),
        ]}
        onSelect={onSelect}
      />,
    );

    await userEvent.click(screen.getByText('112 req/s'));

    expect(onSelect).toHaveBeenCalledWith('breakpoint-check');
  });

  it('reveals workload, environment and release detail on hover, not on the default surface', async () => {
    renderWithProviders(
      <EvidenceStrip
        evidence={[
          anEvidence({
            hasEvidence: true,
            outcome: 'PASS',
            outcomeLabel: 'Pass',
            primaryValueKind: 'RATE',
            primaryValue: '112 requests/sec',
            workloadName: 'breakpoint-check',
            environmentName: 'staging',
            release: '2.17.0',
          }),
        ]}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.queryByText('staging')).not.toBeInTheDocument();

    await userEvent.hover(screen.getByText('112 req/s'));

    expect(await screen.findByText(/staging/)).toBeInTheDocument();
  });
});
