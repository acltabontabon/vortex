import { useEffect, useState } from 'react';
import { Text } from '@mantine/core';
import { useElementSize } from '@mantine/hooks';
import { IconArrowRight } from '@tabler/icons-react';
import type { Overview, Verdict } from '../../api/workspace';
import { shortRelativeTime, VERDICT_COLOR } from '../../lib/testState';
import classes from './RecentRunsRail.module.css';

/** Never fewer than this many, regardless of available space — the floor the brief asked for. */
const MIN_VISIBLE = 5;

/** Must match `.timeline`'s own `gap` in RecentRunsRail.module.css. */
const ROW_GAP = 4;

/**
 * Activity, and only activity — the workspace's other half, structurally attached to it rather than
 * floating beside it.
 *
 * <p>Drawn as a timeline — a connecting line through each run's own verdict-coloured node — rather
 * than a flat list of rows that happen to share a container. The rows *are* a sequence (one
 * service's history, newest first); the line is what says so at a glance, instead of leaving spacing
 * alone to imply it.
 *
 * <p>`fitHeight` is the Tests column's own rendered height, in px, or `null` when the two columns
 * aren't genuinely side by side (narrower than the layout's own breakpoint — see `OverviewPage`).
 * When it's a real number, this component grows past its `MIN_VISIBLE` floor to use the space beside
 * Tests rather than leaving it empty, estimating a row's height from its own first rendered row
 * (never from the whole list's height divided by how many rows happen to be showing right now — that
 * self-referential estimate is what used to make the count flicker: dividing a measured height by a
 * count that the *next* render then changes, feeding tiny measurement jitter back into itself). A
 * single row's own height doesn't depend on how many rows are shown, so there's nothing to feed back.
 * This never shows more than the service actually has, and "View all" still appears whenever there's
 * more history than even that.
 *
 * <p>A row's primary action opens that exact run's own full result — never the test's *current*
 * latest run, which this historical one may or may not still be. The arrow only restates that
 * destination visually (revealed on hover/focus, matching the density this rail is for); the whole
 * row is the same link.
 */
export function RecentRunsRail({
  overview,
  serviceId,
  fitHeight = null,
}: {
  overview: Overview;
  serviceId: string;
  fitHeight?: number | null;
}) {
  const { ref: headRef, height: headHeight } = useElementSize<HTMLDivElement>();
  // Measures the first row only — every row in a given render shares the same height (`showName`
  // is one decision for the whole list, not per row), so one row stands in for all of them without
  // that estimate ever depending on `shownCount` itself.
  const { ref: rowRef, height: rowContentHeight } = useElementSize<HTMLAnchorElement>();
  const [shownCount, setShownCount] = useState(MIN_VISIBLE);

  const totalAvailable = overview.recentRuns.length;

  useEffect(() => {
    if (!fitHeight || rowContentHeight === 0) return;

    const rowHeight = rowContentHeight + ROW_GAP;
    const roomForRows = fitHeight - headHeight;
    const fits = Math.floor(roomForRows / rowHeight);

    const target = Math.max(MIN_VISIBLE, Math.min(totalAvailable, fits));
    setShownCount((current) => (target === current ? current : target));
    // `shownCount` is deliberately not a dependency: it no longer affects the height estimate
    // (`rowContentHeight` doesn't move when the row count does), so re-running this effect only
    // when it changes would just be reacting to this same effect's own last update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitHeight, headHeight, rowContentHeight, totalAvailable]);

  // Narrower than the side-by-side breakpoint, or nothing to grow into — the floor, nothing more.
  const visibleCount = fitHeight ? Math.min(shownCount, totalAvailable) : Math.min(MIN_VISIBLE, totalAvailable);
  const shownRuns = overview.recentRuns.slice(0, visibleCount);

  // Grouped by what actually appears in this list, not by how many tests the service happens to
  // have configured — a service with three tests but one that ever runs shouldn't repeat that one
  // test's name five times any more than a service with one test should.
  //
  // <p>Deliberately checked against every fetched run, not just `shownRuns` — `shownRuns` is the
  // elastic slice `fitHeight`'s own effect above grows and shrinks, and whether a row shows its name
  // changes that row's rendered height. Basing the grouping decision on that same elastic slice made
  // the two feed each other: growing past a row whose test name differed flipped `singleTestName`,
  // which changed row height, which changed how many rows fit, which changed the slice again — an
  // oscillation between grouped and ungrouped that never settled. Checking the full fetched list
  // instead is independent of `shownCount`, so it can't be an input to its own effect.
  const testNames = new Set(overview.recentRuns.map((run) => run.testName));
  const singleTestName = testNames.size === 1 ? shownRuns[0]?.testName : null;

  return (
    <section className={classes.railSection}>
      <div ref={headRef}>
        <div className={classes.railHead}>
          <div className={classes.railTitle}>Recent runs</div>
          {overview.header.runCount > shownRuns.length && (
            <a className={classes.more} href={`/services/${serviceId}/runs`}>
              View all
            </a>
          )}
        </div>

        {singleTestName && <div className={classes.railGroup}>{singleTestName}</div>}
      </div>

      {shownRuns.length === 0 ? (
        <Text size="sm" c="dimmed">
          No runs yet.
        </Text>
      ) : (
        <div className={classes.timeline}>
          {shownRuns.map((run, index) => (
            <RunRow
              key={run.id}
              measureRef={index === 0 ? rowRef : undefined}
              testName={run.testName}
              verdict={run.verdict}
              verdictLabel={run.verdictLabel}
              relativeTime={run.relativeTime}
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
  showName,
  viewFullHref,
  measureRef,
}: {
  testName: string;
  verdict: Verdict;
  verdictLabel: string;
  relativeTime: string;
  showName: boolean;
  viewFullHref: string;
  /** Set only on the first row — see the comment on `rowRef` above. */
  measureRef?: (node: HTMLAnchorElement | null) => void;
}) {
  return (
    // A real link, not a clickable `<div>` with a nested one for "view full result" — both used to
    // point at the same destination, which just meant a second, redundant hit target. One link
    // means native keyboard/middle-click/open-in-new-tab behaviour for free, none of it hand-rolled.
    <a ref={measureRef} className={classes.run} href={viewFullHref}>
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
