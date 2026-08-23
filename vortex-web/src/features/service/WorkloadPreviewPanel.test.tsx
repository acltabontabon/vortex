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
    model: 'OPEN',
    ramping: false,
    rate: 50,
    vus: 50,
    durationMinutes: 10,
    peakRate: '',
    stages: 4,
    composition: [aRow()],
    shape: null,
    problem: null,
    ...overrides,
  };
}

describe('the workload preview panel', () => {
  it('shows a graceful placeholder before there is a workload to preview', () => {
    renderWithProviders(<WorkloadPreviewPanel serviceName="checkout-service" showChart snapshot={null} />);

    expect(screen.getByText('Workload')).toBeInTheDocument();
    expect(screen.getByText('Fill in Intent and Load to see the shape.')).toBeInTheDocument();
  });

  it('states a steady arrival-rate headline in requests/sec', () => {
    renderWithProviders(
      <WorkloadPreviewPanel serviceName="checkout-service" showChart snapshot={aSnapshot({ rate: 200 })} />,
    );

    expect(screen.getByText('Soak')).toBeInTheDocument();
    expect(screen.getByText('Hold 200 req/s')).toBeInTheDocument();
    expect(screen.getByText('10 min')).toBeInTheDocument();
  });

  it('states a ramping headline with the stage count, in the workload language', () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        serviceName="checkout-service" showChart
        snapshot={aSnapshot({ ramping: true, peakRate: 300, stages: 5 })}
      />,
    );

    expect(screen.getByText('Ramp to 300 req/s')).toBeInTheDocument();
    expect(screen.getByText('10 min · 5 stages')).toBeInTheDocument();
  });

  it('speaks concurrency in its own units, never comparing them to a rate', () => {
    renderWithProviders(
      <WorkloadPreviewPanel serviceName="checkout-service" showChart snapshot={aSnapshot({ model: 'CLOSED', vus: 75 })} />,
    );

    expect(screen.getByText('Drive 75 concurrent users')).toBeInTheDocument();
  });

  it('shows the traffic mix once the domain has computed a composition', () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        serviceName="checkout-service" showChart
        snapshot={aSnapshot({ composition: [aRow(), aRow({ operationId: 'getOrder', path: '/orders/{id}', sharePercent: '40%', shareFraction: 0.4 })] })}
      />,
    );

    expect(screen.getByText('/accounts/{id}')).toBeInTheDocument();
    expect(screen.getByText('/orders/{id}')).toBeInTheDocument();
  });

  it('never fabricates a mix or a sentence while the workload is incomplete', () => {
    renderWithProviders(
      <WorkloadPreviewPanel serviceName="checkout-service" showChart snapshot={aSnapshot({ composition: [] })} />,
    );

    expect(screen.getByText('Give at least one operation a share of the traffic.')).toBeInTheDocument();
    expect(screen.queryByText(/distributing most traffic/)).not.toBeInTheDocument();
  });

  it('surfaces the domain\'s own refusal instead of the mix when the preview reports one', () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        serviceName="checkout-service" showChart
        snapshot={aSnapshot({ composition: null, problem: 'Choose the operation these virtual users will call.' })}
      />,
    );

    expect(screen.getByText('Choose the operation these virtual users will call.')).toBeInTheDocument();
  });

  it('narrates the workload as a muted caption naming the top operation', () => {
    renderWithProviders(
      <WorkloadPreviewPanel
        serviceName="checkout-service" showChart
        snapshot={aSnapshot({
          ramping: true,
          peakRate: 200,
          stages: 5,
          durationMinutes: 1,
          composition: [
            aRow({ shareFraction: 0.2, path: '/orders/{id}' }),
            aRow({ operationId: 'getAccount', shareFraction: 0.6, path: '/accounts/{id}' }),
          ],
        })}
      />,
    );

    expect(
      screen.getByText(
        'Ramp checkout-service to 200 req/s across 5 stages for 1 min, distributing most traffic to /accounts/{id}.',
      ),
    ).toBeInTheDocument();
  });

  it('hides its own chart on narrow screens, where the composer already shows it inline', () => {
    const { container, rerender } = renderWithProviders(
      <WorkloadPreviewPanel serviceName="checkout-service" showChart snapshot={aSnapshot({ shape: aShape() })} />,
    );
    expect(container.querySelector('svg[role="img"]')).not.toBeNull();

    rerender(
      <WorkloadPreviewPanel
        serviceName="checkout-service"
        showChart={false}
        snapshot={aSnapshot({ shape: aShape() })}
      />,
    );
    expect(container.querySelector('svg[role="img"]')).toBeNull();
  });
});
