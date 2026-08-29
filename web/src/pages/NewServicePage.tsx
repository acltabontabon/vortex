import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm } from '@mantine/form';
import { useDebouncedValue } from '@mantine/hooks';
import {
  Alert,
  Anchor,
  Button,
  Container,
  Drawer,
  Group,
  List,
  Loader,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconAlertTriangle, IconCheck, IconSearch } from '@tabler/icons-react';
import {
  useCreateServiceMutation,
  useAdoptServiceMutation,
  useDetectConfigMutation,
  useOpenApiPreviewMutation,
  useWorkspaceCheckMutation,
  type CreateServiceResponse,
  type DetectConfigResponse,
  type OpenApiPreviewResponse,
  type WorkspaceCheckResponse,
} from '../api/services';
import { useDiscoveryScanMutation } from '../api/discovery';
import { ApiError } from '../api/client';
import { extractErrorMessage } from '../lib/queryFallback';
import { ConfigFoundSummary } from './ConfigFoundSummary';
import { DirectoryBrowserModal } from './DirectoryBrowserModal';
import { DiscoveryReviewPanel, type DiscoverySelections } from './DiscoveryReviewPanel';
import { FieldLabelWithHint } from './FieldLabelWithHint';
import classes from './NewServicePage.module.css';

const MONOSPACE_INPUT = { input: { fontFamily: 'var(--mantine-font-family-monospace)' } };

/**
 * Adding a service.
 *
 * <p>Creation is a discrete act, not step one of a wizard — everything else about a service
 * (operations, workloads, objectives) is entered on Configuration, where each part saves as it is
 * completed rather than as a step somebody can be halfway through.
 *
 * <p>The repository path previews live: as soon as it resolves to a directory, Vortex checks whether
 * it is already onboarded and whether it holds a `vortex.yaml`. Finding one turns the rest of the
 * form into a summary of what would be restored, rather than asking the fields to be filled in again.
 * The path stays optional — a service tested remotely never needs one.
 */
export function NewServicePage() {
  const navigate = useNavigate();
  const createMutation = useCreateServiceMutation();
  const adoptMutation = useAdoptServiceMutation();

  const form = useForm({
    initialValues: { name: '', openApiUrl: '', description: '', workspacePath: '' },
  });

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
  const detectConfig = useDetectConfigMutation();
  const [debouncedWorkspacePath] = useDebouncedValue(form.values.workspacePath, 400);
  const [continueWithoutImporting, setContinueWithoutImporting] = useState(false);
  const [browserOpened, setBrowserOpened] = useState(false);

  // "Inspect project" is a discrete, user-triggered scan — not something that fires on every
  // keystroke like the workspace/config checks above. Approving a proposal in the drawer never
  // calls an API of its own; it just stashes the selection here, folded into the one "create
  // service" submit below, so creation stays a single act (see the file doc comment).
  const discoveryScan = useDiscoveryScanMutation();
  const [discoveryDrawerOpen, setDiscoveryDrawerOpen] = useState(false);
  const [discoverySelections, setDiscoverySelections] = useState<DiscoverySelections | null>(null);

  function inspectProject() {
    const path = form.values.workspacePath.trim();
    if (!path) {
      return;
    }
    discoveryScan.mutate(
      { path },
      {
        onSuccess: (response) => {
          if (response.ok && response.proposedServiceName && !form.values.name.trim()) {
            form.setFieldValue('name', response.proposedServiceName);
          }
          setDiscoveryDrawerOpen(true);
        },
      },
    );
  }

  useEffect(() => {
    const path = debouncedWorkspacePath.trim();
    setContinueWithoutImporting(false);
    // A proposal scanned from a different directory must never silently ride along to this one.
    discoveryScan.reset();
    setDiscoverySelections(null);
    if (path) {
      workspaceCheck.mutate({ path });
      detectConfig.mutate({ path });
    } else {
      workspaceCheck.reset();
      detectConfig.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedWorkspacePath]);

  const detection = detectConfig.data;
  const isAlreadyOnboarded = Boolean(detection?.alreadyOnboarded);
  const isFoundConfig = Boolean(detection && !detection.alreadyOnboarded && detection.found);
  const showFoundSummary = isFoundConfig && !continueWithoutImporting;
  const showManualFields = !isAlreadyOnboarded && !showFoundSummary;

  // The configured service name is the default "Register as" value, but only until the person
  // adopting it edits it — re-filling on every detect response would clobber a rename in progress.
  useEffect(() => {
    if (
      isFoundConfig &&
      detection?.valid &&
      detection.summary &&
      !form.values.name.trim()
    ) {
      form.setFieldValue('name', detection.summary.serviceName);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detection]);

  function handleSuccess(response: CreateServiceResponse) {
    if (response.importOutcome.succeeded) {
      navigate(`/services/${response.service.id}/configuration#operations`);
    } else {
      navigate(`/services/${response.service.id}`);
    }
  }

  function submit(values: typeof form.values) {
    const workspacePath = values.workspacePath.trim() || undefined;
    if (showFoundSummary && detection?.valid) {
      adoptMutation.mutate(
        { workspacePath: workspacePath ?? '', name: values.name.trim() },
        { onSuccess: handleSuccess },
      );
      return;
    }
    const proposal = discoveryScan.data;
    const openApiUrl = values.openApiUrl.trim() || undefined;
    createMutation.mutate(
      {
        name: values.name.trim(),
        description: values.description.trim() || undefined,
        workspacePath,
        openApiUrl,
        // A typed address always wins over a discovered file — the field the person actually
        // filled in is the more deliberate act.
        openApiFile:
          !openApiUrl && discoverySelections?.includeOpenApiSource
            ? (proposal?.proposedOpenApiSourceFile ?? undefined)
            : undefined,
        applyEnvironment:
          discoverySelections?.includeEnvironment ? (proposal?.proposedEnvironment ?? undefined) : undefined,
        applyLocalLabComposeFile:
          discoverySelections?.includeLocalLab
            ? (proposal?.proposedLocalLabComposeFile ?? undefined)
            : undefined,
      },
      { onSuccess: handleSuccess },
    );
  }

  const activeMutation = showFoundSummary && detection?.valid ? adoptMutation : createMutation;
  const serverError =
    activeMutation === adoptMutation && adoptMutation.isError &&
    adoptMutation.error instanceof ApiError && adoptMutation.error.status === 400
      ? null // shown inline next to the name field instead
      : extractErrorMessage(activeMutation, 'Something went wrong creating this service.');

  const nameError =
    adoptMutation.isError && adoptMutation.error instanceof ApiError && adoptMutation.error.status === 400
      ? (adoptMutation.error.detail ?? 'That name is already taken.')
      : null;

  const canSubmit = isAlreadyOnboarded
    ? false
    : showFoundSummary
      ? Boolean(detection?.valid && form.values.name.trim())
      : Boolean(form.values.name.trim());

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

        {createMutation.isSuccess && createMutation.data.importOutcome.attempted &&
          !createMutation.data.importOutcome.succeeded && (
            <Alert color="warn" title="The service was created, but the import did not finish">
              <Text size="sm">{createMutation.data.importOutcome.error}</Text>
              {createMutation.data.importOutcome.errorDetails.length > 0 && (
                <List size="sm" mt="xs">
                  {createMutation.data.importOutcome.errorDetails.map((detail) => (
                    <List.Item key={detail}>{detail}</List.Item>
                  ))}
                </List>
              )}
            </Alert>
          )}

        {createMutation.isSuccess && createMutation.data.setupWarning && (
          <Alert color="warn" title="The service was created, but part of the discovered setup did not apply">
            <Text size="sm">{createMutation.data.setupWarning}</Text>
          </Alert>
        )}

        <form onSubmit={form.onSubmit(submit)}>
          <Stack gap="md">
            <div>
              <Group gap={8} align="flex-end" wrap="nowrap">
                <TextInput
                  label={
                    <FieldLabelWithHint
                      text="Service location"
                      hint="Point Vortex at a service's repository. If it already has a Vortex configuration, Vortex will use it. Leave this blank for a service tested remotely."
                    />
                  }
                  placeholder="/Users/you/code/checkout-service"
                  size="md"
                  styles={MONOSPACE_INPUT}
                  style={{ flex: 1 }}
                  rightSection={
                    workspaceCheck.isPending || detectConfig.isPending ? <Loader size="xs" /> : null
                  }
                  {...form.getInputProps('workspacePath')}
                />
                <Button variant="default" size="md" onClick={() => setBrowserOpened(true)}>
                  Browse…
                </Button>
              </Group>
              <DirectoryBrowserModal
                opened={browserOpened}
                startingPath={form.values.workspacePath}
                onClose={() => setBrowserOpened(false)}
                onChoose={(path) => form.setFieldValue('workspacePath', path)}
              />
              <RepositoryHint
                path={form.values.workspacePath}
                detection={detection}
                detectPending={detectConfig.isPending}
                workspaceCheckData={workspaceCheck.data}
                workspaceCheckError={workspaceCheck.isError}
              />
              {showManualFields && workspaceCheck.data?.isDirectory && (
                <div className={classes.evidence} style={{ marginTop: 6 }}>
                  <Text size="xs" c="dimmed">
                    Vortex can inspect this project and suggest a setup based on what it finds.
                  </Text>
                  <Button
                    variant="light"
                    size="xs"
                    leftSection={<IconSearch size={13} />}
                    loading={discoveryScan.isPending}
                    onClick={inspectProject}
                    style={{ alignSelf: 'flex-start' }}
                  >
                    Inspect project
                  </Button>
                  {discoveryScan.data && !discoveryScan.data.ok && (
                    <Text size="xs" c="fail">
                      {discoveryScan.data.error}
                    </Text>
                  )}
                  {discoverySelections && discoveryScan.data?.ok && (
                    <Text size="xs" c="pass">
                      Discovered setup selected —{' '}
                      <Anchor size="xs" onClick={() => setDiscoveryDrawerOpen(true)}>
                        review it
                      </Anchor>
                      .
                    </Text>
                  )}
                </div>
              )}
            </div>

            {isAlreadyOnboarded && detection?.existingService && (
              <Alert color="warn" title="This repository is already in Vortex">
                It is registered as &ldquo;{detection.existingService.name}&rdquo;.{' '}
                <Anchor href={`/services/${detection.existingService.id}`}>Open it</Anchor> instead
                of adding it again.
              </Alert>
            )}

            {showFoundSummary && detection && (
              <ConfigFoundSummary
                detection={detection}
                name={form.values.name}
                onNameChange={(value) => form.setFieldValue('name', value)}
                nameError={nameError}
                onContinueWithoutImporting={() => setContinueWithoutImporting(true)}
              />
            )}

            {showManualFields && (
              <>
                <TextInput
                  label="Service name"
                  placeholder="checkout-service"
                  withAsterisk
                  size="md"
                  {...form.getInputProps('name')}
                />

                <div>
                  <TextInput
                    label={<OptionalLabel text="API definition" />}
                    placeholder="https://localhost:8080/openapi.yaml"
                    type="url"
                    size="md"
                    styles={MONOSPACE_INPUT}
                    rightSection={openApiPreview.isPending ? <Loader size="xs" /> : null}
                    {...form.getInputProps('openApiUrl')}
                  />
                  <OpenApiHint value={form.values.openApiUrl} mutation={openApiPreview} />
                </div>

                <TextInput
                  label={<OptionalLabel text="Description" />}
                  placeholder="Places and manages customer orders."
                  size="md"
                  {...form.getInputProps('description')}
                />
              </>
            )}

            <Group mt="sm">
              <Button type="submit" loading={activeMutation.isPending} disabled={!canSubmit}>
                Add service
              </Button>
              <Button component="a" href="/" variant="subtle" color="gray">
                Cancel
              </Button>
            </Group>
          </Stack>
        </form>
      </Stack>

      <Drawer
        opened={discoveryDrawerOpen}
        onClose={() => setDiscoveryDrawerOpen(false)}
        position="right"
        size="lg"
        padding="xl"
        title="Discovered setup"
      >
        {discoveryScan.data?.ok && (
          <DiscoveryReviewPanel
            proposal={discoveryScan.data}
            primaryLabel="Use this setup"
            onApply={(selections) => {
              setDiscoverySelections(selections);
              setDiscoveryDrawerOpen(false);
            }}
          />
        )}
      </Drawer>
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

/**
 * The repository field's live evidence. While no `vortex.yaml` is found, this is the plain
 * writable/git check Vortex has always shown; once a configuration is found (or the repository is
 * already onboarded), those states carry their own, richer evidence and this hint steps aside.
 */
function RepositoryHint({
  path,
  detection,
  detectPending,
  workspaceCheckData,
  workspaceCheckError,
}: {
  path: string;
  detection: DetectConfigResponse | undefined;
  detectPending: boolean;
  workspaceCheckData: WorkspaceCheckResponse | undefined;
  workspaceCheckError: boolean;
}) {
  if (!path.trim()) {
    return null;
  }
  if (detection?.alreadyOnboarded || (detection?.found && !detectPending)) {
    return null;
  }

  if (workspaceCheckData) {
    if (workspaceCheckData.exists && workspaceCheckData.isDirectory) {
      return (
        <div className={classes.evidence} style={{ marginTop: 6 }}>
          {workspaceCheckData.gitRepository && (
            <Group gap={6} wrap="nowrap">
              <IconCheck size={13} stroke={2.5} className={classes.evidenceIcon} />
              <Text size="xs">Git repository</Text>
            </Group>
          )}
          <Group gap={6} wrap="nowrap">
            {workspaceCheckData.writable ? (
              <IconCheck size={13} stroke={2.5} className={classes.evidenceIcon} />
            ) : (
              <IconAlertTriangle size={13} stroke={2.5} className={classes.warnIcon} />
            )}
            <Text size="xs">{workspaceCheckData.writable ? 'Writable' : 'Not writable'}</Text>
          </Group>
          <Text size="xs" c="dimmed">
            No Vortex configuration found here yet → .vortex/vortex.yaml
          </Text>
        </div>
      );
    }
    return (
      <Text size="xs" c="fail" mt={6}>
        {workspaceCheckData.error ?? 'Could not check that path.'}
      </Text>
    );
  }

  if (workspaceCheckError) {
    return (
      <Text size="xs" c="fail" mt={6}>
        Could not check that path right now.
      </Text>
    );
  }

  return null;
}
