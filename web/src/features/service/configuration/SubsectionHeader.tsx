import type { ReactNode } from 'react';
import { Group, Text } from '@mantine/core';
import classes from './SubsectionHeader.module.css';

/**
 * The one header shape all three parts of "Service definition" share — an eyebrow label with
 * optional meta text on the left, a single action on the right — so those actions (Re-import,
 * Add a dataset) land in the same visual slot instead of wherever each section happened to put them.
 */
export function SubsectionHeader({
  label,
  meta,
  action,
}: {
  label: string;
  meta?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <Group justify="space-between" align="center" wrap="nowrap" className={classes.header}>
      <Group gap={6} wrap="nowrap" align="baseline">
        <Text component="span" className={classes.eyebrow}>
          {label}
        </Text>
        {meta && (
          <Text size="xs" c="dimmed">
            {meta}
          </Text>
        )}
      </Group>
      {action}
    </Group>
  );
}
