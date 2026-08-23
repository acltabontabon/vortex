import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { MantineProvider } from '@mantine/core';
import { theme } from '../theme';
import { RouteErrorFallback } from './RouteErrorFallback';

function ThrowingPage(): never {
  throw new Error('boom');
}

describe('RouteErrorFallback', () => {
  it('renders instead of leaving the app blank when a route throws during render', () => {
    // React Router's data router catches a route's render throw with its own internal boundary
    // before it would ever reach an ordinary React error boundary wrapping <RouterProvider> — so
    // this has to render through a real router with errorElement configured, the same way
    // src/app/router.tsx does, rather than mounting the fallback directly.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const router = createMemoryRouter(
      [{ path: '/', element: <ThrowingPage />, errorElement: <RouteErrorFallback /> }],
      { initialEntries: ['/'] },
    );

    render(
      <MantineProvider theme={theme}>
        <RouterProvider router={router} />
      </MantineProvider>,
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();

    consoleError.mockRestore();
  });

  it('renders the route normally when nothing throws', () => {
    const router = createMemoryRouter(
      [{ path: '/', element: <div>the actual page</div>, errorElement: <RouteErrorFallback /> }],
      { initialEntries: ['/'] },
    );

    render(
      <MantineProvider theme={theme}>
        <RouterProvider router={router} />
      </MantineProvider>,
    );

    expect(screen.getByText('the actual page')).toBeInTheDocument();
    expect(screen.queryByText('Something went wrong')).not.toBeInTheDocument();
  });
});
