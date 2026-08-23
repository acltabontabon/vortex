-- Vortex local database, baseline schema.
--
-- What lives here, and what deliberately does not:
--
--   vortex.yaml                  the portable definition of what to test — the source of truth,
--                                belongs in version control next to the service
--   this database                local application state, history and an index over the above
--   executions/<id>/ on disk     immutable evidence: the effective plan, the generated script,
--                                the engine's output, the report
--
-- Configuration is stored here as a mirror of the file, never as the only copy. A performance
-- definition that exists solely inside one installation of a UI cannot be committed, reviewed,
-- shared or run from a pipeline, and is therefore not reproducible.
--
-- Large artifacts stay on the filesystem with a reference here. Engine output for a long run
-- reaches hundreds of megabytes, which does not belong in a relational column and is far easier
-- to inspect with ordinary tools when it is a file.
--
-- Timestamps are ISO-8601 UTC strings. SQLite has no native timestamp type, and storing text
-- keeps the database readable with any tool rather than requiring an epoch conversion.

CREATE TABLE projects (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    description     TEXT NOT NULL DEFAULT '',
    workspace_path  TEXT NOT NULL DEFAULT '',
    service_version TEXT NOT NULL DEFAULT '',
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

-- The project's performance definition, mirrored from vortex.yaml.
-- Stored in its own serialised form rather than shredded across tables: the file is the
-- authority, and re-deriving it from normalised columns would invite the two to diverge.
CREATE TABLE project_configurations (
    project_id  TEXT PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    format      TEXT NOT NULL DEFAULT 'yaml',
    content     TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

-- Operations discovered from an API description. Evidence of what a specification declared at a
-- point in time, not a live view of the service — hence import_source and imported_at.
CREATE TABLE service_catalogs (
    project_id    TEXT PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    import_source TEXT NOT NULL,
    source_ref    TEXT NOT NULL DEFAULT '',
    title         TEXT NOT NULL DEFAULT '',
    version       TEXT NOT NULL DEFAULT '',
    content       TEXT NOT NULL,
    imported_at   TEXT NOT NULL
);

-- One run of a performance test.
--
-- The plan, results and deterministic summary are stored as JSON documents because they are read
-- and written whole, and because their shape must be free to evolve without a migration for every
-- new metric. The denormalised columns beside them exist purely so the history list can be
-- rendered and filtered without deserialising every row.
CREATE TABLE executions (
    id                TEXT PRIMARY KEY,
    project_id        TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    state             TEXT NOT NULL,
    verdict           TEXT NOT NULL DEFAULT 'NOT_EVALUATED',
    workload_name     TEXT NOT NULL DEFAULT '',
    test_type         TEXT NOT NULL DEFAULT '',
    environment_name  TEXT NOT NULL DEFAULT '',
    classification    TEXT NOT NULL DEFAULT '',
    service_version   TEXT NOT NULL DEFAULT '',
    plan_fingerprint  TEXT NOT NULL DEFAULT '',
    requested_at      TEXT NOT NULL,
    started_at        TEXT,
    finished_at       TEXT,
    plan_json         TEXT NOT NULL,
    results_json      TEXT,
    summary_json      TEXT,
    tool_versions_json TEXT,
    artifacts_json    TEXT NOT NULL DEFAULT '{}',
    failure_reason    TEXT,
    failure_detail    TEXT NOT NULL DEFAULT ''
);

CREATE INDEX idx_executions_project_requested
    ON executions (project_id, requested_at DESC);

CREATE INDEX idx_executions_requested
    ON executions (requested_at DESC);

-- Finds runs left mid-flight by a previous process, so history never shows a test that appears
-- to still be running when nothing is running it.
CREATE INDEX idx_executions_state
    ON executions (state);

-- Lets a later run be recognised as testing the same thing, which is what regression comparison
-- requires before it will produce a verdict.
CREATE INDEX idx_executions_fingerprint
    ON executions (plan_fingerprint);

-- AI interpretations of an execution.
--
-- Many rows per execution, by design. Re-analysing with a newer model or a revised prompt adds a
-- record; it never overwrites the previous interpretation. Measurements are immutable, and which
-- model produced an opinion is part of how much that opinion is worth.
CREATE TABLE analyses (
    id             TEXT PRIMARY KEY,
    execution_id   TEXT NOT NULL REFERENCES executions (id) ON DELETE CASCADE,
    state          TEXT NOT NULL,
    conclusion     TEXT NOT NULL DEFAULT '',
    content_json   TEXT NOT NULL,
    provider       TEXT NOT NULL DEFAULT '',
    model          TEXT NOT NULL DEFAULT '',
    prompt_version TEXT NOT NULL DEFAULT '',
    generated_at   TEXT,
    duration_ms    INTEGER NOT NULL DEFAULT 0,
    failure_message TEXT NOT NULL DEFAULT ''
);

CREATE INDEX idx_analyses_execution
    ON analyses (execution_id, generated_at DESC);

-- Evidence that a service met its objectives at a particular traffic level.
--
-- Accumulates as history rather than being overwritten: tested capacity moves with the version,
-- the configuration, the infrastructure and the size of the data, so "the capacity" is not a
-- property a service has. Every row carries the conditions it was measured under, because the
-- number means nothing without them.
CREATE TABLE capacity_observations (
    id               TEXT PRIMARY KEY,
    project_id       TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    execution_id     TEXT NOT NULL REFERENCES executions (id) ON DELETE CASCADE,
    service_version  TEXT NOT NULL DEFAULT '',
    -- Stored with its unit ("118 requests/sec", "50 VUs"). A capacity figure that has forgotten
    -- which quantity it counted is not a recoverable number.
    compliant_rate   TEXT NOT NULL,
    environment_name TEXT NOT NULL DEFAULT '',
    classification   TEXT NOT NULL,
    dependency_mode  TEXT NOT NULL,
    content_json     TEXT NOT NULL,
    observed_at      TEXT NOT NULL
);

CREATE INDEX idx_capacity_project_observed
    ON capacity_observations (project_id, observed_at DESC);

-- Draft state for the onboarding wizard.
--
-- An eight-step wizard that loses everything when the browser is closed is a wizard people abandon.
-- Drafts are separate from real projects so unfinished work never appears as configuration.
CREATE TABLE onboarding_drafts (
    id         TEXT PRIMARY KEY,
    step       INTEGER NOT NULL DEFAULT 1,
    content    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
