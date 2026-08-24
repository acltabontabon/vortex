import { Button, Card, Stack, Text, Title } from '@mantine/core';
import type { Analysis } from '../../api/run';
import { AsyncPanel, type AiAvailability } from '../../components/AsyncPanel';
import { scrollToEvidence } from '../../lib/evidenceLink';
import classes from './RunAnalysisPanel.module.css';

interface AiPanelStatus {
  analysing: boolean;
  latest: Analysis | null;
  availability: AiAvailability;
}

/**
 * AI interpretation of a completed run or a run-to-run comparison — exploratory, and clearly
 * separated from the measurements above it. Shared by {@link ../run/RunAnalysisPanel} and
 * {@link ../run/ComparePage} so the two don't drift out of visual sync; only the surrounding text
 * and the presence of earlier-analysis history differ between the two.
 */
export function AiAnalysisPanel({
  title,
  disclaimer,
  runningLabel,
  runningMessage,
  triggerLabel,
  status,
  onStart,
  starting,
  earlierCount,
  earlier,
  hideWhenLoading = false,
}: {
  title: string;
  disclaimer: string;
  runningLabel: string;
  runningMessage: string;
  triggerLabel: string;
  status: AiPanelStatus | undefined;
  onStart: () => void;
  starting: boolean;
  earlierCount?: number;
  earlier?: Analysis[];
  hideWhenLoading?: boolean;
}) {
  if (!status) {
    if (hideWhenLoading) return null;
    return (
      <section>
        <Title order={2} size="h4" mb={2}>
          {title}
        </Title>
      </section>
    );
  }

  return (
    <section>
      <Title order={2} size="h4" mb={2}>
        {title}
      </Title>
      <Text size="xs" c="dimmed" mb="sm">
        {disclaimer}
      </Text>

      <AsyncPanel
        title={runningLabel}
        isRunning={status.analysing}
        runningMessage={runningMessage}
        availability={status.availability}
        hasResult={status.latest !== null}
      >
        {status.latest && <AnalysisView analysis={status.latest} />}
      </AsyncPanel>

      {!status.analysing && status.latest === null && status.availability.available && (
        <Button onClick={onStart} loading={starting} variant="light" color="ai" size="xs">
          {triggerLabel}
        </Button>
      )}

      {earlier && earlier.length > 0 && (
        <details className={classes.disclosure}>
          <summary>Earlier analyses ({earlierCount})</summary>
          <Stack gap="md" mt="sm">
            {earlier.map((analysis, i) => (
              <AnalysisView key={i} analysis={analysis} />
            ))}
          </Stack>
        </details>
      )}
    </section>
  );
}

function AnalysisView({ analysis }: { analysis: Analysis }) {
  return (
    <Card withBorder radius="md">
      <Text size="sm">{analysis.conclusion}</Text>

      {analysis.findings.length > 0 && (
        <Stack gap={4} mt="sm">
          {analysis.findings.map((finding, i) => (
            <Text key={i} size="sm">
              <span className={classes.typeTag}>{finding.typeLabel}</span> {finding.statement}
              <span className={classes.dim}> ({finding.confidenceLabel})</span>
              <EvidenceBadges ids={finding.evidenceIds} />
            </Text>
          ))}
        </Stack>
      )}

      {analysis.recommendations.length > 0 && (
        <Stack gap={4} mt="sm">
          <Text size="xs" fw={600} c="dimmed">
            Recommended
          </Text>
          {analysis.recommendations.map((rec, i) => (
            <Text key={i} size="sm">
              {rec.action}
              <span className={classes.dim}> — {rec.rationale}</span>
              <EvidenceBadges ids={rec.evidenceIds} />
            </Text>
          ))}
        </Stack>
      )}

      {analysis.nextTest && (
        <Stack gap={2} mt="sm">
          <Text size="xs" fw={600} c="dimmed">
            Suggested next test
          </Text>
          <Text size="sm">
            {analysis.nextTest.action}
            <EvidenceBadges ids={analysis.nextTest.evidenceIds} />
          </Text>
          <Text size="xs" c="dimmed">
            {analysis.nextTest.rationale} Would distinguish: {analysis.nextTest.wouldDistinguish}
          </Text>
        </Stack>
      )}

      {analysis.missingTelemetry.length > 0 && (
        <Stack gap={2} mt="sm">
          <Text size="xs" fw={600} c="dimmed">
            Would help next time
          </Text>
          {analysis.missingTelemetry.map((missing, i) => (
            <Text key={i} size="xs" c="dimmed">
              <strong>{missing.what}</strong> — {missing.whyItMatters}
            </Text>
          ))}
        </Stack>
      )}

      {analysis.provenanceDescribe && (
        <Text size="xs" c="dimmed" mt="sm">
          {analysis.provenanceDescribe}
        </Text>
      )}
    </Card>
  );
}

/**
 * Every citation an AI finding carries, clickable — jumps to and briefly highlights the
 * deterministic finding row it names, so the interpretation stays traceable to the measurement
 * rather than merely asserting that it is. A citation with nothing to jump to on this page (e.g. a
 * comparison's `delta:` ids, which don't yet have an addressable row) renders as inert text.
 */
function EvidenceBadges({ ids }: { ids: string[] }) {
  if (ids.length === 0) return null;
  return (
    <span className={classes.evidenceBadges}>
      {ids.map((id) => (
        <button
          key={id}
          type="button"
          className={classes.evidenceBadge}
          onClick={() => scrollToEvidence(id)}
          title={`Jump to ${id}`}
        >
          {id}
        </button>
      ))}
    </span>
  );
}
