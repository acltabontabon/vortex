import { QueryClient } from '@tanstack/react-query';

// One instance for the whole app, so a mutation anywhere can invalidate a query key defined
// elsewhere (e.g. creating a service invalidates the service switcher's list) without threading
// the client through props.
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
  },
});
