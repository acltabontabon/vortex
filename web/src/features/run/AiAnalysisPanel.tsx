import { Accordion, Badge, Button, Card, Group, Stack, Text, Title, Tooltip } from '@mantine/core';
import {
  IconArrowRight,
  IconBulb,
  IconCircleDashed,
  IconEye,
  IconLink,
  IconSparkles,
} from '@tabler/icons-react';
import { useState, type ComponentType } from 'react';
import type { AnalysisFinding, AnalysisState, MissingTelemetry, NextTest, Recommendation } from '../../api/run';
import { AsyncPanel, type AiAvailability } from '../../components/AsyncPanel';
import { scrollToEvidence } from '../../lib/evidenceLink';
import classes from './RunAnalysisPanel.module.css';

/**
 * The common shape between a single-run {@link ../../api/run.Analysis} and the slimmer
 * comparison {@link ../../api/globalRuns.ComparisonAnalysis} — recommendations and nextTest are
 * optional because a comparison interpretation has neither.
 */
export interface AnalysisLike {
  state: AnalysisState;
  conclusion: string;
  findings: AnalysisFinding[];
  recommendations?: Recommendation[];
  missingTelemetry: MissingTelemetry[];
  nextTest?: NextTest | null;
  provenanceDescribe: string | null;
  failureMessage: string | null;
}

interface AiPanelStatus {
  analysing: boolean;
  latest: AnalysisLike | null;
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
  earlier?: AnalysisLike[];
  hideWhenLoading?: boolean;
}) {
  if (!status) {
    if (hideWhenLoading) return null;
    return (
      <section>
        <PanelHeader title={title} />
      </section>
    );
  }

  const isFailed = !status.analysing && status.latest?.state === 'FAILED';
  const hasUsableResult =
    !status.analysing && status.latest !== null && status.latest.state === 'COMPLETED';
  const showTrigger =
    !status.analysing && !isFailed && status.latest === null && status.availability.available;

  return (
    <section>
      <PanelHeader title={title} />
      <Text size="xs" c="dimmed" mb="sm">
        {disclaimer}
      </Text>

      <AsyncPanel
        title={runningLabel}
        isRunning={status.analysing}
        runningMessage={runningMessage}
        availability={status.availability}
        hasResult={hasUsableResult}
        failed={isFailed}
        failureMessage={status.latest?.failureMessage}
        onRetry={onStart}
        retrying={starting}
      >
        {status.latest && <AnalysisView analysis={status.latest} />}
      </AsyncPanel>

      {showTrigger && (
        <Button
          onClick={onStart}
          loading={starting}
          variant="light"
          color="ai"
          size="xs"
          leftSection={<IconSparkles size={14} />}
        >
          {triggerLabel}
        </Button>
      )}

      {earlier && earlier.length > 0 && (
        <Stack gap={4} mt="md">
          <Text size="xs" fw={600} c="dimmed" tt="uppercase">
            Earlier analyses ({earlierCount})
          </Text>
          <Accordion variant="separated" chevronPosition="left" className={classes.history}>
            {earlier.map((analysis, i) => (
              <Accordion.Item key={i} value={String(i)}>
                <Accordion.Control>
                  <Text size="sm" lineClamp={1}>
                    {analysis.conclusion || '(no conclusion)'}
                  </Text>
                  {analysis.provenanceDescribe && (
                    <Text size="xs" c="dimmed" lineClamp={1}>
                      {analysis.provenanceDescribe}
                    </Text>
                  )}
                </Accordion.Control>
                <Accordion.Panel>
                  <AnalysisView analysis={analysis} compact />
                </Accordion.Panel>
              </Accordion.Item>
            ))}
          </Accordion>
        </Stack>
      )}
    </section>
  );
}

function PanelHeader({ title }: { title: string }) {
  return (
    <Group gap={6} mb={2}>
      <Title order={2} size="h4">
        {title}
      </Title>
      <Badge color="ai" variant="light" size="sm" leftSection={<IconSparkles size={11} />}>
        AI
      </Badge>
    </Group>
  );
}

const TYPE_ICON: Record<string, ComponentType<{ size?: number; className?: string }>> = {
  OBSERVATION: IconEye,
  CORRELATION: IconLink,
  HYPOTHESIS: IconBulb,
  LIMITATION: IconCircleDashed,
};

function AnalysisView({ analysis, compact = false }: { analysis: AnalysisLike; compact?: boolean }) {
  return (
    <Card withBorder radius="md" p={compact ? 'sm' : 'md'} className={classes.card}>
      <Group gap="xs" wrap="nowrap" align="flex-start">
        <IconSparkles size={16} className={classes.headlineIcon} />
        <Text size="sm" fw={500}>
          {analysis.conclusion}
        </Text>
      </Group>

      {analysis.findings.length > 0 && (
        <div className={classes.findings}>
          {analysis.findings.map((finding, i) => (
            <FindingRow key={i} finding={finding} />
          ))}
        </div>
      )}

      {analysis.recommendations && analysis.recommendations.length > 0 && (
        <Stack gap={4} mt="sm">
          <Text size="xs" fw={600} c="dimmed" tt="uppercase">
            Recommended
          </Text>
          {analysis.recommendations.map((rec, i) => (
            <div key={i} className={classes.recommendationRow}>
              <IconArrowRight size={14} className={classes.recommendationIcon} />
              <Text size="sm" span>
                {rec.action}
                <Text span size="xs" c="dimmed">
                  {' '}
                  — {rec.rationale}
                </Text>
                <EvidenceBadges ids={rec.evidenceIds} />
              </Text>
            </div>
          ))}
        </Stack>
      )}

      {analysis.nextTest && (
        <div className={classes.nextTestBox}>
          <Text size="xs" fw={600} c="ai" tt="uppercase">
            Suggested next test
          </Text>
          <Text size="sm" mt={2}>
            {analysis.nextTest.action}
            <EvidenceBadges ids={analysis.nextTest.evidenceIds} />
          </Text>
          <Text size="xs" c="dimmed" mt={2}>
            {analysis.nextTest.rationale} Would distinguish: {analysis.nextTest.wouldDistinguish}
          </Text>
        </div>
      )}

      {analysis.missingTelemetry.length > 0 && (
        <Stack gap={2} mt="sm">
          <Text size="xs" fw={600} c="dimmed" tt="uppercase">
            Would help next time
          </Text>
          {analysis.missingTelemetry.map((missing, i) => (
            <Text key={i} size="xs" c="dimmed">
              <strong>{missing.what}</strong> — {missing.whyItMatters}
            </Text>
          ))}
        </Stack>
      )}

      {analysis.provenanceDescribe && !compact && (
        <Text size="xs" c="dimmed" mt="sm">
          {analysis.provenanceDescribe}
        </Text>
      )}
    </Card>
  );
}

/**
 * One AI finding, collapsed to its statement plus a type glyph and a confidence dot-scale — a
 * deliberately different visual grammar from {@link ./evidence/FindingsTable}'s
 * pass/fail/warn/neutral tick marks, so a reader never mistakes an interpretation for a
 * measurement at a glance. Expands to name what kind of claim it is and its evidence.
 */
function FindingRow({ finding }: { finding: AnalysisFinding }) {
  const Icon = TYPE_ICON[finding.typeKind] ?? IconCircleDashed;
  return (
    <details className={classes.findingRow}>
      <summary className={classes.findingSummary}>
        <Icon size={16} className={classes.findingIcon} />
        <span className={classes.findingStatement}>{finding.statement}</span>
        <ConfidenceDots kind={finding.confidenceKind} label={finding.confidenceLabel} />
      </summary>
      <div className={classes.findingDetail}>
        <Text size="xs" c="dimmed">
          {finding.typeLabel} · {finding.confidenceLabel} confidence
        </Text>
        <EvidenceBadges ids={finding.evidenceIds} />
      </div>
    </details>
  );
}

const CONFIDENCE_DOTS: Record<string, number> = { HIGH: 3, MEDIUM: 2, LOW: 1 };

function ConfidenceDots({ kind, label }: { kind: string; label: string }) {
  const filled = CONFIDENCE_DOTS[kind] ?? 1;
  return (
    <span
      className={classes.confidenceDots}
      title={`${label} confidence`}
      aria-label={`${label} confidence`}
    >
      {[0, 1, 2].map((i) => (
        <span key={i} className={i < filled ? classes.dotFilled : classes.dotEmpty} />
      ))}
    </span>
  );
}

/**
 * Every citation an AI finding carries, clickable — jumps to and briefly highlights the
 * deterministic finding row it names, so the interpretation stays traceable to the measurement
 * rather than merely asserting that it is. Hovering previews the target row's own headline first,
 * so a reader can judge relevance before jumping. A citation with nothing to jump to on this page
 * (e.g. a comparison's `delta:` ids, which don't yet have an addressable row) renders as inert
 * text.
 */
function EvidenceBadges({ ids }: { ids: string[] }) {
  if (ids.length === 0) return null;
  return (
    <span className={classes.evidenceBadges}>
      {ids.map((id) => (
        <EvidenceBadge key={id} id={id} />
      ))}
    </span>
  );
}

function EvidenceBadge({ id }: { id: string }) {
  const [preview, setPreview] = useState<string | null>(null);

  return (
    <Tooltip label={preview ?? id} disabled={!preview} withArrow position="top" openDelay={200}>
      <button
        type="button"
        className={classes.evidenceBadge}
        onClick={() => scrollToEvidence(id)}
        onMouseEnter={() => {
          if (preview) return;
          const target = document.querySelector<HTMLElement>(`[data-evidence-ids~="${CSS.escape(id)}"]`);
          const text = target?.querySelector('summary')?.textContent?.trim();
          if (text) setPreview(text.length > 90 ? `${text.slice(0, 90)}…` : text);
        }}
      >
        {id}
      </button>
    </Tooltip>
  );
}
