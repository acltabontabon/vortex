import { useEffect, useState } from 'react';
import { Button, Group, Skeleton, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  useProjectThresholdsQuery,
  useSaveProjectThresholdsMutation,
  type ThresholdDto,
  type ThresholdProvenanceDto,
} from '../../../api/thresholds';
import { ThresholdsRegion, type ThresholdsRegionValues } from '../thresholds/ThresholdsRegion';
import classes from './ObjectivesSection.module.css';

const EMPTY_VALUES: ThresholdsRegionValues = { p95Millis: '', p99Millis: '', errorPercent: '' };

/**
 * The service-level objectives every workload inherits unless it sets its own — the normal home for
 * a service's thresholds. Self-contained: fetches its own evidence and current values through the
 * Threshold Assistant (see `ThresholdsRegion`), the same building blocks used anywhere else a
 * threshold is configured. The domain also supports per-operation and per-workload objectives, but
 * nothing here creates or edits one — this stays deliberately narrow to the three overall objectives.
 */
export function ObjectivesSection({ serviceId, onSaved }: { serviceId: string; onSaved?: () => void }) {
  const query = useProjectThresholdsQuery(serviceId);
  const mutation = useSaveProjectThresholdsMutation(serviceId);
  const [editing, setEditing] = useState(false);
  const [values, setValues] = useState<ThresholdsRegionValues>(EMPTY_VALUES);
  const [provenance, setProvenance] = useState<Record<string, ThresholdProvenanceDto>>({});
  const [prefilled, setPrefilled] = useState(false);

  // Prefills once, from whatever is currently saved — auto-opens the editor when nothing is
  // configured yet, otherwise shows the read-only summary, matching the section's previous behaviour.
  useEffect(() => {
    if (query.data && !prefilled) {
      const p95 = query.data.thresholds.find((t) => t.kind === 'LATENCY' && t.percentile === 95);
      const p99 = query.data.thresholds.find((t) => t.kind === 'LATENCY' && t.percentile === 99);
      const errorRate = query.data.thresholds.find((t) => t.kind === 'ERROR_RATE');
      setValues({
        p95Millis: p95?.maxMillis ?? '',
        p99Millis: p99?.maxMillis ?? '',
        errorPercent: errorRate?.maxErrorPercent ?? '',
      });
      setProvenance(query.data.provenance);
      setEditing(query.data.thresholds.length === 0);
      setPrefilled(true);
    }
  }, [query.data, prefilled]);

  if (query.isLoading || !query.data || !prefilled) {
    return <Skeleton height={80} radius="sm" />;
  }

  function submit() {
    const thresholds: ThresholdDto[] = [];
    if (values.p95Millis !== '') thresholds.push({ kind: 'LATENCY', percentile: 95, maxMillis: values.p95Millis });
    if (values.p99Millis !== '') thresholds.push({ kind: 'LATENCY', percentile: 99, maxMillis: values.p99Millis });
    if (values.errorPercent !== '') {
      thresholds.push({ kind: 'ERROR_RATE', maxErrorPercent: values.errorPercent });
    }

    mutation.mutate(
      { thresholds, provenance },
      {
        onSuccess: (response) => {
          if (response.ok) {
            notifications.show({ message: 'Objectives saved.', color: 'pass' });
            setEditing(false);
            onSaved?.();
          } else {
            notifications.show({ message: response.error ?? 'Could not save objectives.', color: 'fail' });
          }
        },
      }
    );
  }

  if (!editing) {
    return (
      <div>
        <div className={classes.cells}>
          <Cell label="p95 latency" value={values.p95Millis !== '' ? `< ${values.p95Millis} ms` : '—'} />
          <Cell label="p99 latency" value={values.p99Millis !== '' ? `< ${values.p99Millis} ms` : '—'} />
          <Cell label="Error rate" value={values.errorPercent !== '' ? `< ${values.errorPercent}%` : '—'} />
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
      <ThresholdsRegion
        serviceId={serviceId}
        workloadName=""
        values={values}
        onChange={setValues}
        provenance={provenance}
        onProvenanceChange={(thresholdId, p) => setProvenance((prev) => ({ ...prev, [thresholdId]: p }))}
      />
      <Group mt="sm">
        <Button onClick={submit} loading={mutation.isPending}>
          Save objectives
        </Button>
        {query.data.thresholds.length > 0 && (
          <Button variant="default" onClick={() => setEditing(false)}>
            Cancel
          </Button>
        )}
      </Group>
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
