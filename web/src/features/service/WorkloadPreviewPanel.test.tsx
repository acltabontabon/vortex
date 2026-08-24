import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { MixRow } from '../../api/workspace';
import type { ShapeDto } from '../../api/tests';
import { WorkloadPreviewPanel, type ComposerPreviewSnapshot } from './WorkloadPreviewPanel';

function aShape(overrides: Partial<ShapeDto> = {}): ShapeDto {
  return {
    unit: 'requests/sec',
    ramping: false,
    peakLevelValue: 50,
    peakLevelDisplay: '50 requests/sec',
    totalDurationMillis: 600_000,
    stages: [{ levelValue: 50, levelDisplay: '50 requests/sec', durationMillis: 600_000, durationDisplay: '10m' }],
    ...overrides,
  };
}

/** A spike's non-monotonic [baseline, peak, peak, baseline] stage list — used to confirm the panel
 *  never assumes a ramp only ever climbs. */
function aSpikeShape(): ShapeDto {
  return aShape({
    ramping: true,
    peakLevelValue: 100,
    peakLevelDisplay: '100 requests/sec',
    stages: [
      { levelValue: 10, levelDisplay: '10 requests/sec', durationMillis: 30_000, durationDisplay: '30s' },
      { levelValue: 100, levelDisplay: '100 requests/sec', durationMillis: 15_000, durationDisplay: '15s' },
      { levelValue: 100, levelDisplay: '100 requests/sec', durationMillis: 60_000, durationDisplay: '1m' },
      { levelValue: 10, levelDisplay: '10 requests/sec', durationMillis: 15_000, durationDisplay: '15s' },
    ],
  });
}

function aRow(overrides: Partial<MixRow> = {}): MixRow {
  return {
    operationId: 'getAccount',
    label: 'GET /accounts/{id}',
    method: 'GET',
    path: '/accounts/{id}',
    sharePercent: '60%',
    shareFraction: 0.6,
    rateDisplay: '120',
    known: true,
    ...overrides,
  };
}

function aSnapshot(overrides: Partial<ComposerPreviewSnapshot> = {}): ComposerPreviewSnapshot {
  return {
    testTypeLabel: 'Soak',
    headline: 'Hold 50 requests/sec for 10 min',
    durationMinutes: 10,
    composition: [aRow()],
    shape: null,
    problem: null,
    targetSummary: null,
    resourceSummary: null,
    ...overrides,
  };
}

describe('the workload preview panel', () => {
  it('shows a graceful placeholder before there is a workload to preview', () => {
    renderWithProviders(<WorkloadPreviewPanel showChart snapshot={null} />);

    expect(screen.getByText('Workload')).toBeInTheDocument();
    expect(screen.getByText('Fill in Intent and Load to see the shape.')).toBeInTheDocument();
  });

  it('renders the backend-built headline verbatim, never re-deriving it', () => {
    renderWithProviders(
      <WorkloadPreviewPanel showChart snapshot={aSnapshot({ headline: 'Hold 200 requests/sec for 10 min' })} />,
    );

    expect(screen.getByText('Soak')).toBeInTheDocument();
    expect(screen.getByText('Hold 200 requests/sec for 10 min')).toBeInTheDocument();
    expect(screen.getByText('10 min')).toBeInTheDocument();
  });

  it('describes a spike headline exactly as the backend phrased it', () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        showChart
        snapshot={aSnapshot({
          headline: 'Jump from 10 requests/sec to 100 requests/sec and back over 2m',
          shape: aSpikeShape(),
        })}
      />,
    );

    expect(
      screen.getByText('Jump from 10 requests/sec to 100 requests/sec and back over 2m'),
    ).toBeInTheDocument();
    // Stage count comes from the shape, not from a raw "ramping" flag that no longer exists.
    expect(screen.getByText('10 min · 4 stages')).toBeInTheDocument();
  });

  it('reads concurrency from the shape unit, never comparing VUs to a rate', () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        showChart
        snapshot={aSnapshot({
          headline: 'Drive 75 concurrent users for 10 min',
          shape: aShape({ unit: 'VUs' }),
        })}
      />,
    );

    expect(screen.getByText('Drive 75 concurrent users for 10 min')).toBeInTheDocument();
  });

  it('shows the traffic mix once the domain has computed a composition', () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        showChart
        snapshot={aSnapshot({ composition: [aRow(), aRow({ operationId: 'getOrder', path: '/orders/{id}', sharePercent: '40%', shareFraction: 0.4 })] })}
      />,
    );

    expect(screen.getByText('/accounts/{id}')).toBeInTheDocument();
    expect(screen.getByText('/orders/{id}')).toBeInTheDocument();
  });

  it('never fabricates a mix or a sentence while the workload is incomplete', () => {
    renderWithProviders(<WorkloadPreviewPanel showChart snapshot={aSnapshot({ composition: [] })} />);

    expect(screen.getByText('Give at least one operation a share of the traffic.')).toBeInTheDocument();
    expect(screen.queryByText(/distributing most traffic/)).not.toBeInTheDocument();
  });

  it("surfaces the domain's own refusal instead of the mix when the preview reports one", () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        showChart
        snapshot={aSnapshot({ composition: null, problem: 'Choose the operation these virtual users will call.' })}
      />,
    );

    expect(screen.getByText('Choose the operation these virtual users will call.')).toBeInTheDocument();
  });

  it('narrates the top operation as its own caption, separate from the headline', () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        showChart
        snapshot={aSnapshot({
          composition: [
            aRow({ shareFraction: 0.2, path: '/orders/{id}' }),
            aRow({ operationId: 'getAccount', shareFraction: 0.6, path: '/accounts/{id}' }),
          ],
        })}
      />,
    );

    expect(screen.getByText('Distributing most traffic to /accounts/{id}.')).toBeInTheDocument();
  });

  it('shows the target/resource caption when the snapshot provides it', () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        showChart
        snapshot={aSnapshot({
          targetSummary: 'Docker: payment-service:1.4.2',
          resourceSummary: '0.5 CPU · 512 MiB',
        })}
      />,
    );

    expect(
      screen.getByText('Target · Docker: payment-service:1.4.2 · Resources · 0.5 CPU · 512 MiB'),
    ).toBeInTheDocument();
  });

  it('omits the target/resource caption entirely for an external-endpoint target', () => {
    renderWithProviders(
      <WorkloadPreviewPanel showChart snapshot={aSnapshot({ targetSummary: null, resourceSummary: null })} />,
    );

    expect(screen.queryByText(/^Target ·/)).not.toBeInTheDocument();
  });

  it('hides its own chart on narrow screens, where the composer already shows it inline', () => {
    const { container, rerender } = renderWithProviders(
      <WorkloadPreviewPanel showChart snapshot={aSnapshot({ shape: aShape() })} />,
    );
    expect(container.querySelector('svg[role="img"]')).not.toBeNull();

    rerender(<WorkloadPreviewPanel showChart={false} snapshot={aSnapshot({ shape: aShape() })} />);
    expect(container.querySelector('svg[role="img"]')).toBeNull();
  });
});
