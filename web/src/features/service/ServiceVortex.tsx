import { useId, useMemo, useState } from 'react';
import { Button, Text, Title, Tooltip, UnstyledButton } from '@mantine/core';
import { motion, useReducedMotion } from 'motion/react';
import { IconArrowRight, IconCheck, IconGitBranch } from '@tabler/icons-react';
import { useConfigurationQuery } from '../../api/configuration';
import { EnvironmentDrawer, type EnvironmentDrawerState } from './configuration/EnvironmentDrawer';
import type { ServiceHeader as Header, ReadinessItem } from '../../api/workspace';
import { ImportOpenApiDrawer } from './ImportOpenApiDrawer';
import { ObjectivesDrawer } from './ObjectivesDrawer';
import { ProductionTrafficDrawer } from './ProductionTrafficDrawer';
import { SignalIcon } from './SignalIcon';
import classes from './ServiceVortex.module.css';

/** Every step whose CTA opens a scoped drawer instead of navigating to the Configuration page —
 *  "Workload defined" is the deliberate exception: `TestComposer` is a page-sized form (operation
 *  mix, load shape, live chart, recommendations), not a quick-add one, so it keeps its full-page
 *  destination. */
const DRAWER_KEYS = new Set(['API_IMPORTED', 'ENVIRONMENT', 'OBJECTIVES', 'PRODUCTION_TRAFFIC']);

/**
 * What a service looks like before it can run a meaningful experiment.
 *
 * <p>Not a settings form — the Configuration page already is one, and every CTA below hands off to
 * it rather than restating it. This page's only job is orientation: which one thing is most useful
 * to do next, why Vortex needs it, and how far along the experiment-in-waiting already is. Answering
 * that is the whole point, so every state on screen answers the same three questions — where am I,
 * what's next, why does it matter — rather than listing configuration fields.
 *
 * <h2>A pipeline, not a wizard</h2>
 *
 * <p>{@link mainSequence} is a fixed, opinionated order — contract, target, workload, objectives,
 * reality — because that is the order a performance question actually gets assembled in, not
 * because anything here refuses to be configured out of order. A signal reachable ahead of the
 * current one is still a real, independently clickable node (see {@link PipelineStep}); the
 * "current" step is simply whichever unsatisfied signal is *first* in that fixed order, so
 * completing things out of sequence just means a later node quietly shows done while an earlier one
 * keeps the stage.
 *
 * <p>Production traffic sits last in that sequence, not off to the side: {@code Kind.GROUNDING} in
 * the domain means Vortex will not call a project ready without it, even though — like importing
 * the API — it never gates a run itself. What still renders as its own optional branch is
 * {@code AVERAGE_LOAD_WORKLOAD}: a workload sized specifically to compare future releases against,
 * genuinely nice-to-have rather than unavoidable, and only reachable once a workload exists at all.
 *
 * <h2>What it will not do</h2>
 *
 * <p>It will not hold the stage once there is nothing left to decide: once contract, target,
 * workload and objectives are all satisfied, the active slot becomes a plain confirmation with one
 * CTA — Vortex has enough to run something — and this whole screen is moments from being replaced by
 * the ordinary workbench anyway (see {@code isUnconfigured} in {@code OverviewPage}). It will not
 * celebrate a finished step either: a node just compresses and gains a checkmark, a statement about
 * state, not a reward.
 */
export function ServiceVortex({
  header,
  serviceId,
}: {
  header: Header;
  serviceId: string;
}) {
  const headingId = useId();
  // `useReducedMotion` returns null before it has resolved, so compare rather than coerce.
  const reducedMotion = useReducedMotion() === true;
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [importDrawerOpen, setImportDrawerOpen] = useState(false);
  const [environmentDrawerState, setEnvironmentDrawerState] = useState<EnvironmentDrawerState | null>(null);
  const [objectivesDrawerOpen, setObjectivesDrawerOpen] = useState(false);
  const [productionDrawerOpen, setProductionDrawerOpen] = useState(false);
  // Only the drawers above need Configuration's data (environment options, current thresholds, the
  // catalog for production's per-operation weights) — fetched once here rather than threading it
  // down from a page that isn't mounted while this one is.
  const configQuery = useConfigurationQuery(serviceId);

  function openDrawerFor(key: string) {
    switch (key) {
      case 'API_IMPORTED':
        setImportDrawerOpen(true);
        break;
      case 'ENVIRONMENT':
        setEnvironmentDrawerState({ mode: 'create' });
        break;
      case 'OBJECTIVES':
        setObjectivesDrawerOpen(true);
        break;
      case 'PRODUCTION_TRAFFIC':
        setProductionDrawerOpen(true);
        break;
    }
  }

  const sequence = useMemo(() => mainSequence(header.readiness.items), [header.readiness.items]);
  const branch = useMemo(() => optionalBranch(header.readiness.items), [header.readiness.items]);
  const dueNext = sequence.find((item) => !item.satisfied) ?? null;
  const allDone = dueNext === null;

  const selectedItem = sequence.find((item) => item.key === selectedKey)
    ?? (branch?.key === selectedKey ? branch : null);
  const activeItem = selectedItem ?? dueNext;

  const percent = Math.round(
    (sequence.filter((item) => item.satisfied).length / Math.max(sequence.length, 1)) * 100,
  );

  return (
    <section className={classes.setup} aria-labelledby={headingId}>
      <div className={classes.intro}>
        <Text className={classes.eyebrow}>Get this service ready</Text>
        <Title order={2} id={headingId} className={classes.headline}>
          {allDone
            ? "Vortex has what it needs to test this properly."
            : "Let's give Vortex enough context to test this properly."}
        </Title>
        <Text className={classes.supporting}>
          Give Vortex the API, somewhere to run against, and what "good" looks like. We'll handle
          the experiment from there.
        </Text>
      </div>

      <div className={classes.layout}>
        <div className={classes.main}>
          <ol className={classes.pipeline} aria-label="Steps to a runnable experiment">
            {sequence.map((item, index) => (
              <PipelineStep
                key={item.key}
                number={index + 1}
                item={item}
                header={header}
                status={stepStatus(item, item.key === activeItem?.key)}
                expanded={item.key === activeItem?.key}
                onSelect={setSelectedKey}
                reducedMotion={reducedMotion}
                onOpenDrawer={openDrawerFor}
              />
            ))}
            <ReadyStep expanded={allDone && activeItem?.key === undefined} allDone={allDone} serviceId={serviceId} />
          </ol>

          {branch && (
            <BranchStep
              item={branch}
              header={header}
              expanded={branch.key === activeItem?.key}
              onSelect={setSelectedKey}
              onOpenDrawer={openDrawerFor}
            />
          )}
        </div>

        <ReadinessPanel sequence={sequence} branch={branch} percent={percent} serviceId={serviceId} />
      </div>

      <ImportOpenApiDrawer
        serviceId={serviceId}
        opened={importDrawerOpen}
        onClose={() => setImportDrawerOpen(false)}
      />
      <EnvironmentDrawer
        state={environmentDrawerState}
        existingNames={(configQuery.data?.environments ?? []).map((e) => e.name)}
        environmentTypes={configQuery.data?.environmentTypes ?? []}
        dependencyModes={configQuery.data?.dependencyModes ?? []}
        serviceId={serviceId}
        onClose={() => setEnvironmentDrawerState(null)}
      />
      <ObjectivesDrawer
        serviceId={serviceId}
        thresholds={configQuery.data?.thresholds}
        opened={objectivesDrawerOpen}
        onClose={() => setObjectivesDrawerOpen(false)}
      />
      <ProductionTrafficDrawer
        serviceId={serviceId}
        catalog={configQuery.data?.catalog}
        opened={productionDrawerOpen}
        onClose={() => setProductionDrawerOpen(false)}
      />
    </section>
  );
}

// ---------------------------------------------------------------- sequencing

/** Fixed pipeline order — the order a performance question is actually assembled in. */
const SEQUENCE_RANK: Record<string, number> = {
  API_IMPORTED: 0,
  ENVIRONMENT: 1,
  WORKLOAD: 2,
  AVERAGE_LOAD_WORKLOAD: 2,
  OBJECTIVES: 3,
  PRODUCTION_TRAFFIC: 4,
};

/**
 * The unavoidable signals, in the fixed order above. RESULT is excluded because "Test executed" is
 * not a signal you configure — it becomes true once a run has happened. Anything that merely
 * narrows a signal still outstanding is excluded too — `distinct` in the domain decides that; once
 * a workload exists it becomes its own node instead of `AVERAGE_LOAD_WORKLOAD` staying visible
 * alongside it.
 */
function mainSequence(items: ReadinessItem[]): ReadinessItem[] {
  return items
    .filter((item) => item.kind !== 'RESULT' && item.distinct && item.effectivelyRequired)
    .sort((a, b) => (SEQUENCE_RANK[a.key] ?? 99) - (SEQUENCE_RANK[b.key] ?? 99));
}

/** The one signal that only strengthens the evidence rather than gating anything — never plural,
 *  today, but written to not assume that stays true. */
function optionalBranch(items: ReadinessItem[]): ReadinessItem | null {
  return items.find((item) => item.kind !== 'RESULT' && item.distinct && !item.effectivelyRequired) ?? null;
}

type StepStatus = 'done' | 'active' | 'upcoming' | 'blocked';

function stepStatus(item: ReadinessItem, isActive: boolean): StepStatus {
  if (item.satisfied) return 'done';
  if (!item.available) return 'blocked';
  return isActive ? 'active' : 'upcoming';
}

// ---------------------------------------------------------------- copy

interface StepCopy {
  eyebrow: string;
  question: string;
  explanation: string;
  hint?: string;
  unlocks: string;
}

const STEP_COPY: Record<string, StepCopy> = {
  API_IMPORTED: {
    eyebrow: 'Give Vortex the contract',
    question: 'What can this service actually do?',
    explanation:
      'Import an OpenAPI document and Vortex will discover the operations available for testing.',
    hint: 'Works from a URL, a pasted document, or an existing vortex.yaml.',
    unlocks: 'Operation discovery — Vortex learns what this service can do.',
  },
  ENVIRONMENT: {
    eyebrow: 'Where do we hit?',
    question: 'Where should the traffic go?',
    explanation:
      'Point Vortex at an address it can actually reach — local, staging, or a real environment.',
    unlocks: 'Execution becomes possible — Vortex has somewhere to run a test.',
  },
  WORKLOAD: {
    eyebrow: 'What traffic?',
    question: 'What kind of pressure are we creating?',
    explanation:
      'Describe the mix of operations and the shape of load — smoke, average, stress, spike, or breakpoint.',
    unlocks: 'Vortex knows how much traffic to generate, and in what shape.',
  },
  OBJECTIVES: {
    eyebrow: 'What counts as good?',
    question: 'What counts as acceptable?',
    explanation: 'State the latency and error thresholds a passing run has to meet.',
    unlocks: 'Results can be interpreted, not merely graphed.',
  },
  PRODUCTION_TRAFFIC: {
    eyebrow: 'Calibrate with reality',
    question: 'What does reality look like?',
    explanation:
      "Tell Vortex what production normally looks like and it can anchor your workload to something real.",
    hint: "A ballpark figure is fine — this doesn't require a live observation source, even before the service is really in production.",
    unlocks: 'Workloads can be calibrated against real traffic.',
  },
};
STEP_COPY.AVERAGE_LOAD_WORKLOAD = STEP_COPY.WORKLOAD;

/** A key nobody has written bespoke copy for falls back to the domain's own words rather than
 *  rendering a hole. */
function copyFor(item: ReadinessItem): StepCopy {
  return (
    STEP_COPY[item.key] ?? {
      eyebrow: item.label,
      question: item.label,
      explanation: item.nextStep,
      unlocks: item.nextStep,
    }
  );
}

// ---------------------------------------------------------------- pipeline nodes

/**
 * One node of the fixed sequence — a big hero card while it holds the stage, a compact row
 * otherwise. Every state is the same underlying control: clicking a compact node, whatever its
 * status, brings it forward, because a signal reachable ahead of the current one is still real and
 * still clickable. What changes between states is how much room the node earns, never whether it
 * can be reached.
 */
function PipelineStep({
  number,
  item,
  header,
  status,
  expanded,
  onSelect,
  reducedMotion,
  onOpenDrawer,
}: {
  number: number;
  item: ReadinessItem;
  header: Header;
  status: StepStatus;
  expanded: boolean;
  onSelect: (key: string | null) => void;
  reducedMotion: boolean;
  onOpenDrawer: (key: string) => void;
}) {
  const copy = copyFor(item);

  return (
    <motion.li layout={!reducedMotion} className={classes.step} data-status={status}>
      <span className={classes.marker} aria-hidden="true">
        {status === 'done' ? <IconCheck size={16} stroke={2.4} /> : number}
      </span>

      {expanded ? (
        <ActiveCard copy={copy} item={item} header={header} onJump={onSelect} onOpenDrawer={onOpenDrawer} />
      ) : (
        <CompactStep number={number} copy={copy} item={item} status={status} onSelect={onSelect} />
      )}
    </motion.li>
  );
}

function CompactStep({
  copy,
  item,
  status,
  onSelect,
}: {
  number: number;
  copy: StepCopy;
  item: ReadinessItem;
  status: StepStatus;
  onSelect: (key: string | null) => void;
}) {
  return (
    <Tooltip label={copy.question} openDelay={350} withArrow position="right">
      <UnstyledButton
        className={classes.compactRow}
        onClick={() => onSelect(item.key)}
        aria-label={
          status === 'done'
            ? `${item.label}, done`
            : status === 'blocked'
              ? `${item.label}, not available yet`
              : item.label
        }
      >
        <span className={classes.compactLabel}>{item.label}</span>
        <span className={classes.compactMeta}>
          {status === 'done' ? 'Done' : status === 'blocked' ? 'Not available yet' : copy.question}
        </span>
      </UnstyledButton>
    </Tooltip>
  );
}

/** The hero card — explanation, what it unlocks, and a hand-off to the real configuration
 *  experience. Never a form of its own; see the file doc comment. */
function ActiveCard({
  copy,
  item,
  header,
  onJump,
  onOpenDrawer,
}: {
  copy: StepCopy;
  item: ReadinessItem;
  header: Header;
  onJump: (key: string | null) => void;
  onOpenDrawer?: (key: string) => void;
}) {
  if (!item.available) {
    return <BlockedCard item={item} onJump={onJump} />;
  }

  if (item.satisfied) {
    return <DoneCard item={item} header={header} />;
  }

  return (
    <div className={classes.activeCard}>
      <div className={classes.activeBody}>
        <Text className={classes.activeEyebrow}>{copy.eyebrow.toUpperCase()}</Text>
        <Title order={3} className={classes.activeQuestion}>
          {copy.question}
        </Title>
        <Text className={classes.activeExplanation}>{copy.explanation}</Text>

        {onOpenDrawer && DRAWER_KEYS.has(item.key) ? (
          <Button
            onClick={() => onOpenDrawer(item.key)}
            rightSection={<IconArrowRight size={15} />}
            className={classes.activeCta}
          >
            {ctaLabel(item)}
          </Button>
        ) : (
          <Button
            component="a"
            href={item.href}
            rightSection={<IconArrowRight size={15} />}
            className={classes.activeCta}
          >
            {ctaLabel(item)}
          </Button>
        )}

        {copy.hint && <Text className={classes.activeHint}>{copy.hint}</Text>}

        <Text className={classes.activeUnlocks}>
          <strong>Unlocks:</strong> {copy.unlocks}
        </Text>
      </div>

      <div className={classes.illustration} aria-hidden="true">
        <span className={classes.illustrationGlow} />
        <span className={classes.illustrationTile}>
          <SignalIcon signalKey={item.key} size={34} />
        </span>
      </div>
    </div>
  );
}

/**
 * A satisfied node brought forward — a quiet confirmation with the domain's own real numbers, never
 * the question-and-CTA copy the same node showed while it was still outstanding. `View` hands off
 * to the same Configuration section, since "done" is not "unreachable".
 */
function DoneCard({ item, header }: { item: ReadinessItem; header: Header }) {
  return (
    <div className={classes.activeCard} data-done="true">
      <div className={classes.activeBody}>
        <Text className={classes.activeEyebrow}>DONE</Text>
        <Title order={3} className={classes.activeQuestion}>
          {item.label}
        </Title>
        <Text className={classes.activeExplanation}>{doneSummaryFor(item, header)}</Text>
        <Button
          component="a"
          href={item.href}
          variant="light"
          rightSection={<IconArrowRight size={15} />}
          className={classes.activeCta}
        >
          View
        </Button>
      </div>
    </div>
  );
}

/** What to say a satisfied node actually accomplished — the domain's own figures where this
 *  component already has them on hand, rather than restating the generic "unlocks" sentence. */
function doneSummaryFor(item: ReadinessItem, header: Header): string {
  switch (item.key) {
    case 'API_IMPORTED':
      return `${header.operationCount} operation${header.operationCount === 1 ? '' : 's'} discovered.`;
    case 'ENVIRONMENT':
      return header.target ? `Pointed at ${header.target.environmentName}.` : 'Target configured.';
    case 'WORKLOAD':
    case 'AVERAGE_LOAD_WORKLOAD':
      return `${header.testCount} test${header.testCount === 1 ? '' : 's'} defined.`;
    case 'OBJECTIVES':
      return 'Latency and error thresholds are set.';
    case 'PRODUCTION_TRAFFIC':
      return 'Recorded — workloads can calibrate against it.';
    default:
      return 'Done.';
  }
}

function ctaLabel(item: ReadinessItem): string {
  switch (item.key) {
    case 'API_IMPORTED':
      return 'Import OpenAPI';
    case 'ENVIRONMENT':
      return 'Add a target';
    case 'WORKLOAD':
    case 'AVERAGE_LOAD_WORKLOAD':
      return 'Describe a workload';
    case 'OBJECTIVES':
      return 'Set objectives';
    case 'PRODUCTION_TRAFFIC':
      return 'Record production traffic';
    default:
      return 'Configure';
  }
}

/**
 * What is in the way, and the way to it. A blocked node never dead-ends — the prerequisite is a
 * real button right here, jumping the stage straight to it, the same as clicking that node in the
 * pipeline itself would.
 */
function BlockedCard({ item, onJump }: { item: ReadinessItem; onJump: (key: string | null) => void }) {
  return (
    <div className={classes.blockedCard}>
      <Text className={classes.activeEyebrow}>NOT AVAILABLE YET</Text>
      <Title order={3} className={classes.activeQuestion}>
        {item.label}
      </Title>
      <Text className={classes.activeExplanation}>{item.blockedReason}</Text>
      {item.blockedBy.length > 0 && (
        <Button
          variant="light"
          rightSection={<IconArrowRight size={15} />}
          onClick={() => onJump(item.blockedBy[0])}
          className={classes.activeCta}
        >
          Do that first
        </Button>
      )}
    </div>
  );
}

/** The branch that never gates anything — visually a fork off the main pipeline rather than a fifth
 *  rung on the same ladder. */
function BranchStep({
  item,
  header,
  expanded,
  onSelect,
  onOpenDrawer,
}: {
  item: ReadinessItem;
  header: Header;
  expanded: boolean;
  onSelect: (key: string | null) => void;
  onOpenDrawer: (key: string) => void;
}) {
  const copy = copyFor(item);

  return (
    <div className={classes.branch} data-status={item.satisfied ? 'done' : 'open'}>
      <IconGitBranch size={14} className={classes.branchGlyph} aria-hidden="true" />
      {expanded ? (
        <ActiveCard copy={copy} item={item} header={header} onJump={onSelect} onOpenDrawer={onOpenDrawer} />
      ) : (
        <UnstyledButton
          className={classes.branchRow}
          onClick={() => onSelect(item.key)}
          aria-label={item.satisfied ? `${item.label}, done` : item.label}
        >
          <Text className={classes.branchEyebrow}>Optional · {copy.eyebrow}</Text>
          <Text className={classes.compactLabel}>{item.label}</Text>
          <Text className={classes.compactMeta}>
            {item.satisfied ? 'Done' : copy.question}
          </Text>
        </UnstyledButton>
      )}
    </div>
  );
}

/** The pipeline's terminal node — a milestone, not a signal, so it is never independently clickable
 *  and never itself in `mainSequence`. */
function ReadyStep({
  expanded,
  allDone,
  serviceId,
}: {
  expanded: boolean;
  allDone: boolean;
  serviceId: string;
}) {
  return (
    <li className={classes.step} data-status={allDone ? 'active' : 'upcoming'}>
      <span className={classes.marker} aria-hidden="true">
        {allDone ? <IconCheck size={16} stroke={2.4} /> : <IconArrowRight size={14} />}
      </span>
      {expanded ? (
        <div className={classes.activeCard}>
          <div className={classes.activeBody}>
            <Text className={classes.activeEyebrow}>READY TO EXPERIMENT</Text>
            <Title order={3} className={classes.activeQuestion}>
              Vortex has what it needs.
            </Title>
            <Text className={classes.activeExplanation}>
              The contract, a target, a workload and a definition of "good" are all in place —
              enough for a reproducible experiment.
            </Text>
            <Button
              component="a"
              href={`/services/${serviceId}?compose=new`}
              rightSection={<IconArrowRight size={15} />}
              className={classes.activeCta}
            >
              Run first test
            </Button>
          </div>
        </div>
      ) : (
        <div className={classes.compactRow} aria-hidden="true">
          <span className={classes.compactLabel}>Ready to experiment</span>
          <span className={classes.compactMeta}>Once everything above is in place</span>
        </div>
      )}
    </li>
  );
}

// ---------------------------------------------------------------- readiness panel

/**
 * The right-hand context panel — what Vortex already knows, what it still needs, and what changes
 * once it has it. Transforms outright once every unavoidable signal is satisfied, because the page
 * should stop feeling like onboarding the moment onboarding is actually done.
 */
function ReadinessPanel({
  sequence,
  branch,
  percent,
  serviceId,
}: {
  sequence: ReadinessItem[];
  branch: ReadinessItem | null;
  percent: number;
  serviceId: string;
}) {
  const done = sequence.filter((item) => item.satisfied);
  const remaining = sequence.filter((item) => !item.satisfied);
  const allDone = remaining.length === 0;

  if (allDone) {
    return (
      <aside className={classes.panel} aria-label="Service readiness">
        <Text className={classes.panelEyebrow}>Ready for an experiment</Text>
        <ul className={classes.readyList}>
          {sequence.map((item) => (
            <li key={item.key} className={classes.readyRow}>
              <span>{item.label}</span>
              <IconCheck size={15} stroke={2.4} className={classes.readyCheck} />
            </li>
          ))}
        </ul>
        <Button
          component="a"
          href={`/services/${serviceId}?compose=new`}
          fullWidth
          rightSection={<IconArrowRight size={15} />}
        >
          Run first test
        </Button>
      </aside>
    );
  }

  return (
    <aside className={classes.panel} aria-label="Service readiness">
      <Text className={classes.panelEyebrow}>Service readiness</Text>
      <div className={classes.panelBar} role="presentation">
        <div className={classes.panelBarFill} style={{ width: `${percent}%` }} />
      </div>

      {done.length > 0 && (
        <div className={classes.panelGroup}>
          <Text className={classes.panelGroupLabel}>Known</Text>
          {done.map((item) => (
            <div key={item.key} className={classes.panelItem} data-done="true">
              <IconCheck size={13} stroke={2.4} />
              <span>{item.label}</span>
            </div>
          ))}
        </div>
      )}

      <div className={classes.panelGroup}>
        <Text className={classes.panelGroupLabel}>Still needed</Text>
        {remaining.map((item) => (
          <div key={item.key} className={classes.panelItem}>
            <span className={classes.panelDot} aria-hidden="true" />
            <span>{item.label}</span>
          </div>
        ))}
      </div>

      {branch && (
        <div className={classes.panelGroup}>
          <Text className={classes.panelGroupLabel}>Optional</Text>
          <div className={classes.panelItem} data-done={branch.satisfied ? 'true' : undefined}>
            {branch.satisfied ? <IconCheck size={13} stroke={2.4} /> : <span className={classes.panelDot} aria-hidden="true" />}
            <span>{branch.label}</span>
          </div>
        </div>
      )}

      <Text className={classes.panelFooter}>
        When these are ready, Vortex can run a reproducible experiment against this service.
      </Text>
    </aside>
  );
}
