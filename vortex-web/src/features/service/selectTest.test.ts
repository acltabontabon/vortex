import { describe, expect, it } from 'vitest';
import type { Overview, RunSummary, TestRow } from '../../api/workspace';
import { resolveSelectedTest } from './selectTest';

function aRun(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    id: 'run-1',
    verdict: 'PASS',
    verdictLabel: 'Pass',
    stateLabel: 'Completed',
    terminal: true,
    testName: 'capacity-check',
    testType: 'AVERAGE_LOAD',
    testTypeLabel: 'Average load',
    levelDisplay: '50 requests/sec',
    environmentName: 'local',
    classification: 'ISOLATED',
    release: '2.17.0',
    answer: 'Objectives held at 50 requests/sec.',
    p95: '120 ms',
    durationDisplay: '1m',
    relativeTime: '44 minutes ago',
    isoTimestamp: '2026-08-22T04:55:00Z',
    matchesCurrentTest: true,
    differences: [],
    ...overrides,
  };
}

function aTest(overrides: Partial<TestRow> = {}): TestRow {
  return {
    name: 'capacity-check',
    description: null,
    question: 'Does the service meet its objectives under normal traffic?',
    testType: 'AVERAGE_LOAD',
    testTypeLabel: 'Average load',
    testTypeQuestion: 'Does the service meet its objectives under normal traffic?',
    saturating: false,
    model: 'OPEN',
    modelLabel: 'Arrival rate',
    levelDisplay: '50 requests/sec',
    levelUnit: 'requests/sec',
    durationDisplay: '1m',
    stageCount: 1,
    ramping: false,
    operationCount: 4,
    source: {
      kind: 'MANUAL',
      label: 'Manually entered',
      describe: 'Manually entered',
      detail: null,
      productionInformed: false,
      observedWindow: null,
      derivation: null,
    },
    versusProduction: null,
    runnable: true,
    problems: [],
    environmentName: 'local',
    latestRun: null,
    runCount: 0,
    drift: null,
    composition: [],
    compositionDrift: null,
    capacity: null,
    range: { renderable: false, unit: null, markers: [], openEnded: false },
    ...overrides,
  };
}

function anOverview(overrides: Partial<Overview> = {}): Overview {
  return {
    header: {
      id: 'checkout',
      name: 'checkout-service',
      description: null,
      target: null,
      environmentCount: 1,
      release: '2.17.0',
      readiness: {
        canRun: true,
        satisfiedCount: 7,
        totalCount: 7,
        blockerCount: 0,
        items: [],
        nextStepText: null,
      },
      operationCount: 4,
      testCount: 1,
      runCount: 1,
      running: null,
    },
    production: null,
    objectives: [],
    capacity: null,
    range: { renderable: false, unit: null, markers: [], openEnded: false },
    latestRun: null,
    tests: [],
    recentRuns: [],
    suggestSmokeTest: false,
    evidencePredatesRelease: false,
    releaseGapText: null,
    evidenceByTestType: [],
    ...overrides,
  };
}

describe('resolveSelectedTest', () => {
  it('returns null when the service has no tests configured', () => {
    expect(resolveSelectedTest(anOverview({ tests: [] }), null)).toBeNull();
  });

  it('honors an explicit selection that still names a real test', () => {
    const overview = anOverview({
      tests: [aTest({ name: 'capacity-check' }), aTest({ name: 'breakpoint-check' })],
    });

    expect(resolveSelectedTest(overview, 'breakpoint-check')!.name).toBe('breakpoint-check');
  });

  it('ignores an explicit selection that no longer names a configured test', () => {
    const overview = anOverview({ tests: [aTest({ name: 'capacity-check' })] });

    expect(resolveSelectedTest(overview, 'deleted-test')!.name).toBe('capacity-check');
  });

  it('treats an explicit empty selection as "collapse everything," not "pick a default"', () => {
    const overview = anOverview({
      tests: [
        aTest({
          name: 'capacity-check',
          latestRun: aRun({ verdict: 'PASS', isoTimestamp: '2026-08-22T04:00:00Z' }),
        }),
      ],
    });

    // Without the sentinel, resolveSelectedTest(overview, null) would pick capacity-check by rule
    // 1 — the empty string must short-circuit that, or a click meant to close the last open row
    // would instead reopen whatever the default-picking rules prefer.
    expect(resolveSelectedTest(overview, '')).toBeNull();
  });

  it('rule 1: selects the test behind the most recently evaluated run', () => {
    const overview = anOverview({
      tests: [
        aTest({
          name: 'capacity-check',
          latestRun: aRun({ verdict: 'PASS', isoTimestamp: '2026-08-22T04:00:00Z' }),
        }),
        aTest({
          name: 'breakpoint-check',
          latestRun: aRun({ verdict: 'FAIL', isoTimestamp: '2026-08-22T05:00:00Z' }),
        }),
      ],
    });

    expect(resolveSelectedTest(overview, null)!.name).toBe('breakpoint-check');
  });

  it('rule 1 skips a NOT_EVALUATED run even if it is the most recent one', () => {
    const overview = anOverview({
      tests: [
        aTest({
          name: 'capacity-check',
          latestRun: aRun({ verdict: 'PASS', isoTimestamp: '2026-08-22T04:00:00Z' }),
        }),
        aTest({
          name: 'smoke-check',
          latestRun: aRun({ verdict: 'NOT_EVALUATED', isoTimestamp: '2026-08-22T06:00:00Z' }),
        }),
      ],
    });

    expect(resolveSelectedTest(overview, null)!.name).toBe('capacity-check');
  });

  it('rule 2: falls back to the most recently executed test when nothing was evaluated', () => {
    const overview = anOverview({
      tests: [
        aTest({
          name: 'smoke-check',
          latestRun: aRun({ verdict: 'NOT_EVALUATED', isoTimestamp: '2026-08-22T04:00:00Z' }),
        }),
        aTest({
          name: 'newer-smoke-check',
          latestRun: aRun({ verdict: 'NOT_EVALUATED', isoTimestamp: '2026-08-22T05:00:00Z' }),
        }),
      ],
    });

    expect(resolveSelectedTest(overview, null)!.name).toBe('newer-smoke-check');
  });

  it('rule 3: falls back to the first configured test when nothing has ever run', () => {
    const overview = anOverview({
      tests: [aTest({ name: 'capacity-check' }), aTest({ name: 'breakpoint-check' })],
    });

    expect(resolveSelectedTest(overview, null)!.name).toBe('capacity-check');
  });

  it('compares timestamps numerically, not as strings, across differing fractional precision', () => {
    // '.' sorts before 'Z' in ASCII, so a naive string comparison would rank the run WITHOUT
    // fractional seconds as "later" than the one a full second after it that happens to carry
    // fractional digits — exactly backwards.
    const overview = anOverview({
      tests: [
        aTest({
          name: 'earlier-but-string-greater',
          latestRun: aRun({ verdict: 'PASS', isoTimestamp: '2026-08-22T04:00:00Z' }),
        }),
        aTest({
          name: 'later-but-string-lesser',
          latestRun: aRun({ verdict: 'PASS', isoTimestamp: '2026-08-22T04:00:00.500Z' }),
        }),
      ],
    });

    expect(resolveSelectedTest(overview, null)!.name).toBe('later-but-string-lesser');
  });
});
