import { useForm } from '@mantine/form';
import { Button, Group, NumberInput, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { ThresholdEdit } from '../../../api/configuration';
import { useSetThresholdsMutation } from '../../../api/configuration';
import { SectionDisclosure } from './SectionDisclosure';

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
      { onSuccess: (r) => notifications.show({ message: r.message, color: 'pass' }) }
    );
  }

  return (
    <SectionDisclosure
      id="objectives"
      title="Objectives"
      openByDefault={thresholds.describe.length === 0}
      state={
        thresholds.describe.length === 0 ? 'none set' : thresholds.describe.join(' · ')
      }
    >
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
        <Button type="submit" mt="sm" loading={mutation.isPending}>
          Save objectives
        </Button>
      </form>
    </SectionDisclosure>
  );
}
