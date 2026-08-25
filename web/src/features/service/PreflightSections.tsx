import { Alert, Stack, Text, Title } from '@mantine/core';
import type { Preflight } from '../../api/run';
import { Fact, Facts } from '../../components/Fact';
import classes from './PreflightSections.module.css';

const SEVERITY_COLOR: Record<string, string> = {
  BLOCKING: 'fail',
  WARNING: 'warn',
  INFO: 'neutral',
};

const CHECK_COLOR: Record<string, string> = {
  FAIL: 'fail',
  WARN: 'warn',
  PASS: 'pass',
  SKIPPED: 'neutral',
};

// A rotating palette for the operation-mix bar. Not a verdict color (pass/fail/warn) — how traffic
// is split across operations carries no pass/fail meaning of its own, so it draws from the app's
// non-verdict hues instead.
const MIX_COLORS = ['brand', 'live', 'ai', 'warn', 'neutral'];

/** Best-effort width for one segment of the mix bar. A figure that fails to parse degrades to a
 *  thin sliver (`.mixSegment`'s own min-width) rather than vanishing — the exact number is still
 *  stated in the legend beneath the bar regardless, so the bar is a supplement, never the record. */
function shareWeight(sharePercent: string): number {
  const parsed = Number.parseFloat(sharePercent);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0.1;
}

function Tile({ value, label }: { value: string; label: string }) {
  return (
    <div className={classes.tile}>
      <div className={classes.tileValue}>{value}</div>
      <div className={classes.tileLabel}>{label}</div>
    </div>
  );
}

/**
 * What will happen if you press Run, before anything is sent — the content both the standalone
 * Preflight page and the in-place drawer show. Somebody who has never used Vortex should be able to
 * read it and answer, without asking anybody: what service, what question, which workload, how much
 * traffic, spread how, for how long, against what, and what would count as success.
 *
 * <p>Organized as a reading order, not a pile of equally-weighted cards: the question, then the one
 * sentence that answers it, then anything that changes what pressing Run actually risks (mutating
 * data, a safety finding) surfaced before the specifics rather than buried after them, then "This
 * run" — the shot itself, led by the handful of numbers worth reading at a glance before the
 * supporting detail — then "Pass conditions", which is deliberately one panel: readiness checks and
 * success thresholds are two different questions ("can this run at all" vs. "what would this run
 * have to show"), but they're both "what decides pass or fail" and belong under one heading.
 */
export function PreflightSections({
  preflight,
  showHeading = true,
  showQuestion = true,
}: {
  preflight: Preflight;
  /** False when a shell already states the test type as its own title (the drawer's header bar). */
  showHeading?: boolean;
  /** False when a shell already states the question as part of its own title block (PreflightPage
   *  combines heading + question + actions into one header row it owns). */
  showQuestion?: boolean;
}) {
  const hasPassConditions = preflight.checks.length > 0 || preflight.thresholdDescriptions.length > 0;
  // The classification caveat arrives twice — once as its own field, once folded into
  // safetyFindings as an INFO-severity entry with the identical title/detail — because the domain
  // treats "this is an isolated test" as both a fact about the run (classificationLabel) and a
  // thing worth flagging (a finding). Rendered once each is enough; showing the same sentence as
  // both a fact and a full-width alert box reads as noise, not two pieces of information.
  const safetyFindings = preflight.safetyFindings.filter(
    (finding) => finding.title !== preflight.classificationLabel,
  );

  return (
    <>
      {showHeading && (
        <Title order={2} size="h3" mb={4}>
          {preflight.testTypeLabel}
        </Title>
      )}
      {showQuestion && (
        <Text c="dimmed" size="sm" mb="lg">
          {preflight.testTypeQuestion}
        </Text>
      )}

      {preflight.plainEnglishSummary && (
        <div className={classes.summaryPanel}>
          {/* The domain sends real paragraph breaks and bullet lines (`\n\n`, `\n  • `) — plain JSX
              text collapses all of that to one run-on line, so this has to opt back into respecting
              them. */}
          <p className={classes.summary}>{preflight.plainEnglishSummary}</p>
        </div>
      )}

      <Stack gap="md">
        {preflight.mutatingOperations.length > 0 && (
          <Alert color="warn" title="This run will mutate data">
            <Text size="sm" mb="xs">
              These operations write, not just read:
            </Text>
            <ul className={classes.list}>
              {preflight.mutatingOperations.map((op) => (
                <li key={op}>{op}</li>
              ))}
            </ul>
          </Alert>
        )}

        {safetyFindings.length > 0 && (
          <Stack gap="xs">
            {safetyFindings.map((finding) => (
              <Alert
                key={finding.title}
                color={SEVERITY_COLOR[finding.severityKind] ?? 'neutral'}
                title={finding.title}
              >
                <Text size="sm">{finding.detail}</Text>
              </Alert>
            ))}
          </Stack>
        )}

        <div className={classes.runConditionsGrid}>
        <div className={classes.group}>
          <Title order={3} size="h4" mb="md" className={classes.groupTitle}>
            This run
          </Title>

          <div className={classes.tileRow}>
            {preflight.peakLevelDisplay && <Tile value={preflight.peakLevelDisplay} label="Level" />}
            {preflight.durationDisplay && <Tile value={preflight.durationDisplay} label="Duration" />}
            {preflight.environmentName && <Tile value={preflight.environmentName} label="Environment" />}
          </div>

          <Facts>
            <Fact label="Test">{preflight.workloadName}</Fact>
            <Fact label="Target" note={preflight.targetRewritten ? preflight.targetRewriteReason : undefined}>
              {preflight.effectiveTarget}
              {preflight.targetRewritten && preflight.configuredTarget && (
                <Text size="xs" c="dimmed">
                  Configured as {preflight.configuredTarget}
                </Text>
              )}
            </Fact>
            <Fact label="Dependencies">{preflight.dependencyModeLabel}</Fact>
            <Fact label="Classification" note={preflight.classificationCaveat}>
              {preflight.classificationLabel}
            </Fact>
            <Fact label="Source">{preflight.workloadSourceDescribe}</Fact>
            {preflight.hasRequestEstimate && (
              <Fact label="Estimated requests" note={preflight.estimateCaveat}>
                {preflight.requests}
              </Fact>
            )}
          </Facts>

          {preflight.operations.length > 0 && (
            <div className={classes.mixSection}>
              <div className={classes.mixBar}>
                {preflight.operations.map((op, index) => (
                  <div
                    key={op.name}
                    className={classes.mixSegment}
                    style={{
                      flexGrow: shareWeight(op.sharePercent),
                      background: `var(--mantine-color-${MIX_COLORS[index % MIX_COLORS.length]}-6)`,
                    }}
                    title={`${op.name} — ${op.sharePercent}${op.rateDisplay ? ` · ${op.rateDisplay}` : ''}`}
                  />
                ))}
              </div>
              <div className={classes.mixLegend}>
                {preflight.operations.map((op, index) => (
                  <div key={op.name} className={classes.mixLegendRow}>
                    <span
                      className={classes.mixSwatch}
                      style={{ background: `var(--mantine-color-${MIX_COLORS[index % MIX_COLORS.length]}-6)` }}
                      aria-hidden="true"
                    />
                    <span>{op.name}</span>
                    <span className={classes.dim}>
                      {op.sharePercent}
                      {op.rateDisplay && ` · ${op.rateDisplay}`}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {hasPassConditions && (
          <div className={classes.group}>
            <Title order={3} size="h4" mb="md" className={classes.groupTitle}>
              Pass conditions
            </Title>

            {preflight.checks.length > 0 && (
              <div className={classes.checkGrid}>
                {preflight.checks.map((check) => (
                  <div key={check.name} className={classes.checkCard}>
                    <div className={classes.checkHeader}>
                      <span
                        className={classes.checkDot}
                        style={{ background: `var(--mantine-color-${CHECK_COLOR[check.statusKind] ?? 'neutral'}-6)` }}
                        aria-hidden="true"
                      />
                      <Text size="sm" fw={600}>
                        {check.name} — {check.statusLabel}
                      </Text>
                    </div>
                    <Text size="xs" c="dimmed">
                      {check.detail}
                      {check.remedy && ` ${check.remedy}`}
                    </Text>
                  </div>
                ))}
              </div>
            )}

            {preflight.checks.length > 0 && preflight.thresholdDescriptions.length > 0 && (
              <div className={classes.divider} />
            )}

            {preflight.thresholdDescriptions.length > 0 && (
              <ul className={classes.list}>
                {preflight.thresholdDescriptions.map((description) => (
                  <li key={description}>{description}</li>
                ))}
              </ul>
            )}
          </div>
        )}
        </div>
      </Stack>
    </>
  );
}
