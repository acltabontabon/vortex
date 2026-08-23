import type { KeyboardEvent, MouseEvent } from 'react';
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
 * <p>A row's primary action selects the run's test, bringing that test's own current evidence into
 * the inspector — it never claims that this specific historical run's evidence is what's now shown,
 * since the inspector always reads a test's *current* latest run, which may or may not be this one.
 * The one path to this exact run's forensic detail is its own "View full result" link, revealed on
 * hover/focus rather than permanent, matching the density this rail is for.
 */
export function RecentRunsRail({
  overview,
  serviceId,
  onSelectTest,
  fitHeight = null,
}: {
  overview: Overview;
  serviceId: string;
  onSelectTest: (name: string) => void;
  fitHeight?: number | null;
}) {
  const { ref: headRef, height: headHeight } = useElementSize<HTMLDivElement>();
  // Measures the first row only — every row in a given render shares the same height (`showName`
  // is one decision for the whole list, not per row), so one row stands in for all of them without
  // that estimate ever depending on `shownCount` itself.
  const { ref: rowRef, height: rowContentHeight } = useElementSize<HTMLDivElement>();
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
  const testNames = new Set(shownRuns.map((run) => run.testName));
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
              onSelectTest={onSelectTest}
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
  onSelectTest,
  measureRef,
}: {
  testName: string;
  verdict: Verdict;
  verdictLabel: string;
  relativeTime: string;
  showName: boolean;
  viewFullHref: string;
  onSelectTest: (name: string) => void;
  /** Set only on the first row — see the comment on `rowRef` above. */
  measureRef?: (node: HTMLDivElement | null) => void;
}) {
  function isOnInteractiveChild(target: EventTarget) {
    return (target as HTMLElement).closest('a') !== null;
  }

  function onClick(event: MouseEvent<HTMLElement>) {
    if (isOnInteractiveChild(event.target)) return;
    onSelectTest(testName);
  }

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (isOnInteractiveChild(event.target)) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSelectTest(testName);
    }
  }

  return (
    <div
      ref={measureRef}
      className={classes.run}
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={onKeyDown}
    >
      {/* The dot is this row's node on the timeline, not a repeat of VerdictBadge's inline one — it
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
      {/* An icon, not a phrase — "View full result" read as a cramped, wrapping sentence squeezed
          into an already-dense meta line at this column's width. Its own column, vertically centred
          on the row rather than baseline-locked to the text beside it, same hover/focus reveal as
          before. */}
      <a
        className={classes.viewFull}
        href={viewFullHref}
        aria-label={`View full result for ${testName}`}
      >
        <IconArrowRight size={14} stroke={1.75} />
      </a>
    </div>
  );
}
