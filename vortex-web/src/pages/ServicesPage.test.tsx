import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../test/renderWithProviders';
import type { ServiceListItem } from '../api/services';
import { ServicesPage } from './ServicesPage';

let queryResult: { data: ServiceListItem[] | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};

vi.mock('../api/services', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/services')>();
  return { ...actual, useServicesListQuery: () => queryResult };
});

describe('the services page', () => {
  it('offers to add a service when there are none yet, rather than showing an empty table', () => {
    queryResult = { data: [], isError: false };
    renderWithProviders(<ServicesPage />);

    expect(screen.getByText('No services yet.')).toBeInTheDocument();
  });

  it('lists each service by name, with its description and release when known', () => {
    queryResult = {
      data: [
        { id: 'checkout', name: 'checkout-service', description: 'Order placement and lookup.', serviceVersion: '2.17.0' },
      ],
      isError: false,
    };
    renderWithProviders(<ServicesPage />);

    expect(screen.getByText('checkout-service')).toBeInTheDocument();
    expect(screen.getByText('Order placement and lookup.')).toBeInTheDocument();
    expect(screen.getByText('2.17.0')).toBeInTheDocument();
  });

  it('surfaces a failed load rather than a silent empty state', () => {
    queryResult = { data: undefined, isError: true };
    renderWithProviders(<ServicesPage />);

    expect(screen.getByText('Could not load services')).toBeInTheDocument();
  });
});
