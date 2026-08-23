import { Alert, Card, Container, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useRuntimeRefreshQuery } from '../app-shell/api';
import type { RuntimeCheck } from '../app-shell/api';
import classes from './RuntimePage.module.css';

/**
 * What Vortex can currently do on this machine.
 *
 * <p>Called Runtime rather than Diagnostics, because "diagnostics" is where you go when something
 * has already gone wrong, and most of what this page reports is simply the state of the toolchain.
 *
 * <p>Deliberately does not open with a single word for all of it. "Ready" would have to cover five
 * capabilities of which two are optional, and none of them says anything about whether the service
 * you are about to test can be reached — that question has a different answer per environment and
 * is answered in that service's own workspace.
 */
export function RuntimePage() {
  const { data, isError } = useRuntimeRefreshQuery();

  if (isError) {
    return (
      <Container size={760} px={0} py="xl">
        <Alert color="fail" title="Could not check the runtime">
          /api/runtime/refresh did not respond. Reload the page to try again.
        </Alert>
      </Container>
    );
  }

  if (!data) {
    return (
      <Container size={760} px={0} py="xl">
        <Skeleton height={400} radius="md" />
      </Container>
    );
  }

  const required = data.checks.filter((check) => check.required);
  const optional = data.checks.filter((check) => !check.required);

  return (
    <Container size={760} px={0} py="xl">
      <Stack gap="lg">
        <div>
          <Title order={1} size="h2">
            Runtime
          </Title>
          <Text c="dimmed" size="sm" maw={620}>
            What Vortex can see on this machine. Most trouble getting started is environmental,
            and this page exists so you never have to guess which piece is missing.
          </Text>
        </div>

        <Alert color={data.requirementsMet ? 'pass' : 'warn'} title={
          data.requirementsMet
            ? 'Everything Vortex requires is present'
            : 'Something Vortex requires is missing'
        }>
          <Text size="sm">
            {data.requirementsMet
              ? 'Optional tools that are absent are listed below. Vortex works without them — they add capability rather than enabling it.'
              : 'The required items below have to be resolved before a test can run.'}
          </Text>
        </Alert>

        <section>
          <Title order={2} size="h4" mb="sm">
            Required to run a test
          </Title>
          <Card withBorder radius="md" p={0}>
            <CheckTable checks={required} missingLabel="Missing" missingColor="fail" />
          </Card>
        </section>

        <section>
          <Title order={2} size="h4" mb={4}>
            Optional
          </Title>
          <Text size="sm" c="dimmed" mb="sm">
            Absent is not a problem. Each of these adds something when it is there, and costs
            nothing when it is not.
          </Text>
          <Card withBorder radius="md" p={0}>
            <CheckTable checks={optional} missingLabel="Not installed" missingColor="neutral" />
            <div className={classes.footer}>
              The same checks run from the command line: <code>vortex doctor</code>
            </div>
          </Card>
        </section>

        <Text size="sm">
          <a href="/settings">Settings</a> — where the load generator, the workspace and the local
          model are configured.
        </Text>
      </Stack>
    </Container>
  );
}

function CheckTable({
  checks,
  missingLabel,
  missingColor,
}: {
  checks: RuntimeCheck[];
  missingLabel: string;
  missingColor: 'fail' | 'neutral';
}) {
  return (
    <div className={classes.table}>
      {checks.map((check) => (
        <div key={check.name} className={classes.row}>
          <span className={`${classes.status} ${classes[check.ok ? 'pass' : missingColor]}`}>
            {check.ok ? 'Present' : missingLabel}
          </span>
          <span className={classes.name}>{check.name}</span>
          <div className={classes.detail}>
            <span>{check.detail}</span>
            {check.remedy && <div className={classes.remedy}>{check.remedy}</div>}
          </div>
        </div>
      ))}
    </div>
  );
}
