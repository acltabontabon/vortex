import { Text } from '@mantine/core';
import { IconArrowRight } from '@tabler/icons-react';
import type { Overview, Verdict } from '../../api/workspace';
import { shortRelativeTime, VERDICT_COLOR } from '../../lib/testState';
import classes from './RecentRunsRail.module.css';

/** Always exactly this many (or fewer, if the service has less history than that). */
const VISIBLE_COUNT = 5;

/**
 * Activity, and only activity — the workspace's other half, structurally attached to it rather than
 * floating beside it.
 *
 * <p>Drawn as a timeline — a connecting line through each run's own verdict-coloured node — rather
 * than a flat list of rows that happen to share a container. The rows *are* a sequence (one
 * service's history, newest first); the line is what says so at a glance, instead of leaving spacing
 * alone to imply it.
 *
 * <p>A row's primary action opens that exact run's own full result — never the test's *current*
 * latest run, which this historical one may or may not still be. The arrow only restates that
 * destination visually (revealed on hover/focus, matching the density this rail is for); the whole
 * row is the same link.
 */
export function RecentRunsRail({ overview, serviceId }: { overview: Overview; serviceId: string }) {
  const totalAvailable = overview.recentRuns.length;
  const shownRuns = overview.recentRuns.slice(0, Math.min(VISIBLE_COUNT, totalAvailable));

  // Grouped by what actually appears in this list, not by how many tests the service happens to
  // have configured — a service with three tests but one that ever runs shouldn't repeat that one
  // test's name five times any more than a service with one test should. Checked against every
  // fetched run, not just `shownRuns`, so a service whose 5 most recent runs happen to share a
  // test name but whose 6th-most-recent doesn't isn't misreported as single-test.
  const testNames = new Set(overview.recentRuns.map((run) => run.testName));
  const singleTestName = testNames.size === 1 ? shownRuns[0]?.testName : null;

  return (
    <section className={classes.railSection}>
      <div className={classes.railHead}>
        <div className={classes.railTitle}>Recent runs</div>
        {overview.header.runCount > shownRuns.length && (
          <a className={classes.more} href={`/services/${serviceId}/runs`}>
            View all
          </a>
        )}
      </div>

      {singleTestName && <div className={classes.railGroup}>{singleTestName}</div>}

      {shownRuns.length === 0 ? (
        <Text size="sm" c="dimmed">
          No runs yet.
        </Text>
      ) : (
        <div className={classes.timeline}>
          {shownRuns.map((run) => (
            <RunRow
              key={run.id}
              testName={run.testName}
              verdict={run.verdict}
              verdictLabel={run.verdictLabel}
              relativeTime={run.relativeTime}
              gist={run.answer}
              showName={!singleTestName}
              viewFullHref={`/runs/${run.id}`}
            />
          ))}
        </div>
      )}
    </section>
  );
}

function RunRow({
  testName,
  verdict,
  verdictLabel,
  relativeTime,
  gist,
  showName,
  viewFullHref,
}: {
  testName: string;
  verdict: Verdict;
  verdictLabel: string;
  relativeTime: string;
  /** The run's own one-sentence outcome (`RunSummary.answer`) — what "Pass"/"Fail" alone leaves out. */
  gist: string;
  showName: boolean;
  viewFullHref: string;
}) {
  return (
    // A real link, not a clickable `<div>` with a nested one for "view full result" — both used to
    // point at the same destination, which just meant a second, redundant hit target. One link
    // means native keyboard/middle-click/open-in-new-tab behaviour for free, none of it hand-rolled.
    <a className={classes.run} href={viewFullHref}>
      {/* The dot is this run's node on the timeline, not a repeat of VerdictBadge's inline one — it
          sits in its own column so the connecting line can pass through its centre. Colour lives on
          the dot only; the word beside it stays the page's normal text colour, same convention
          VerdictBadge's own `subtleText` variant uses for a column of several verdicts in a row. */}
      <span className={classes.node} style={{ background: VERDICT_COLOR[verdict] }} aria-hidden="true" />
      <div className={classes.content}>
        {showName && <div className={classes.runName}>{testName}</div>}
        <div className={classes.runMeta}>
          <span className={classes.verdictLabel}>{verdictLabel}</span>
          <span className={classes.runWhen}>{shortRelativeTime(relativeTime)}</span>
        </div>
        {/* Truncated to one line by CSS — `title` carries the full sentence for the cases that clip,
            same pattern RunsPage uses for a drifted test's full difference list. */}
        <div className={classes.runGist} title={gist}>
          {gist}
        </div>
      </div>
      {/* Purely decorative now — the whole row is the link, this only restates it visually. Still
          hover/focus revealed, same reasoning as before: the row's headline is the test name and
          the verdict, and which exact run this was is one glance away, not a fixture. */}
      <span className={classes.viewFull} aria-hidden="true">
        <IconArrowRight size={14} stroke={1.75} />
      </span>
    </a>
  );
}
