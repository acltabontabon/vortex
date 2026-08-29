import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { ThresholdScaleBar, type ScaleMarker } from './ThresholdScaleBar';

describe('ThresholdScaleBar', () => {
  it('renders nothing for fewer than two markers', () => {
    const { container } = render(<ThresholdScaleBar markers={[{ value: 500, label: '500 ms', kind: 'objective' }]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('renders an accessible label naming every marker', () => {
    const markers: ScaleMarker[] = [
      { value: 510, label: '510 ms', kind: 'baseline' },
      { value: 620, label: '620 ms', kind: 'production' },
      { value: 550, label: '550 ms', kind: 'objective' },
    ];

    const { getByRole } = render(<ThresholdScaleBar markers={markers} />);

    expect(getByRole('img')).toHaveAccessibleName('baseline: 510 ms, production: 620 ms, objective: 550 ms');
  });
});
