import { Drawer, SimpleGrid, Stack, Text } from '@mantine/core';
import type { TestRow as Test } from '../../api/workspace';
import { provenanceLine, shortRate } from '../../lib/testState';
import { TrafficDistribution } from '../../components/TrafficDistribution';
import classes from './TestDetailsDrawer.module.css';

/**
 * A test's definition — everything about it that is not its result.
 *
 * <p>Used to live permanently on the row: an inline "Traffic distribution" `Collapse` toggle, plus a
 * full provenance sentence printed beneath the shape line on every load. Neither is a decision input
 * — nobody chooses whether to run a test based on its exact operation count or the arithmetic behind
 * a derived level — so both move here, reached from the row's overflow menu, alongside the
 * definition and workload facts the row itself now only summarizes.
 *
 * <p>This drawer used to open with the test's latest result (verdict, answer, p95) as well — that
 * moved to the inspector below the Tests list, which is now the one canonical home for a test's
 * *result*. Nothing here duplicates it; this panel is definition only.
 *
 * <p>An inspection panel, not a settings form: sections are separated by space and a small caps
 * label, never a ruled line — a divider earns its place by marking an actual boundary, and there
 * isn't one between "what this test asks" and "what it sends".
 */
export function TestDetailsDrawer({
  test,
  opened,
  onClose,
}: {
  test: Test;
  opened: boolean;
  onClose: () => void;
}) {
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size={480}
      title={
        <span className={classes.title}>
          <span className={classes.name}>{test.name}</span>
          <span className={classes.type}>{test.testTypeLabel}</span>
        </span>
      }
      padding="xl"
    >
      <Stack gap="xl">
        <div>
          <p className={classes.question}>{test.question}</p>
          {test.description && (
            <Text size="sm" c="dimmed" mt={4}>
              {test.description}
            </Text>
          )}
        </div>

        <div>
          <div className={classes.sectionLabel}>Workload</div>
          <SimpleGrid cols={2} spacing="lg" verticalSpacing="md">
            <MiniFact value={shortRate(test.levelDisplay)} label={test.modelLabel} />
            <MiniFact value={test.durationDisplay} label="Duration" />
            {test.environmentName && <MiniFact value={test.environmentName} label="Environment" />}
            <MiniFact
              value={String(test.operationCount)}
              label={test.operationCount === 1 ? 'Operation' : 'Operations'}
            />
          </SimpleGrid>
        </div>

        {test.composition.length > 0 && (
          <div>
            <div className={classes.sectionLabel}>Traffic</div>
            <TrafficDistribution
              rows={test.composition}
              concurrency={test.model === 'CLOSED'}
              drift={test.compositionDrift}
            />
          </div>
        )}

        <div>
          <div className={classes.sectionLabel}>Source</div>
          <Text size="sm">{provenanceLine(test)}</Text>
          {test.source.derivation && (
            <Text size="xs" c="dimmed" mt={4} className={classes.derivation}>
              {test.source.derivation}
            </Text>
          )}
        </div>
      </Stack>
    </Drawer>
  );
}

function MiniFact({ value, label }: { value: string; label: string }) {
  return (
    <div>
      <div className={classes.factValue}>{value}</div>
      <div className={classes.factLabel}>{label}</div>
    </div>
  );
}
