import type { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { theme } from '../theme';

/**
 * Every component in the app shell needs Mantine's theme context and a query client to render at
 * all; most also need a router context since they use `<Link>`/hooks from react-router-dom. A
 * fresh QueryClient per render keeps tests isolated from each other's cache. Passed to `render` as
 * a `wrapper`, not nested around `ui` directly, so a caller's own `rerender(next)` re-wraps `next`
 * with the same providers automatically — needed to test a prop transition (e.g. a run finishing)
 * without tearing down and remounting the whole tree, which is not what happens in the real app.
 */
export function renderWithProviders(ui: ReactElement, { route = '/' }: { route?: string } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MantineProvider theme={theme}>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
        </QueryClientProvider>
      </MantineProvider>
    );
  }

  return render(ui, { wrapper: Wrapper });
}
