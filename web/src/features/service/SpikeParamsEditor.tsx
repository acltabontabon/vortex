import { Group, NumberInput, Text } from '@mantine/core';

export interface SpikeParams {
  baseline: number;
  peak: number;
  holdBeforeMinutes: number;
  holdAtPeakMinutes: number;
}

/**
 * The four numbers a spike test needs — baseline, the level it jumps to, and how long it holds
 * before and at that peak. Deliberately not a stage editor: the jump and recovery themselves are
 * `SpikeShapes.TRANSITION`, a backend policy constant never exposed here, so a spike stays four
 * fields regardless of how the domain's stage list is actually built.
 */
export function SpikeParamsEditor({
  value,
  onChange,
}: {
  value: SpikeParams;
  onChange: (next: SpikeParams) => void;
}) {
  function set<K extends keyof SpikeParams>(key: K, next: number | string) {
    onChange({ ...value, [key]: typeof next === 'number' ? next : Number(next) || 0 });
  }

  return (
    <Group gap="md" align="flex-end" wrap="wrap">
      <NumberInput
        label="Baseline"
        description="requests/sec"
        min={0}
        step={0.1}
        w={130}
        value={value.baseline}
        onChange={(v) => set('baseline', v)}
      />
      <NumberInput
        label="Peak"
        description="requests/sec"
        min={0}
        step={0.1}
        w={130}
        value={value.peak}
        onChange={(v) => set('peak', v)}
      />
      <Text size="sm" c="dimmed" pb={6}>
        held for
      </Text>
      <NumberInput
        label="Hold before"
        description="minutes"
        min={0}
        step={0.5}
        w={120}
        value={value.holdBeforeMinutes}
        onChange={(v) => set('holdBeforeMinutes', v)}
      />
      <NumberInput
        label="Hold at peak"
        description="minutes"
        min={0}
        step={0.5}
        w={120}
        value={value.holdAtPeakMinutes}
        onChange={(v) => set('holdAtPeakMinutes', v)}
      />
    </Group>
  );
}
