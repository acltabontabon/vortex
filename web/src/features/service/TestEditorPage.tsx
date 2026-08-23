import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useDebouncedValue } from '@mantine/hooks';
import { useForm } from '@mantine/form';
import {
  Alert,
  Button,
  Card,
  Group,
  NumberInput,
  Radio,
  Select,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTestsQuery } from '../../api/workspace';
import {
  useCatalogOperationsQuery,
  usePreviewMutation,
  useSaveTestMutation,
  useTestEditQuery,
} from '../../api/tests';
import { Unknown } from '../../components/Unknown';
import { TrafficDistribution } from '../../components/TrafficDistribution';
import { errorFallback, extractErrorMessage } from '../../lib/queryFallback';
import classes from './TestEditorPage.module.css';

const WORKLOAD_MODELS = [
  {
    value: 'OPEN',
    label: 'Arrival rate',
    question: 'How does the service behave when this much traffic arrives, whatever it does about it?',
  },
  {
    value: 'CLOSED',
    label: 'Concurrency',
    question:
      'How does the service behave with this many clients working through it as fast as it lets them?',
  },
] as const;

interface FormValues {
  name: string;
  description: string;
  type: string;
  model: 'OPEN' | 'CLOSED';
  rate: number;
  vus: number;
  durationMinutes: number;
  ramping: boolean;
  peakRate: number | '';
  stages: number;
  singleOperation: string;
  weights: Record<string, number>;
}

/**
 * Defining or changing a test — what traffic, split across which operations, held for how long.
 *
 * <p>Vortex picks the k6 executor from the answers; nobody chooses one directly. And an invalid
 * state is unbuildable rather than explained afterwards: choosing concurrency swaps the weight
 * grid for a single-operation selector, because a concurrency workload driving several operations
 * is something the domain refuses to construct.
 */
export function TestEditorPage() {
  const { id = '', name } = useParams();
  const editing = name !== undefined;
  const navigate = useNavigate();

  const testsQuery = useTestsQuery(id);
  const catalogQuery = useCatalogOperationsQuery(id);
  const editQuery = useTestEditQuery(id, name);
  const saveMutation = useSaveTestMutation(id);
  const previewMutation = usePreviewMutation(id);
  const [prefilled, setPrefilled] = useState(!editing);

  const form = useForm<FormValues>({
    initialValues: {
      name: '',
      description: '',
      type: 'AVERAGE_LOAD',
      model: 'OPEN',
      rate: 50,
      vus: 50,
      durationMinutes: 10,
      ramping: false,
      peakRate: '',
      stages: 4,
      singleOperation: '',
      weights: {},
    },
    validate: {
      name: (value) => (value.trim().length > 0 ? null : 'A test needs a name.'),
    },
  });

  // Prefills once the raw editable values arrive — done as an effect rather than initialValues
  // because the data isn't there on first render.
  useEffect(() => {
    if (editing && editQuery.data && !prefilled) {
      const data = editQuery.data;
      form.setValues({
        name: data.name,
        description: data.description,
        type: data.type,
        model: data.model,
        rate: data.rate ?? 50,
        vus: data.vus ?? 50,
        durationMinutes: data.durationMinutes,
        ramping: data.ramping,
        peakRate: data.peakRate ?? '',
        stages: data.stages ?? 4,
        singleOperation: data.singleOperation ?? '',
        weights: data.weights,
      });
      setPrefilled(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editing, editQuery.data, prefilled]);

  const [debounced] = useDebouncedValue(form.values, 400);

  useEffect(() => {
    if (!prefilled) return;
    previewMutation.mutate({
      model: debounced.model,
      rate: debounced.model === 'OPEN' ? debounced.rate : undefined,
      vus: debounced.model === 'CLOSED' ? debounced.vus : undefined,
      durationMinutes: debounced.durationMinutes,
      peakRate: debounced.ramping && debounced.peakRate !== '' ? debounced.peakRate : undefined,
      stages: debounced.ramping ? debounced.stages : undefined,
      singleOperation: debounced.model === 'CLOSED' ? debounced.singleOperation : undefined,
      weights: debounced.model === 'OPEN' ? debounced.weights : undefined,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced, prefilled]);

  function submit(values: FormValues) {
    saveMutation.mutate(
      {
        name: values.name.trim(),
        originalName: editing ? name : undefined,
        type: values.type,
        description: values.description.trim() || undefined,
        model: values.model,
        rate: values.model === 'OPEN' ? values.rate : undefined,
        vus: values.model === 'CLOSED' ? values.vus : undefined,
        durationMinutes: values.durationMinutes,
        peakRate: values.ramping && values.peakRate !== '' ? values.peakRate : undefined,
        stages: values.ramping ? values.stages : undefined,
        singleOperation: values.model === 'CLOSED' ? values.singleOperation : undefined,
        weights: values.model === 'OPEN' ? values.weights : undefined,
      },
      {
        onSuccess: (response) => {
          notifications.show({ message: `'${response.name}' saved.`, color: 'pass' });
          navigate(`/services/${id}`);
        },
      }
    );
  }

  const serverError = extractErrorMessage(saveMutation, 'Something went wrong saving this test.');

  const error = errorFallback(
    testsQuery.isError || catalogQuery.isError || editQuery.isError,
    'Could not load the editor',
  );
  if (error) return error;

  if (!testsQuery.data || !catalogQuery.data || (editing && !editQuery.data)) {
    return <Skeleton height={480} radius="md" />;
  }

  if (catalogQuery.data.length === 0) {
    return (
      <Unknown
        what="No operations yet."
        reason="A test spreads traffic across the things a service can do, so Vortex needs to know what those are first."
        actionLabel="Import an API description"
        actionHref={`/services/${id}/configuration#operations`}
      />
    );
  }

  return (
    <Stack gap="lg" className={classes.split}>
      <div className={classes.main}>
        <Title order={1} size="h2" mb={4}>
          {editing ? name : 'Define a test'}
        </Title>
        <Text c="dimmed" size="sm" mb="lg" maw={640}>
          A test describes a traffic condition this service experiences: how much load, split
          across which operations, held for how long.
        </Text>

        {serverError && (
          <Alert color="fail" title="Could not save this test" mb="md">
            {serverError}
          </Alert>
        )}

        <form onSubmit={form.onSubmit(submit)}>
          <Stack gap="md">
            <Card withBorder radius="md">
              <Title order={2} size="h4" mb="sm">
                Basics
              </Title>
              <Stack gap="sm">
                <TextInput
                  label="Name"
                  placeholder="production-peak"
                  description="Lower case, no spaces. It is how you refer to this test on the command line."
                  {...form.getInputProps('name')}
                />
                <TextInput
                  label="What does this traffic represent?"
                  placeholder="The workload the service receives at month-end settlement"
                  {...form.getInputProps('description')}
                />
              </Stack>
            </Card>

            <Card withBorder radius="md">
              <Title order={2} size="h4" mb={4}>
                What question is this test for?
              </Title>
              <Text size="sm" c="dimmed" mb="sm">
                Vortex calls this the evaluation. It states the intent — it does not choose how
                traffic is produced.
              </Text>
              <Radio.Group {...form.getInputProps('type')}>
                <Stack gap="xs">
                  {testsQuery.data.testTypes.map((type) => (
                    <Radio.Card key={type.name} value={type.name} className={classes.option} p="sm">
                      <Group wrap="nowrap" align="flex-start">
                        <Radio.Indicator />
                        <div>
                          <Text fw={600} size="sm">
                            {type.label}
                          </Text>
                          <Text size="xs" c="dimmed">
                            {type.question}
                          </Text>
                        </div>
                      </Group>
                    </Radio.Card>
                  ))}
                </Stack>
              </Radio.Group>
            </Card>

            <Card withBorder radius="md">
              <Title order={2} size="h4" mb={4}>
                How is the traffic produced?
              </Title>
              <Text size="sm" c="dimmed" mb="sm">
                These are different quantities, not two ways of saying the same thing.
              </Text>
              <Radio.Group {...form.getInputProps('model')}>
                <Stack gap="xs">
                  {WORKLOAD_MODELS.map((wm) => (
                    <Radio.Card key={wm.value} value={wm.value} className={classes.option} p="sm">
                      <Group wrap="nowrap" align="flex-start">
                        <Radio.Indicator />
                        <div>
                          <Text fw={600} size="sm">
                            {wm.label}
                          </Text>
                          <Text size="xs" c="dimmed">
                            {wm.question}
                          </Text>
                        </div>
                      </Group>
                    </Radio.Card>
                  ))}
                </Stack>
              </Radio.Group>

              <Group grow mt="md" align="flex-start">
                {form.values.model === 'OPEN' ? (
                  // Hidden rather than disabled once staging is on: TestDefinitions.shape() derives
                  // every stage's target from peakRate/stages alone and never reads this field in that
                  // mode, so a live, editable "Requests per second" here would look load-bearing while
                  // doing nothing — exactly what misled the level chosen for the first stage once.
                  !form.values.ramping && (
                    <NumberInput
                      label="Requests per second"
                      min={0}
                      step={0.1}
                      {...form.getInputProps('rate')}
                    />
                  )
                ) : (
                  <NumberInput
                    label="Concurrent users"
                    min={1}
                    step={1}
                    {...form.getInputProps('vus')}
                  />
                )}
                <NumberInput
                  label={form.values.ramping ? 'Total duration (minutes)' : 'Duration (minutes)'}
                  description={
                    form.values.ramping && form.values.stages > 0
                      ? `Split evenly across ${form.values.stages} stages — ${(
                          form.values.durationMinutes / form.values.stages
                        ).toLocaleString(undefined, { maximumFractionDigits: 1 })} min each`
                      : undefined
                  }
                  min={1}
                  {...form.getInputProps('durationMinutes')}
                />
              </Group>

              {form.values.model === 'OPEN' && (
                <>
                  <Button
                    variant="subtle"
                    size="xs"
                    mt="sm"
                    onClick={() => form.setFieldValue('ramping', !form.values.ramping)}
                  >
                    {form.values.ramping ? 'Hold the load steady instead' : 'Increase the load in stages instead'}
                  </Button>
                  {form.values.ramping && (
                    <Group grow mt="sm">
                      <NumberInput
                        label="Increase to (requests/sec)"
                        min={0}
                        step={0.1}
                        {...form.getInputProps('peakRate')}
                      />
                      <NumberInput
                        label="Number of stages"
                        min={2}
                        max={20}
                        {...form.getInputProps('stages')}
                      />
                    </Group>
                  )}
                  {form.values.ramping && form.values.peakRate !== '' && form.values.stages > 0 && (
                    <Text size="xs" c="dimmed" mt={4}>
                      Stages are evenly spaced from{' '}
                      {(form.values.peakRate / form.values.stages).toLocaleString(undefined, {
                        maximumFractionDigits: 1,
                      })}{' '}
                      to {form.values.peakRate} requests/sec — there is no separate starting rate to
                      set.
                    </Text>
                  )}
                </>
              )}
            </Card>

            <Card withBorder radius="md">
              <Title order={2} size="h4" mb="sm">
                Which operations does this traffic reach?
              </Title>

              {form.values.model === 'OPEN' ? (
                <>
                  <Text size="sm" c="dimmed" mb="sm">
                    The total above is divided across these operations in proportion to their
                    weights. Weights are relative, so 15/25/55/5 and 3/5/11/1 mean the same thing.
                  </Text>
                  <Stack gap="xs">
                    {catalogQuery.data.map((op) => (
                      <Group key={op.id} justify="space-between" wrap="nowrap">
                        <Text size="sm" className={classes.opLabel}>
                          <span className={classes.method}>{op.method}</span> {op.path}
                        </Text>
                        <NumberInput
                          min={0}
                          step={1}
                          w={100}
                          {...form.getInputProps(`weights.${op.id}`)}
                        />
                      </Group>
                    ))}
                  </Stack>
                </>
              ) : (
                <>
                  <Text size="sm" c="dimmed" mb="sm">
                    A concurrency workload drives <strong>one</strong> operation. Splitting virtual
                    users across several would not split the traffic: how much each user produces
                    depends on how fast the service answers, so the shares would drift apart
                    exactly as the service slowed down.
                  </Text>
                  <Select
                    label="Operation these users call"
                    data={catalogQuery.data.map((op) => ({ value: op.id, label: op.label }))}
                    {...form.getInputProps('singleOperation')}
                  />
                </>
              )}
            </Card>
          </Stack>

          <Group mt="lg">
            <Button type="submit" size="lg" loading={saveMutation.isPending}>
              {editing ? 'Save changes' : 'Create test'}
            </Button>
            <Button component="a" href={`/services/${id}`} variant="default">
              Cancel
            </Button>
          </Group>
          <Text size="xs" c="dimmed" mt="sm">
            Saving writes to this service's <code>vortex.yaml</code>, which belongs in version
            control next to the service it describes.
          </Text>
        </form>
      </div>

      <div className={classes.aside}>
        <Card withBorder radius="md" className={classes.stickyCard}>
          <Title order={2} size="h4">
            What this describes
          </Title>
          <Text size="xs" c="dimmed" mb="sm">
            Recalculated as you type, using the same arithmetic the run will use.
          </Text>
          {previewMutation.data?.problem && (
            <Text size="sm" c="dimmed">
              {previewMutation.data.problem}
            </Text>
          )}
          {previewMutation.data?.composition && (
            <TrafficDistribution
              rows={previewMutation.data.composition}
              concurrency={form.values.model === 'CLOSED'}
            />
          )}
          {!previewMutation.data && (
            <Text size="sm" c="dimmed">
              Fill in the traffic and the operation mix to see how the load divides.
            </Text>
          )}
        </Card>
      </div>
    </Stack>
  );
}
