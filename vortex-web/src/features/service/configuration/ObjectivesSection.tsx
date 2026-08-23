import { useState } from 'react';
import { useForm } from '@mantine/form';
import { Button, Group, NumberInput, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { ThresholdEdit } from '../../../api/configuration';
import { useSetThresholdsMutation } from '../../../api/configuration';
import classes from './ObjectivesSection.module.css';

/**
 * The three overall objectives Configuration edits. The domain also supports per-operation
 * thresholds, but no form here has ever created or edited one — this is deliberately narrow,
 * matching what `understand-sections.html`'s objectives form always was.
 */
export function ObjectivesSection({
  serviceId,
  thresholds,
}: {
  serviceId: string;
  thresholds: ThresholdEdit;
}) {
  const [editing, setEditing] = useState(thresholds.describe.length === 0);
  const mutation = useSetThresholdsMutation(serviceId);
  const form = useForm({
    initialValues: {
      p95Millis: thresholds.p95Millis ?? 500,
      p99Millis: thresholds.p99Millis ?? 1000,
      errorPercent: thresholds.errorPercent ?? 1,
    },
  });

  function submit(values: typeof form.values) {
    mutation.mutate(
      { p95Millis: values.p95Millis, p99Millis: values.p99Millis, errorPercent: values.errorPercent },
      {
        onSuccess: (r) => {
          notifications.show({ message: r.message, color: 'pass' });
          setEditing(false);
        },
      }
    );
  }

  if (!editing) {
    return (
      <div>
        <div className={classes.cells}>
          <Cell label="p95 latency" value={thresholds.p95Millis ? `< ${thresholds.p95Millis} ms` : '—'} />
          <Cell label="p99 latency" value={thresholds.p99Millis ? `< ${thresholds.p99Millis} ms` : '—'} />
          <Cell
            label="Error rate"
            value={thresholds.errorPercent !== null ? `< ${thresholds.errorPercent}%` : '—'}
          />
        </div>
        <Button size="compact-xs" variant="subtle" color="gray" mt="xs" onClick={() => setEditing(true)}>
          Edit
        </Button>
      </div>
    );
  }

  return (
    <div>
      <Text size="sm" c="dimmed" mb="sm">
        Saving always replaces the whole set — leaving a field blank drops that objective rather
        than keeping whatever was there before.
      </Text>
      <form onSubmit={form.onSubmit(submit)}>
        <Group grow align="flex-end">
          <NumberInput label="p95 latency (ms)" min={1} {...form.getInputProps('p95Millis')} />
          <NumberInput label="p99 latency (ms)" min={1} {...form.getInputProps('p99Millis')} />
          <NumberInput
            label="Error rate (%)"
            min={0}
            max={100}
            step={0.01}
            {...form.getInputProps('errorPercent')}
          />
        </Group>
        <Group mt="sm">
          <Button type="submit" loading={mutation.isPending}>
            Save objectives
          </Button>
          {thresholds.describe.length > 0 && (
            <Button variant="default" onClick={() => setEditing(false)}>
              Cancel
            </Button>
          )}
        </Group>
      </form>
    </div>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div className={classes.cell}>
      <Text size="xs" c="dimmed" tt="uppercase">
        {label}
      </Text>
      <Text size="md" fw={600}>
        {value}
      </Text>
    </div>
  );
}
