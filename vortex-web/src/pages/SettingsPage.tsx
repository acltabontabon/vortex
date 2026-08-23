import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Container,
  Group,
  Select,
  Skeleton,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  useChooseModelMutation,
  useRetryAiMutation,
  useSettingsQuery,
} from '../api/settings';
import { Fact, Facts } from '../components/Fact';
import { errorFallback } from '../lib/queryFallback';
import classes from './SettingsPage.module.css';

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
            lets you change the one setting that&apos;s genuinely a per-machine, day-to-day choice:
            which local AI model to use.
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
