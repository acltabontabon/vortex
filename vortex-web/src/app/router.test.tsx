import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { RouterProvider } from 'react-router-dom';
import { MantineProvider } from '@mantine/core';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { theme } from '../theme';
import { router } from './router';

describe('router', () => {
  it('renders Home inside the app shell at the root path, with no console errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((path: string) => {
        if (path === '/api/home') {
          return Promise.resolve(new Response(JSON.stringify({ cards: [] }), { status: 200 }));
        }
        if (path === '/api/runtime') {
          return Promise.resolve(
            new Response(
              JSON.stringify({ checks: [], satisfied: 0, total: 0, requirementsMet: true }),
              { status: 200 },
            ),
          );
        }
        return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }));
      }),
    );
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <MantineProvider theme={theme}>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </MantineProvider>,
    );

    await waitFor(() =>
      expect(screen.getByText('What do you want to prove about your service?')).toBeInTheDocument(),
    );

    expect(consoleError).not.toHaveBeenCalled();
    consoleError.mockRestore();
  });
});
