import { Popover, Text, Button } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import type { Readiness } from '../../api/workspace';
import classes from './ReadinessPill.module.css';

/**
 * Readiness as a compact state, not a page.
 *
 * <p>It used to be a permanent checklist at the top of the service's landing screen — useful
 * information that consumed the structure of the page and made setup look like the destination.
 * Here it is one pill, and the checklist opens behind it.
 *
 * <p>Two things it will not do. It will not become a score: turning engineering readiness into a
 * percentage invites optimising the percentage. And it will not flatten the required/advisory
 * split — "a test will still run without objectives, what changes is what the result can conclude"
 * is a different statement from "you cannot run yet", and only two of the seven items make the
 * second one.
 */
export function ReadinessPill({ readiness }: { readiness: Readiness }) {
  const [opened, handlers] = useDisclosure(false);
  const blocked = !readiness.canRun;

  const summary = blocked
    ? `${readiness.blockerCount} thing${readiness.blockerCount === 1 ? '' : 's'} need attention`
    : 'Ready';

  return (
    <Popover
      opened={opened}
      onChange={handlers.toggle}
      position="bottom-end"
      width={430}
      shadow="md"
      withArrow
    >
      <Popover.Target>
        <button
          type="button"
          className={`${classes.pill} ${blocked ? classes.blocked : ''}`}
          onClick={handlers.toggle}
          aria-expanded={opened}
        >
          <span
            className={classes.dot}
            style={{
              background: blocked
                ? 'var(--mantine-color-warn-6)'
                : 'var(--mantine-color-pass-6)',
            }}
            aria-hidden="true"
          />
          {summary}
        </button>
      </Popover.Target>

      <Popover.Dropdown>
        <Text size="xs" c="dimmed" mb="xs">
          {readiness.satisfiedCount} of {readiness.totalCount} in place.{' '}
          {blocked
            ? 'A test cannot run until the required items are done.'
            : 'The rest make the evidence stronger rather than making it possible.'}
        </Text>

        <ul className={classes.items}>
          {readiness.items.map((item) => (
            <li
              key={item.label}
              className={`${classes.item} ${item.satisfied ? classes.done : ''}`}
            >
              <span className={classes.mark} aria-hidden="true">
                {item.satisfied ? '✓' : '○'}
              </span>
              <div className={classes.itemBody}>
                <div className={classes.itemLabel}>
                  {item.label}
                  {!item.requiredToRun && (
                    <span className={classes.optional}>optional</span>
                  )}
                  <span className="visually-hidden">
                    {item.satisfied ? ' satisfied' : ' still to do'}
                  </span>
                </div>
                {/* The domain's own instruction, never reworded here. */}
                {!item.satisfied && (
                  <div className={classes.next}>{item.nextStep}</div>
                )}
              </div>
              {!item.satisfied && (
                <Button
                  component="a"
                  href={item.href}
                  size="compact-xs"
                  variant="default"
                  className={classes.fix}
                >
                  Fix
                </Button>
              )}
            </li>
          ))}
        </ul>
      </Popover.Dropdown>
    </Popover>
  );
}
