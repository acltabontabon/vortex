import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '../test/renderWithProviders';
import type { MixRow } from '../api/workspace';
import { TrafficDistribution } from './TrafficDistribution';

function aRow(overrides: Partial<MixRow> = {}): MixRow {
  return {
    operationId: 'getAccount',
    label: 'GET /accounts/{id}',
    method: 'GET',
    path: '/accounts/{id}',
    sharePercent: '70%',
    shareFraction: 0.7,
    rateDisplay: '35',
    known: true,
    ...overrides,
  };
}

const CATALOG = [
  { id: 'getAccount', label: 'GET /accounts/{id}', method: 'GET', path: '/accounts/{id}' },
  { id: 'getOrder', label: 'GET /orders/{id}', method: 'GET', path: '/orders/{id}' },
];

describe('traffic distribution — read-only', () => {
  it('renders nothing for an empty mix', () => {
    const { container } = renderWithProviders(<TrafficDistribution rows={[]} />);
    // Not an empty container outright — MantineProvider injects its own <style> tags alongside
    // whatever the component renders — so check for the absence of the component's own output.
    expect(container.querySelector('div')).toBeNull();
  });

  it('shows the share and rate for each row', () => {
    renderWithProviders(<TrafficDistribution rows={[aRow()]} />);
    expect(screen.getByText('70%')).toBeInTheDocument();
    expect(screen.getByText('· 35/sec')).toBeInTheDocument();
  });

  it('names the driven operation instead of a share under concurrency', () => {
    renderWithProviders(<TrafficDistribution rows={[aRow()]} concurrency />);
    expect(screen.getByText('drives these virtual users')).toBeInTheDocument();
    expect(screen.queryByText('70%')).not.toBeInTheDocument();
  });
});

describe('traffic distribution — editable mixer', () => {
  it('renders every catalog operation, including one with no weight yet', () => {
    renderWithProviders(
      <TrafficDistribution
        rows={[aRow()]}
        edit={{ catalog: CATALOG, weights: { getAccount: 70 }, onChangeWeight: vi.fn() }}
      />,
    );

    // getAccount has a computed share; getOrder has none yet, but still appears, editable.
    expect(screen.getByText('70%')).toBeInTheDocument();
    expect(screen.getByLabelText('Weight for GET /accounts/{id}')).toHaveValue('70');
    // Blank, not a literal "0" — so typing into a fresh operation starts clean.
    expect(screen.getByLabelText('Weight for GET /orders/{id}')).toHaveValue('');
  });

  it('reports a weight change by operation id, leaving the other weights untouched', () => {
    const onChangeWeight = vi.fn();

    function Harness() {
      const [weights, setWeights] = useState<Record<string, number>>({ getAccount: 70 });
      return (
        <TrafficDistribution
          rows={[aRow()]}
          edit={{
            catalog: CATALOG,
            weights,
            onChangeWeight: (id, value) => {
              setWeights((w) => ({ ...w, [id]: value }));
              onChangeWeight(id, value);
            },
          }}
        />
      );
    }

    renderWithProviders(<Harness />);

    fireEvent.change(screen.getByLabelText('Weight for GET /orders/{id}'), { target: { value: '30' } });

    expect(onChangeWeight).toHaveBeenLastCalledWith('getOrder', 30);
    expect(screen.getByLabelText('Weight for GET /accounts/{id}')).toHaveValue('70');
  });
});
