import { useState } from 'react';
import { Button, Group, NumberInput, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { Catalog } from '../../../api/configuration';
import { useRecordProductionMutation } from '../../../api/configuration';
import { extractErrorMessage } from '../../../lib/queryFallback';

/** A manual "ballpark number" entry for production traffic — no observation source required, since
 *  a service that isn't in production yet still has an expectation worth recording. */
export function RecordedTrafficPanel({
  serviceId,
  catalog,
  onSaved,
}: {
  serviceId: string;
  catalog: Catalog;
  onSaved: () => void;
}) {
  const recordMutation = useRecordProductionMutation(serviceId);
  const [peakRate, setPeakRate] = useState<number | ''>('');
  const [averageRate, setAverageRate] = useState<number | ''>('');
  const [p95Rate, setP95Rate] = useState<number | ''>('');
  const [source, setSource] = useState('');
  const [note, setNote] = useState('');
  const [weights, setWeights] = useState<Record<string, number>>({});

  const recordError = extractErrorMessage(recordMutation, 'Something went wrong recording production traffic.');

  function onRecord() {
    if (peakRate === '') return;
    recordMutation.mutate(
      {
        peakRate: Number(peakRate),
        averageRate: averageRate === '' ? undefined : Number(averageRate),
        p95ObservedRate: p95Rate === '' ? undefined : Number(p95Rate),
        mixOperation: catalog.operations.map((op) => op.id),
        mixWeight: catalog.operations.map((op) => weights[op.id] ?? 0),
        source: source || undefined,
        note: note || undefined,
      },
      {
        onSuccess: (r) => {
          notifications.show({ message: r.message, color: 'pass' });
          onSaved();
        },
      }
    );
  }

  return (
    <div>
      {recordError && (
        <Text size="sm" c="fail" mb="xs">
          {recordError}
        </Text>
      )}
      <Group grow>
        <NumberInput
          label="Peak rate (req/sec)"
          required
          min={0}
          step={0.1}
          value={peakRate}
          onChange={(v) => setPeakRate(typeof v === 'number' ? v : '')}
        />
        <NumberInput
          label="Average rate (req/sec)"
          min={0}
          step={0.1}
          value={averageRate}
          onChange={(v) => setAverageRate(typeof v === 'number' ? v : '')}
        />
        <NumberInput
          label="p95 rate (req/sec)"
          min={0}
          step={0.1}
          value={p95Rate}
          onChange={(v) => setP95Rate(typeof v === 'number' ? v : '')}
        />
      </Group>

      {catalog.operations.length > 0 && (
        <Stack gap={4} mt="sm">
          <Text size="xs" c="dimmed">
            Operation mix (relative weights)
          </Text>
          {catalog.operations.map((op) => (
            <Group key={op.id} justify="space-between" wrap="nowrap">
              <Text size="sm">
                {op.method} {op.path}
              </Text>
              <NumberInput
                min={0}
                step={1}
                w={100}
                value={weights[op.id] ?? 0}
                onChange={(v) =>
                  setWeights((prev) => ({ ...prev, [op.id]: typeof v === 'number' ? v : 0 }))
                }
              />
            </Group>
          ))}
        </Stack>
      )}

      <Group grow mt="sm">
        <TextInput
          label="Source"
          placeholder="Grafana · checkout-service overview"
          value={source}
          onChange={(e) => setSource(e.currentTarget.value)}
        />
        <Textarea label="Note" minRows={1} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
      </Group>

      <Button mt="sm" size="sm" onClick={onRecord} loading={recordMutation.isPending} disabled={peakRate === ''}>
        Record
      </Button>
    </div>
  );
}
