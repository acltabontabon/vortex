import { beforeAll, describe, expect, it } from 'vitest';
import type { ResourceKindPlot, ResourceSeries } from '../../api/run';
import { renderWithProviders } from '../../test/renderWithProviders';
import { ResourceKindChart } from './ResourceKindChart';
import { mixesCpuRatioWithPercent, toDisplayValue } from './chartTime';

// Recharts' ResponsiveContainer measures its DOM node before drawing anything, and jsdom always
// reports 0x0 for layout — without this, every chart renders as an empty container and these tests
// would falsely fail regardless of what ResourceKindChart actually produced.
beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, value: 600 });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, value: 200 });
  HTMLElement.prototype.getBoundingClientRect = () =>
    ({ width: 600, height: 200, top: 0, left: 0, bottom: 200, right: 600, x: 0, y: 0, toJSON() {} }) as DOMRect;
});

// A 0.5-CPU container limit, matching the real run that motivated this fix: docker's raw ratio
// (a fraction of one whole host core) needs dividing by this before it means the same thing as
// Actuator's already-cgroup-relative percentage.
const CONTAINER_CPU_LIMIT = 0.5;

function series(overrides: Partial<ResourceSeries> = {}): ResourceSeries {
  return {
    signalId: 'metric:docker.cpu.utilization',
    providerId: 'docker',
    scope: 'SYSTEM_UNDER_TEST',
    scopeLabel: 'System under test',
    seriesLabel: 'Container CPU',
    unitSymbol: '',
    points: [
      { atIso: '2026-08-22T10:00:00Z', value: 0.211 },
      { atIso: '2026-08-22T10:00:05Z', value: 0.2 },
    ],
    display: '0.211 cores',
    limitDisplay: '0.5',
    utilisationDisplay: '42%',
    atItsLimit: false,
    utilisationFraction: 0.422,
    limitValue: CONTAINER_CPU_LIMIT,
    ...overrides,
  };
}

function actuatorSeries(overrides: Partial<ResourceSeries> = {}): ResourceSeries {
  return series({
    signalId: 'actuator:metric:system.cpu.usage',
    providerId: 'actuator',
    unitSymbol: '%',
    points: [
      { atIso: '2026-08-22T10:00:00Z', value: 42.06 },
      { atIso: '2026-08-22T10:00:05Z', value: 40 },
    ],
    limitDisplay: '',
    utilisationDisplay: '',
    utilisationFraction: null,
    limitValue: null,
    ...overrides,
  });
}

function plot(seriesList: ResourceSeries[]): ResourceKindPlot {
  return { kind: 'CPU', kindLabel: 'CPU', series: seriesList };
}

describe('mixesCpuRatioWithPercent', () => {
  it('is false for a CPU plot where every series shares one unit', () => {
    expect(mixesCpuRatioWithPercent(plot([series(), series({ providerId: 'generator' })]))).toBe(false);
  });

  it('is true when a bare-ratio series and a percentage series share one CPU plot', () => {
    expect(mixesCpuRatioWithPercent(plot([series(), actuatorSeries()]))).toBe(true);
  });

  it('is false for a non-CPU plot, even with mismatched units', () => {
    const memoryPlot: ResourceKindPlot = {
      kind: 'MEMORY',
      kindLabel: 'Memory',
      series: [series({ unitSymbol: '' }), actuatorSeries({ unitSymbol: '%' })],
    };
    expect(mixesCpuRatioWithPercent(memoryPlot)).toBe(false);
  });
});

describe('toDisplayValue', () => {
  it('scales a bare ratio against its own confirmed limit when the plot is mixed', () => {
    // 0.211 of one host core, against a 0.5-core limit: 42.2% of what this container was allotted
    // — comparable to Actuator's own cgroup-relative percentage, not a flat ×100 (21.1%).
    expect(toDisplayValue(series(), 0.211, true)).toBeCloseTo(42.2);
  });

  it('falls back to a flat ×100 when the ratio series has no confirmed limit', () => {
    expect(toDisplayValue(series({ limitValue: null }), 0.211, true)).toBeCloseTo(21.1);
  });

  it('leaves an already-percent series untouched when the plot is mixed', () => {
    expect(toDisplayValue(actuatorSeries(), 42.06, true)).toBe(42.06);
  });

  it('leaves every value untouched when the plot is not mixed', () => {
    expect(toDisplayValue(series(), 0.211, false)).toBe(0.211);
  });
});

describe('ResourceKindChart', () => {
  it('renders a mixed docker-ratio / actuator-percent CPU plot without throwing', () => {
    const { container } = renderWithProviders(
      <ResourceKindChart plot={plot([series(), actuatorSeries()])} />,
    );
    expect(container.querySelector('svg')).toBeInTheDocument();
  });

  it('renders nothing when every series is empty', () => {
    const { container } = renderWithProviders(
      <ResourceKindChart plot={plot([series({ points: [] })])} />,
    );
    expect(container.querySelector('svg')).toBeNull();
  });
});
