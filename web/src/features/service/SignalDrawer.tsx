import { Button, Drawer, Skeleton, Stack, Text } from '@mantine/core';
import { IconArrowRight } from '@tabler/icons-react';
import type { ReadinessItem } from '../../api/workspace';
import { useConfigurationQuery } from '../../api/configuration';
import { Unknown } from '../../components/Unknown';
import { EnvironmentsSection } from './configuration/EnvironmentsSection';
import { OperationsSection } from './configuration/OperationsSection';
import { ObjectivesSection } from './configuration/ObjectivesSection';
import { ProductionRealitySection } from './configuration/ProductionRealitySection';
import { TestComposer } from './TestComposer';
import classes from './SignalDrawer.module.css';

/**
 * One readiness item, configured without leaving the service.
 *
 * <p>Every panel below is the *same component the Configuration page renders* — `EnvironmentsSection`,
 * `OperationsSection`, `ObjectivesSection`, `ProductionRealitySection`, `TestComposer` — mounted here
 * instead of there. Nothing about validation, persistence or the shape of a form is restated: a
 * second implementation of "add an environment" is a second thing to keep correct, and the two would
 * disagree within a release. What this file owns is which panel answers which item, and the sentence
 * above it.
 *
 * <p>It does not report success. It does not need to: every one of those mutations invalidates the
 * service, the readiness that opened this drawer is refetched, and the item flips to satisfied. The
 * caller closes on that, which means the drawer closes on *persisted* state rather than on a
 * callback that fired optimistically.
 */
export function SignalDrawer({
  item,
  serviceId,
  items,
  opened,
  onClose,
  onOpenOther,
}: {
  item: ReadinessItem | null;
  serviceId: string;
  /** Every readiness item, so a blocked panel can offer the prerequisite that is in the way. */
  items: ReadinessItem[];
  opened: boolean;
  onClose: () => void;
  onOpenOther: (key: string) => void;
}) {
  // A workload is composed, not filled in — it needs the room the composer's own split layout wants.
  // A blocked panel is three lines and a button, and should not open a hall.
  const wide = item?.available !== false && (item?.key === 'WORKLOAD' || item?.key === 'AVERAGE_LOAD_WORKLOAD');

  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size={wide ? 'xl' : 560}
      title={
        <span className={classes.title}>
          {item?.label ?? ''}
          {/* The same rule the ring uses: optional means an answer survives without it, not merely
              that a run does. Objectives gate no run and decide every verdict. */}
          {item && !item.effectivelyRequired && (
            <span className={classes.optional}>optional</span>
          )}
        </span>
      }
    >
      {item &&
        (item.available ? (
          <>
            {/* The domain's own sentence for why this matters, and the only prose here. */}
            <Text size="sm" c="dimmed" className={classes.why}>
              {item.nextStep}
            </Text>
            <SignalPanel item={item} serviceId={serviceId} onClose={onClose} />
          </>
        ) : (
          <Blocked item={item} items={items} onOpenOther={onOpenOther} />
        ))}
    </Drawer>
  );
}

/**
 * What is in the way, and the way to it.
 *
 * <p>A blocked signal opens this instead of a form it cannot fill in. Two things it deliberately is
 * not: a dead end, because the prerequisite is right here as a button; and a scolding, because
 * nothing has gone wrong — this capability simply is not possible yet, and the sentence saying so is
 * the domain's own.
 */
function Blocked({
  item,
  items,
  onOpenOther,
}: {
  item: ReadinessItem;
  items: ReadinessItem[];
  onOpenOther: (key: string) => void;
}) {
  const prerequisites = items.filter((candidate) => item.blockedBy.includes(candidate.key));

  return (
    <Stack gap="md" className={classes.blocked}>
      <Text size="sm" className={classes.blockedWhat}>
        {item.label} is not available yet
      </Text>
      <Text size="sm" c="dimmed" className={classes.why}>
        {item.blockedReason}
      </Text>
      {prerequisites.length > 0 && (
        <div className={classes.prerequisites}>
          <span className={classes.prerequisitesLead}>Needs first</span>
          {prerequisites.map((prerequisite) => (
            <Button
              key={prerequisite.key}
              onClick={() => onOpenOther(prerequisite.key)}
              size="xs"
              variant="default"
              w="fit-content"
              leftSection={<IconArrowRight size={14} />}
            >
              {/* The label verbatim. These labels are states ("API imported", "Environment
                  configured"), and bending one into a verb phrase produces either "Configure aPI
                  imported" or "Configure environment configured" — the lead-in above carries the
                  verb so the label never has to. */}
              {prerequisite.label}
            </Button>
          ))}
        </div>
      )}
    </Stack>
  );
}

function SignalPanel({
  item,
  serviceId,
  onClose,
}: {
  item: ReadinessItem;
  serviceId: string;
  onClose: () => void;
}) {
  const { data, isError } = useConfigurationQuery(serviceId);

  // The composer fetches its own inputs and does not read this page's configuration payload.
  if (item.key === 'WORKLOAD' || item.key === 'AVERAGE_LOAD_WORKLOAD') {
    return <TestComposer serviceId={serviceId} mode="create" onClose={onClose} />;
  }

  if (isError) {
    return (
      <Unknown
        what="Could not load this service's configuration"
        reason={`/api/services/${serviceId}/configuration did not respond. Close this and try again.`}
      />
    );
  }

  if (!data) return <Skeleton height={260} radius="md" />;

  switch (item.key) {
    case 'ENVIRONMENT':
      return (
        <EnvironmentsSection
          serviceId={serviceId}
          environments={data.environments}
          environmentTypes={data.environmentTypes}
          dependencyModes={data.dependencyModes}
        />
      );
    case 'API_IMPORTED':
      return <OperationsSection serviceId={serviceId} catalog={data.catalog} />;
    case 'OBJECTIVES':
      return <ObjectivesSection serviceId={serviceId} thresholds={data.thresholds} />;
    case 'PRODUCTION_TRAFFIC':
      return (
        <ProductionRealitySection
          serviceId={serviceId}
          production={data.production}
          observationSource={data.observationSource}
          calibrationSuggestions={data.calibrationSuggestions}
          catalog={data.catalog}
        />
      );
    default:
      // A RESULT item has nothing to configure and never opens this drawer; anything else is a new
      // readiness item nobody wired up, and saying so beats rendering an empty panel.
      return (
        <Unknown
          what="Nothing to configure here"
          reason={item.nextStep}
          actionLabel="Open Configuration"
          actionHref={item.href}
        />
      );
  }
}
