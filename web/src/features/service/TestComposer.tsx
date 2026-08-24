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
  Select,
  Skeleton,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconChevronDown, IconPlus } from '@tabler/icons-react';
import { useTestsQuery, type Target } from '../../api/workspace';
import {
  useCatalogOperationsQuery,
  usePreviewMutation,
  useRecommendationQuery,
  useSaveTestMutation,
  useTestEditQuery,
  type CatalogOperation,
  type RecommendationDto,
  type StageInputDto,
} from '../../api/tests';
import { Unknown } from '../../components/Unknown';
import { InfoPopover } from '../../components/InfoPopover';
import { TrafficDistribution } from '../../components/TrafficDistribution';
import { errorFallback, extractErrorMessage } from '../../lib/queryFallback';
import { LoadShapeChart } from '../../components/charts/LoadShapeChart';
import { RecommendedWorkloadCard } from './RecommendedWorkloadCard';
import { SpikeParamsEditor, type SpikeParams } from './SpikeParamsEditor';
import type { ComposerPreviewSnapshot } from './WorkloadPreviewPanel';
import classes from './TestComposer.module.css';

const WORKLOAD_MODELS = [
  {
    value: 'OPEN',
    label: 'Requests per second',
    technicalLabel: 'Arrival rate',
    question: 'How does the service behave when this much traffic arrives, whatever it does about it?',
  },
  {
    value: 'CLOSED',
    label: 'Concurrent users',
    technicalLabel: 'Concurrency',
    question:
      'How does the service behave with this many clients working through it as fast as it lets them?',
  },
] as const;

type ShapeKind = 'STEADY' | 'PROGRESSIVE_RAMP' | 'SPIKE' | 'STAGED';

const ALL_SHAPE_KINDS: { value: ShapeKind; label: string }[] = [
  { value: 'STEADY', label: 'Steady' },
  { value: 'PROGRESSIVE_RAMP', label: 'Progressive ramp' },
  { value: 'SPIKE', label: 'Spike' },
  { value: 'STAGED', label: 'Staged' },
];

const DEFAULT_SPIKE_PARAMS: SpikeParams = {
  baseline: 10,
  peak: 50,
  holdBeforeMinutes: 0.5,
  holdAtPeakMinutes: 1,
};

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

/** Reconstructs a spike's four editable numbers from its saved/recommended
 *  [baseline, peak, peak, baseline] stage list — the inverse of what `TestDefinitions.spikeShape()`
 *  builds server-side. Falls back to the defaults when the stages don't look like a spike (e.g. an
 *  older save, or the list is empty), rather than guessing at four numbers that would misrepresent
 *  what's actually saved. */
function spikeParamsFromStages(stages: StageInputDto[]): SpikeParams {
  if (stages.length !== 4) return DEFAULT_SPIKE_PARAMS;
  return {
    baseline: stages[0].level,
    peak: stages[1].level,
    holdBeforeMinutes: stages[0].durationSeconds / 60,
    holdAtPeakMinutes: stages[2].durationSeconds / 60,
  };
}

interface FormValues {
  name: string;
  description: string;
  type: string;
  model: 'OPEN' | 'CLOSED';
  shapeKind: ShapeKind;
  rate: number;
  vus: number;
  durationMinutes: number;
  peakRate: number | '';
  stages: number;
  /** The exact stage list from the last-applied recommendation, for Progressive-Ramp/Staged only —
   *  carried through save so a non-uniform (e.g. safety-capped) ramp isn't silently replaced by the
   *  equal-ramp reconstruction. Cleared the moment Rate/Stages/Duration is hand-edited, at which
   *  point the ordinary equal-ramp behavior is exactly what should happen again. */
  explicitStages: StageInputDto[] | null;
  spikeParams: SpikeParams;
  singleOperation: string;
  weights: Record<string, number>;
  /** "Customize workload" — reveals every load shape regardless of what's relevant to the selected
   *  Intent. */
  advanced: boolean;
}

/** One line describing what Load currently says, for the collapsed region's summary. */
function loadSummary(values: FormValues): string {
  const duration = `${values.durationMinutes} min`;
  if (values.model === 'CLOSED') {
    return `Concurrency · ${values.vus} VUs · ${duration}`;
  }
  if (values.shapeKind === 'SPIKE') {
    return `Spike · ${values.spikeParams.baseline} → ${values.spikeParams.peak} req/s · ${duration}`;
  }
  if (values.shapeKind !== 'STEADY' && values.peakRate !== '') {
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
  target = null,
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
  /** The service's configured target, from the header this composer sits beside — every test here
   *  targets it, there being no per-test environment picker. Folded into the preview snapshot's
   *  `targetSummary`/`resourceSummary`; null while no environment is configured yet. */
  target?: Target | null;
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
      shapeKind: 'STEADY',
      rate: 50,
      vus: 50,
      durationMinutes: 10,
      peakRate: '',
      stages: 4,
      explicitStages: null,
      spikeParams: DEFAULT_SPIKE_PARAMS,
      singleOperation: '',
      weights: {},
      advanced: false,
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
      const shapeKind = (data.shapeKind as ShapeKind | undefined) ?? 'STEADY';
      form.setValues({
        name: data.name,
        description: data.description,
        type: data.type,
        model: data.model,
        shapeKind,
        rate: data.rate ?? 50,
        vus: data.vus ?? 50,
        durationMinutes: data.durationMinutes,
        peakRate: data.peakRate ?? '',
        stages: data.stages ?? 4,
        explicitStages:
          shapeKind === 'PROGRESSIVE_RAMP' || shapeKind === 'STAGED' ? data.explicitStages : null,
        spikeParams:
          shapeKind === 'SPIKE' ? spikeParamsFromStages(data.explicitStages) : DEFAULT_SPIKE_PARAMS,
        singleOperation: data.singleOperation ?? '',
        weights: data.weights,
        advanced: false,
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
    const ramping = debounced.shapeKind !== 'STEADY';
    previewMutation.mutate({
      model: debounced.model,
      rate: debounced.model === 'OPEN' ? debounced.rate : undefined,
      vus: debounced.model === 'CLOSED' ? debounced.vus : undefined,
      durationMinutes: debounced.durationMinutes,
      peakRate: ramping && debounced.peakRate !== '' ? debounced.peakRate : undefined,
      stages: ramping ? debounced.stages : undefined,
      singleOperation: debounced.model === 'CLOSED' ? debounced.singleOperation : undefined,
      weights: debounced.model === 'OPEN' ? debounced.weights : undefined,
      type: debounced.type,
      shapeKind: debounced.shapeKind,
      spikeParams: debounced.shapeKind === 'SPIKE' ? debounced.spikeParams : undefined,
      explicitStages: debounced.explicitStages ?? undefined,
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
      headline: previewMutation.data?.headline ?? null,
      durationMinutes: form.values.durationMinutes,
      composition: previewMutation.data?.composition ?? null,
      shape: previewMutation.data?.shape ?? null,
      problem: previewMutation.data?.problem ?? null,
      // Omitted for an external endpoint: its address is already stated on the service header on
      // every tab, and it carries no resource envelope for this caption to add. Vortex only knows a
      // declared resource envelope's raw CPU/memory numbers on the Configuration page today — no
      // DTO reachable from here carries them — so resourceSummary stays null even for a
      // Docker-managed target; see WorkloadPreviewPanel's own note.
      targetSummary: target && target.targetKind !== 'EXTERNAL_ENDPOINT' ? target.targetSummary : null,
      resourceSummary: null,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form.values.type, form.values.durationMinutes, previewMutation.data, testsQuery.data, target]);

  // Never leaves a stale workload behind for the rail once this composer session ends.
  useEffect(() => {
    return () => onPreviewChange?.(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function submit(values: FormValues) {
    const ramping = values.shapeKind !== 'STEADY';
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
        peakRate: ramping && values.peakRate !== '' ? values.peakRate : undefined,
        stages: ramping ? values.stages : undefined,
        singleOperation: values.model === 'CLOSED' ? values.singleOperation : undefined,
        weights: values.model === 'OPEN' ? values.weights : undefined,
        shapeKind: values.shapeKind,
        spikeParams: values.shapeKind === 'SPIKE' ? values.spikeParams : undefined,
        explicitStages: values.explicitStages ?? undefined,
      },
      {
        onSuccess: (response) => {
          notifications.show({ message: `'${response.name}' saved.`, color: 'pass' });
          onClose();
        },
      }
    );
  }

  // Also drives the Load-shape selector's default set of options — a second read of the same
  // query `RecommendedWorkloadCard` makes, deduplicated by TanStack Query's cache, so this never
  // costs a second request. Disabled while editing: an existing test's shape selector always shows
  // every option (see `availableShapeKinds` below), since narrowing it by intent-relevance could
  // hide the very shape a saved test already uses.
  const recommendationQuery = useRecommendationQuery(
    serviceId,
    form.values.type,
    form.values.model,
    !editing
  );

  /** Copies a fetched recommendation's numbers into the form — the only place Load fields are set
   *  from anything other than what the user typed. Every number here came from the backend. */
  function applyRecommendation(rec: RecommendationDto) {
    const shapeKind = rec.shapeKind as ShapeKind;
    form.setValues({
      model: rec.model,
      shapeKind,
      rate: rec.model === 'OPEN' ? rec.startLevel : form.values.rate,
      vus: rec.model === 'CLOSED' ? rec.startLevel : form.values.vus,
      durationMinutes: rec.durationMinutes,
      peakRate: rec.explicitStages.length > 0 ? rec.explicitStages.at(-1)!.level : '',
      stages: rec.explicitStages.length,
      // Carried through verbatim for Progressive-Ramp/Staged so save reproduces exactly this ramp,
      // even when it isn't evenly spaced (e.g. a safety-capped Breakpoint ramp). Spike never uses
      // this — its four parameters are the state of record.
      explicitStages:
        shapeKind === 'PROGRESSIVE_RAMP' || shapeKind === 'STAGED' ? rec.explicitStages : null,
      spikeParams:
        shapeKind === 'SPIKE' ? spikeParamsFromStages(rec.explicitStages) : form.values.spikeParams,
    });
  }

  /** Rate/Stages/Duration diverging from the last-applied recommendation means the ramp is no
   *  longer that recommendation's — reverts to the ordinary equal-ramp reconstruction rather than
   *  silently keeping a stage list that no longer matches what's on screen. */
  function editRampField(field: 'peakRate' | 'stages' | 'durationMinutes', value: number | string) {
    form.setFieldValue(field, value as never);
    if (form.values.explicitStages !== null) {
      form.setFieldValue('explicitStages', null);
    }
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
  // Editing always shows every shape — narrowing by intent-relevance could hide the very shape a
  // saved test already uses. Creating shows only what's relevant until "Customize workload".
  const availableShapeKinds: ShapeKind[] =
    form.values.advanced || editing
      ? ALL_SHAPE_KINDS.map((sk) => sk.value)
      : ((recommendationQuery.data?.availableShapeKinds as ShapeKind[] | undefined) ?? ['STEADY']);

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
            {!editing && (
              <RecommendedWorkloadCard
                serviceId={serviceId}
                type={form.values.type}
                model={form.values.model}
                typeLabel={selectedType?.label ?? form.values.type}
                onApply={applyRecommendation}
              />
            )}

            <Text size="xs" fw={650} tt="uppercase" c="dimmed" mb={6}>
              How should load be controlled?
            </Text>
            <Group gap={6} align="center" mb="md" wrap="nowrap">
              <Radio.Group {...form.getInputProps('model')}>
                <div className={classes.modelGrid}>
                  {WORKLOAD_MODELS.map((wm) => (
                    <Radio.Card key={wm.value} value={wm.value} className={classes.modelTile}>
                      <span className={classes.modelPrimary}>{wm.label}</span>
                      <span className={classes.modelSecondary}>{wm.technicalLabel}</span>
                    </Radio.Card>
                  ))}
                </div>
              </Radio.Group>
              <InfoPopover icon ariaLabel="About traffic models" width={300}>
                {WORKLOAD_MODELS.map((wm) => (
                  <Text key={wm.value} size="xs" mb={4}>
                    <strong>
                      {wm.label} ({wm.technicalLabel})
                    </strong>{' '}
                    — {wm.question}
                  </Text>
                ))}
              </InfoPopover>
            </Group>

            {availableShapeKinds.length > 1 && (
              <>
                <Text size="xs" fw={650} tt="uppercase" c="dimmed" mb={6}>
                  How should the load behave?
                </Text>
                <div className={classes.shapeGrid}>
                  {ALL_SHAPE_KINDS.filter((sk) => availableShapeKinds.includes(sk.value)).map((sk) => (
                    <button
                      key={sk.value}
                      type="button"
                      className={classes.shapeTile}
                      data-checked={form.values.shapeKind === sk.value || undefined}
                      onClick={() => form.setFieldValue('shapeKind', sk.value)}
                    >
                      {sk.label}
                    </button>
                  ))}
                </div>
              </>
            )}

            {!editing && !form.values.advanced && availableShapeKinds.length <= 1 && (
              <button
                type="button"
                className={classes.customizeToggle}
                onClick={() => form.setFieldValue('advanced', true)}
              >
                Customize workload
              </button>
            )}

            {form.values.model === 'CLOSED' && form.values.shapeKind === 'STEADY' && (
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

            {form.values.model === 'OPEN' && form.values.shapeKind === 'STEADY' && (
              <Group gap="md" align="flex-end" wrap="wrap">
                <NumberInput
                  label="Rate"
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
            )}

            {(form.values.shapeKind === 'PROGRESSIVE_RAMP' || form.values.shapeKind === 'STAGED') && (
              <Group gap="md" align="flex-end" wrap="wrap">
                <NumberInput
                  label="Target"
                  description={form.values.model === 'OPEN' ? 'requests/sec' : 'concurrent users'}
                  min={0}
                  step={form.values.model === 'OPEN' ? 0.1 : 1}
                  w={150}
                  value={form.values.peakRate}
                  onChange={(v) => editRampField('peakRate', v)}
                />
                <Text size="sm" c="dimmed" pb={6}>
                  over
                </Text>
                <NumberInput
                  label="Stages"
                  min={2}
                  max={20}
                  w={100}
                  value={form.values.stages}
                  onChange={(v) => editRampField('stages', v)}
                />
                <Text size="sm" c="dimmed" pb={6}>
                  during
                </Text>
                <NumberInput
                  label="Total duration"
                  description="minutes"
                  min={1}
                  w={120}
                  value={form.values.durationMinutes}
                  onChange={(v) => editRampField('durationMinutes', v)}
                />
              </Group>
            )}

            {form.values.shapeKind === 'SPIKE' && (
              <SpikeParamsEditor
                value={form.values.spikeParams}
                onChange={(next) => form.setFieldValue('spikeParams', next)}
              />
            )}

            {form.values.explicitStages === null &&
              (form.values.shapeKind === 'PROGRESSIVE_RAMP' || form.values.shapeKind === 'STAGED') &&
              form.values.stages > 0 && (
                <Text size="xs" c="dimmed" mt={8}>
                  Split evenly across {form.values.stages} stages —{' '}
                  {(form.values.durationMinutes / form.values.stages).toLocaleString(undefined, {
                    maximumFractionDigits: 1,
                  })}{' '}
                  min each
                </Text>
              )}
            {form.values.explicitStages === null &&
              (form.values.shapeKind === 'PROGRESSIVE_RAMP' || form.values.shapeKind === 'STAGED') &&
              form.values.peakRate !== '' &&
              form.values.stages > 0 && (
                <Text size="xs" c="dimmed" mt={4}>
                  Stages are evenly spaced from{' '}
                  {(form.values.peakRate / form.values.stages).toLocaleString(undefined, {
                    maximumFractionDigits: 1,
                  })}{' '}
                  to {form.values.peakRate} — there is no separate starting level to set.
                </Text>
              )}

            {!editing && form.values.advanced === false && availableShapeKinds.length > 1 && (
              <Text size="xs" c="dimmed" mt={8}>
                Showing the shapes that make sense for {selectedType?.label ?? 'this intent'}.{' '}
                <button
                  type="button"
                  className={classes.customizeToggle}
                  style={{ display: 'inline', marginBottom: 0 }}
                  onClick={() => form.setFieldValue('advanced', true)}
                >
                  Customize workload
                </button>
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
