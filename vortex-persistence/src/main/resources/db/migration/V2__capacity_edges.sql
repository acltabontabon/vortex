-- Both edges of a tested capacity boundary, and whether the two form one at all.
--
-- A capacity observation used to record only the highest level that met every objective. That is
-- the more quotable half of the evidence and the less useful one: "the service sustained 400
-- requests/sec" invites "and what happened at 450?", and a run that measured the answer and threw
-- it away has kept the wrong half.
--
-- The edge itself, its latency and errors, and the constraint candidates observed there all ride in
-- content_json, which already holds the whole observation. What is promoted to columns here is only
-- what a query needs to filter or sort on.

-- The failing edge, stored with its unit for the same reason compliant_rate is ("450 requests/sec",
-- "60 VUs"): a capacity figure that has forgotten which quantity it counted is not recoverable.
-- Null when the run never violated an objective, which is a real and different outcome from a run
-- that failed at its first level.
ALTER TABLE capacity_observations ADD COLUMN failing_rate TEXT;

-- ESTABLISHED, FAR_EDGE_NOT_REACHED, UNSTABLE or NOT_EVALUATED. A run reading 100 PASS / 200 FAIL /
-- 300 PASS has not found a boundary, and storing its highest passing level without saying so would
-- turn noise into a capacity claim.
--
-- Defaulted rather than backfilled: rows written before this migration recorded no far edge and no
-- monotonicity check, so NOT_EVALUATED is the honest description of what is known about them. It
-- keeps them out of headroom calculations, which is correct — nobody checked.
ALTER TABLE capacity_observations ADD COLUMN boundary_status TEXT NOT NULL DEFAULT 'NOT_EVALUATED';

-- How well the boundary is established. Deliberately not named "strength": it says nothing about
-- any constraint candidate recorded alongside it, and conflating the two would let "HIGH" next to a
-- CPU figure read as "CPU is the cause, with high confidence".
ALTER TABLE capacity_observations ADD COLUMN boundary_strength TEXT NOT NULL DEFAULT 'INSUFFICIENT';

-- Capacity history is read per service version, because tested capacity moves with the release and
-- a list interleaving two of them invites a comparison between numbers that were never comparable.
CREATE INDEX idx_capacity_project_version
    ON capacity_observations (project_id, service_version, observed_at DESC);
