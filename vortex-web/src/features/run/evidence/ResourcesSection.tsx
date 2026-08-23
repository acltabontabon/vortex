import { Alert, Text, Title } from '@mantine/core';
import type { ResourceSignal, Resources } from '../../../api/run';
import { Unknown } from '../../../components/Unknown';
import { UtilizationBar } from '../../../components/charts/UtilizationBar';
import shared from './shared.module.css';
import classes from './ResourcesSection.module.css';

/**
 * CPU and memory, split into unmistakably separate groups — the system under test, the load
 * generator's own process or container, and (as supporting, machine-wide context) the load
 * generator's host — because reading one system's resource as another's is the single most damaging
 * confusion a resource section can produce, and this page never lets a merged table make that mistake
 * possible.
 */
export function ResourcesSection({ resources }: { resources: Resources }) {
  const generatorSaturated = resources.generator.filter((s) => s.atItsLimit);
  const generatorHostUnderPressure = resources.generatorHost.filter((s) => s.atItsLimit);

  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Resources
      </Title>

      <div className={classes.groups}>
        <div className={classes.group}>
          <Text size="xs" c="dimmed" tt="uppercase" fw={600} mb={4}>
            System under test
          </Text>
          {resources.service.length > 0 ? (
            <ResourceList signals={resources.service} />
          ) : (
            <Unknown
              compact
              what="No resource signal describing the service was classified"
              reason="Nothing here can say whether the service ran out of anything. Configure an observability provider to find out."
            />
          )}
        </div>

        <div className={classes.group}>
          <Text size="xs" c="dimmed" tt="uppercase" fw={600} mb={4}>
            Load generator
          </Text>
          {resources.generator.length > 0 ? (
            <ResourceList signals={resources.generator} />
          ) : (
            <Unknown
              compact
              what="The generator's own process or container was not observed"
              reason="That is not evidence it kept up: whether the offered load was actually produced is unknown for this run."
            />
          )}
        </div>

        <div className={classes.groupMuted}>
          <Text size="xs" c="dimmed" tt="uppercase" fw={600} mb={4}>
            Load generator host
          </Text>
          {resources.generatorHost.length > 0 ? (
            <ResourceList signals={resources.generatorHost} />
          ) : (
            <Unknown
              compact
              what="The machine running the load generator was not observed"
              reason="Supporting context only — its absence says nothing about whether the generator's own process or container kept up."
            />
          )}
        </div>
      </div>

      {generatorSaturated.length > 0 && (
        <Alert color="warn" title="Load generator pressure" mt="sm">
          {generatorSaturated.map((s) => s.name).join(', ')} at{' '}
          {generatorSaturated.map((s) => s.utilisationDisplay).join(', ')} of limit. High
          load-generator utilisation may reduce confidence that the requested workload can continue to
          be generated accurately.
        </Alert>
      )}

      {generatorHostUnderPressure.length > 0 && (
        <Alert color="warn" variant="light" title="Load generator host pressure" mt="sm">
          {generatorHostUnderPressure.map((s) => s.name).join(', ')} at{' '}
          {generatorHostUnderPressure.map((s) => s.utilisationDisplay).join(', ')} of limit. This is the
          whole machine running the generator, not its own process or container — it qualifies
          confidence in this run rather than proving the generator itself was constrained.
        </Alert>
      )}
    </section>
  );
}

function ResourceList({ signals }: { signals: ResourceSignal[] }) {
  return (
    <div className={shared.table}>
      {signals.map((signal) => (
        <div key={signal.id} className={classes.row}>
          <div className={classes.name}>
            <Text size="sm">{signal.name}</Text>
            <Text size="xs" c="dimmed">
              {signal.kindLabel}
            </Text>
          </div>
          <Text size="sm" className={classes.display}>
            {signal.display}
            {signal.limitDisplay && <span className={shared.dim}> / {signal.limitDisplay}</span>}
          </Text>
          <UtilizationBar fraction={signal.utilisationFraction} atLimit={signal.atItsLimit} />
          <Text size="xs" className={signal.atItsLimit ? shared.fail : shared.dim}>
            {signal.utilisationDisplay
              ? `${signal.utilisationDisplay} of limit${signal.atItsLimit ? ' — at limit' : ''}`
              : 'no limit published'}
          </Text>
        </div>
      ))}
    </div>
  );
}
