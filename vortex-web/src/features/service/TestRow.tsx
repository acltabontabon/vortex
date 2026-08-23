import { ActionIcon, Button, Collapse, Menu, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { IconChevronDown, IconDots } from '@tabler/icons-react';
import { motion } from 'motion/react';
import type { Production, RunRef, TestRow as Test } from '../../api/workspace';
import { rowContentState, shortRate, shortRelativeTime } from '../../lib/testState';
import { useDeleteTestMutation, useDuplicateTestMutation } from '../../api/tests';
import { VerdictBadge } from '../../components/VerdictBadge';
import { TestDetailsDrawer } from './TestDetailsDrawer';
import { PreflightDrawer } from './PreflightDrawer';
import { RunningTestPanel } from './RunningTestPanel';
import { TestResult } from './TestResult';
import classes from './TestRow.module.css';

/**
 * One configured, executable thing — and, expanded, its own evidence.
 *
 * <p>The primary control is `Run`; everything a person checks before pressing it — what question it
 * answers, what load it offers and for how long, what happened last time — is on the row. The row
 * itself is not a button: with `Run`, the kebab menu, and links to the latest run all living inside
 * it, a whole-row click target either had to swallow those (a trap for anyone aiming slightly off)
 * or special-case around them. The single, dedicated control for expanding — the chevron at the
 * row's far edge — has neither problem, so that is the only thing that opens or closes
 * {@link TestResult} directly underneath, inline, rather than in a separate section elsewhere on the
 * page. That placement is deliberate: a test's result belongs to the row it appears inside, and
 * proximity says so without a label.
 *
 * <p>A saturating test (STRESS/SPIKE/BREAKPOINT) that established a boundary states it plainly right
 * here, always visible, never gated behind expanding — `Verdict.FAIL` sits beside it, honest and
 * unrelabelled, and the boundary sentence is what stops that badge from reading as "something broke."
 *
 * <p>Everything that helps only once somebody has already decided to inspect a test's *definition*
 * rather than its result — the exact operation count, the arithmetic behind a derived level, the
 * full traffic split — lives in {@link TestDetailsDrawer}, reached from the row's own overflow menu
 * ("View definition"). The row's *result* — verdict, evidence — has its one canonical home inline
 * here instead; the drawer no longer repeats it.
 *
 * <p>A test that cannot run keeps its place in the list and says why, in the domain's own words. It
 * does not get a disabled button — a control that looks pressable and is not is a small betrayal
 * every time somebody clicks it — and it does not quietly disappear, because a test you cannot find
 * is worse than one you cannot start.
 *
 * <p>The row states level and duration — what somebody checks before pressing Run — and stops there.
 * Environment is on the row only when it differs from the service's own default (the header already
 * says `LOCAL`; repeating `local` on every test underneath it is the header's fact wearing a second
 * hat). Provenance is not on the row at all: it moved to {@link TestDetailsDrawer}, one click away,
 * because "how this number was chosen" is a question about the test's definition, not about whether
 * to run it — and the drawer, not the row, is where the test's definition lives now.
 *
 * <p>`running` is the service's own in-flight execution, if any (`Overview.header.running`) — never
 * a per-test flag, because only one run can be active per service at a time. {@link rowContentState}
 * turns that, plus whether this test has a `latestRun`, into the one thing every content region below
 * agrees on: `'running' | 'result' | 'empty'`. When it names this test, {@link RunningTestPanel} takes
 * the place of the facts/problems/drift block and the Run button: level and duration are pre-run
 * inputs that already happened, superseded by the panel's own live facts. The swap between the two is
 * wrapped in a layout transition, since the two subtrees are different heights and a CSS transition
 * alone cannot animate between them. When it names a *different* test, this row's Run button is
 * replaced with plain inert text rather than a greyed-out button — starting a second run isn't refused
 * so much as it isn't offered, and a control that looks pressable and isn't is worse than no control
 * at all.
 *
 * <p>A test's own *previous* result must never render underneath its own in-flight run — the state
 * that state was in before this fix, since the row above swapped correctly on `isThisRunning` while
 * the expandable result underneath only ever checked `selected`, entirely independently. A row left
 * open from before a re-run — or auto-selected on load, since "the most recently evaluated run" is
 * still this very run's previous one until the new run finishes — showed both at once: a live panel
 * on top of the very numbers it is in the middle of superseding. `Collapse`'s `expanded` now also
 * requires `rowState !== 'running'`, and the footer's own latest-run pill degrades to a subtle,
 * non-interactive single line (verdict and how long ago, no link) rather than either disappearing or
 * staying fully interactive — a result that's about to be superseded within minutes shouldn't carry
 * more visual weight than that.
 */
export function TestRow({
  serviceId,
  test,
  production = null,
  defaultEnvironment,
  running = null,
  selected = false,
  onSelect,
  onEdit,
}: {
  serviceId: string;
  test: Test;
  /** The service's production traffic — shared context every test's evidence rail draws against. */
  production?: Production | null;
  defaultEnvironment?: string | null;
  running?: RunRef | null;
  selected?: boolean;
  onSelect: (name: string) => void;
  /** Opens the inline composer on this test — replaces navigating to a separate edit page. */
  onEdit: (name: string) => void;
}) {
  const isThisRunning = running?.testName === test.name;
  const anotherRunning = running !== null && !isThisRunning;
  const rowState = rowContentState(test, isThisRunning);
  const [drawerOpened, drawer] = useDisclosure(false);
  const [runDrawerOpened, runDrawer] = useDisclosure(false);
  const duplicate = useDuplicateTestMutation(serviceId);
  const remove = useDeleteTestMutation(serviceId);

  function onDuplicate() {
    // Opens the composer on the new copy in place, rather than navigating to a separate edit page —
    // a duplicate exists to be renamed/adjusted immediately, and that's now a composer session, not
    // a destination.
    duplicate.mutate(test.name, {
      onSuccess: (response) => onEdit(response.name),
    });
  }

  function onDelete() {
    modals.openConfirmModal({
      title: `Delete '${test.name}'?`,
      children: (
        <Text size="sm">
          Previous runs are unaffected — each one kept the test it actually executed. This only
          removes the definition.
        </Text>
      ),
      labels: { confirm: 'Delete', cancel: 'Cancel' },
      confirmProps: { color: 'fail' },
      onConfirm: () =>
        remove.mutate(test.name, {
          onSuccess: () =>
            notifications.show({ message: `'${test.name}' deleted.`, color: 'pass' }),
        }),
    });
  }

  return (
    <>
      <article
        className={`${classes.row} ${test.runnable ? '' : classes.blocked} ${
          selected ? classes.selected : ''
        }`}
        data-test-row={test.name}
      >
        <div className={classes.head}>
          <h3 className={classes.name}>{test.name}</h3>
          <span className={classes.type}>{test.testTypeLabel}</span>
          {!test.runnable && <span className={classes.cannotRun}>cannot run</span>}
          <button
            type="button"
            className={classes.expandButton}
            aria-expanded={selected}
            aria-label={selected ? `Collapse ${test.name}'s result` : `Expand ${test.name}'s result`}
            onClick={() => onSelect(test.name)}
          >
            <IconChevronDown
              size={16}
              className={`${classes.chevron} ${selected ? classes.chevronOpen : ''}`}
              aria-hidden="true"
            />
          </button>
        </div>

        {/* The author's own objective where they wrote one, the test type's standard question
            otherwise — decided in Workload.question(), not here. */}
        <p className={classes.question}>{test.question}</p>

        <motion.div layout>
          {rowState === 'running' ? (
            <RunningTestPanel serviceId={serviceId} running={running!} />
          ) : (
            <>
              {/* Level and duration, spaced apart rather than dot-joined so the row reads as a small
                  instrument panel rather than a sentence. Environment only when it isn't the
                  service's own default; provenance not at all — both are one click away in the
                  drawer. */}
              <div className={classes.facts}>
                <span className={classes.fact}>{shortRate(test.levelDisplay)}</span>
                <span className={classes.fact}>{test.durationDisplay}</span>
                {test.environmentName && test.environmentName !== defaultEnvironment && (
                  <span className={classes.fact}>{test.environmentName}</span>
                )}
              </div>

              {!test.runnable && (
                <ul className={classes.problems}>
                  {test.problems.map((problem) => (
                    <li key={problem}>{problem}</li>
                  ))}
                </ul>
              )}

              {/* Production moved away from what this test assumes. Stated with its arithmetic and
                  never applied silently — accepting it is a decision about what the evidence will
                  mean. */}
              {test.drift?.kind === 'DRIFTED' && (
                <div className={classes.drift}>
                  <div className={classes.driftStatement}>{test.drift.statement}</div>
                  {test.drift.derivation && (
                    <div className={classes.driftWorking}>{test.drift.derivation}</div>
                  )}
                </div>
              )}
            </>
          )}
        </motion.div>

        {/* Always visible, never gated behind expanding — a saturating test's Verdict.FAIL sits in
            .foot below, honest and unrelabelled, and this sentence is what stops it from reading as
            "something broke" when the search actually found what it was looking for. */}
        {test.saturating && test.capacity && (
          <BoundaryStatement capacity={test.capacity} />
        )}

        <div className={classes.foot}>
          {rowState === 'running' ? (
            // Not a link — this result is about to be superseded within minutes, and shouldn't
            // carry more visual weight than a subtle trace of what it was.
            test.latestRun && (
              <span className={classes.previousResult}>
                <VerdictBadge
                  verdict={test.latestRun.verdict}
                  label={test.latestRun.verdictLabel}
                  subtleText
                />
                <span className={classes.when}>
                  Previous · {shortRelativeTime(test.latestRun.relativeTime)}
                </span>
              </span>
            )
          ) : test.latestRun ? (
            // Hidden once expanded: the verdict, how long ago, and the run count all reappear —
            // the verdict more prominently, the other two folded into one provenance line — in
            // TestResult directly below. Showing both at once said "Pass" twice in the same glance.
            !selected && (
              <a className={classes.latest} href={`/runs/${test.latestRun.id}`}>
                <VerdictBadge
                  verdict={test.latestRun.verdict}
                  label={test.latestRun.verdictLabel}
                  subtleText
                />
                <span className={classes.when}>{shortRelativeTime(test.latestRun.relativeTime)}</span>
                <span className={classes.count}>
                  · {test.runCount} run{test.runCount === 1 ? '' : 's'}
                </span>
              </a>
            )
          ) : (
            <span className={classes.never}>Never run</span>
          )}

          <div className={classes.actions}>
            {isThisRunning ? null : anotherRunning ? (
              // Not a disabled button — starting a second run isn't offered right now, and a control
              // styled to look pressable while doing nothing is worse than plain text saying why.
              <span className={classes.waitingForRun}>Waiting for {running!.testName}</span>
            ) : test.runnable ? (
              <Button onClick={runDrawer.open} size="xs">
                Run
              </Button>
            ) : (
              // The only control on a blocked row is one that actually goes somewhere.
              <Button
                component="a"
                href={`/services/${serviceId}/configuration#definition`}
                size="xs"
                variant="default"
              >
                Fix this
              </Button>
            )}

            <Menu shadow="md" width={170} position="bottom-end">
              <Menu.Target>
                <ActionIcon variant="subtle" color="gray" aria-label="More actions">
                  <IconDots size={16} />
                </ActionIcon>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item onClick={drawer.open}>View definition</Menu.Item>
                <Menu.Item onClick={() => onEdit(test.name)}>Edit test</Menu.Item>
                <Menu.Item onClick={onDuplicate} disabled={duplicate.isPending}>
                  Duplicate
                </Menu.Item>
                <Menu.Divider />
                <Menu.Item color="fail" onClick={onDelete} disabled={remove.isPending}>
                  Delete
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </div>
        </div>

        {/* keepMounted={false}: a service with several tests should not carry every test's result
            — chart, conditions list, headroom popover — in the DOM at once just because one row is
            expanded. `rowState !== 'running'` is the actual fix for the two-states-stacked bug: a
            test's own previous result must never render underneath its own in-flight run, no matter
            how the row came to be expanded. */}
        <Collapse expanded={selected && rowState !== 'running'} transitionDuration={200} keepMounted={false}>
          <TestResult test={test} production={production} />
        </Collapse>
      </article>

      <TestDetailsDrawer test={test} opened={drawerOpened} onClose={drawer.close} />
      <PreflightDrawer
        serviceId={serviceId}
        workload={test.name}
        environment={test.environmentName}
        opened={runDrawerOpened}
        onClose={runDrawer.close}
      />
    </>
  );
}

/**
 * Pairs the honest verdict with the domain's own boundary sentence for a saturating test — never a
 * relabelling of `Verdict.FAIL`, since a breakpoint test that finds its limit still gets FAIL exactly
 * like one that failed by accident. `boundaryStatus === 'ESTABLISHED'` is the domain's own answer to
 * "did the search actually find a clean edge," so that — not the verdict — is what decides whether
 * this reads as "as intended" or as an open note.
 */
function BoundaryStatement({ capacity }: { capacity: NonNullable<Test['capacity']> }) {
  if (capacity.boundaryStatus === 'ESTABLISHED') {
    return <p className={classes.boundaryFound}>Boundary found: {capacity.boundary}</p>;
  }
  return <p className={classes.boundaryNote}>{capacity.boundary}</p>;
}
