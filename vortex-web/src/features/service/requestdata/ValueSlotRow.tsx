import { Anchor, Badge, Group, NumberInput, Select, Text, TextInput } from '@mantine/core';
import type {
  DatasetSummary,
  GeneratorInfo,
  ValueSlot,
  ValueSource,
} from '../../../api/requestData';
import classes from './RequestDataDrawer.module.css';

const SOURCES: { value: ValueSource; label: string }[] = [
  { value: '', label: 'Not set' },
  { value: 'fixed', label: 'Fixed' },
  { value: 'generated', label: 'Generated' },
  { value: 'dataset', label: 'Dataset' },
  { value: 'environment', label: 'Environment' },
];

/**
 * One configurable value: its name, where it comes from, and nothing else.
 *
 * <p>Progressive disclosure is the whole design here. Choosing "Fixed" reveals a value field;
 * choosing "Dataset" reveals a dataset and a column; the other three sources' controls are not
 * rendered at all rather than disabled. Ten values each showing every control they might need would
 * be the permanent wall of configuration this feature exists to avoid — and would make the common
 * case, which is two fixed values and a UUID, look like work.
 *
 * <p>Helper text appears only where a control is genuinely ambiguous. "Fixed" needs no explanation.
 */
export function ValueSlotRow({
  slot,
  datasets,
  generators,
  onChange,
}: {
  slot: ValueSlot;
  datasets: DatasetSummary[];
  generators: GeneratorInfo[];
  onChange: (next: ValueSlot) => void;
}) {
  const generator = generators.find((g) => g.key === slot.generator);
  const dataset = datasets.find(
    (d) => d.name === slot.dataset && d.scope === (slot.datasetScope ?? 'local')
  );

  function set(patch: Partial<ValueSlot>) {
    onChange({ ...slot, ...patch });
  }

  /** Choosing a source seeds the fields it needs, so a half-configured value is never savable. */
  function chooseSource(source: ValueSource) {
    switch (source) {
      case 'generated':
        set({ source, generator: slot.generator ?? 'uuid', lifecycle: slot.lifecycle ?? 'per-request' });
        break;
      case 'dataset': {
        const first = datasets[0];
        set({
          source,
          dataset: slot.dataset ?? first?.name ?? null,
          datasetScope: slot.datasetScope ?? first?.scope ?? 'local',
          field: slot.field ?? first?.fields[0] ?? null,
        });
        break;
      }
      default:
        set({ source });
    }
  }

  return (
    <div className={classes.slot}>
      <Group gap={6} wrap="nowrap">
        <span className={classes.slotName}>{slot.name}</span>
        {slot.required && (
          <Badge size="xs" variant="light" color="neutral">
            required
          </Badge>
        )}
      </Group>

      <div className={classes.controls}>
        <Select
          aria-label={`Source for ${slot.name}`}
          size="xs"
          data={SOURCES}
          value={slot.source}
          allowDeselect={false}
          onChange={(value) => chooseSource((value ?? '') as ValueSource)}
        />

        {slot.source === 'fixed' && <FixedControl slot={slot} onChange={set} />}

        {slot.source === 'generated' && (
          <div>
            <div className={classes.pair}>
              <Select
                aria-label={`Generator for ${slot.name}`}
                size="xs"
                data={generators.map((g) => ({ value: g.key, label: g.label }))}
                value={slot.generator}
                allowDeselect={false}
                onChange={(value) => set({ generator: value })}
              />
              <Select
                aria-label={`How often ${slot.name} is generated`}
                size="xs"
                data={[
                  { value: 'per-request', label: 'Every request' },
                  { value: 'per-vu', label: 'Every virtual user' },
                ]}
                value={slot.lifecycle ?? 'per-request'}
                allowDeselect={false}
                onChange={(value) => set({ lifecycle: value })}
              />
            </div>
            {generator?.usesRange && (
              <div className={classes.pair} style={{ marginTop: 8 }}>
                <NumberInput
                  aria-label={`Smallest value for ${slot.name}`}
                  size="xs"
                  placeholder="Smallest"
                  value={slot.minimum ?? 1}
                  onChange={(value) => set({ minimum: Number(value) })}
                />
                <NumberInput
                  aria-label={`Largest value for ${slot.name}`}
                  size="xs"
                  placeholder="Largest"
                  value={slot.maximum ?? 1000000}
                  onChange={(value) => set({ maximum: Number(value) })}
                />
              </div>
            )}
            {generator?.usesLength && (
              <NumberInput
                aria-label={`Length of ${slot.name}`}
                size="xs"
                mt={8}
                min={1}
                max={512}
                value={slot.length ?? 12}
                onChange={(value) => set({ length: Number(value) })}
              />
            )}
            {generator && <div className={classes.hint}>{generator.meaning}</div>}
          </div>
        )}

        {slot.source === 'dataset' &&
          (datasets.length === 0 ? (
            <Text size="xs" c="dimmed">
              This service has no datasets yet. Add one under Datasets to map values from it.
            </Text>
          ) : (
            <div>
              <div className={classes.pair}>
                <Select
                  aria-label={`Dataset for ${slot.name}`}
                  size="xs"
                  data={datasets.map((d) => ({
                    value: `${d.scope}:${d.name}`,
                    label: d.scope === 'portable' ? `${d.name} (committed)` : d.name,
                  }))}
                  value={slot.dataset ? `${slot.datasetScope ?? 'local'}:${slot.dataset}` : null}
                  allowDeselect={false}
                  onChange={(value) => {
                    const [scope, name] = (value ?? '').split(':');
                    const chosen = datasets.find((d) => d.name === name && d.scope === scope);
                    set({ dataset: name, datasetScope: scope, field: chosen?.fields[0] ?? null });
                  }}
                />
                <Select
                  aria-label={`Field of the dataset for ${slot.name}`}
                  size="xs"
                  data={dataset?.fields ?? []}
                  value={slot.field}
                  allowDeselect={false}
                  onChange={(value) => set({ field: value })}
                />
              </div>
              {dataset && (
                <div className={classes.hint}>
                  {dataset.records.toLocaleString()} records, walked in order
                </div>
              )}
            </div>
          ))}

        {slot.source === 'environment' && (
          <div>
            <TextInput
              aria-label={`Environment variable for ${slot.name}`}
              size="xs"
              placeholder="API_TOKEN"
              value={slot.environmentVariable ?? ''}
              onChange={(e) => set({ environmentVariable: e.currentTarget.value })}
            />
            {/* Whether it is set, never what it holds. Vortex resolves it only when it launches
                the load generator, and the value reaches nothing else. */}
            <div className={classes.hint}>
              {slot.environmentVariable
                ? slot.environmentSet
                  ? 'Set on this machine. Vortex reads it when the run starts.'
                  : 'Not set on this machine yet — export it before running.'
                : 'Read from the environment when the run starts. The value is never stored.'}
            </div>
          </div>
        )}
      </div>

      {slot.source === '' && slot.suggestion && (
        <SuggestionHint slot={slot} onAccept={(patch) => set(patch)} />
      )}
    </div>
  );
}

function FixedControl({
  slot,
  onChange,
}: {
  slot: ValueSlot;
  onChange: (patch: Partial<ValueSlot>) => void;
}) {
  // A specification that constrains a value to a fixed set gets a selector rather than a text
  // field: typing a value the service will reject is a mistake the schema can prevent.
  const choices = slot.suggestion?.choices ?? [];
  if (choices.length > 0) {
    return (
      <Select
        aria-label={`Value for ${slot.name}`}
        size="xs"
        data={choices}
        value={slot.literal || null}
        searchable={choices.length > 8}
        onChange={(value) => onChange({ literal: value ?? '' })}
      />
    );
  }
  return (
    <TextInput
      aria-label={`Value for ${slot.name}`}
      size="xs"
      value={slot.literal ?? ''}
      onChange={(e) => onChange({ literal: e.currentTarget.value })}
    />
  );
}

/**
 * What the API description implies, offered rather than applied.
 *
 * <p>A schema says how a value looks, not what it means: `format: uuid` on a customer id does not
 * say whether the customer should be new, existing, or the same one every time. So this appears as
 * a sentence with its reason and a link that accepts it, and never as a value Vortex has already
 * filled in.
 */
function SuggestionHint({
  slot,
  onAccept,
}: {
  slot: ValueSlot;
  onAccept: (patch: Partial<ValueSlot>) => void;
}) {
  const suggestion = slot.suggestion!;
  const label =
    suggestion.choices.length > 0
      ? `one of ${suggestion.choices.slice(0, 3).join(', ')}${
          suggestion.choices.length > 3 ? '…' : ''
        }`
      : suggestion.generator;

  return (
    <Text size="xs" c="dimmed">
      {suggestion.reason} —{' '}
      <Anchor
        component="button"
        type="button"
        size="xs"
        onClick={() =>
          onAccept(
            suggestion.choices.length > 0
              ? { source: 'fixed', literal: suggestion.choices[0] }
              : { source: 'generated', generator: suggestion.generator, lifecycle: 'per-request' }
          )
        }
      >
        use {label}
      </Anchor>
    </Text>
  );
}
