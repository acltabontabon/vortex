import { HoverCard, Text, VisuallyHidden } from '@mantine/core';
import type { TestTypeEvidence } from '../../api/workspace';
import { VERDICT_COLOR, shortRate, shortRelativeTime } from '../../lib/testState';
import { UnknownInline } from '../../components/Unknown';
import classes from './EvidenceStrip.module.css';

/** Evidence older than this reads at reduced opacity — a rendering choice only, never a domain claim
 *  that the reading is "stale". Nothing here knows whether the age of a measurement matters. */
const QUIET_AFTER_DAYS = 14;

function isQuiet(isoTimestamp: string | null): boolean {
  if (!isoTimestamp) return false;
  const ageMs = Date.now() - new Date(isoTimestamp).getTime();
  return ageMs > QUIET_AFTER_DAYS * 24 * 60 * 60 * 1000;
}

/**
 * What Vortex currently knows about this service, one cell per test type — the compact instrument
 * that replaced a single "whatever ran last" capacity tile.
 *
 * <p>Each cell shows the fact its test type actually answers, not a manufactured universal number:
 * an outcome word for Smoke, a tested rate for Average load and Spike, the highest sustained or
 * detected level for Stress and Breakpoint, a measured duration for Soak. All of it — including
 * whether a headroom multiple exists at all — comes pre-decided from {@code evidenceByTestType};
 * nothing here recomputes a figure the domain already refused or already produced.
 *
 * <p>Always six cells, in the order the domain teaches them (Smoke through Breakpoint), so a
 * freshly configured service says "not tested" for the five it hasn't run rather than omitting
 * them — and so the most consequential number, the detected breakpoint, reads last, like a
 * conclusion rather than a scattered fact.
 */
export function EvidenceStrip({
  evidence,
  onSelect,
}: {
  evidence: TestTypeEvidence[];
  onSelect: (workloadName: string) => void;
}) {
  return (
    <div className={classes.strip}>
      {evidence.map((item) => (
        <EvidenceCell key={item.testType} item={item} onSelect={onSelect} />
      ))}
    </div>
  );
}

function EvidenceCell({
  item,
  onSelect,
}: {
  item: TestTypeEvidence;
  onSelect: (workloadName: string) => void;
}) {
  const quiet = item.hasEvidence && isQuiet(item.isoTimestamp);

  return (
    <div className={`${classes.cell} ${quiet ? classes.quiet : ''}`}>
      <div className={classes.labelRow}>
        <span className={classes.label}>{item.testTypeLabel}</span>
        {item.running && (
          <>
            <span className={classes.liveDot} aria-hidden="true" />
            <VisuallyHidden>{`${item.testTypeLabel} test running now`}</VisuallyHidden>
          </>
        )}
      </div>

      {!item.hasEvidence ? (
        item.running ? (
          <span className={classes.running}>Running…</span>
        ) : (
          <UnknownInline>Not tested</UnknownInline>
        )
      ) : (
        <HoverCard width={260} openDelay={150} position="bottom-start" withArrow>
          <HoverCard.Target>
            <button
              type="button"
              className={classes.valueButton}
              onClick={() => item.workloadName && onSelect(item.workloadName)}
            >
              <span className={classes.valueRow}>
                <span
                  className={classes.dot}
                  style={{ background: item.outcome ? VERDICT_COLOR[item.outcome] : undefined }}
                  aria-hidden="true"
                />
                <span
                  className={item.primaryValueKind === 'OUTCOME' ? classes.outcomeValue : classes.value}
                  style={
                    item.primaryValueKind === 'OUTCOME' && item.outcome
                      ? { color: VERDICT_COLOR[item.outcome] }
                      : undefined
                  }
                >
                  {item.primaryValueKind === 'RATE' && item.primaryValue
                    ? shortRate(item.primaryValue)
                    : item.primaryValue}
                </span>
              </span>
              <span className={classes.secondary}>
                {item.secondaryValue
                  ? `${item.secondaryValue} production`
                  : item.relativeTime
                    ? shortRelativeTime(item.relativeTime)
                    : null}
              </span>
            </button>
          </HoverCard.Target>
          <HoverCard.Dropdown>
            <Text size="xs" fw={600} mb={2}>
              {item.workloadName}
            </Text>
            <Text size="xs">{item.answer}</Text>
            <Text size="xs" c="dimmed" mt={4}>
              {item.environmentName}
              {item.release && ` · ${item.release}`}
            </Text>
            <Text size="xs" c="dimmed">
              {item.isoTimestamp && new Date(item.isoTimestamp).toLocaleString()}
            </Text>
          </HoverCard.Dropdown>
        </HoverCard>
      )}
    </div>
  );
}
