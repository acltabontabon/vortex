import type { RunSummary, TestRow, Verdict } from '../api/workspace';

/**
 * Pure state functions for the workspace, kept out of JSX for the same reason
 * {@link ./workbenchState} is: a rule expressed as a nested ternary inside a component is a rule
 * nobody can test and nobody finds again.
 *
 * None of this decides anything the domain decides. Runnability, verdicts, drift and refusals all
 * arrive already settled; what is below only chooses how to say them.
 */

export const VERDICT_COLOR: Record<Verdict, string> = {
  PASS: 'var(--mantine-color-pass-6)',
  FAIL: 'var(--mantine-color-fail-6)',
  // Neutral, deliberately. An objective that was never checked has not been met, and colouring it
  // like a pass or a failure would claim one of the two.
  NOT_EVALUATED: 'var(--mantine-color-neutral-6)',
};

export type TestState = 'blocked' | 'never-run' | 'pass' | 'fail' | 'unevaluated';

export function stateOfTest(test: TestRow): TestState {
  if (!test.runnable) return 'blocked';
  if (!test.latestRun) return 'never-run';
  if (test.latestRun.verdict === 'PASS') return 'pass';
  if (test.latestRun.verdict === 'FAIL') return 'fail';
  return 'unevaluated';
}

/**
 * What a test row's own content region shows right now — a separate question from
 * {@link TestState}, which is about a verdict. `'running'` and `'result'` are mutually exclusive by
 * construction: a test's own previous result must never render underneath its own in-flight run, no
 * matter how the row got expanded (an already-open row, or a fresh visit's auto-selection landing on
 * the test that is itself now running again).
 */
export type RowContentState = 'running' | 'result' | 'empty';

export function rowContentState(test: TestRow, isThisRunning: boolean): RowContentState {
  if (isThisRunning) return 'running';
  if (test.latestRun) return 'result';
  return 'empty';
}

/**
 * Where re-running a past run goes: the standalone Preflight page, reached by
 * `(service, test, environment)` and nothing else. A test row's own Run opens {@link
 * ./PreflightDrawer} in place instead — this link is only for the pages that aren't the service
 * workspace itself (a run's own page, the Runs list, Evidence's history).
 */
export function runAgainHref(serviceId: string, run: RunSummary): string {
  const params = new URLSearchParams({ workload: run.testName });
  if (run.environmentName) params.set('environment', run.environmentName);
  return `/services/${serviceId}/run?${params}`;
}

/** The tests that could be started right now, in configured order. */
export function runnableTests(tests: TestRow[]): TestRow[] {
  return tests.filter((test) => test.runnable);
}

/**
 * One line describing what a test offers, without repeating its name or type.
 *
 * Level, then duration, then environment — the facts somebody checks before pressing Run, in the
 * order they check them. Operation count is noise at that moment (nobody decides whether to run a
 * test based on how many operations it touches) so it's opt-in only, for contexts — a chooser with
 * no detail view behind it, a details drawer — where it's the only thing distinguishing two tests.
 */
export function testShapeLine(
  test: TestRow,
  options?: { includeOperationCount?: boolean },
): string {
  const parts = [test.levelDisplay, test.durationDisplay];
  if (test.environmentName) parts.push(test.environmentName);
  if (options?.includeOperationCount) {
    parts.push(test.operationCount === 1 ? '1 operation' : `${test.operationCount} operations`);
  }
  return parts.join(' · ');
}

/**
 * Where a test's numbers came from, with the production comparison when there is one.
 *
 * Provenance is never dropped: a conclusion inherits the confidence of its weakest input, and a
 * manually chosen 50 requests/sec must not read like a measured one.
 */
export function provenanceLine(test: TestRow): string {
  const source = test.source.detail
    ? `${test.source.describe} — ${test.source.detail}`
    : test.source.describe;
  return test.versusProduction ? `${source} · ${test.versusProduction}` : source;
}

/**
 * The same unit, shorter — never a different quantity. `50 requests/sec` and `50 req/s` are the same
 * fact, so abbreviating is a display choice, not a conversion: it only ever touches the compact
 * surfaces (the summary strip, the evidence scale, a test row, the details drawer) that need the
 * room back for the composition to read as an instrument rather than a sentence. The domain's own
 * strings stay untouched everywhere else — the Evidence tab, run pages, the chooser.
 */
export function shortRate(display: string): string {
  return display.replace(/requests\/sec/g, 'req/s');
}

const RELATIVE_TIME_UNIT: Record<string, string> = {
  second: 's',
  minute: 'm',
  hour: 'h',
  day: 'd',
  week: 'w',
  month: 'mo',
  year: 'y',
};

/**
 * `9 hours ago` → `9h` — the same abbreviation-not-conversion move as {@link shortRate}, for the one
 * place (a rail of several runs, a test row's own footer) where reading five full phrases in a
 * column is heavier than the fact warrants. Falls back to the original string for anything that
 * doesn't match the domain's own "N unit(s) ago" phrasing, so an unfamiliar format never disappears.
 */
export function shortRelativeTime(display: string): string {
  const match = display.match(/^(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago$/i);
  if (!match) return display;
  const [, amount, unit] = match;
  return `${amount}${RELATIVE_TIME_UNIT[unit.toLowerCase()]}`;
}

/**
 * Whether a run and the test behind it still describe the same experiment.
 *
 * Three answers, not two. `null` means the test no longer resolves — renamed, edited into something
 * unrunnable, or deleted — and that is not the same claim as "the definition moved", so it is never
 * rendered as one.
 */
export function driftedFromCurrent(run: RunSummary): boolean {
  return run.matchesCurrentTest === false;
}
