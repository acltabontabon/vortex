import { useForm } from '@mantine/form';
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Group,
  NumberInput,
  Select,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { Environment, EnvironmentOption, EnvironmentRequest } from '../../../api/configuration';
import { useAddEnvironmentMutation, useValidateTargetMutation } from '../../../api/configuration';
import { Fact, Facts } from '../../../components/Fact';
import { extractErrorMessage } from '../../../lib/queryFallback';
import { SectionDisclosure } from './SectionDisclosure';

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
  headerNames: string;
  headerValues: string;
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

const INITIAL_VALUES: FormValues = {
  name: 'local',
  baseUrl: '',
  type: 'LOCAL_ISOLATED',
  dependencies: 'MOCKED',
  headerNames: '',
  headerValues: '',
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

const READINESS_ALL_OR_NOTHING_MESSAGE =
  'A readiness check needs a path, expected status and timeout together, or none of them — leave ' +
  'all three blank to fall back to a plain TCP connect once the port opens.';

/**
 * The request an add-environment or a Test Connection click actually sends, built fresh from the
 * form's current in-progress values every time — never from what is already saved, since Test
 * Connection has to validate what the user is about to save, before they save it.
 *
 * <p>Target-kind-irrelevant fields are left `undefined` rather than included with a stale value —
 * `JSON.stringify` drops an `undefined` property entirely, so an `EXTERNAL_ENDPOINT` save produces
 * exactly the request shape this form has always sent, a strict superset rather than a rewrite.
 */
function buildRequest(values: FormValues): EnvironmentRequest {
  const request: EnvironmentRequest = {
    name: values.name,
    baseUrl: values.targetKind === 'EXTERNAL_ENDPOINT' ? values.baseUrl : '',
    type: values.type,
    dependencies: values.dependencies,
    productionLike: values.productionLike,
    headerNames: values.headerNames || undefined,
    headerValues: values.headerValues || undefined,
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

export function EnvironmentsSection({
  serviceId,
  environments,
  environmentTypes,
  dependencyModes,
}: {
  serviceId: string;
  environments: Environment[];
  environmentTypes: EnvironmentOption[];
  dependencyModes: EnvironmentOption[];
}) {
  const mutation = useAddEnvironmentMutation(serviceId);
  const validation = useValidateTargetMutation(serviceId);

  const form = useForm<FormValues>({
    initialValues: INITIAL_VALUES,
    validate,
  });

  function submit(values: FormValues) {
    mutation.mutate(buildRequest(values), {
      onSuccess: (response) => {
        notifications.show({ message: response.message, color: 'pass' });
        form.setValues({
          ...form.values,
          baseUrl: '',
          headerNames: '',
          headerValues: '',
          image: '',
          containerPort: '',
          cpuCores: '',
          memoryMebibytes: '',
          readinessPath: '',
          readinessExpectedStatus: '',
          readinessTimeoutSeconds: '',
          composeFile: '',
          composeService: '',
        });
        validation.reset();
      },
    });
  }

  const serverError = extractErrorMessage(mutation, 'Something went wrong saving this environment.');
  const isDockerManaged = form.values.targetKind !== 'EXTERNAL_ENDPOINT';

  return (
    <SectionDisclosure
      id="environments"
      title="Environments"
      openByDefault={environments.length === 0}
      state={environments.length === 0 ? 'none configured' : `${environments.length} configured`}
    >
      <Stack gap="lg">
        {environments.length > 0 && (
          <Stack gap="sm">
            {environments.map((env) => (
              <div key={env.name}>
                <Facts>
                  <Fact label={env.name}>{env.baseUrl}</Fact>
                  <Fact label="Type">{env.typeLabel}</Fact>
                  <Fact label="Dependencies">{env.dependencyModeLabel}</Fact>
                  <Fact label="Classification">
                    <Badge size="sm" variant="light">
                      {env.classificationLabel}
                    </Badge>
                  </Fact>
                  <Fact label="Target">{env.target.summary}</Fact>
                  {env.hasSecretReferences && (
                    <Fact label="Headers">
                      {Object.entries(env.maskedHeaders)
                        .map(([k, v]) => `${k}: ${v}`)
                        .join('  ')}
                    </Fact>
                  )}
                </Facts>
              </div>
            ))}
          </Stack>
        )}

        {serverError && (
          <Text size="sm" c="fail">
            {serverError}
          </Text>
        )}

        <form onSubmit={form.onSubmit(submit)}>
          <Stack gap="sm">
            <Group grow>
              <TextInput label="Name" placeholder="local" {...form.getInputProps('name')} />
              <Select
                label="Type"
                data={environmentTypes.map((t) => ({ value: t.name, label: t.label }))}
                {...form.getInputProps('type')}
              />
            </Group>

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
                    placeholder="10"
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

            <Select
              label="Dependencies"
              data={dependencyModes.map((m) => ({ value: m.name, label: m.label }))}
              {...form.getInputProps('dependencies')}
            />

            <Group grow align="flex-start">
              <Textarea
                label="Header names"
                description="one per line"
                minRows={2}
                {...form.getInputProps('headerNames')}
              />
              <Textarea
                label="Header values"
                description="paired by line, ${NAME} for secrets"
                minRows={2}
                {...form.getInputProps('headerValues')}
              />
            </Group>
            <Checkbox
              label="Sized and configured like production"
              {...form.getInputProps('productionLike', { type: 'checkbox' })}
            />

            {isDockerManaged && (
              <Group>
                <Button
                  type="button"
                  variant="default"
                  size="sm"
                  loading={validation.isPending}
                  onClick={() => validation.mutate(buildRequest(form.values))}
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

            <Group>
              <Button type="submit" size="sm" loading={mutation.isPending}>
                Save environment
              </Button>
            </Group>
          </Stack>
        </form>
      </Stack>
    </SectionDisclosure>
  );
}
