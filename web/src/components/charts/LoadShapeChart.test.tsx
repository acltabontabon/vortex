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
});
