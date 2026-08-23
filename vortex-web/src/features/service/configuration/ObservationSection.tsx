import { useForm } from '@mantine/form';
import { Alert, Button, Group, Select, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { ObservationSource } from '../../../api/configuration';
import {
  useSaveObservationSourceMutation,
  useTestObservationSourceMutation,
} from '../../../api/configuration';
import { Fact, Facts } from '../../../components/Fact';
import { extractErrorMessage } from '../../../lib/queryFallback';
import { SectionDisclosure } from './SectionDisclosure';

export function ObservationSection({
  serviceId,
  source,
}: {
  serviceId: string;
  source: ObservationSource | null;
}) {
  const save = useSaveObservationSourceMutation(serviceId);
  const test = useTestObservationSourceMutation(serviceId);

  const form = useForm({
    initialValues: {
      source: source?.kind.toLowerCase() ?? 'prometheus',
      endpoint: source?.endpoint ?? '',
      serviceIdentifier: source?.serviceIdentifier ?? '',
      window: source?.windowDisplay ?? '30d',
      headerNames: '',
      headerValues: '',
    },
  });

  function payload(values: typeof form.values) {
    return {
      source: values.source,
      endpoint: values.endpoint,
      serviceIdentifier: values.serviceIdentifier,
      window: values.window,
      headerName: values.headerNames ? values.headerNames.split('\n') : undefined,
      headerValue: values.headerValues ? values.headerValues.split('\n') : undefined,
    };
  }

  function onSave(values: typeof form.values) {
    save.mutate(payload(values), {
      onSuccess: (r) => notifications.show({ message: r.message, color: 'pass' }),
    });
  }

  function onTest() {
    test.mutate(payload(form.values));
  }

  const saveError = extractErrorMessage(save, 'Something went wrong saving the observation source.');

  return (
    <SectionDisclosure
      id="observation"
      title="Observation source"
      openByDefault={!source}
      state={source ? `${source.kind} at ${source.endpoint}` : 'not configured'}
    >
      {source && (
        <Facts>
          <Fact label="System">{source.kind}</Fact>
          <Fact label="Endpoint">{source.endpoint}</Fact>
          <Fact label={source.kind === 'DYNATRACE' ? 'Entity' : 'Service label'}>
            {source.serviceIdentifier}
          </Fact>
          <Fact label="Window">{source.windowDisplay}</Fact>
        </Facts>
      )}

      {test.data && (
        <Alert color={test.data.succeeded ? 'pass' : 'warn'} title="Test connection" mt="md" mb="md">
          {test.data.message}
        </Alert>
      )}
      {saveError && (
        <Text size="sm" c="fail" mb="xs">
          {saveError}
        </Text>
      )}

      <form onSubmit={form.onSubmit(onSave)}>
        <Group grow>
          <Select
            label="System"
            data={[
              { value: 'prometheus', label: 'Prometheus' },
              { value: 'dynatrace', label: 'Dynatrace' },
            ]}
            {...form.getInputProps('source')}
          />
          <TextInput
            label="Endpoint"
            placeholder="http://prometheus.internal:9090"
            {...form.getInputProps('endpoint')}
          />
        </Group>
        <Group grow mt="sm">
          <TextInput
            label={form.values.source === 'dynatrace' ? 'Entity id' : 'Service label'}
            placeholder={form.values.source === 'dynatrace' ? 'SERVICE-1A2B3C4D5E6F7890' : 'checkout-service'}
            {...form.getInputProps('serviceIdentifier')}
          />
          <TextInput label="Window" placeholder="30d" {...form.getInputProps('window')} />
        </Group>
        <Group grow mt="sm" align="flex-start">
          <Textarea label="Header names" description="one per line" minRows={2} {...form.getInputProps('headerNames')} />
          <Textarea
            label="Header values"
            description="paired by line, ${NAME} for secrets"
            minRows={2}
            {...form.getInputProps('headerValues')}
          />
        </Group>
        <Group mt="sm">
          <Button type="submit" loading={save.isPending}>
            Save source
          </Button>
          <Button variant="default" onClick={onTest} loading={test.isPending}>
            Test connection
          </Button>
        </Group>
      </form>
    </SectionDisclosure>
  );
}
