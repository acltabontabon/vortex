import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Container,
  Group,
  List,
  NumberInput,
  SegmentedControl,
  Select,
  Skeleton,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCheck, IconX } from '@tabler/icons-react';
import {
  useChooseLoadGeneratorBudgetMutation,
  useChooseModelMutation,
  useRetryAiMutation,
  useSaveDynatraceMcpMutation,
  useSettingsQuery,
  useTestDynatraceMcpMutation,
} from '../api/settings';
import type {
  ChooseLoadGeneratorBudgetRequest,
  DynatraceMcpAvailability,
  DynatraceMcpSettings,
} from '../api/settings';
import { Fact, Facts } from '../components/Fact';
import { errorFallback, extractErrorMessage } from '../lib/queryFallback';
import classes from './SettingsPage.module.css';

/** "4 cores", "0.5 cores" — never a bare "4000" a reader has to convert. */
function formatCores(millicores: number | null): string {
  if (millicores === null) {
    return '—';
  }
  const cores = millicores / 1000;
  const rounded = Math.round(cores * 100) / 100;
  return `${rounded} core${rounded === 1 ? '' : 's'}`;
}

/** "4 GiB" above one gibibyte, "256 MiB" below it — matching how the result page already shows
 *  memory quantities. */
function formatMemory(bytes: number | null): string {
  if (bytes === null) {
    return '—';
  }
  const gib = bytes / 1024 ** 3;
  if (gib >= 1) {
    return `${Math.round(gib * 10) / 10} GiB`;
  }
  return `${Math.round(bytes / 1024 ** 2)} MiB`;
}

/**
 * What Vortex is configured to do, and the one setting worth changing from day to day.
 *
 * <p>Vortex reads its configuration from application.yaml, so it can be version-controlled and
 * shared with a team. This page shows what is in effect, plus which local AI model to use — the
 * one genuinely per-machine, day-to-day choice.
 */
export function SettingsPage() {
  const { data, isError } = useSettingsQuery();
  const retryAi = useRetryAiMutation();
  const chooseModel = useChooseModelMutation();
  const [selectedModel, setSelectedModel] = useState<string | null>(null);
  const chooseLoadGeneratorBudget = useChooseLoadGeneratorBudgetMutation();
  const [loadGeneratorMode, setLoadGeneratorMode] = useState<'automatic' | 'custom' | null>(null);
  const [customCpuCores, setCustomCpuCores] = useState<number | ''>('');
  const [customMemoryMebibytes, setCustomMemoryMebibytes] = useState<number | ''>('');

  const error = errorFallback(isError, 'Could not load settings',
      '/api/settings did not respond. Reload the page to try again.');
  if (error) {
    return (
      <Container size={820} px={0} py="xl">
        {error}
      </Container>
    );
  }

  if (!data) {
    return (
      <Container size={820} px={0} py="xl">
        <Skeleton height={400} radius="md" />
      </Container>
    );
  }

  const model = selectedModel ?? data.aiSettings.model;

  const loadGenerator = data.loadGenerator;
  const loadGeneratorModeValue = loadGeneratorMode ?? loadGenerator.configured.mode;
  // Starting point when Custom is first selected: the saved custom values if there are any,
  // otherwise what Automatic would currently choose — so the fields never open blank.
  const startingCpuMillicores =
    loadGenerator.configured.cpuMillicores ?? loadGenerator.automaticPreview.allocation.cpuMillicores;
  const startingMemoryMebibytes =
    loadGenerator.configured.memoryMebibytes ??
    (loadGenerator.automaticPreview.allocation.memoryBytes === null
      ? null
      : Math.round(loadGenerator.automaticPreview.allocation.memoryBytes / 1024 ** 2));
  const loadGeneratorCpuCores =
    customCpuCores !== '' ? customCpuCores : (startingCpuMillicores ?? 0) / 1000;
  const loadGeneratorMemoryMebibytes =
    customMemoryMebibytes !== '' ? customMemoryMebibytes : (startingMemoryMebibytes ?? '');
  const hostCores = loadGenerator.effective.detectedHost.availableProcessors;
  const hostMemoryBytes = loadGenerator.effective.detectedHost.totalMemoryBytes;
  const customLeavesLittleHeadroom =
    loadGeneratorModeValue === 'custom' &&
    ((hostCores > 0 && loadGeneratorCpuCores >= hostCores - 0.5) ||
      (hostMemoryBytes > 0 &&
        loadGeneratorMemoryMebibytes !== '' &&
        loadGeneratorMemoryMebibytes * 1024 ** 2 >= hostMemoryBytes * 0.9));

  function retry() {
    retryAi.mutate(undefined, {
      onSuccess: (response) => {
        notifications.show({
          message: response.message,
          color: response.succeeded ? 'pass' : 'fail',
        });
      },
    });
  }

  function saveModel() {
    chooseModel.mutate(model, {
      onSuccess: (response) => {
        notifications.show({ message: response.message, color: 'pass' });
      },
    });
  }

  function saveLoadGeneratorBudget() {
    const request: ChooseLoadGeneratorBudgetRequest =
      loadGeneratorModeValue === 'custom'
        ? {
            mode: 'custom',
            cpuMillicores: Math.round(loadGeneratorCpuCores * 1000),
            memoryMebibytes:
              loadGeneratorMemoryMebibytes === '' ? undefined : loadGeneratorMemoryMebibytes,
          }
        : { mode: 'automatic' };
    chooseLoadGeneratorBudget.mutate(request, {
      onSuccess: (response) => {
        notifications.show({ message: response.message, color: 'pass' });
        setLoadGeneratorMode(null);
        setCustomCpuCores('');
        setCustomMemoryMebibytes('');
      },
    });
  }

  return (
    <Container size={820} px={0} py="xl">
      <Stack gap="lg">
        <div>
          <Title order={1} size="h2">
            Settings
          </Title>
          <Text c="dimmed" size="sm" maw={640}>
            Vortex reads its configuration from <code>application.yaml</code>, so it can be
            version-controlled and shared with your team. This page shows what is in effect, and
            lets you change the settings that are genuinely per-machine, day-to-day choices: how
            much of this machine the load generator may use, and which local AI model to use.
          </Text>
        </div>

        <Card withBorder radius="md">
          <Title order={2} size="h4" mb="sm">
            Execution engine
          </Title>
          <Facts>
            <Fact label="Status">
              <Badge color={data.engineAvailability.available ? 'pass' : 'fail'}>
                {data.engineAvailability.available ? 'Available' : 'Not available'}
              </Badge>
            </Fact>
            <Fact label="Runner">{data.engine.usesDocker ? 'k6 in Docker' : 'Local k6 binary'}</Fact>
            <Fact label="Version">
              <span className={classes.mono}>
                {data.engineAvailability.available ? data.engineAvailability.version : '—'}
              </span>
            </Fact>
            {data.engine.usesDocker && (
              <Fact label="Image">
                <span className={classes.mono}>{data.engine.dockerImage}</span>
              </Fact>
            )}
          </Facts>

          {!data.engineAvailability.available && (
            <Alert color="warn" title={data.engineAvailability.problem} mt="md">
              <Text size="sm" style={{ whiteSpace: 'pre-line' }}>
                {data.engineAvailability.remedy}
              </Text>
            </Alert>
          )}

          <details className={classes.advanced}>
            <summary>Configuration keys</summary>
            <pre className={classes.configPre}>
              {`vortex:
  engine:
    runner: ${data.engine.runner}
    executable: ${data.engine.executable}
    docker-image: ${data.engine.dockerImage}
    compress-raw-metrics: ${data.engine.compressRawMetrics}`}
            </pre>
          </details>
        </Card>

        <Card withBorder radius="md">
          <Title order={2} size="h4" mb="sm">
            Load Generator Resources
          </Title>
          <Text size="sm" c="dimmed" mb="md">
            Controls how much of this machine&apos;s CPU and memory Vortex allows the load
            generator to use during a run. Automatic is recommended.
          </Text>

          <SegmentedControl
            value={loadGeneratorModeValue}
            onChange={(value) => setLoadGeneratorMode(value as 'automatic' | 'custom')}
            data={[
              { value: 'automatic', label: 'Automatic' },
              { value: 'custom', label: 'Custom' },
            ]}
            mb="md"
          />

          {loadGeneratorModeValue === 'automatic' ? (
            <>
              <Facts>
                <Fact label="Detected host">
                  {hostCores > 0
                    ? `${formatCores(hostCores * 1000)} / ${formatMemory(hostMemoryBytes || null)}`
                    : 'Not recorded'}
                </Fact>
                <Fact label="Allocated to load generator">
                  {formatCores(loadGenerator.effective.allocation.cpuMillicores)} /{' '}
                  {formatMemory(loadGenerator.effective.allocation.memoryBytes)}
                </Fact>
              </Facts>
              {loadGenerator.effective.colocatedWithManagedSut &&
                loadGenerator.effective.sutReserve.cpuMillicores !== null && (
                  <Text size="xs" c="dimmed" mt="sm">
                    Assumes a Vortex-managed system under test may share this machine, so part of
                    the host is reserved for it alongside the load generator.
                  </Text>
                )}
            </>
          ) : (
            <>
              <Group align="flex-end" gap="sm">
                <NumberInput
                  label="CPU"
                  description="cores"
                  min={0}
                  step={0.1}
                  decimalScale={2}
                  value={loadGeneratorCpuCores}
                  onChange={(value) => setCustomCpuCores(value === '' ? '' : Number(value))}
                  w={140}
                />
                <NumberInput
                  label="Memory"
                  description="MiB"
                  min={1}
                  value={loadGeneratorMemoryMebibytes}
                  onChange={(value) => setCustomMemoryMebibytes(value === '' ? '' : Number(value))}
                  w={140}
                />
              </Group>
              <Text size="xs" c="dimmed" mt="sm">
                Automatic would currently choose{' '}
                {formatCores(loadGenerator.automaticPreview.allocation.cpuMillicores)} /{' '}
                {formatMemory(loadGenerator.automaticPreview.allocation.memoryBytes)}.
              </Text>
              {customLeavesLittleHeadroom && (
                <Alert color="warn" mt="sm">
                  This leaves little headroom for the host and anything else running on it.
                </Alert>
              )}
            </>
          )}

          <Button
            size="sm"
            mt="md"
            onClick={saveLoadGeneratorBudget}
            loading={chooseLoadGeneratorBudget.isPending}
            disabled={
              loadGeneratorModeValue === 'custom' &&
              (loadGeneratorCpuCores <= 0 || loadGeneratorMemoryMebibytes === '')
            }
          >
            Save
          </Button>
        </Card>

        <Card withBorder radius="md">
          <Group justify="space-between" align="flex-start" mb="sm">
            <div>
              <Title order={2} size="h4">
                Local AI
              </Title>
              <Text size="sm" c="dimmed">
                Optional. Vortex onboards, executes, evaluates and reports without it.
              </Text>
            </div>
            <Button size="xs" variant="default" onClick={retry} loading={retryAi.isPending}>
              Test connection
            </Button>
          </Group>

          <Facts>
            <Fact label="Status">
              <Badge color={data.aiAvailability.available ? 'pass' : 'neutral'}>
                {data.aiAvailability.available ? 'Connected' : 'Unavailable'}
              </Badge>
            </Fact>
            <Fact label="Provider">{data.aiSettings.provider}</Fact>
            <Fact label="Endpoint">
              <span className={classes.mono}>{data.aiSettings.baseUrl}</span>
            </Fact>
          </Facts>

          {!data.aiAvailability.available && (
            <Alert color="neutral" title={data.aiAvailability.problem} mt="md">
              <Text size="sm" style={{ whiteSpace: 'pre-line' }}>
                {data.aiAvailability.remedy}
              </Text>
            </Alert>
          )}

          {data.installedModels.length > 0 ? (
            <Group align="flex-end" mt="md" gap="sm">
              <Select
                label="Model"
                description="Vortex is designed so a modest model is enough: it sends a small package of already-calculated evidence rather than raw output."
                data={[{ value: '', label: 'not selected' }, ...data.installedModels.map((m) => ({ value: m, label: m }))]}
                value={model}
                onChange={setSelectedModel}
                w={280}
              />
              <Button size="sm" onClick={saveModel} loading={chooseModel.isPending}>
                Save
              </Button>
            </Group>
          ) : (
            <Alert color="neutral" mt="md">
              No models found at <span className={classes.mono}>{data.aiSettings.baseUrl}</span>.
              Pull one with Ollama, for example <code>ollama pull qwen3:4b</code>, then reload this
              page.
            </Alert>
          )}

          <details className={classes.advanced}>
            <summary>What Vortex sends to the model</summary>
            <div className={classes.advancedBody}>
              <p>
                Requests go to the endpoint above and nowhere else. With Ollama running locally,
                that means the request does not leave this machine — though what a provider does
                with a request is a property of that provider, not something Vortex can guarantee
                for it.
              </p>
              <p>Each analysis request contains:</p>
              <ul>
                <li>the kind of test, its question, and the verdict Vortex already calculated</li>
                <li>latency percentiles, throughput, error rate and threshold outcomes</li>
                <li>per-stage behaviour for ramping workloads</li>
                <li>the names of your workloads and operations</li>
                <li>an explicit list of what was <em>not</em> measured</li>
              </ul>
              <p>
                It never contains raw engine output, request or response bodies, or resolved
                secrets. Full prompts are not logged unless <code>vortex.ai.log-prompts</code> is
                enabled, since they carry your service&apos;s operation names and measurements.
              </p>
            </div>
          </details>
        </Card>

        <DynatraceMcpCard settings={data.dynatraceMcp} availability={data.dynatraceMcpAvailability} />

        <Card withBorder radius="md">
          <Title order={2} size="h4" mb="sm">
            Docker
          </Title>
          <Text size="sm" c="dimmed" mb="sm">
            Optional. Needed only for containerised dependencies or the containerised load
            generator.
          </Text>
          <Facts>
            <Fact label="Status">
              <Badge color={data.labStatus.usable ? 'pass' : 'neutral'}>
                {data.labStatus.usable ? 'Available' : 'Unavailable'}
              </Badge>
            </Fact>
            <Fact label="Version">
              <span className={classes.mono}>{data.labStatus.version || '—'}</span>
            </Fact>
          </Facts>
          {data.labStatus.remedy && (
            <Text size="sm" c="dimmed" mt="md">
              {data.labStatus.remedy}
            </Text>
          )}
          <Text size="sm" c="dimmed" mt="md">
            This is what this machine can do. Which Compose file a service starts is configured
            per service, under its <a href="/">settings</a>.
          </Text>
        </Card>

        <Card withBorder radius="md">
          <Title order={2} size="h4" mb="sm">
            Workspace
          </Title>
          <Facts>
            <Fact label="Location">
              <span className={classes.mono}>{data.workspacePath}</span>
            </Fact>
            <Fact label="Vortex">{data.vortexVersion}</Fact>
            <Fact label="Interface">Bound to 127.0.0.1 — reachable from this machine only</Fact>
          </Facts>
          <Text size="xs" c="dimmed" mt="md">
            Vortex has no authentication and generates traffic on your behalf, so it listens on the
            loopback address. Exposing it to a network is a different deployment model and needs
            its own security review.
          </Text>
        </Card>
      </Stack>
    </Container>
  );
}

/**
 * The global Dynatrace MCP connection, shared by every service whose production observation is
 * configured to reach Dynatrace via MCP. Mirrors the "Local AI" card's shape: status, facts, a
 * form to change them, and a test-connection action — except the test reports a stage-by-stage
 * breakdown rather than one message, since the four things that can be wrong (the endpoint, the
 * credential, the server's tool, and Vortex's own request) each need a different remedy.
 */
function DynatraceMcpCard({
  settings,
  availability,
}: {
  settings: DynatraceMcpSettings;
  availability: DynatraceMcpAvailability;
}) {
  const save = useSaveDynatraceMcpMutation();
  const test = useTestDynatraceMcpMutation();

  const [enabled, setEnabled] = useState(settings.enabled);
  const [endpoint, setEndpoint] = useState(settings.endpoint);
  const [defaultWindow, setDefaultWindow] = useState(settings.defaultWindowDisplay);
  const [organization, setOrganization] = useState(settings.organization);

  function payload() {
    return { enabled, endpoint, defaultWindow, organization };
  }

  const organizationOptions = test.data?.organizationOptions ?? [];

  function onSave() {
    save.mutate(payload(), {
      onSuccess: (r) => notifications.show({ message: r.message, color: 'pass' }),
    });
  }

  const saveError = extractErrorMessage(save, 'Something went wrong saving Dynatrace MCP settings.');
  const testError = extractErrorMessage(test, 'Something went wrong testing the connection.');

  return (
    <Card withBorder radius="md">
      <Group justify="space-between" align="flex-start" mb="sm">
        <div>
          <Title order={2} size="h4">
            Dynatrace
          </Title>
          <Text size="sm" c="dimmed">
            Optional. Used to calibrate workloads from real production traffic over MCP.
          </Text>
        </div>
        <Badge color={availability.available ? 'pass' : 'neutral'}>
          {availability.available ? 'Connected' : 'Unavailable'}
        </Badge>
      </Group>

      <Facts>
        <Fact label="Endpoint">
          <span className={classes.mono}>{settings.endpoint || 'not configured'}</span>
        </Fact>
        <Fact label="Default window">{settings.defaultWindowDisplay}</Fact>
        <Fact label="Organization">
          <span className={classes.mono}>{settings.organization || 'auto-detect'}</span>
        </Fact>
      </Facts>

      {!availability.available && (
        <Alert color="neutral" title={availability.problem} mt="md">
          <Text size="sm">{availability.remedy}</Text>
        </Alert>
      )}

      <Select
        mt="md"
        label="Enabled"
        data={[{ value: 'yes', label: 'Yes' }, { value: 'no', label: 'No' }]}
        value={enabled ? 'yes' : 'no'}
        onChange={(v) => setEnabled(v === 'yes')}
      />

      <Stack gap="sm" mt="md">
        <TextInput
          label="Endpoint"
          placeholder="https://dynatrace-mcp.internal/mcp"
          value={endpoint}
          onChange={(e) => setEndpoint(e.currentTarget.value)}
        />
        <TextInput
          label="Default observation window"
          placeholder="30d"
          value={defaultWindow}
          onChange={(e) => setDefaultWindow(e.currentTarget.value)}
        />
      </Stack>

      <Alert color="neutral" title="Local bridge mode" mt="md">
        <Text size="sm">
          Vortex spawns <code>npx mcp-remote</code> locally and lets it sign in to Dynatrace itself —
          the first connection opens a browser to complete that login, and the session is reused
          after. This needs Node.js and a browser on the same machine as Vortex, so it only makes
          sense for a local, single-machine install.
        </Text>
      </Alert>

      {test.data && (
        <div style={{ marginTop: 'var(--mantine-spacing-md)' }}>
          <List spacing={4} size="sm" center>
            {test.data.stages.map((stage) => (
              <List.Item
                key={stage.stage}
                icon={
                  <ThemeIcon color={stage.succeeded ? 'pass' : 'fail'} size={18} radius="xl">
                    {stage.succeeded ? <IconCheck size={12} /> : <IconX size={12} />}
                  </ThemeIcon>
                }
              >
                {stage.stage}
                {!stage.succeeded && stage.detail && (
                  <Text size="xs" c="dimmed">
                    {stage.detail}
                  </Text>
                )}
              </List.Item>
            ))}
          </List>
        </div>
      )}

      {organizationOptions.length > 0 && (
        <Select
          mt="md"
          label="Organization"
          description="Dynatrace found multiple organizations for this endpoint — choose which one Vortex should query, then Save."
          placeholder="Pick an organization"
          data={organizationOptions}
          value={organization || null}
          onChange={(v) => setOrganization(v ?? '')}
        />
      )}
      {saveError && (
        <Text size="sm" c="fail" mt="md">
          {saveError}
        </Text>
      )}
      {testError && (
        <Text size="sm" c="fail" mt="md">
          {testError}
        </Text>
      )}

      <Group mt="md">
        <Button size="sm" onClick={onSave} loading={save.isPending}>
          Save
        </Button>
        <Button
          size="sm"
          variant="default"
          onClick={() => test.mutate(payload())}
          loading={test.isPending}
          disabled={!endpoint.trim()}
        >
          Test connection
        </Button>
      </Group>

      <details className={classes.advanced}>
        <summary>What Vortex sends</summary>
        <div className={classes.advancedBody}>
          <p>
            Vortex reaches the endpoint above through a locally-spawned <code>npx mcp-remote</code>{' '}
            bridge, which performs Dynatrace&apos;s own interactive OAuth itself — see the note above.
          </p>
          <p>
            Only the deterministic queries below travel to Dynatrace: throughput, request latency
            and failure rate for the entity mapped from a service&apos;s Configuration page. Vortex
            never asks Dynatrace or any AI tool to interpret, summarise or decide what a baseline
            means.
          </p>
        </div>
      </details>
    </Card>
  );
}
