import type { ReactNode } from 'react';
import { Button, Group, Text } from '@mantine/core';
import classes from './SummaryField.module.css';

/**
 * A fact with an Edit action, collapsed to `label: display [Edit]` until touched — the compact
 * read state the brief asks for, for the sections that are one simple value rather than a form.
 */
export function SummaryField({
  label,
  display,
  editing,
  onEdit,
  children,
}: {
  label: string;
  display: ReactNode;
  editing: boolean;
  onEdit: () => void;
  children: ReactNode;
}) {
  if (!editing) {
    return (
      <Group gap={6} wrap="nowrap" className={classes.row}>
        <Text size="sm" c="dimmed">
          {label}:
        </Text>
        <Text size="sm" fw={600}>
          {display}
        </Text>
        <Button size="compact-xs" variant="subtle" color="gray" onClick={onEdit} ml="auto">
          Edit
        </Button>
      </Group>
    );
  }

  return <div>{children}</div>;
}
