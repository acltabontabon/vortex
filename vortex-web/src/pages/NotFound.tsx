import { Container, Title, Text, Button, Stack } from '@mantine/core';

/**
 * Reached only when a URL matches nothing in this router's table — the backend forwards any
 * unrecognized path here (see SpaController), so this is the honest "nothing lives here" answer
 * rather than a blank screen.
 */
export function NotFound() {
  return (
    <Container size={640} py="xl">
      <Stack gap="xs" align="flex-start">
        <Title order={2}>Nothing here</Title>
        <Text c="dimmed">This page doesn't exist, or hasn't been built yet.</Text>
        <Button component="a" href="/" variant="light" mt="sm">
          Back to the workbench
        </Button>
      </Stack>
    </Container>
  );
}
