Two load tests have already been compared. Every difference between them, whether they are
comparable, and whether one regressed are already computed and decided. Explain what materially
changed and which changes deserve investigation — do not recalculate a percentage or disagree with
the verdict.

Rules you must follow:

1. COMPARABILITY and the regression verdict are settled facts. Do not contradict them. When
   COMPARABILITY is not HIGH, say plainly that the comparison is limited and why, using DIFFERENCES
   or MISSING TELEMETRY — do not present the deltas as an unqualified regression or improvement.
2. Cite evidence by its exact "delta:" identifier from AVAILABLE EVIDENCE. Never compute your own
   percentage from the baseline/candidate values in DELTAS — refer only to the change already
   expressed.
3. Every finding has a type, and the type bounds your confidence:
   - OBSERVATION: a measured difference. May be HIGH.
   - CORRELATION: two deltas moved together. At most MEDIUM.
   - HYPOTHESIS: a plausible explanation for a delta needing another measurement to confirm. At
     most LOW.
   - LIMITATION: what could not be determined, given DIFFERENCES or MISSING TELEMETRY.
4. Describe association, not causation, exactly as for a single run.
5. No recommendations, no next test — findings only. Naming what deserves investigation is enough.
6. Be brief.

Respond with JSON only — no prose before or after, no markdown fences:

{
  "conclusion": "one or two sentences on what materially changed and whether it deserves attention",
  "findings": [
    {
      "statement": "an interpretation, phrased as correlation or hypothesis",
      "type": "OBSERVATION | CORRELATION | HYPOTHESIS | LIMITATION",
      "confidence": "HIGH | MEDIUM | LOW",
      "evidence": ["exact delta: identifiers from AVAILABLE EVIDENCE"]
    }
  ]
}

=== COMPARISON ===
Baseline: {baselineLabel}
Candidate: {candidateLabel}
Comparability (already decided): {comparability}
Regression verdict (already decided): {regressionVerdict}

=== DIFFERENCES (why these runs are or are not the same experiment) ===
{differences}

=== DELTAS ===
{deltas}

=== AVAILABLE EVIDENCE (the only identifiers you may cite) ===
{evidenceIds}

=== MISSING TELEMETRY ON EITHER SIDE (not measured — do not guess at these) ===
{missingOnEitherSide}
