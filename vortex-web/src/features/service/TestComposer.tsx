import { useEffect, useRef, useState } from 'react';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import { useForm } from '@mantine/form';
import {
  Alert,
  Button,
  Collapse,
  Group,
  NumberInput,
  Radio,
  SegmentedControl,
  Select,
  Skeleton,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconChevronDown, IconPlus } from '@tabler/icons-react';
import { useTestsQuery } from '../../api/workspace';
import {
  useCatalogOperationsQuery,
  usePreviewMutation,
  useSaveTestMutation,
  useTestEditQuery,
  type CatalogOperation,
} from '../../api/tests';
import { Unknown } from '../../components/Unknown';
import { InfoPopover } from '../../components/InfoPopover';
import { TrafficDistribution } from '../../components/TrafficDistribution';
import { errorFallback, extractErrorMessage } from '../../lib/queryFallback';
import { LoadShapeChart } from '../../components/charts/LoadShapeChart';
import type { ComposerPreviewSnapshot } from './WorkloadPreviewPanel';
import classes from './TestComposer.module.css';

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

/** A fixed lookup, not a re-derivation of `TestDefinitions.slug()` — it can never drift from the
 *  backend's own name normalization because it never tries to reproduce it. */
const NAME_SUGGESTIONS: Record<string, string> = {
  SMOKE: 'smoke-check',
  AVERAGE_LOAD: 'average-load-check',
  STRESS: 'stress-check',
  SPIKE: 'spike-check',
  SOAK: 'soak-check',
  BREAKPOINT: 'breakpoint-check',
};

function suggestName(type: string): string {
  return NAME_SUGGESTIONS[type] ?? 'new-test';
}

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

/** One line describing what Load currently says, for the collapsed region's summary. */
function loadSummary(values: FormValues): string {
  const duration = `${values.durationMinutes} min`;
  if (values.model === 'CLOSED') {
    return `Concurrency · ${values.vus} VUs · ${duration}`;
  }
  if (values.ramping && values.peakRate !== '') {
    return `Arrival rate · ramp to ${values.peakRate} req/s over ${values.stages} stages · ${duration}`;
  }
  return `Arrival rate · ${values.rate} req/s · ${duration}`;
}

/** One line describing what Operations currently says, for the collapsed region's summary. */
function operationsSummary(
  values: FormValues,
  catalog: CatalogOperation[] | undefined,
): string | null {
  if (!catalog) return null;
  if (values.model === 'CLOSED') {
    const op = catalog.find((o) => o.id === values.singleOperation);
    return op ? `${op.method} ${op.path}` : null;
  }
  const active = Object.entries(values.weights)
    .filter(([, weight]) => weight > 0)
    .sort((a, b) => b[1] - a[1]);
  if (active.length === 0) return null;
  const top = catalog.find((o) => o.id === active[0][0]);
  const topLabel = top ? `${top.method} ${top.path}` : active[0][0];
  return active.length > 1 ? `${topLabel} +${active.length - 1} more` : topLabel;
}

/**
 * One collapsible region of the composer — a label, a one-line summary when collapsed, and its
 * content when expanded. Not a wizard step: any region opens or closes independently, in any
 * order, and nothing here decides what "done" means for the workload as a whole. Purely a
 * disclosure shell — every domain decision lives in the region's own content, not in this wrapper.
 */
function ComposerRegion({
  title,
  summary,
  defaultExpanded,
  children,
}: {
  title: string;
  summary: string | null;
  defaultExpanded: boolean;
  children: React.ReactNode;
}) {
  const [expanded, { toggle }] = useDisclosure(defaultExpanded);

  return (
    <div className={classes.region}>
      <button
        type="button"
        className={classes.regionHeader}
        onClick={toggle}
        aria-expanded={expanded}
      >
        <span className={classes.regionTitle}>{title}</span>
        {!expanded && summary && <span className={classes.regionSummary}>{summary}</span>}
        <IconChevronDown
          size={14}
          className={expanded ? classes.chevronOpen : classes.chevron}
          aria-hidden="true"
        />
      </button>
      <Collapse expanded={expanded} transitionDuration={200}>
        <div className={classes.regionBody}>{children}</div>
      </Collapse>
    </div>
  );
}

/**
 * Composing or changing a test, in place inside the Service Overview page — what traffic, split
 * across which operations, held for how long. One instrument with three progressively-disclosed
 * regions (Intent, Load, Operations), not a stepper and not a stack of cards: typography, hairline
 * dividers and disclosure carry the structure instead of borders and backgrounds.
 *
 * <p>Vortex picks the k6 executor from the answers; nobody chooses one directly. And an invalid
 * state is unbuildable rather than explained afterwards: choosing concurrency swaps the weight
 * grid for a single-operation selector, because a concurrency workload driving several operations
 * is something the domain refuses to construct.
 */
export function TestComposer({
  serviceId,
  mode,
  editingName,
  onClose,
  onPreviewChange,
  showInlineChart = false,
}: {
  serviceId: string;
  mode: 'create' | 'edit';
  editingName?: string;
  onClose: () => void;
  /** Reports the live form state up to the Workload Preview rail — see `WorkloadPreviewPanel.tsx`.
   *  Called with `null` on unmount so the rail never shows a stale snapshot after the composer
   *  closes. */
  onPreviewChange?: (snapshot: ComposerPreviewSnapshot | null) => void;
  /** True on narrow screens, where there's no rail slot for the Workload Preview to occupy — the
   *  Load Shape chart renders here instead, in the Load region, so it's never simply absent. On
   *  wide screens the rail already shows it, and this stays false so the chart never renders
   *  twice for the same workload. */
  showInlineChart?: boolean;
}) {
  const editing = mode === 'edit';

  const testsQuery = useTestsQuery(serviceId);
  const catalogQuery = useCatalogOperationsQuery(serviceId);
  const editQuery = useTestEditQuery(serviceId, editingName);
  const saveMutation = useSaveTestMutation(serviceId);
  const previewMutation = usePreviewMutation(serviceId);
  const [prefilled, setPrefilled] = useState(!editing);
  const [descriptionOpen, setDescriptionOpen] = useState(false);

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

  // Follows the chosen evaluation until the user actually types something of their own — tracked by
  // comparing the current name against the last value *this* effect itself set, not by a separate
  // "touched" flag. That means changing Intent before ever touching Name keeps the suggestion in
  // sync (empty → "average-load-check" → "stress-check" → ...), but the instant the name diverges
  // from what was last suggested — one keystroke, or clearing it back to empty and typing something
  // else — Vortex never touches it again. Never runs in edit mode at all.
  const suggestedName = useRef('');
  useEffect(() => {
    if (editing || !prefilled) return;
    if (form.values.name === suggestedName.current || form.values.name.trim() === '') {
      const suggestion = suggestName(form.values.type);
      suggestedName.current = suggestion;
      form.setFieldValue('name', suggestion);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form.values.type, editing, prefilled]);

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

  // Publishes the live workload — raw values already known client-side, plus the one thing the
  // domain computed (`composition`, from the same preview response the Operations mixer already
  // reads) — up to the Workload Preview rail. Deliberately not the whole `form.values` object as a
  // single dependency: that reference changes on every keystroke, including ones (name,
  // description, individual weights) that don't change what the rail shows until the debounced
  // preview itself resolves.
  useEffect(() => {
    if (!onPreviewChange) return;
    const testTypeLabel =
      testsQuery.data?.testTypes.find((t) => t.name === form.values.type)?.label ?? form.values.type;
    onPreviewChange({
      testTypeLabel,
      model: form.values.model,
      ramping: form.values.ramping,
      rate: form.values.rate,
      vus: form.values.vus,
      durationMinutes: form.values.durationMinutes,
      peakRate: form.values.peakRate,
      stages: form.values.stages,
      composition: previewMutation.data?.composition ?? null,
      shape: previewMutation.data?.shape ?? null,
      problem: previewMutation.data?.problem ?? null,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    form.values.type,
    form.values.model,
    form.values.ramping,
    form.values.rate,
    form.values.vus,
    form.values.durationMinutes,
    form.values.peakRate,
    form.values.stages,
    previewMutation.data,
    testsQuery.data,
  ]);

  // Never leaves a stale workload behind for the rail once this composer session ends.
  useEffect(() => {
    return () => onPreviewChange?.(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function submit(values: FormValues) {
    saveMutation.mutate(
      {
        name: values.name.trim(),
        originalName: editing ? editingName : undefined,
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
          onClose();
        },
      }
    );
  }

  const serverError = extractErrorMessage(saveMutation, 'Something went wrong saving this test.');

  const error = errorFallback(
    testsQuery.isError || catalogQuery.isError || editQuery.isError,
    'Could not load the composer',
  );
  if (error) return error;

  if (!testsQuery.data || !catalogQuery.data || (editing && !editQuery.data)) {
    return <Skeleton height={320} radius="md" />;
  }

  if (catalogQuery.data.length === 0) {
    return (
      <Unknown
        what="No operations yet."
        reason="A test spreads traffic across the things a service can do, so Vortex needs to know what those are first."
        actionLabel="Import an API description"
        actionHref={`/services/${serviceId}/configuration#operations`}
      />
    );
  }

  const selectedType = testsQuery.data.testTypes.find((t) => t.name === form.values.type);
  const showDescription = descriptionOpen || form.values.description.trim().length > 0;

  return (
    <div className={classes.main}>
      {serverError && (
          <Alert color="fail" title="Could not save this test" mb="md">
            {serverError}
          </Alert>
        )}

        <form onSubmit={form.onSubmit(submit)}>
          <div className={classes.identity}>
            <TextInput
              variant="unstyled"
              aria-label="Name"
              placeholder="production-peak"
              classNames={{ input: classes.nameInput }}
              {...form.getInputProps('name')}
            />
            {showDescription ? (
              <TextInput
                variant="unstyled"
                size="sm"
                aria-label="Description"
                placeholder="What does this traffic represent?"
                classNames={{ input: classes.descriptionInput }}
                {...form.getInputProps('description')}
              />
            ) : (
              <button
                type="button"
                className={classes.addDescription}
                onClick={() => setDescriptionOpen(true)}
              >
                <IconPlus size={12} /> Add description
              </button>
            )}
          </div>

          <ComposerRegion
            title="Intent"
            summary={selectedType?.label ?? null}
            defaultExpanded={!editing}
          >
            <Radio.Group {...form.getInputProps('type')}>
              <div className={classes.typeGrid}>
                {testsQuery.data.testTypes.map((type) => (
                  // The info trigger is a sibling positioned over the tile, not a descendant of
                  // Radio.Card — Radio.Card renders as a real <button>, and a <button> nested
                  // inside another <button> is invalid HTML (and unreachable by keyboard/AT).
                  <div key={type.name} className={classes.typeTile}>
                    <Radio.Card value={type.name} className={classes.typeTileButton}>
                      <span className={classes.typeLabel}>{type.label}</span>
                      <div className={classes.typeQuestion}>{type.question}</div>
                    </Radio.Card>
                    <div className={classes.typeInfo}>
                      <InfoPopover icon ariaLabel={`About ${type.label}`} width={280}>
                        <Text size="xs">{type.guidance}</Text>
                      </InfoPopover>
                    </div>
                  </div>
                ))}
              </div>
            </Radio.Group>
          </ComposerRegion>

          <ComposerRegion
            title="Load"
            summary={loadSummary(form.values)}
            defaultExpanded={editing}
          >
            <Group gap={6} align="center" mb="md" wrap="nowrap">
              <Radio.Group {...form.getInputProps('model')}>
                <div className={classes.modelGrid}>
                  {WORKLOAD_MODELS.map((wm) => (
                    <Radio.Card key={wm.value} value={wm.value} className={classes.modelTile}>
                      {wm.label}
                    </Radio.Card>
                  ))}
                </div>
              </Radio.Group>
              <InfoPopover icon ariaLabel="About traffic models" width={300}>
                {WORKLOAD_MODELS.map((wm) => (
                  <Text key={wm.value} size="xs" mb={4}>
                    <strong>{wm.label}</strong> — {wm.question}
                  </Text>
                ))}
              </InfoPopover>
            </Group>

            {form.values.model === 'OPEN' && (
              <SegmentedControl
                mb="md"
                data={[
                  { value: 'steady', label: 'Steady' },
                  { value: 'ramping', label: 'Ramping' },
                ]}
                value={form.values.ramping ? 'ramping' : 'steady'}
                onChange={(value) => form.setFieldValue('ramping', value === 'ramping')}
              />
            )}

            {form.values.model === 'OPEN' ? (
              form.values.ramping ? (
                <Group gap="md" align="flex-end" wrap="wrap">
                  <NumberInput
                    label="Ramp to"
                    description="requests/sec"
                    min={0}
                    step={0.1}
                    w={150}
                    {...form.getInputProps('peakRate')}
                  />
                  <Text size="sm" c="dimmed" pb={6}>
                    over
                  </Text>
                  <NumberInput label="Stages" min={2} max={20} w={100} {...form.getInputProps('stages')} />
                  <Text size="sm" c="dimmed" pb={6}>
                    during
                  </Text>
                  <NumberInput
                    label="Total duration"
                    description="minutes"
                    min={1}
                    w={120}
                    {...form.getInputProps('durationMinutes')}
                  />
                </Group>
              ) : (
                <Group gap="md" align="flex-end" wrap="wrap">
                  <NumberInput
                    label="Hold"
                    description="requests/sec"
                    min={0}
                    step={0.1}
                    w={150}
                    {...form.getInputProps('rate')}
                  />
                  <Text size="sm" c="dimmed" pb={6}>
                    for
                  </Text>
                  <NumberInput
                    label="Duration"
                    description="minutes"
                    min={1}
                    w={120}
                    {...form.getInputProps('durationMinutes')}
                  />
                </Group>
              )
            ) : (
              <Group gap="md" align="flex-end" wrap="wrap">
                <NumberInput
                  label="Clients"
                  description="concurrent users"
                  min={1}
                  w={150}
                  {...form.getInputProps('vus')}
                />
                <Text size="sm" c="dimmed" pb={6}>
                  for
                </Text>
                <NumberInput
                  label="Duration"
                  description="minutes"
                  min={1}
                  w={120}
                  {...form.getInputProps('durationMinutes')}
                />
              </Group>
            )}

            {form.values.ramping && form.values.stages > 0 && (
              <Text size="xs" c="dimmed" mt={8}>
                Split evenly across {form.values.stages} stages —{' '}
                {(form.values.durationMinutes / form.values.stages).toLocaleString(undefined, {
                  maximumFractionDigits: 1,
                })}{' '}
                min each
              </Text>
            )}
            {form.values.ramping && form.values.peakRate !== '' && form.values.stages > 0 && (
              <Text size="xs" c="dimmed" mt={4}>
                Stages are evenly spaced from{' '}
                {(form.values.peakRate / form.values.stages).toLocaleString(undefined, {
                  maximumFractionDigits: 1,
                })}{' '}
                to {form.values.peakRate} requests/sec — there is no separate starting rate to set.
              </Text>
            )}

            {showInlineChart && previewMutation.data?.shape && (
              <div className={classes.inlineChart}>
                <LoadShapeChart shape={previewMutation.data.shape} />
              </div>
            )}
          </ComposerRegion>

          <ComposerRegion
            title="Operations"
            summary={operationsSummary(form.values, catalogQuery.data)}
            defaultExpanded={false}
          >
            {form.values.model === 'OPEN' ? (
              <>
                <Text size="sm" c="dimmed" mb="sm">
                  Weighted by how much each operation gets — relative, so 15/25/55/5 and 3/5/11/1
                  mean the same thing. Percent, bar and rate come from the live preview above.
                </Text>
                <TrafficDistribution
                  rows={previewMutation.data?.composition ?? []}
                  edit={{
                    catalog: catalogQuery.data,
                    weights: form.values.weights,
                    onChangeWeight: (operationId, value) =>
                      form.setFieldValue(`weights.${operationId}`, value),
                  }}
                />
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
          </ComposerRegion>

          <div className={classes.actions}>
            <Group>
              <Button type="submit" size="sm" loading={saveMutation.isPending}>
                {editing ? 'Save test' : 'Create test'}
              </Button>
              <Button type="button" size="sm" variant="default" onClick={onClose}>
                Cancel
              </Button>
            </Group>
            <Text size="xs" c="dimmed" mt="sm">
              Saving writes to this service's <code>vortex.yaml</code>, which belongs in version
              control next to the service it describes.
            </Text>
          </div>
      </form>
    </div>
  );
}
