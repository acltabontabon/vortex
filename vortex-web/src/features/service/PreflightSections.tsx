import { Alert, Card, Stack, Text, Title } from '@mantine/core';
import type { Preflight } from '../../api/run';
import { Fact, Facts } from '../../components/Fact';
import { ServerSvg } from '../../components/charts/ServerSvg';
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

/**
 * What will happen if you press Run, before anything is sent — the content both the standalone
 * Preflight page and the in-place drawer show. Somebody who has never used Vortex should be able to
 * read it and answer, without asking anybody: what service, what question, which workload, how much
 * traffic, spread how, for how long, against what, and what would count as success.
 */
export function PreflightSections({
  preflight,
  showHeading = true,
}: {
  preflight: Preflight;
  /** False when a shell already states the test type as its own title (the drawer's header bar) —
   *  the question line still renders either way, since nothing else states that. */
  showHeading?: boolean;
}) {
  return (
    <>
      {showHeading && (
        <Title order={2} size="h3" mb={4}>
          {preflight.testTypeLabel}
        </Title>
      )}
      <Text c="dimmed" size="sm" mb="lg">
        {preflight.testTypeQuestion}
      </Text>

      {preflight.plainEnglishSummary && (
        <Alert color="live" mb="md">
          {/* The domain sends real paragraph breaks and bullet lines (`\n\n`, `\n  • `) — plain JSX
              text collapses all of that to one run-on line, so this has to opt back into respecting
              them. */}
          <p className={classes.summary}>{preflight.plainEnglishSummary}</p>
        </Alert>
      )}

      <Stack gap="md">
        <Card withBorder radius="md">
          <Title order={3} size="h4" mb="sm">
            What will run
          </Title>
          <Facts>
            <Fact label="Test">{preflight.workloadName}</Fact>
            <Fact label="Environment">{preflight.environmentName}</Fact>
            <Fact label="Target" note={preflight.targetRewritten ? preflight.targetRewriteReason : undefined}>
              {preflight.effectiveTarget}
              {preflight.targetRewritten && preflight.configuredTarget && (
                <Text size="xs" c="dimmed">
                  Configured as {preflight.configuredTarget}
                </Text>
              )}
            </Fact>
            <Fact label="Dependencies">{preflight.dependencyModeLabel}</Fact>
            <Fact label="Duration">{preflight.durationDisplay}</Fact>
            <Fact label="Classification" note={preflight.classificationCaveat}>
              {preflight.classificationLabel}
            </Fact>
          </Facts>
        </Card>

        <Card withBorder radius="md">
          <Title order={3} size="h4" mb="sm">
            How much traffic
          </Title>
          <Facts>
            <Fact label="Model">{preflight.workloadModelLabel}</Fact>
            <Fact label="Level">{preflight.peakLevelDisplay}</Fact>
            <Fact label="Source">{preflight.workloadSourceDescribe}</Fact>
            {preflight.hasRequestEstimate && (
              <Fact label="Estimated requests" note={preflight.estimateCaveat}>
                {preflight.requests}
              </Fact>
            )}
          </Facts>

          {preflight.compositionRenderable && preflight.compositionSvg && (
            <ServerSvg svg={preflight.compositionSvg} className={classes.diagram} />
          )}

          {preflight.operations.length > 0 && (
            <div className={classes.operations}>
              {preflight.operations.map((op) => (
                <div key={op.name} className={classes.operationRow}>
                  <span>{op.name}</span>
                  <span className={classes.dim}>
                    {op.sharePercent}
                    {op.rateDisplay && ` · ${op.rateDisplay}`}
                  </span>
                </div>
              ))}
            </div>
          )}
        </Card>

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

        {preflight.checks.length > 0 && (
          <Card withBorder radius="md">
            <Title order={3} size="h4" mb="sm">
              Checks
            </Title>
            <Stack gap="xs">
              {preflight.checks.map((check) => (
                <div key={check.name} className={classes.checkRow}>
                  <span
                    className={classes.checkDot}
                    style={{ background: `var(--mantine-color-${CHECK_COLOR[check.statusKind] ?? 'neutral'}-6)` }}
                    aria-hidden="true"
                  />
                  <div>
                    <Text size="sm" fw={600}>
                      {check.name} — {check.statusLabel}
                    </Text>
                    <Text size="xs" c="dimmed">
                      {check.detail}
                      {check.remedy && ` ${check.remedy}`}
                    </Text>
                  </div>
                </div>
              ))}
            </Stack>
          </Card>
        )}

        {preflight.safetyFindings.length > 0 && (
          <Stack gap="xs">
            {preflight.safetyFindings.map((finding) => (
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

        {preflight.thresholdDescriptions.length > 0 && (
          <Card withBorder radius="md">
            <Title order={3} size="h4" mb="sm">
              What would count as success
            </Title>
            <ul className={classes.list}>
              {preflight.thresholdDescriptions.map((description) => (
                <li key={description}>{description}</li>
              ))}
            </ul>
          </Card>
        )}
      </Stack>
    </>
  );
}
