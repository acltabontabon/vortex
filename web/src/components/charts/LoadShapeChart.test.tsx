import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { ShapeDto } from '../../api/tests';
import { LoadShapeChart } from './LoadShapeChart';

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

describe('the load shape chart', () => {
  it('draws one flat segment for a constant shape', () => {
    const { container } = renderWithProviders(<LoadShapeChart shape={aShape()} />);

    const line = container.querySelector('polyline');
    expect(line).not.toBeNull();
    // A single stage is two points — the start and end of the one flat segment.
    expect(line!.getAttribute('points')!.trim().split(/\s+/)).toHaveLength(2);
  });

  it('draws one segment per stage for a ramping shape, ending at the peak', () => {
    const shape = aShape({
      ramping: true,
      peakLevelValue: 300,
      peakLevelDisplay: '300 requests/sec',
      stages: [
        { levelValue: 60, levelDisplay: '60 requests/sec', durationMillis: 120_000, durationDisplay: '2m' },
        { levelValue: 180, levelDisplay: '180 requests/sec', durationMillis: 120_000, durationDisplay: '2m' },
        { levelValue: 300, levelDisplay: '300 requests/sec', durationMillis: 120_000, durationDisplay: '2m' },
      ],
    });
    const { container } = renderWithProviders(<LoadShapeChart shape={shape} />);

    const line = container.querySelector('polyline');
    // Two points per stage, its own flat span — 3 stages is 6 points.
    expect(line!.getAttribute('points')!.trim().split(/\s+/)).toHaveLength(6);
    expect(container.querySelector('svg')!.getAttribute('aria-label')).toContain('300 requests/sec');
  });

  it('renders nothing for a shape with no stages', () => {
    const { container } = renderWithProviders(<LoadShapeChart shape={aShape({ stages: [] })} />);
    expect(container.querySelector('svg')).toBeNull();
  });

  it('draws a spike\'s non-monotonic [baseline, peak, peak, baseline] pattern correctly, with no assumption that a ramp only ever climbs', () => {
    const shape = aShape({
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
    const { container } = renderWithProviders(<LoadShapeChart shape={shape} />);

    const line = container.querySelector('polyline');
    const points = line!.getAttribute('points')!.trim().split(/\s+/);
    // Two points per stage, four stages — 8 points, and the shape returns to its starting height.
    expect(points).toHaveLength(8);
    const y = (point: string) => Number(point.split(',')[1]);
    const [baselineStartY, , peakStartY, , , peakEndY, recoveryStartY, recoveryEndY] = points.map(y);
    // Baseline sits lower on screen (a larger SVG y) than the peak — the staircase actually rises...
    expect(baselineStartY).toBeGreaterThan(peakStartY);
    // ...holds flat at the peak...
    expect(peakStartY).toBe(peakEndY);
    // ...and comes back down to exactly where it started, not partway or further.
    expect(recoveryStartY).toBe(baselineStartY);
    expect(recoveryEndY).toBe(baselineStartY);
  });
});
