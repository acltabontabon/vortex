import { useState } from 'react';
import { useForm } from '@mantine/form';
import {
  Alert,
  Button,
  Checkbox,
  Drawer,
  Group,
  NumberInput,
  Select,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type {
  Environment,
  EnvironmentOption,
  EnvironmentRequest,
} from '../../../api/configuration';
import { useAddEnvironmentMutation, useValidateTargetMutation } from '../../../api/configuration';
import { extractErrorMessage } from '../../../lib/queryFallback';
import { HeaderRows } from './HeaderRows';
import { rowsFromMasked, rowsToWire, type HeaderRow } from './headerRowUtils';
import classes from './EnvironmentDrawer.module.css';

const TARGET_KIND_OPTIONS = [
  { value: 'EXTERNAL_ENDPOINT', label: 'Existing endpoint' },
  { value: 'DOCKER_IMAGE', label: 'Docker image' },
  { value: 'DOCKER_COMPOSE', label: 'Docker Compose' },
];

interface FormValues {
  name: string;
  baseUrl: string;
  type: string;
  dependencies: string;
  productionLike: boolean;
  targetKind: string;
  image: string;
  containerPort: number | '';
  cpuCores: number | '';
  memoryMebibytes: number | '';
  readinessPath: string;
  readinessExpectedStatus: number | '';
  readinessTimeoutSeconds: number | '';
  composeFile: string;
  composeService: string;
}

const CREATE_VALUES: FormValues = {
  name: '',
  baseUrl: '',
  type: 'LOCAL_ISOLATED',
  dependencies: 'MOCKED',
  productionLike: false,
  targetKind: 'EXTERNAL_ENDPOINT',
  image: '',
  containerPort: '',
  cpuCores: '',
  memoryMebibytes: '',
  readinessPath: '',
  readinessExpectedStatus: '',
  readinessTimeoutSeconds: '',
  composeFile: '',
  composeService: '',
};

function valuesFrom(environment: Environment): FormValues {
  return {
    name: environment.name,
    baseUrl: environment.baseUrl ?? '',
    type: environment.type,
    dependencies: environment.dependencyMode,
    productionLike: environment.productionLike,
    targetKind: environment.target.kind,
    image: environment.image ?? '',
    containerPort: environment.containerPort ?? '',
    cpuCores: environment.cpuMillicores ? environment.cpuMillicores / 1000 : '',
    memoryMebibytes: environment.memoryMebibytes ?? '',
    readinessPath: environment.readinessPath ?? '',
    readinessExpectedStatus: environment.readinessExpectedStatus ?? '',
    readinessTimeoutSeconds: environment.readinessTimeoutSeconds ?? '',
    composeFile: environment.composeFile ?? '',
    composeService: environment.composeService ?? '',
  };
}

const READINESS_ALL_OR_NOTHING_MESSAGE =
  'A readiness check needs a path, expected status and timeout together, or none of them — leave ' +
  'all three blank to fall back to a plain TCP connect once the port opens.';

/**
 * The request an Add/Save or a Test Connection click actually sends, built fresh from the form's
 * current in-progress values every time — never from what is already saved, since Test Connection
 * has to validate what the user is about to save, before they save it.
 *
 * <p>Target-kind-irrelevant fields are left `undefined` rather than included with a stale value —
 * `JSON.stringify` drops an `undefined` property entirely, so an `EXTERNAL_ENDPOINT` save produces
 * exactly the request shape this form has always sent, a strict superset rather than a rewrite.
 */
function buildRequest(values: FormValues, headerRows: HeaderRow[]): EnvironmentRequest {
  const { headerNames, headerValues } = rowsToWire(headerRows);
  const request: EnvironmentRequest = {
    name: values.name,
    baseUrl: values.targetKind === 'EXTERNAL_ENDPOINT' ? values.baseUrl : '',
    type: values.type,
    dependencies: values.dependencies,
    productionLike: values.productionLike,
    headerNames: headerNames || undefined,
    headerValues: headerValues || undefined,
    targetKind: values.targetKind === 'EXTERNAL_ENDPOINT' ? undefined : values.targetKind,
  };

  if (values.targetKind === 'DOCKER_IMAGE') {
    request.image = values.image || undefined;
    request.containerPort = values.containerPort === '' ? undefined : values.containerPort;
    request.cpuMillicores =
      values.cpuCores === '' ? undefined : Math.round(values.cpuCores * 1000);
    request.memoryMebibytes = values.memoryMebibytes === '' ? undefined : values.memoryMebibytes;

    const hasReadiness =
      values.readinessPath.trim() !== '' &&
      values.readinessExpectedStatus !== '' &&
      values.readinessTimeoutSeconds !== '';
    if (hasReadiness) {
      request.readinessPath = values.readinessPath;
      request.readinessExpectedStatus = values.readinessExpectedStatus as number;
      request.readinessTimeoutSeconds = values.readinessTimeoutSeconds as number;
    }
  } else if (values.targetKind === 'DOCKER_COMPOSE') {
    request.composeFile = values.composeFile || undefined;
    request.composeService = values.composeService || undefined;
    request.containerPort = values.containerPort === '' ? undefined : values.containerPort;
  }

  return request;
}

function validate(values: FormValues): Record<string, string> {
  const errors: Record<string, string> = {};

  if (!values.name.trim()) {
    errors.name = 'A name is required';
  }

  if (values.targetKind === 'EXTERNAL_ENDPOINT' && !values.baseUrl.trim()) {
    errors.baseUrl = 'A target URL is required';
  }

  if (values.targetKind === 'DOCKER_IMAGE') {
    if (!values.image.trim()) {
      errors.image = 'An image is required, e.g. "payment-service:1.4.2"';
    }
    if (values.containerPort === '') {
      errors.containerPort = 'The port the container listens on is required';
    }
    const hasPath = values.readinessPath.trim() !== '';
    const hasStatus = values.readinessExpectedStatus !== '';
    const hasTimeout = values.readinessTimeoutSeconds !== '';
    const anySet = hasPath || hasStatus || hasTimeout;
    const allSet = hasPath && hasStatus && hasTimeout;
    if (anySet && !allSet) {
      if (!hasPath) errors.readinessPath = READINESS_ALL_OR_NOTHING_MESSAGE;
      if (!hasStatus) errors.readinessExpectedStatus = READINESS_ALL_OR_NOTHING_MESSAGE;
      if (!hasTimeout) errors.readinessTimeoutSeconds = READINESS_ALL_OR_NOTHING_MESSAGE;
    }
  }

  if (values.targetKind === 'DOCKER_COMPOSE') {
    if (!values.composeFile.trim()) {
      errors.composeFile = 'The Compose file this repository already owns is required';
    }
    if (!values.composeService.trim()) {
      errors.composeService = 'A service name is required';
    }
    if (values.containerPort === '') {
      errors.containerPort = 'The port the service listens on is required';
    }
  }

  return errors;
}

export interface EnvironmentDrawerState {
  mode: 'create' | 'edit';
  environment?: Environment;
}

/**
 * The single Add/Edit surface for an environment — a Mantine `Drawer`, matching the workspace's
 * established right-side-panel convention (`RequestDataDrawer`). Add and Edit share one form: the
 * only difference is what it starts from.
 */
export function EnvironmentDrawer({
  state,
  existingNames,
  environmentTypes,
  dependencyModes,
  serviceId,
  onClose,
}: {
  state: EnvironmentDrawerState | null;
  existingNames: string[];
  environmentTypes: EnvironmentOption[];
  dependencyModes: EnvironmentOption[];
  serviceId: string;
  onClose: () => void;
}) {
  return (
    <Drawer
      opened={state !== null}
      onClose={onClose}
      position="right"
      size={640}
      padding="xl"
      title={
        state?.mode === 'edit' && state.environment
          ? `Edit '${state.environment.name}'`
          : 'Add environment'
      }
    >
      {state && (
        // Keyed by what this instance edits, so switching from editing one environment to another
        // (or from edit to create) starts from that target's own values rather than carrying the
        // previous form's in-progress edits across — the same reason RequestDataDrawer keys its form.
        <EnvironmentForm
          key={state.mode === 'edit' ? state.environment?.name : 'create'}
          mode={state.mode}
          environment={state.environment}
          existingNames={existingNames}
          environmentTypes={environmentTypes}
          dependencyModes={dependencyModes}
          serviceId={serviceId}
          onClose={onClose}
        />
      )}
    </Drawer>
  );
}

function EnvironmentForm({
  mode,
  environment,
  existingNames,
  environmentTypes,
  dependencyModes,
  serviceId,
  onClose,
}: {
  mode: 'create' | 'edit';
  environment?: Environment;
  existingNames: string[];
  environmentTypes: EnvironmentOption[];
  dependencyModes: EnvironmentOption[];
  serviceId: string;
  onClose: () => void;
}) {
  const mutation = useAddEnvironmentMutation(serviceId);
  const validation = useValidateTargetMutation(serviceId);

  const form = useForm<FormValues>({
    initialValues: environment ? valuesFrom(environment) : CREATE_VALUES,
    validate,
  });
  const [headerRows, setHeaderRows] = useState<HeaderRow[]>(
    environment ? rowsFromMasked(environment.maskedHeaders) : []
  );
  const [collisionWarning, setCollisionWarning] = useState<string | null>(null);

  function submit(values: FormValues) {
    mutation.mutate(buildRequest(values, headerRows), {
      onSuccess: (response) => {
        notifications.show({ message: response.message, color: 'pass' });
        onClose();
      },
    });
  }

  function trySubmit(values: FormValues) {
    const target = values.name.trim().toLowerCase();
    const selfName = (environment?.name ?? '').toLowerCase();
    const collision = existingNames.find(
      (name) => name.toLowerCase() === target && name.toLowerCase() !== selfName
    );
    if (collision && collisionWarning !== collision) {
      setCollisionWarning(collision);
      return;
    }
    submit(values);
  }

  const serverError = extractErrorMessage(mutation, 'Something went wrong saving this environment.');
  const isDockerManaged = form.values.targetKind !== 'EXTERNAL_ENDPOINT';

  return (
    <form onSubmit={form.onSubmit(trySubmit)}>
      <Stack gap="lg">
        <div>
          <Text size="sm" fw={600} mb="xs" className={classes.groupLabel}>
            Identity
          </Text>
          <Group grow>
            <TextInput label="Name" placeholder="local" {...form.getInputProps('name')} />
            <Select
              label="Type"
              data={environmentTypes.map((t) => ({ value: t.name, label: t.label }))}
              {...form.getInputProps('type')}
            />
          </Group>
        </div>

        <div>
          <Text size="sm" fw={600} mb="xs" className={classes.groupLabel}>
            Execution target
          </Text>
          <Stack gap="sm">
            <SegmentedControl
              data={TARGET_KIND_OPTIONS}
              fullWidth
              {...form.getInputProps('targetKind')}
            />

            {form.values.targetKind === 'EXTERNAL_ENDPOINT' && (
              <TextInput
                label="Target URL"
                placeholder="http://localhost:8080"
                {...form.getInputProps('baseUrl')}
              />
            )}

            {form.values.targetKind === 'DOCKER_IMAGE' && (
              <Stack gap="sm">
                <Group grow>
                  <TextInput
                    label="Image"
                    placeholder="payment-service:1.4.2"
                    {...form.getInputProps('image')}
                  />
                  <NumberInput
                    label="Container port"
                    placeholder="8080"
                    min={1}
                    max={65535}
                    {...form.getInputProps('containerPort')}
                  />
                </Group>
                <Group grow>
                  <NumberInput
                    label="CPU (cores)"
                    description="e.g. 0.5 — converted to millicores before saving"
                    placeholder="0.5"
                    min={0}
                    step={0.1}
                    decimalScale={2}
                    {...form.getInputProps('cpuCores')}
                  />
                  <NumberInput
                    label="Memory (MiB)"
                    placeholder="512"
                    min={1}
                    {...form.getInputProps('memoryMebibytes')}
                  />
                </Group>
                <Text size="sm" fw={600}>
                  Readiness (optional)
                </Text>
                <Text size="xs" c="dimmed">
                  How Vortex decides the container is ready to receive load. The timeout covers the
                  service&rsquo;s whole cold start, on whatever CPU you allotted it above — a JVM
                  service on a fraction of a core routinely needs 30&nbsp;seconds or more before it
                  answers its first request, and a run that starts too early fails before it
                  measures anything.
                </Text>
                <Group grow align="flex-start">
                  <TextInput
                    label="Path"
                    placeholder="/actuator/health"
                    {...form.getInputProps('readinessPath')}
                  />
                  <NumberInput
                    label="Expected status"
                    placeholder="200"
                    min={100}
                    max={599}
                    {...form.getInputProps('readinessExpectedStatus')}
                  />
                  <NumberInput
                    label="Timeout (sec)"
                    placeholder="60"
                    min={1}
                    {...form.getInputProps('readinessTimeoutSeconds')}
                  />
                </Group>
              </Stack>
            )}

            {form.values.targetKind === 'DOCKER_COMPOSE' && (
              <Group grow align="flex-start">
                <TextInput
                  label="Compose file"
                  placeholder="compose.yaml"
                  {...form.getInputProps('composeFile')}
                />
                <TextInput label="Service" {...form.getInputProps('composeService')} />
                <NumberInput
                  label="Container port"
                  placeholder="8080"
                  min={1}
                  max={65535}
                  {...form.getInputProps('containerPort')}
                />
              </Group>
            )}

            {isDockerManaged && (
              <Group>
                <Button
                  type="button"
                  variant="default"
                  size="sm"
                  loading={validation.isPending}
                  onClick={() => validation.mutate(buildRequest(form.values, headerRows))}
                >
                  Test Connection
                </Button>
              </Group>
            )}

            {validation.data && (
              <Alert
                color={validation.data.valid ? 'pass' : 'fail'}
                title={validation.data.valid ? 'Connection checks passed' : 'Connection checks failed'}
              >
                <Stack gap={4}>
                  {validation.data.checks.map((check) => (
                    <Text key={check} size="sm">
                      {check}
                    </Text>
                  ))}
                </Stack>
              </Alert>
            )}
          </Stack>
        </div>

        <div>
          <Text size="sm" fw={600} mb="xs" className={classes.groupLabel}>
            Dependencies
          </Text>
          <Select
            data={dependencyModes.map((m) => ({ value: m.name, label: m.label }))}
            {...form.getInputProps('dependencies')}
          />
        </div>

        <HeaderRows rows={headerRows} onChange={setHeaderRows} />

        <div>
          <Text size="sm" fw={600} mb="xs" className={classes.groupLabel}>
            Production equivalence
          </Text>
          <Checkbox
            label="Sized and configured like production"
            {...form.getInputProps('productionLike', { type: 'checkbox' })}
          />
        </div>

        {collisionWarning && (
          <Alert color="warn" title="This name is already in use">
            Saving as &quot;{form.values.name.trim()}&quot; will replace the existing environment
            &quot;{collisionWarning}&quot; instead of {mode === 'create' ? 'creating a new one' : 'renaming this one'}.
          </Alert>
        )}
        {serverError && (
          <Text size="sm" c="fail">
            {serverError}
          </Text>
        )}

        <Group justify="flex-end">
          <Button type="button" variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={mutation.isPending}>
            {collisionWarning ? 'Save anyway' : 'Save'}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
