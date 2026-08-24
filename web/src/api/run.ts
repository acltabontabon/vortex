// Field-for-field against com.acltabontabon.vortex.app.web.RunApiController, RunDtos and RunEvidenceDtos.
// See vortex-app's RunDtos javadoc for why the charts below arrive as pre-rendered SVG strings
// rather than geometry: LoadAxis, SeriesPlot and CapacityRange are semantic, not geometric, and the
// path math already lives once in LoadAxisRenderer/SvgChartRenderer — re-deriving it here would be
// a second implementation of the same drawing logic for no reader-facing benefit.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';
import { useAsyncPanel } from './asyncPanel';
import { invalidateService } from './workspace';
import type { AiAvailability } from '../components/AsyncPanel';
import type { RunProgress } from './runs';

// ---------------------------------------------------------------- preflight

export interface PreflightOperation {
  name: string;
  sharePercent: string;
  rateDisplay: string | null;
}

export interface PreflightCheck {
  name: string;
  statusKind: string;
  statusLabel: string;
  detail: string;
  remedy: string | null;
}

export interface SafetyFinding {
  severityKind: string;
  severityLabel: string;
  title: string;
  detail: string;
}

export interface Preflight {
  canRun: boolean;
  plainEnglishSummary: string | null;
  classification: string | null;
  classificationLabel: string | null;
  classificationCaveat: string | null;
  targetRewritten: boolean;
  configuredTarget: string | null;
  effectiveTarget: string | null;
  targetRewriteReason: string | null;
  testTypeLabel: string | null;
  testTypeQuestion: string | null;
  workloadName: string | null;
  environmentName: string | null;
  environmentTypeLabel: string | null;
  dependencyModeLabel: string | null;
  durationDisplay: string | null;
  workloadModelLabel: string | null;
  peakLevelDisplay: string | null;
  workloadSourceDescribe: string | null;
  operations: PreflightOperation[];
  compositionRenderable: boolean;
  compositionSvg: string | null;
  offeredLoad: string | null;
  hasRequestEstimate: boolean;
  requests: number | null;
  estimateCaveat: string | null;
  mutatingOperations: string[];
  checks: PreflightCheck[];
  safetyFindings: SafetyFinding[];
  requiredChallenges: string[];
  fingerprintShortHash: string | null;
  runnerLabel: string | null;
  scriptSourceLabel: string | null;
  thresholdDescriptions: string[];
  error: string | null;
  errorDetails: string[];
}

export function usePreflightQuery(
  serviceId: string,
  workload?: string | null,
  environment?: string | null,
  objective?: string | null
) {
  const params = new URLSearchParams();
  if (workload) params.set('workload', workload);
  if (environment) params.set('environment', environment);
  if (objective) params.set('objective', objective);
  const query = params.toString();

  return useQuery({
    queryKey: ['preflight', serviceId, workload ?? null, environment ?? null, objective ?? null],
    queryFn: () =>
      apiClient.get<Preflight>(`/api/services/${serviceId}/preflight${query ? `?${query}` : ''}`),
    // The drawer that reads this stays mounted (closed) alongside every test row so it can open
    // instantly — without this, every row would fire a preflight request the moment it renders,
    // whether or not anyone ever opens it.
    enabled: Boolean(workload),
  });
}

export interface StartRunResponse {
  started: boolean;
  executionId: string | null;
  error: string | null;
  errorDetails: string[];
}

export function useStartRunMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: {
      workload: string | null;
      environment: string | null;
      objective?: string | null;
      confirmation?: string | null;
    }) => apiClient.post<StartRunResponse>(`/api/services/${serviceId}/run`, request),
    // Without this, navigating straight back to the service page after starting a run could hit a
    // cache still under the global 30s staleTime from before the run existed — showing no inline
    // progress until the new refetchInterval polling incidentally catches up.
    onSuccess: () => invalidateService(queryClient, serviceId),
  });
}

// ---------------------------------------------------------------- the live/terminal read

export interface RunPlanSummary {
  projectId: string;
  projectName: string;
  testTypeLabel: string;
  testTypeQuestion: string;
  workloadName: string;
  environmentName: string;
  targetDisplay: string;
  environmentTypeLabel: string;
  workloadModelLabel: string;
  peakLevelDisplay: string;
  singleOperation: boolean;
  operationsSummary: string;
  classification: string;
  classificationLabel: string;
  classificationCaveat: string;
  totalDurationDisplay: string;
}

export interface RunIdentity {
  executionId: string;
  shortId: string;
  serviceName: string;
  serviceVersion: string | null;
  workloadName: string;
  testTypeLabel: string;
  environmentName: string;
  environmentTypeLabel: string;
  classification: string;
  classificationLabel: string;
  targetUrl: string;
  targetWasRewritten: boolean;
  targetRewriteReason: string | null;
  /** `EXTERNAL_ENDPOINT` | `DOCKER_IMAGE` | `DOCKER_COMPOSE` — always known. */
  targetKind: string;
  targetSummary: string;
  targetOwnershipLabel: string;
  /** The run's confirmed resource envelope, e.g. "0.5 CPU · 512 MiB" — null when none was
   *  confirmed (an external endpoint, a Compose target, or a run with no resolved target at all). */
  resourceSummary: string | null;
  requestedAtIso: string | null;
  finishedAtDisplay: string;
  durationDisplay: string | null;
  /** Raw `TestType` enum name, e.g. `STRESS` — alongside `testTypeLabel` so a renderer can key
   *  stable behaviour off an identifier rather than a display string that is free to reword. Null
   *  for a run recorded before this field existed. */
  testType: string | null;
}

export interface VerdictSection {
  question: string;
  verdict: string;
  verdictLabel: string;
  answer: string;
  qualifications: string[];
}

export interface WorkloadEvidence {
  open: boolean;
  modelLabel: string;
  modelGuidance: string;
  configuredPeakDisplay: string;
  sourceDescribe: string;
  achievedRateDisplay: string | null;
  deliveredPercent: string | null;
  fellShort: boolean;
  deliveredCaveat: string | null;
  requestsDisplay: string;
  estimatedRequestsDisplay: string | null;
  errorRateDisplay: string;
  failuresDisplay: string;
  configuredDurationDisplay: string;
  actualDurationDisplay: string;
  operationMix: string[];
  scriptSourceLabel: string;
}

export interface LatencyRow {
  percentileLabel: string;
  durationDisplay: string;
}

export interface PerformanceEvidence {
  latencyRows: LatencyRow[];
  maxLatencyDisplay: string | null;
  hasLimitsCard: boolean;
  sloBreakpointDisplay: string | null;
  sloBreakpointStrengthLabel: string | null;
  sloBreakpointStagesText: string | null;
  systemSaturationDescribe: string | null;
  systemSaturationExplanation: string | null;
  headroomDisplay: string | null;
  headroomRefusal: string | null;
  baselineQuality: string[];
}

export type ObjectiveKind = 'LATENCY' | 'ERROR_RATE';

export interface AcceptanceResult {
  describe: string;
  verdict: string;
  verdictLabel: string;
  observed: string;
  note: string | null;
  /** From the sealed domain `Threshold` type the result was evaluated against — never guessed
   *  client-side by matching words in `describe`. */
  kind: ObjectiveKind;
  /** `observed` as a fraction of the threshold's own limit — 1.0 sits exactly at the limit, a
   *  failed objective can exceed 1.0. Null when the measurement was unavailable. Never re-derived
   *  by parsing `observed`; the domain already computed it once. */
  observedPosition: number | null;
}

export interface AcceptanceEvidence {
  hasObjectives: boolean;
  results: AcceptanceResult[];
  absenceExplanation: string | null;
}

export interface OperationEvidence {
  name: string;
  hasTraffic: boolean;
  requestsDisplay: string | null;
  rateDisplay: string | null;
  p95Display: string | null;
  p99Display: string | null;
  errorRateDisplay: string | null;
}

export interface LoadAxis {
  renderable: boolean;
  svg: string | null;
  drawsBoundary: boolean;
  drawsSaturation: boolean;
  highestCompliantDisplay: string | null;
  firstNonCompliantDisplay: string | null;
  boundaryStatement: string | null;
  saturationDescribe: string | null;
  testedToDisplay: string | null;
}

export interface TimelineStageRow {
  levelDisplay: string;
  achievedDisplay: string;
  p95Display: string;
  errorRateDisplay: string;
  resultKind: string;
  violatedThresholds: string[];
  signals: string[];
  basisLabel: string;
}

export interface TimelinePoint {
  atIso: string | null;
  /** null marks a gap the series was split on — the point where nothing was measured. */
  value: number | null;
}

export interface TimelinePlot {
  label: string;
  hasData: boolean;
  unitSymbol: string;
  points: TimelinePoint[];
  referencePoints: TimelinePoint[];
  referenceLevel: number | null;
}

export interface TimelineSampleRow {
  timeDisplay: string;
  offeredDisplay: string;
  achievedDisplay: string;
  p95Display: string;
  errorRateDisplay: string;
}

export interface TimelineEvidence {
  present: boolean;
  plots: TimelinePlot[];
  stages: TimelineStageRow[];
  showsDerivedCaveat: boolean;
  tableRows: TimelineSampleRow[];
  /** When the run first stopped complying — an instant shared by every plot's own reference line,
   *  never derived independently in the browser. Null when every stage complied. */
  breakpointAtIso: string | null;
  /** When the workload first moved off its opening stage. Generic to any multi-stage run; a Spike
   *  test's own semantics are what call this instant "the jump" — see `testVisualization.ts`. Null
   *  with fewer than two stages. */
  levelChangeAtIso: string | null;
}

export interface ObservedSignal {
  name: string;
  display: string;
  movement: string | null;
  sourceLabel: string;
  sourceUrl: string | null;
}

export interface ObservabilityGap {
  what: string;
  howToCollect: string;
}

export interface ObservabilityEvidence {
  present: boolean;
  signals: ObservedSignal[];
  providersConsulted: string[];
  gaps: ObservabilityGap[];
}

export interface FindingRow {
  levelKind: string;
  levelLabel: string;
  headline: string;
  detail: string | null;
  hasDetail: boolean;
  strengthLabel: string;
  evidenceIds: string[];
}

export interface MetricDelta {
  metric: string;
  display: string;
  percentChangeDisplay: string;
  /** The domain's own regression classification for this one delta, at its own noise threshold —
   *  null when the change was too small to classify or a percentage does not apply. Never
   *  re-derived by comparing baseline/candidate again in the browser. */
  isDegradation: boolean | null;
  /** Signed percent change as a number — null under the same condition `percentChangeDisplay`
   *  reads "—". */
  percentChange: number | null;
}

export interface ComparisonEvidence {
  baselineLabel: string;
  baselineFinishedAtDisplay: string;
  deltas: MetricDelta[];
  supportsVerdict: boolean;
  verdictLabel: string | null;
  verdictDescription: string | null;
  notComparableExplanation: string | null;
  differences: string[];
}

export interface EvidenceProvenance {
  vortexVersion: string;
  engineVersion: string;
  runtimeVersion: string;
  dockerImage: string | null;
  configurationHash: string;
  secretReferences: string[];
  artifactDirectory: string;
  reproductionCommand: string;
  hasArtifacts: boolean;
  artifactNames: string[];
}

/**
 * Whether the experiment was carried out as specified — the fourth axis, beside the verdict.
 *
 * A run can meet every objective and be invalid; a run can miss every objective and be perfectly
 * valid. Both are shown, and neither is derived from the other.
 */
export interface ValidityFinding {
  code: string;
  label: string;
  effect: string;
  /** Always names the measurement and the threshold it crossed, so it can be argued with. */
  statement: string;
  fromLevel: string | null;
  evidenceIds: string[];
}

export interface Validity {
  grade: string;
  label: string;
  explanation: string;
  /** False for a run recorded before this axis existed. Not a grade, and withholds nothing. */
  assessed: boolean;
  permitsCapacityClaims: boolean;
  findings: ValidityFinding[];
}

export interface ResourceSignal {
  id: string;
  name: string;
  kind: string;
  kindLabel: string;
  scope: string;
  scopeLabel: string;
  display: string;
  /** Empty when the provider published no limit — not the same as staying clear of one. */
  limitDisplay: string;
  utilisationDisplay: string;
  atItsLimit: boolean;
  describe: string;
  /** The fraction `utilisationDisplay` formats, as a number — null under the same condition
   *  `utilisationDisplay` is empty. */
  utilisationFraction: number | null;
}

export interface Resources {
  present: boolean;
  service: ResourceSignal[];
  /** The load generator's own process or container — the narrowest measurement Vortex could isolate. */
  generator: ResourceSignal[];
  /** The whole machine running the load generator — supporting telemetry, never proof by itself that
   *  the generator was constrained. */
  generatorHost: ResourceSignal[];
  /** False means nobody looked at the machine producing the traffic — never that it was healthy. */
  generatorObserved: boolean;
  gaps: ObservabilityGap[];
}

export interface ResourceTimelinePoint {
  atIso: string;
  value: number;
}

export interface ResourceSeries {
  signalId: string;
  providerId: string;
  /** `SYSTEM_UNDER_TEST`, `LOAD_GENERATOR` or `DEPENDENCY` — kept distinct for the same reason
   *  {@link ResourceSignal.scope} is: reading one system's resource as another's is the failure
   *  this whole phase exists to prevent. */
  scope: string;
  scopeLabel: string;
  seriesLabel: string;
  unitSymbol: string;
  points: ResourceTimelinePoint[];
  display: string;
  /** Empty when the provider published no limit. */
  limitDisplay: string;
  utilisationDisplay: string;
  atItsLimit: boolean;
  /** The fraction `utilisationDisplay` formats, as a number — null under the same condition
   *  `utilisationDisplay` is empty. */
  utilisationFraction: number | null;
  /** The published limit's raw value, in this series' own `unitSymbol` — null under the same
   *  condition `limitDisplay` is empty. */
  limitValue: number | null;
}

export interface ResourceKindPlot {
  kind: string;
  kindLabel: string;
  series: ResourceSeries[];
}

export interface ResourceTimelineEvidence {
  present: boolean;
  /** `COMPLETE`, `PARTIAL` or `UNAVAILABLE`. Artifact presence is not completeness — a chart must
   *  never render a partial series as though it were the whole run. */
  completenessStatus: string;
  completenessReason: string;
  plots: ResourceKindPlot[];
}

export interface ConditionRow {
  condition: string;
  label: string;
  outcome: string;
  outcomeLabel: string;
  statement: string;
}

export interface LimitRow {
  kind: string;
  label: string;
  level: string;
  describe: string;
  established: boolean;
}

export interface Capacity {
  present: boolean;
  /** The headline. Empty when no sustainable capacity was established. */
  sustainableDisplay: string;
  /** Why there is no headline. Never both this and a figure, never neither. */
  refusal: string;
  /** Today's tested compliant level, kept beneath and explicitly not a capacity claim. */
  highestPassing: string;
  strengthLabel: string;
  conditions: ConditionRow[];
  limits: LimitRow[];
  firstLimit: string;
  noLimitEstablished: boolean;
  headroomDisplay: string;
  headroomRefusal: string;
}

export interface LoadSummary {
  requestedDisplay: string;
  achievedDisplay: string;
  iterationRateDisplay: string;
  /** Empty when the engine reported nothing. An empty string is not zero drops. */
  droppedDisplay: string;
  droppedWork: boolean;
  observedConcurrency: string;
  deliveredShare: string;
}

export interface OutcomeRow {
  label: string;
  count: number;
  share: string;
}

export interface Reliability {
  /** False means nothing was classified, which must never read as everything having succeeded. */
  reported: boolean;
  errorRateDisplay: string;
  byResponseClass: OutcomeRow[];
  byFailureClass: OutcomeRow[];
}

export interface RunEvidence {
  identity: RunIdentity;
  verdict: VerdictSection;
  workload: WorkloadEvidence;
  performance: PerformanceEvidence;
  acceptance: AcceptanceEvidence;
  hasOperationBreakdown: boolean;
  operations: OperationEvidence[];
  loadAxis: LoadAxis;
  timeline: TimelineEvidence;
  observability: ObservabilityEvidence;
  hasFindings: boolean;
  findings: FindingRow[];
  comparison: ComparisonEvidence | null;
  provenance: EvidenceProvenance;
  releaseMoved: boolean;
  previousCompatibleExecutionId: string | null;
  validity: Validity;
  resources: Resources;
  resourceTimeline: ResourceTimelineEvidence;
  capacity: Capacity;
  load: LoadSummary;
  reliability: Reliability;
}

export interface Run {
  executionId: string;
  running: boolean;
  terminal: boolean;
  stateLabel: string;
  plan: RunPlanSummary;
  progress: RunProgress | null;
  requestedAtDisplay: string;
  startedAtDisplay: string | null;
  failed: boolean;
  failureLabel: string | null;
  failureGuidance: string | null;
  failureDetail: string | null;
  cancelled: boolean;
  evidence: RunEvidence | null;
}

/**
 * `executionId` is nullable so a caller that only sometimes has a run to ask about — {@link
 * TestResult}, gated behind whether a test has ever run — can call this unconditionally (React's
 * rules of hooks forbid calling it only sometimes) and let `enabled` decide whether it actually
 * fetches, rather than passing a made-up id just to satisfy the type.
 */
export function useRunQuery(executionId: string | null) {
  return useQuery({
    queryKey: ['run', executionId],
    queryFn: () => apiClient.get<Run>(`/api/runs/${executionId}`),
    enabled: executionId !== null,
    // The live view refetches this itself (via useRunProgress's onFinished, and manually on
    // cancel) — an automatic refetch here would race those and is never needed while running.
    refetchOnWindowFocus: false,
  });
}

export interface CancelResponse {
  cancelled: boolean;
  message: string;
}

export function useCancelRunMutation(executionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiClient.post<CancelResponse>(`/api/runs/${executionId}/cancel`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['run', executionId] }),
  });
}

// ---------------------------------------------------------------- AI analysis

export interface AnalysisFinding {
  statement: string;
  typeKind: string;
  typeLabel: string;
  confidenceLabel: string;
  evidenceIds: string[];
}

export interface Recommendation {
  action: string;
  rationale: string;
  evidenceIds: string[];
}

export interface NextTest {
  action: string;
  rationale: string;
  wouldDistinguish: string;
  evidenceIds: string[];
}

export interface MissingTelemetry {
  what: string;
  whyItMatters: string;
}

export interface Analysis {
  conclusion: string;
  findings: AnalysisFinding[];
  recommendations: Recommendation[];
  missingTelemetry: MissingTelemetry[];
  nextTest: NextTest | null;
  provenanceDescribe: string | null;
}

export interface AnalysisPanel {
  analysing: boolean;
  latest: Analysis | null;
  earlierCount: number;
  earlier: Analysis[];
  availability: AiAvailability;
}

export function useRunAnalysisPanel(executionId: string) {
  return useAsyncPanel<AnalysisPanel>({
    queryKey: ['run', executionId, 'analysis'],
    statusPath: `/api/runs/${executionId}/analysis`,
    startPath: `/api/runs/${executionId}/analyze`,
    isRunning: (status) => status.analysing,
  });
}
