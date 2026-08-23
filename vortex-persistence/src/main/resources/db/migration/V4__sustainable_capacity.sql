-- The level a service was demonstrated to *sustain*, as distinct from the highest one that passed.
--
-- Those are different questions. The highest level that passed answers "what did we test and get
-- away with?"; this answers "what would we stand behind?". A level can pass every objective while
-- the generator produced it badly, while it was held for forty seconds, or while throughput had
-- already stopped responding to offered load two stages earlier — and the five conditions behind
-- this column are each separately checkable and separately falsifiable.
--
-- Frequently lower than compliant_rate, and sometimes absent where compliant_rate exists. That is
-- the intent rather than a defect: a capacity figure that occasionally declines to exist is the
-- price of one that means something when it does.

-- Stored with its unit for the same reason compliant_rate and failing_rate are — "410
-- requests/sec", "60 VUs". A capacity that has forgotten which quantity it counted cannot be
-- recovered, and cannot be safely divided into anything.
--
-- NULL means two different things, and the JSON below distinguishes them: an observation recorded
-- before this migration, where the conditions were never evaluated, and one where they were
-- evaluated and not all met. Headroom reads that difference — the first keeps reporting against the
-- compliant level it always used, because retroactively deleting a number from every historical
-- page would read as a bug rather than as the announced change it is.
ALTER TABLE capacity_observations ADD COLUMN sustainable_rate TEXT;

-- The five conditions, each with its outcome and the number behind it. Content rather than a query
-- key, following what V2 established: promote what a query filters on, and nothing else.
ALTER TABLE capacity_observations ADD COLUMN sustainable_json TEXT;
