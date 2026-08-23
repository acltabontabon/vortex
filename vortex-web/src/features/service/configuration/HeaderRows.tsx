import { ActionIcon, Button, Group, Text, TextInput } from '@mantine/core';
import { IconPlus, IconX } from '@tabler/icons-react';
import { emptyRow, SECRET_MASK, type HeaderRow } from './headerRowUtils';
import classes from './HeaderRows.module.css';

/**
 * Structured name/value rows for an environment's request headers, replacing two paired
 * free-text textareas ("one per line", paired by position) with something a person can actually
 * scan and edit one header at a time.
 *
 * <p>A masked row can't safely show its stored value — Vortex never sends one back — so it renders
 * a placeholder with a "Replace" action instead of a blank the user could mistake for "no value".
 * Left untouched, it submits the mask string unchanged, which the server resolves back to the
 * real value rather than overwriting it.
 */
export function HeaderRows({
  rows,
  onChange,
}: {
  rows: HeaderRow[];
  onChange: (rows: HeaderRow[]) => void;
}) {
  function update(id: string, patch: Partial<HeaderRow>) {
    onChange(rows.map((row) => (row.id === id ? { ...row, ...patch } : row)));
  }

  function remove(id: string) {
    onChange(rows.filter((row) => row.id !== id));
  }

  return (
    <div>
      <Text size="sm" fw={600} mb={4}>
        Request headers
      </Text>
      {rows.length === 0 && (
        <Text size="xs" c="dimmed" mb="xs">
          None configured.
        </Text>
      )}
      {rows.length > 0 && (
        <div className={classes.rows}>
          {rows.map((row) => (
            <Group key={row.id} gap="xs" wrap="nowrap" className={classes.row}>
              <TextInput
                placeholder="Header name"
                value={row.name}
                onChange={(e) => update(row.id, { name: e.currentTarget.value })}
                className={classes.name}
              />
              {row.masked ? (
                <Group gap={6} wrap="nowrap" className={classes.value}>
                  <Text size="sm" c="dimmed" ff="monospace">
                    {SECRET_MASK}
                  </Text>
                  <Button
                    size="compact-xs"
                    variant="subtle"
                    onClick={() => update(row.id, { value: '', masked: false })}
                  >
                    Replace
                  </Button>
                </Group>
              ) : (
                <TextInput
                  placeholder={'Value, or ${NAME} for a secret'}
                  value={row.value}
                  onChange={(e) => update(row.id, { value: e.currentTarget.value })}
                  className={classes.value}
                />
              )}
              <ActionIcon
                variant="subtle"
                color="gray"
                aria-label={`Remove header ${row.name || ''}`.trim()}
                onClick={() => remove(row.id)}
              >
                <IconX size={14} />
              </ActionIcon>
            </Group>
          ))}
        </div>
      )}
      <Button
        size="compact-xs"
        variant="subtle"
        mt={rows.length > 0 ? 'xs' : 0}
        leftSection={<IconPlus size={12} />}
        onClick={() => onChange([...rows, emptyRow()])}
      >
        Add header
      </Button>
    </div>
  );
}
