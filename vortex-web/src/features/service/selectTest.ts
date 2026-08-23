import type { Overview, TestRow } from '../../api/workspace';

/**
 * Which test's row is expanded.
 *
 * <p>An explicit selection (typically the `?test=` URL param, so a link is shareable and survives
 * back/forward) always wins when it still names a real test. An explicit *empty* selection
 * (`?test=`, distinct from the param being absent entirely) means every row was deliberately
 * collapsed — a user closing the one open accordion panel — and must not fall through to the
 * default-picking rules below, or a click meant to collapse the last open row would instead reopen
 * whichever test those rules prefer.
 *
 * <p>With no explicit opinion at all (the param absent — a fresh visit, not a click in this
 * session): the test behind the most recently *evaluated* run, then the most recently *executed*
 * test regardless of verdict, then the first configured test.
 *
 * <p>Both ranking rules scan every test's own `latestRun` rather than `overview.recentRuns` (capped
 * at a handful of rows) — each test's `latestRun` is already that test's own latest terminal
 * execution, so this is the most complete answer available without a new fetch.
 */
export function resolveSelectedTest(
  overview: Overview,
  requestedName: string | null,
): TestRow | null {
  if (overview.tests.length === 0) return null;
  if (requestedName === '') return null;

  const requested = requestedName && overview.tests.find((test) => test.name === requestedName);
  if (requested) return requested;

  const byMostRecentRun = (a: TestRow, b: TestRow) =>
    Date.parse(b.latestRun!.isoTimestamp) - Date.parse(a.latestRun!.isoTimestamp);

  const evaluated = overview.tests
    .filter((test) => test.latestRun && test.latestRun.verdict !== 'NOT_EVALUATED')
    .sort(byMostRecentRun);
  if (evaluated.length > 0) return evaluated[0];

  const everRun = overview.tests.filter((test) => test.latestRun !== null).sort(byMostRecentRun);
  if (everRun.length > 0) return everRun[0];

  return overview.tests[0];
}
