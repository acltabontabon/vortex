// Field-for-field against com.acltabontabon.vortex.app.web.WorkspaceDtos.
//
// Two conventions run through every type here, and both are the server's, not this file's:
//
//   A level always arrives with its unit. There is no bare number for an offered load anywhere
//   below — `50 requests/sec` and `50 VUs` are the same number and different facts, and no
//   conversion between them exists.
//
//   Absence always arrives with its reason. Where the domain refused to compute something it also
//   said why, so the refusal is a null value beside a populated reason, never an empty string this
//   client has to interpret.
//
// Nothing here is re-derived on the client. Readiness, runnability, comparability, provenance,
// capacity and every refusal were decided in vortex-core; React renders them.

import { useQuery, type QueryClient } from '@tanstack/react-query';
import { apiClient } from './client';

export type Verdict = 'PASS' | 'FAIL' | 'NOT_EVALUATED';
export type Classification = 'ISOLATED' | 'INTEGRATED';
export type WorkloadModel = 'OPEN' | 'CLOSED';
export type SourceKind = 'PRODUCTION_OBSERVED' | 'DERIVED_FROM_OBSERVATION' | 'MANUAL';
export type DriftKind = 'UNCHANGED' | 'DRIFTED' | 'NOT_ASSESSABLE';
export type MarkerKind = 'PRODUCTION' | 'TESTED_CAPACITY' | 'FIRST_FAILING';
export type BoundaryStatus =
  | 'ESTABLISHED'
  | 'FAR_EDGE_NOT_REACHED'
  | 'UNSTABLE'
  | 'NOT_EVALUATED';

// ---------------------------------------------------------------- identity and chrome

export interface Target {
  environmentName: string;
  /** The target's real pre-run address, or the empty string for a Docker/Compose target — there is
   *  no address to show before a run resolves one; see `targetSummary` for what to show instead. */
  baseUrl: string;
  environmentTypeLabel: string;
  classification: Classification;
  classificationLabel: string;
  /** The domain's own sentence about what this classification does not establish. */
  classificationCaveat: string;
  dependencyModeLabel: string;
  /** `EXTERNAL_ENDPOINT` | `DOCKER_IMAGE` | `DOCKER_COMPOSE`. */
  targetKind: string;
  /** The target's own summary, e.g. "Docker: payment-service:1.4.2" — carries the useful identity
   *  when `baseUrl` is empty. */
  targetSummary: string;
}

/**
 * What sort of readiness item this is — a different question from whether it is done.
 *
 * <p>Three failures, each different. `REQUIRED`: no run. `EVALUATION`: a run that decides nothing.
 * `ENRICHMENT`: an answer that still stands, just less confidently — the only one an interface may
 * call optional. `RESULT` is not configuration at all: nobody sets it, it becomes true once a run
 * has happened. All decided in `ProjectReadiness.Kind`, never here.
 */
export type ReadinessKind = 'REQUIRED' | 'EVALUATION' | 'ENRICHMENT' | 'RESULT';

export interface ReadinessItem {
  /** Stable identity. Switch on this, never on `label`, which is prose and will be reworded. */
  key: string;
  kind: ReadinessKind;
  label: string;
  satisfied: boolean;
  /** False means "this makes the evidence stronger", not "you cannot run". The two differ. */
  requiredToRun: boolean;
  /**
   * Whether it is unavoidable on the way to a run, directly or through something that is. Not the
   * same as `requiredToRun`: importing an API does not itself gate a run, but the required workload
   * cannot be defined without it, so calling it optional would be untrue.
   */
  effectivelyRequired: boolean;
  /**
   * Whether this can be worked on yet — a third dimension, independent of `requiredToRun` and
   * `satisfied`. A workload on a service with no imported API is required, unsatisfied and
   * unavailable all at once, and collapsing any two of those produces a different wrong screen.
   */
  available: boolean;
  /**
   * False while this only narrows a broader item that is still unsatisfied — one act of
   * configuration answers both, so offering the two separately invites doing the same thing twice.
   */
  distinct: boolean;
  /** Keys of the prerequisites still outstanding. Empty when available. */
  blockedBy: string[];
  /** The domain's own sentence for why it cannot be done yet. Null when available. */
  blockedReason: string | null;
  nextStep: string;
  href: string;
}

export interface Readiness {
  canRun: boolean;
  satisfiedCount: number;
  totalCount: number;
  blockerCount: number;
  items: ReadinessItem[];
  nextStepText: string | null;
}

export interface RunRef {
  id: string;
  testName: string;
  testTypeLabel: string;
  stateLabel: string;
}

export interface ServiceHeader {
  id: string;
  name: string;
  description: string | null;
  target: Target | null;
  environmentCount: number;
  release: string | null;
  readiness: Readiness;
  operationCount: number;
  testCount: number;
  runCount: number;
  running: RunRef | null;
}

// ---------------------------------------------------------------- tests

export interface Source {
  kind: SourceKind;
  label: string;
  describe: string;
  detail: string | null;
  productionInformed: boolean;
  observedWindow: string | null;
  /** The arithmetic behind a derived figure, so the claim is checkable rather than asserted. */
  derivation: string | null;
}

export interface MixRow {
  operationId: string;
  label: string;
  method: string;
  path: string;
  sharePercent: string;
  shareFraction: number;
  /** Null under a concurrency workload, where there is no traffic total to divide. */
  rateDisplay: string | null;
  /** False means the operation is not in the imported description — why the test will not run. */
  known: boolean;
}

export interface Drift {
  kind: DriftKind;
  statement: string;
  derivedFrom: string | null;
  proposedNow: string | null;
  derivation: string | null;
}

export interface TestTypeInfo {
  name: string;
  label: string;
  question: string;
  guidance: string;
  saturating: boolean;
  configuredTestCount: number;
}

export interface TestRow {
  name: string;
  description: string | null;
  question: string;
  testType: string;
  testTypeLabel: string;
  testTypeQuestion: string;
  saturating: boolean;
  model: WorkloadModel;
  modelLabel: string;
  levelDisplay: string;
  levelUnit: string;
  durationDisplay: string;
  stageCount: number;
  ramping: boolean;
  operationCount: number;
  source: Source;
  /** Null when the two are not the same quantity, or no production observation exists. */
  versusProduction: string | null;
  runnable: boolean;
  /** Why not, in the domain's own words. Empty when runnable. */
  problems: string[];
  environmentName: string | null;
  latestRun: RunSummary | null;
  runCount: number;
  drift: Drift | null;
  composition: MixRow[];
  compositionDrift: string | null;
  /** This test's own tested-capacity evidence, or null where no run of it has established one —
   *  never a different test's, and never the service-wide reading above (Overview.capacity). */
  capacity: Capacity | null;
  /** This test's own production/tested/failing-edge range. Never null; `renderable` says whether
   *  there is anything to draw. */
  range: CapacityRange;
}

// ---------------------------------------------------------------- runs

export interface RunSummary {
  id: string;
  verdict: Verdict;
  verdictLabel: string;
  stateLabel: string;
  terminal: boolean;
  testName: string;
  testType: string;
  testTypeLabel: string;
  levelDisplay: string;
  environmentName: string;
  classification: Classification;
  release: string | null;
  answer: string;
  p95: string | null;
  durationDisplay: string | null;
  relativeTime: string;
  isoTimestamp: string;
  /** Null where the test behind this run no longer resolves — which is not a mismatch. */
  matchesCurrentTest: boolean | null;
  differences: string[];
}

// ---------------------------------------------------------------- capacity

export interface Marker {
  kind: MarkerKind;
  label: string;
  displayWithUnit: string;
  /** 0..1 along the axis, computed by CapacityRange. Never recomputed here. */
  position: number;
}

export interface CapacityRange {
  renderable: boolean;
  unit: string | null;
  markers: Marker[];
  openEnded: boolean;
}

export interface ConstraintCandidate {
  /** The domain's own careful sentence — correlation stated, causation explicitly not claimed. */
  describe: string;
  strengthLabel: string;
  support: string;
}

export interface Capacity {
  compliantLevel: string;
  label: string;
  boundary: string;
  boundaryLabel: string;
  quotable: boolean;
  boundaryStatus: BoundaryStatus;
  boundaryStatusLabel: string;
  boundaryStrength: string;
  firstNonCompliant: string | null;
  /** Exactly one of headroom and headroomRefusal is populated. Never both, never neither. */
  headroom: string | null;
  headroomRefusal: string | null;
  serviceVersion: string | null;
  environmentName: string;
  classification: Classification;
  dependencyMode: string;
  workloadName: string;
  operationMix: string[];
  objectives: string[];
  durationDisplay: string;
  measuredAt: string;
  runId: string;
  /** The domain's own conditions() sentences, used verbatim — never re-derived from the fields above. */
  conditions: string[];
  constraintCandidates: ConstraintCandidate[];
}

// ---------------------------------------------------------------- production

export interface Production {
  peakRate: string;
  averageRate: string | null;
  p95ObservedRate: string | null;
  source: string | null;
  attributed: boolean;
  /** False means somebody typed these in. The interface must never dress that as telemetry. */
  fetched: boolean;
  observedWindow: string | null;
  note: string | null;
  qualityFacts: string[];
  observedMix: MixRow[];
  mixCoverage: string | null;
}

// ---------------------------------------------------------------- pages

export interface Overview {
  header: ServiceHeader;
  production: Production | null;
  objectives: string[];
  capacity: Capacity | null;
  range: CapacityRange;
  latestRun: RunSummary | null;
  tests: TestRow[];
  recentRuns: RunSummary[];
  suggestSmokeTest: boolean;
  evidencePredatesRelease: boolean;
  releaseGapText: string | null;
  /** Every test type's own most recent evidence, in `TestType` order — always six entries. */
  evidenceByTestType: TestTypeEvidence[];
}

export interface Tests {
  header: ServiceHeader;
  tests: TestRow[];
  testTypes: TestTypeInfo[];
  environmentNames: string[];
}

/**
 * One test type's own most recent evidence, or the honest absence of any — one entry per
 * `TestType`, always, so a service that has never run a kind of test still says so.
 *
 * `primaryValueKind` says which of `primaryValue`/`outcomeLabel` is the number this test type is
 * actually about — decided server-side, from figures the domain already computed, never re-derived
 * here.
 */
export interface TestTypeEvidence {
  testType: string;
  testTypeLabel: string;
  hasEvidence: boolean;
  outcome: Verdict | null;
  outcomeLabel: string | null;
  primaryValueKind: 'RATE' | 'DURATION' | 'OUTCOME' | null;
  primaryValue: string | null;
  /** Tested capacity over observed production peak (e.g. `"1.76×"`). Null wherever the domain did
   *  not produce one — never computed on the client. */
  secondaryValue: string | null;
  workloadName: string | null;
  environmentName: string | null;
  release: string | null;
  executionId: string | null;
  relativeTime: string | null;
  isoTimestamp: string | null;
  answer: string | null;
  /** Whether a run of this test type is in flight right now — independent of `hasEvidence`, so a
   *  first-ever run in progress is never confused with prior completed evidence. */
  running: boolean;
  runningWorkloadName: string | null;
}

// ---------------------------------------------------------------- hooks

/**
 * Marks everything cached about one service stale in one call. Every query key in this module (and
 * `configuration.ts`'s) starts with `['service', id, ...]`, so a single prefix invalidation is
 * exactly "this service's data may have changed" — cheap, since an inactive query is only flagged
 * stale, not eagerly refetched, until something next observes it.
 */
export function invalidateService(queryClient: QueryClient, id: string) {
  queryClient.invalidateQueries({ queryKey: ['service', id] });
}

export function useServiceHeaderQuery(id: string) {
  return useQuery({
    queryKey: ['service', id],
    queryFn: () => apiClient.get<ServiceHeader>(`/api/services/${id}`),
    // While a run is in flight, poll — the same 2s cadence and the same "already have the running
    // flag, just check it" shape configuration.ts uses for the local lab's own running state.
    refetchInterval: (query) => (query.state.data?.running ? 2000 : false),
  });
}

export function useOverviewQuery(id: string) {
  return useQuery({
    queryKey: ['service', id, 'overview'],
    queryFn: () => apiClient.get<Overview>(`/api/services/${id}/overview`),
    refetchInterval: (query) => (query.state.data?.header.running ? 2000 : false),
  });
}

export function useTestsQuery(id: string) {
  return useQuery({
    queryKey: ['service', id, 'tests'],
    queryFn: () => apiClient.get<Tests>(`/api/services/${id}/tests`),
  });
}

export interface Runs {
  header: ServiceHeader;
  runs: RunSummary[];
}

export interface CapacityHistoryEntry {
  serviceVersion: string;
  /** Whether this is the release currently under test — the row most likely worth checking. */
  current: boolean;
  observations: Capacity[];
}

export interface Evidence {
  header: ServiceHeader;
  capacity: Capacity | null;
  range: CapacityRange;
  headroomLabel: string | null;
  production: Production | null;
  releaseMoved: boolean;
  history: CapacityHistoryEntry[];
  runs: RunSummary[];
}

export function useRunsQuery(id: string) {
  return useQuery({
    queryKey: ['service', id, 'runs'],
    queryFn: () => apiClient.get<Runs>(`/api/services/${id}/runs`),
  });
}

export function useEvidenceQuery(id: string) {
  return useQuery({
    queryKey: ['service', id, 'evidence'],
    queryFn: () => apiClient.get<Evidence>(`/api/services/${id}/evidence`),
  });
}
