import { Tooltip } from '@mantine/core';
import type { Classification } from '../api/workspace';
import classes from './ClassificationChip.module.css';

/**
 * What class of question a run against this target can answer.
 *
 * <p>An isolated run cannot establish production capacity however fast it was, so the figure and
 * its caveat have to travel together — otherwise "1000 requests/sec against mocks" ends up on a
 * slide as production capacity. The caveat is the domain's own sentence, carried in the tooltip
 * rather than reworded here.
 */
export function ClassificationChip({
  classification,
  label,
  caveat,
}: {
  classification: Classification;
  label: string;
  caveat?: string;
}) {
  const chip = (
    <span
      className={`${classes.chip} ${
        classification === 'INTEGRATED' ? classes.integrated : ''
      }`}
    >
      {label}
    </span>
  );

  return caveat ? (
    <Tooltip label={caveat} openDelay={300} withArrow multiline maw={340}>
      {chip}
    </Tooltip>
  ) : (
    chip
  );
}
