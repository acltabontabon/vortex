import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm } from '@mantine/form';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import {
  Alert,
  Button,
  Collapse,
  Container,
  Group,
  List,
  Loader,
  Stack,
  Text,
  TextInput,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { IconAlertTriangle, IconCheck, IconChevronRight } from '@tabler/icons-react';
import {
  useCreateServiceMutation,
  useOpenApiPreviewMutation,
  useWorkspaceCheckMutation,
  type OpenApiPreviewResponse,
  type WorkspaceCheckResponse,
} from '../api/services';
import { extractErrorMessage } from '../lib/queryFallback';
import classes from './NewServicePage.module.css';

const MONOSPACE_INPUT = { input: { fontFamily: 'var(--mantine-font-family-monospace)' } };

/**
 * Adding a service.
 *
 * <p>Creation is a discrete act, not step one of a wizard — everything else about a service
 * (operations, workloads, objectives) is entered on Configuration, where each part saves as it is
 * completed rather than as a step somebody can be halfway through.
 *
 * <p>The API definition and repository fields preview live, so what Vortex found replaces what it
 * might do — the same parse and filesystem check the real submit performs, just not committed yet.
 */
export function NewServicePage() {
  const navigate = useNavigate();
  const mutation = useCreateServiceMutation();

  const form = useForm({
    initialValues: { name: '', openApiUrl: '', description: '', workspacePath: '' },
  });

  const [advancedOpened, advancedHandlers] = useDisclosure(false);

  const openApiPreview = useOpenApiPreviewMutation();
  const [debouncedOpenApiUrl] = useDebouncedValue(form.values.openApiUrl, 400);
  useEffect(() => {
    const url = debouncedOpenApiUrl.trim();
    if (url) {
      openApiPreview.mutate({ url });
    } else {
      openApiPreview.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedOpenApiUrl]);

  const workspaceCheck = useWorkspaceCheckMutation();
  const [debouncedWorkspacePath] = useDebouncedValue(form.values.workspacePath, 400);
  useEffect(() => {
    const path = debouncedWorkspacePath.trim();
    if (path) {
      workspaceCheck.mutate({ path });
    } else {
      workspaceCheck.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedWorkspacePath]);

  function submit(values: typeof form.values) {
    mutation.mutate(
      {
        name: values.name.trim(),
        description: values.description.trim() || undefined,
        workspacePath: values.workspacePath.trim() || undefined,
        openApiUrl: values.openApiUrl.trim() || undefined,
      },
      {
        onSuccess: (response) => {
          if (response.importOutcome.succeeded) {
            navigate(`/services/${response.service.id}/configuration#operations`);
          } else {
            navigate(`/services/${response.service.id}`);
          }
        },
      }
    );
  }

  const serverError = extractErrorMessage(mutation, 'Something went wrong creating this service.');

  return (
    <Container size={680} px={0} py="xl">
      <Stack gap="lg">
        <div>
          <Title order={1} size="h2">
            Add service
          </Title>
          <Text c="dimmed" size="sm" mt={4}>
            Register a service to start building and running performance tests.
          </Text>
        </div>

        {serverError && (
          <Alert color="fail" title="Could not create this service">
            {serverError}
          </Alert>
        )}

        {mutation.isSuccess && mutation.data.importOutcome.attempted &&
          !mutation.data.importOutcome.succeeded && (
            <Alert color="warn" title="The service was created, but the import did not finish">
              <Text size="sm">{mutation.data.importOutcome.error}</Text>
              {mutation.data.importOutcome.errorDetails.length > 0 && (
                <List size="sm" mt="xs">
                  {mutation.data.importOutcome.errorDetails.map((detail) => (
                    <List.Item key={detail}>{detail}</List.Item>
                  ))}
                </List>
              )}
            </Alert>
          )}

        <form onSubmit={form.onSubmit(submit)}>
          <Stack gap="md">
            <TextInput
              label="Service name"
              placeholder="checkout-service"
              autoFocus
              withAsterisk
              size="md"
              {...form.getInputProps('name')}
            />

            <div>
              <TextInput
                label={<OptionalLabel text="API definition" />}
                placeholder="https://localhost:8080/openapi.yaml"
                type="url"
                styles={MONOSPACE_INPUT}
                rightSection={openApiPreview.isPending ? <Loader size="xs" /> : null}
                {...form.getInputProps('openApiUrl')}
              />
              <OpenApiHint value={form.values.openApiUrl} mutation={openApiPreview} />
            </div>

            <TextInput
              label={<OptionalLabel text="Description" />}
              placeholder="Places and manages customer orders."
              {...form.getInputProps('description')}
            />

            <div>
              <UnstyledButton
                onClick={advancedHandlers.toggle}
                className={classes.disclosureToggle}
                aria-expanded={advancedOpened}
              >
                <IconChevronRight
                  size={14}
                  className={`${classes.chevron} ${advancedOpened ? classes.chevronOpen : ''}`}
                />
                Workspace options
              </UnstyledButton>
              <Collapse expanded={advancedOpened}>
                <TextInput
                  mt="sm"
                  label="Repository path"
                  placeholder="/Users/you/code/checkout-service"
                  styles={MONOSPACE_INPUT}
                  rightSection={workspaceCheck.isPending ? <Loader size="xs" /> : null}
                  {...form.getInputProps('workspacePath')}
                />
                <WorkspaceHint value={form.values.workspacePath} mutation={workspaceCheck} />
              </Collapse>
            </div>

            <Group mt="sm">
              <Button type="submit" loading={mutation.isPending} disabled={!form.values.name.trim()}>
                Add service
              </Button>
              <Button component="a" href="/" variant="subtle" color="gray">
                Cancel
              </Button>
            </Group>
          </Stack>
        </form>
      </Stack>
    </Container>
  );
}

function OptionalLabel({ text }: { text: string }) {
  return (
    <Group justify="space-between" wrap="nowrap" gap="xs">
      <Text component="span" size="sm">
        {text}
      </Text>
      <Text component="span" size="xs" c="dimmed">
        Optional
      </Text>
    </Group>
  );
}

interface PreviewMutationLike<T> {
  data: T | undefined;
  isError: boolean;
}

function OpenApiHint({
  value,
  mutation,
}: {
  value: string;
  mutation: PreviewMutationLike<OpenApiPreviewResponse>;
}) {
  if (!value.trim()) {
    return (
      <Text size="xs" c="dimmed" mt={4}>
        OpenAPI 3.x · URL, YAML or JSON
      </Text>
    );
  }

  if (mutation.data) {
    if (mutation.data.ok) {
      const extra = mutation.data.operationCount - mutation.data.sample.length;
      const title = mutation.data.title?.trim() || 'OpenAPI document';
      return (
        <div className={classes.evidence}>
          <Group gap={6} wrap="nowrap">
            <IconCheck size={13} stroke={2.5} className={classes.evidenceIcon} />
            <Text size="xs" fw={600}>
              {title} · {mutation.data.operationCount}{' '}
              operation{mutation.data.operationCount === 1 ? '' : 's'} discovered
            </Text>
          </Group>
          {mutation.data.sample.length > 0 && (
            <div className={classes.operationPeek}>
              {mutation.data.sample.map((operation) => (
                <span key={operation.label}>{operation.label}</span>
              ))}
              {extra > 0 && <span>+{extra} more</span>}
            </div>
          )}
        </div>
      );
    }
    return (
      <Text size="xs" c="fail" mt={4}>
        {mutation.data.error ?? 'Could not check that address.'}
      </Text>
    );
  }

  if (mutation.isError) {
    return (
      <Text size="xs" c="fail" mt={4}>
        Could not check that address right now.
      </Text>
    );
  }

  return (
    <Text size="xs" c="dimmed" mt={4}>
      OpenAPI 3.x · URL, YAML or JSON
    </Text>
  );
}

function WorkspaceHint({
  value,
  mutation,
}: {
  value: string;
  mutation: PreviewMutationLike<WorkspaceCheckResponse>;
}) {
  if (!value.trim()) {
    return null;
  }

  if (mutation.data) {
    if (mutation.data.exists && mutation.data.isDirectory) {
      return (
        <div className={classes.evidence} style={{ marginTop: 6 }}>
          {mutation.data.gitRepository && (
            <Group gap={6} wrap="nowrap">
              <IconCheck size={13} stroke={2.5} className={classes.evidenceIcon} />
              <Text size="xs">Git repository</Text>
            </Group>
          )}
          <Group gap={6} wrap="nowrap">
            {mutation.data.writable ? (
              <IconCheck size={13} stroke={2.5} className={classes.evidenceIcon} />
            ) : (
              <IconAlertTriangle size={13} stroke={2.5} className={classes.warnIcon} />
            )}
            <Text size="xs">{mutation.data.writable ? 'Writable' : 'Not writable'}</Text>
          </Group>
          <Text size="xs" c="dimmed">
            → .vortex/vortex.yaml
          </Text>
        </div>
      );
    }
    return (
      <Text size="xs" c="fail" mt={6}>
        {mutation.data.error ?? 'Could not check that path.'}
      </Text>
    );
  }

  if (mutation.isError) {
    return (
      <Text size="xs" c="fail" mt={6}>
        Could not check that path right now.
      </Text>
    );
  }

  return null;
}
