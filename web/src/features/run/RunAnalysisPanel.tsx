import { useRunAnalysisPanel } from '../../api/run';
import { AiAnalysisPanel } from './AiAnalysisPanel';

/**
 * AI interpretation of a completed run — exploratory, and clearly separated from the measurements
 * above it. The measurements are already final by the time this panel exists; this only adds a
 * second reading of them, one an analyst could get wrong, so the panel that carries it looks
 * nothing like the evidence sections it sits beside.
 */
export function RunAnalysisPanel({ executionId }: { executionId: string }) {
  const { status, start } = useRunAnalysisPanel(executionId);

  return (
    <AiAnalysisPanel
      title="Interpretation"
      disclaimer="Vortex has already established the deterministic findings above. This adds an AI reading of that same evidence — it does not determine pass/fail or capacity."
      runningLabel="Analysing"
      runningMessage="Analysing. The measurements above are already final — this only adds interpretation."
      triggerLabel="Analyse evidence with AI"
      status={status.data}
      onStart={() => start.mutate()}
      starting={start.isPending}
      earlierCount={status.data?.earlierCount}
      earlier={status.data?.earlier}
      hideWhenLoading
    />
  );
}
