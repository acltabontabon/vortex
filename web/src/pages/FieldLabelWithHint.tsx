import { Group, Text, Tooltip } from '@mantine/core';
import { IconHelpCircle } from '@tabler/icons-react';

/** A field label with its explanation tucked behind a hover icon, instead of always-on prose. */
export function FieldLabelWithHint({ text, hint }: { text: string; hint: string }) {
  return (
    <Group gap={6} wrap="nowrap">
      <Text component="span" size="sm">
        {text}
      </Text>
      <Tooltip label={hint} openDelay={200} withArrow multiline maw={320}>
        <IconHelpCircle
          size={14}
          stroke={1.75}
          style={{ color: 'var(--mantine-color-dimmed)', cursor: 'help' }}
        />
      </Tooltip>
    </Group>
  );
}
