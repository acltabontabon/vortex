import type { RunEvidence } from '../../../api/run';

/**
 * "What Vortex learned" — a short, ordered reading of evidence the domain already computed.
 *
 * <p>Every line below is a selection or a light rephrasing of an existing field — `verdict.answer`,
 * a `ValidityFinding.statement`, `capacity.sustainableDisplay`/`refusal`, a `ResourceSignal`'s own
 * `describe`, a `DeterministicFinding`'s `headline`. None of it is synthesized: this function picks
 * and orders sentences the domain already wrote, the same discipline `EvidenceIds` enforces on an AI
 * interpretation, applied here to a deterministic summary instead.
 */

export interface NarrativeLine {
  tone: 'pass' | 'warn' | 'fail' | 'info';
  text: string;
}

const MAX_FINDING_LINES = 4;

/**
 * Deliberately never repeats `verdict.answer` — that sentence already leads the "Result" block right
 * above this one. What belongs here is what a reader would otherwise have to piece together from
 * several sections: the specific facts behind the verdict, not the verdict again.
 */
export function deriveNarrative(evidence: RunEvidence): NarrativeLine[] {
  const lines: NarrativeLine[] = [];
  const seen = new Set<string>();
  const add = (tone: NarrativeLine['tone'], text: string) => {
    if (!text || seen.has(text)) return;
    seen.add(text);
    lines.push({ tone, text });
  };

  // 1. Evidence quality, when it is not simply valid — the one-line consequence, not the full
  // finding-by-finding detail already listed in the "Evidence quality" block above this one.
  if (evidence.validity.assessed && evidence.validity.grade !== 'VALID') {
    add(evidence.validity.grade === 'INVALID' ? 'fail' : 'warn', evidence.validity.explanation);
  }

  // 3. Capacity — the headline if one was established, otherwise its refusal.
  if (evidence.capacity.sustainableDisplay) {
    add('pass', `Sustainable capacity: ${evidence.capacity.sustainableDisplay}.`);
  } else if (evidence.capacity.refusal) {
    add('info', evidence.capacity.refusal);
  }

  // 4. The worst resource signal on the system under test — the one closest to, or past, its limit.
  const worstService = worstSignal(evidence.resources.service);
  if (worstService) {
    add(worstService.atItsLimit ? 'fail' : (worstService.utilisationFraction ?? 0) >= 0.7 ? 'warn' : 'info',
      worstService.describe);
  }

  // 5. Load-generator saturation, called out on its own — never folded into the service's own line,
  // since confusing the two is the exact failure this whole phase exists to prevent.
  const saturatedGenerator = evidence.resources.generator.find((signal) => signal.atItsLimit);
  if (saturatedGenerator) {
    add('warn', `The load generator itself is at its limit (${saturatedGenerator.describe}) — this `
      + 'may limit how accurately the offered load reflects what was actually requested.');
  }

  // 6. Deterministic findings not already covered above, worst first — including PASS-level ones:
  // "the configured workload was sustained", "all objectives passed" are exactly the concrete facts
  // this section exists to surface, and nothing above this line restates them.
  const rank: Record<string, number> = { FAIL: 0, WARNING: 1, OBSERVATION: 2, PASS: 3 };
  const remaining = [...evidence.findings]
    .sort((a, b) => (rank[a.levelKind] ?? 9) - (rank[b.levelKind] ?? 9));
  let added = 0;
  for (const finding of remaining) {
    if (added >= MAX_FINDING_LINES) break;
    const tone = finding.levelKind === 'FAIL' ? 'fail' : finding.levelKind === 'WARNING' ? 'warn' : 'info';
    const before = lines.length;
    add(tone, finding.headline);
    if (lines.length > before) added += 1;
  }

  return lines;
}

function worstSignal(signals: RunEvidence['resources']['service']) {
  if (signals.length === 0) return null;
  return [...signals].sort(
    (a, b) => (b.utilisationFraction ?? -1) - (a.utilisationFraction ?? -1),
  )[0];
}
