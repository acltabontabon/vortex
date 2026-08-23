import { Stack } from '@mantine/core';
import type { RunEvidence } from '../../api/run';
import { ExperimentHeader } from './evidence/ExperimentHeader';
import { ResultAndQuality } from './evidence/ResultAndQuality';
import { KeyMetrics } from './evidence/KeyMetrics';
import { Narrative } from './evidence/Narrative';
import { PerformanceSection } from './evidence/PerformanceSection';
import { ObjectivesPanel } from './evidence/ObjectivesPanel';
import { OperationsTable } from './evidence/OperationsTable';
import { RunTimeline } from './evidence/RunTimeline';
import { ResourcesSection } from './evidence/ResourcesSection';
import { CapacitySection } from './evidence/CapacitySection';
import { ComparisonSection } from './evidence/ComparisonSection';
import { EvidenceProvenanceSection } from './evidence/EvidenceProvenanceSection';
import { FindingsTable } from './evidence/FindingsTable';
import { emphasisFor } from './evidence/testTypeEmphasis';

/**
 * One completed run's evidence, laid out to progressively answer the questions a performance
 * engineer actually asks: what happened, can I trust it, how did the service behave, what
 * constrained it, why does Vortex believe this, and what changed — in that order, with technical
 * detail (provenance, raw samples, per-finding evidence ids) reachable but collapsed by default
 * rather than sharing the main reading path.
 *
 * <p>Shared between the result view ({@link RunPage}) and the shareable report
 * ({@link RunReportPage}) — one renderer of run evidence, not two. `variant="report"` hides the
 * live-page action row; the AI interpretation panel is mounted by the caller, never by this
 * component, so the report's absence of one is a fact about composition, not a prop to thread
 * through every section here.
 */
export function RunEvidenceView({
  evidence,
  serviceId,
  executionId,
  runAgainHref,
  variant = 'page',
}: {
  evidence: RunEvidence;
  serviceId: string;
  executionId: string;
  runAgainHref: string | null;
  variant?: 'page' | 'report';
}) {
  const emphasis = emphasisFor(evidence.identity.testType);

  return (
    <Stack gap="xl">
      <ExperimentHeader
        identity={evidence.identity}
        verdict={evidence.verdict}
        releaseMoved={evidence.releaseMoved}
        serviceId={serviceId}
        executionId={executionId}
        runAgainHref={runAgainHref}
        previousCompatibleExecutionId={evidence.previousCompatibleExecutionId}
        variant={variant}
      />

      <ResultAndQuality verdict={evidence.verdict} validity={evidence.validity} />

      <KeyMetrics
        verdict={evidence.verdict}
        load={evidence.load}
        performance={evidence.performance}
        acceptance={evidence.acceptance}
        reliability={evidence.reliability}
        resources={evidence.resources}
        capacitySummary={evidence.capacity.sustainableDisplay || null}
        emphasis={emphasis}
      />

      <Narrative evidence={evidence} />

      <PerformanceSection
        load={evidence.load}
        workload={evidence.workload}
        performance={evidence.performance}
        reliability={evidence.reliability}
        stageCount={evidence.timeline.stages.length}
      />

      <ObjectivesPanel acceptance={evidence.acceptance} />

      {evidence.hasOperationBreakdown && evidence.operations.length > 0 && (
        <OperationsTable operations={evidence.operations} />
      )}

      {evidence.timeline.present && (
        <RunTimeline
          executionId={executionId}
          timeline={evidence.timeline}
          resourceTimeline={evidence.resourceTimeline}
        />
      )}

      <ResourcesSection resources={evidence.resources} />

      <CapacitySection
        capacity={evidence.capacity}
        stages={evidence.timeline.stages}
        showCurve={emphasis.showCapacityCurve}
      />

      {evidence.comparison && (
        <ComparisonSection
          comparison={evidence.comparison}
          previousId={evidence.previousCompatibleExecutionId}
        />
      )}

      {evidence.hasFindings && <FindingsTable findings={evidence.findings} />}

      <EvidenceProvenanceSection
        workload={evidence.workload}
        loadAxis={evidence.loadAxis}
        observability={evidence.observability}
        timeline={evidence.timeline}
        provenance={evidence.provenance}
      />
    </Stack>
  );
}
