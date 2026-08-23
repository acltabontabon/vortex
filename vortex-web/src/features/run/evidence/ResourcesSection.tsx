import { Alert, Text, Title } from '@mantine/core';
import type { ResourceSignal, Resources } from '../../../api/run';
import { Unknown } from '../../../components/Unknown';
import { UtilizationBar } from '../../../components/charts/UtilizationBar';
import shared from './shared.module.css';
import classes from './ResourcesSection.module.css';

/**
 * CPU and memory, split into two unmistakably separate groups — the system under test and the load
 * generator — because reading one system's resource as the other's is the single most damaging
 * confusion a resource section can produce, and this page never lets a merged table make that mistake
 * possible.
 */
export function ResourcesSection({ resources }: { resources: Resources }) {
  const generatorSaturated = resources.generator.filter((s) => s.atItsLimit);

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
              what="The machine generating the traffic was not observed"
              reason="That is not evidence it kept up: whether the offered load was actually produced is unknown for this run."
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
