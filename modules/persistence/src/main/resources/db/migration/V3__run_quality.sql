-- Whether an experiment was carried out as specified, stored beside whether the service passed.
--
-- These are different questions and the answers routinely disagree. A run can meet every objective
-- and be invalid — a workload asking for 1,000 requests/sec on a machine that could produce 720
-- reports a comfortable service and measures Vortex's own hardware. A run can miss every objective
-- and be perfectly valid; a stress test that breaks the service is the healthiest artefact in the
-- product. Storing the grade next to the verdict is what keeps a reader from collapsing them.

-- VALID, DEGRADED, INVALID, or NOT_ASSESSED.
--
-- Promoted to a column because comparison has to exclude an invalid baseline when *choosing* one,
-- and doing that from content would mean deserialising every candidate execution to reject most of
-- them. Everything else about the assessment stays in JSON below, following what V2 established:
-- promote what a query filters on, and nothing else.
--
-- Defaulted rather than backfilled, and deliberately not to 'VALID'. A row written before this
-- migration was never assessed, and NOT_ASSESSED is the only honest description of it. It is not a
-- grade: it carries no reason codes and therefore withholds nothing, so an older run's page gains a
-- note rather than losing a number. Defaulting to VALID would have been the same mistake this whole
-- axis exists to prevent — a judgement nobody made, standing behind a capacity figure.
ALTER TABLE executions ADD COLUMN run_quality TEXT NOT NULL DEFAULT 'NOT_ASSESSED';

-- The findings behind the grade: which rule fired, what it measured, and the threshold it crossed.
-- Content rather than a query key. Null for a run recorded before validity was assessed, which is
-- the same thing the column above says in one word.
ALTER TABLE executions ADD COLUMN run_quality_json TEXT;

-- Baselines are chosen by fingerprint and must skip the invalid ones. Without this the exclusion
-- turns every baseline lookup into a scan of a service's whole history.
CREATE INDEX idx_executions_fingerprint_quality
    ON executions (plan_fingerprint, run_quality);
