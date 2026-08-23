import { beforeAll, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import type { TimelinePlot } from '../../api/run';
import { renderWithProviders } from '../../test/renderWithProviders';
import { TimelineChart } from './TimelineChart';

// Recharts' ResponsiveContainer measures its DOM node before drawing anything, and jsdom always
// reports 0x0 for layout — without this, every chart renders as an empty container and these
// tests would falsely fail regardless of what TimelineChart actually produced.
beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, value: 600 });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, value: 200 });
  HTMLElement.prototype.getBoundingClientRect = () =>
    ({ width: 600, height: 200, top: 0, left: 0, bottom: 200, right: 600, x: 0, y: 0, toJSON() {} }) as DOMRect;
});

function plot(overrides: Partial<TimelinePlot> = {}): TimelinePlot {
  return {
    label: 'Throughput',
    hasData: true,
    unitSymbol: 'requests/sec',
    points: [
      { atIso: '2026-08-22T10:00:00Z', value: 10 },
      { atIso: '2026-08-22T10:00:10Z', value: 20 },
      { atIso: '2026-08-22T10:00:20Z', value: null },
      { atIso: '2026-08-22T10:00:30Z', value: 30 },
    ],
    referencePoints: [],
    referenceLevel: null,
    ...overrides,
  };
}

describe('TimelineChart', () => {
  it('renders nothing when the plot has no data', () => {
    const { container } = renderWithProviders(<TimelineChart plot={plot({ hasData: false })} />);
    expect(container.querySelector('svg')).toBeNull();
  });

  it('renders a chart for a plot with a mid-series gap', () => {
    const { container } = renderWithProviders(<TimelineChart plot={plot()} />);
    expect(container.querySelector('svg')).toBeInTheDocument();
  });

  it('renders a reference series and a threshold line without throwing', () => {
    const withReference = plot({
      label: 'Latency',
      unitSymbol: 'ms',
      referencePoints: [
        { atIso: '2026-08-22T10:00:00Z', value: 50 },
        { atIso: '2026-08-22T10:00:10Z', value: 50 },
      ],
      referenceLevel: 200,
    });
    const { container } = renderWithProviders(<TimelineChart plot={withReference} />);
    expect(container.querySelector('svg')).toBeInTheDocument();
  });

  it('labels the shared breakpoint instant when one falls within this plot\'s own span', () => {
    renderWithProviders(
      <TimelineChart
        plot={plot()}
        markAtIso="2026-08-22T10:00:15Z"
        markLabel="First objective violation"
      />,
    );

    expect(screen.getByText('First objective violation')).toBeInTheDocument();
  });

  it('draws no label on a chart that only carries the shared line, not the naming one', () => {
    const { container } = renderWithProviders(
      <TimelineChart plot={plot()} markAtIso="2026-08-22T10:00:15Z" />,
    );

    expect(container.querySelector('svg')).toBeInTheDocument();
    expect(screen.queryByText('First objective violation')).not.toBeInTheDocument();
  });

  it('never marks a breakpoint absent from this run\'s evidence', () => {
    renderWithProviders(
      <TimelineChart plot={plot()} markAtIso={null} markLabel="First objective violation" />,
    );

    expect(screen.queryByText('First objective violation')).not.toBeInTheDocument();
  });

  it('drops a breakpoint that falls outside this plot\'s own measured span', () => {
    renderWithProviders(
      <TimelineChart
        plot={plot()}
        markAtIso="2026-08-22T09:00:00Z"
        markLabel="First objective violation"
      />,
    );

    expect(screen.queryByText('First objective violation')).not.toBeInTheDocument();
  });
});
