import { useEffect } from 'react';
import { useRouteError } from 'react-router-dom';
import { Container, Title, Text, Button, Stack } from '@mantine/core';

/**
 * The router's root `errorElement`.
 *
 * This is the actual place an uncaught throw during a route's render, loader or action is caught —
 * React Router's data router (`createBrowserRouter`) installs its own error boundary around route
 * content, ahead of any ordinary React error boundary wrapping `<RouterProvider>`. Without this, the
 * router falls back to its own unstyled default screen, which is not a blank page but is also not
 * one a user should be looking at.
 */
export function RouteErrorFallback() {
  const error = useRouteError();

  useEffect(() => {
    console.error('Vortex hit an error while rendering', error);
  }, [error]);

  return (
    <Container size={640} py="xl">
      <Stack gap="xs" align="flex-start">
        <Title order={2}>Something went wrong</Title>
        <Text c="dimmed">
          Vortex hit an unexpected error while rendering this page. Reloading usually clears it.
        </Text>
        <Button onClick={() => window.location.reload()} variant="light" mt="sm">
          Reload
        </Button>
      </Stack>
    </Container>
  );
}
