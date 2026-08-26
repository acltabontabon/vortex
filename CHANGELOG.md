# Changelog

All notable changes to Vortex are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Unreleased

## [0.1.0-alpha.12] - 2026-08-27

### Fixed

- Dynatrace MCP's `execute_dql` extraction now prefers the largest syntactically valid JSON
  fragment found in the tool's text response, instead of the first one. A `timeseries` query with a
  fine interval can return thousands of per-bucket values wrapped in Dynatrace's explanatory prose,
  which made it possible for an earlier, smaller, incidentally-valid JSON fragment to be mistaken for
  the real payload — surfacing as "no numeric value was found" even though the real data was present
  further along in the same response.

## [0.1.0-alpha.11] - 2026-08-27

### Fixed

- Dynatrace MCP's `execute_dql` responses are now extracted correctly regardless of how the
  server wraps its JSON — a fenced ` ```json ` block, or (as also observed against a real endpoint)
  a bare prefix like `"DQL Response: [...]"` with no fence at all. Vortex now scans for the first
  syntactically complete JSON value anywhere in the text rather than only recognizing one specific
  wrapping style; a response with no JSON in it at all (a markdown table, plain prose) is still
  correctly refused rather than guessed at.

## [0.1.0-alpha.10] - 2026-08-27

### Fixed

- Dynatrace MCP's `execute_dql` responses are now parsed correctly when the server wraps its JSON
  result in explanatory prose and a fenced code block, rather than returning bare JSON — the shape
  Dynatrace's real MCP server actually answers with. Vortex previously treated that whole response as
  unparseable prose and refused it outright ("the tool returned text instead of structured data").
  The rejection message also now includes a snippet of what was actually returned, for easier
  diagnosis if this happens again in some other shape.

## [0.1.0-alpha.9] - 2026-08-27

### Fixed

- Dynatrace MCP's generated DQL queries now quote the `from:`/`to:` timestamp values. Dynatrace's
  parser rejected the unquoted form with a confusing "an integer number like `18` isn't allowed
  here" error (pointing at a digit inside the timestamp itself), which made every query against a
  real Dynatrace MCP endpoint fail.

## [0.1.0-alpha.8] - 2026-08-27

### Added

- Dynatrace MCP now supports multi-organization accounts: when Dynatrace advertises more than one
  organization, Test Connection surfaces them and Settings → Dynatrace offers a dropdown to pick
  which one to query. Single-organization accounts are unaffected — nothing new to configure.

### Changed

- Startup now shows a Vortex banner and a real "ready" summary (URL, workspace path, and whether
  k6/Docker/Local AI were detected) instead of the generic Spring Boot banner and framework startup
  chatter (auto-config announcements, embedded Tomcat/Flyway/Hikari noise). Failures still surface in
  full at WARN/ERROR — only routine, expected startup noise was quieted.
- The Settings page now shows the actual released Vortex version instead of a hand-maintained string
  that had drifted out of date.
- Dynatrace MCP Settings no longer offers a separate "paste an MCP configuration" tab — there is now
  one way to set the endpoint: type or paste the URL directly into the Endpoint field.

## [0.1.0-alpha.7] - 2026-08-27

### Fixed

- Dynatrace MCP's `execute_dql` calls now send the arguments the real server requires
  (`dqlStatement` and an `organization` resolved from the server's own tool schema) instead of a
  `query` key it rejected. The Settings-page Dynatrace badge now reflects the result of your last
  Test Connection click instead of permanently reading "Unavailable."

### Removed

- Dynatrace MCP's Direct HTTPS connection mode and OAuth client credentials auth are removed — the
  local `npx mcp-remote` bridge added in 0.1.0-alpha.6 is now the only way Vortex reaches a Dynatrace
  MCP endpoint. See ADR-052.

## [0.1.0-alpha.6] - 2026-08-26

### Added

- Dynatrace MCP can now be reached through a local `npx mcp-remote` bridge instead of connecting
  directly, for setups where direct HTTPS access or an OAuth client isn't an option — pick "Local
  bridge" under Settings → Dynatrace → Connection mode. The first connection opens a browser to sign
  in to Dynatrace; Vortex reuses that session afterward. This requires Node.js and a browser on the
  same machine as Vortex, so it's meant for local, single-machine use.

## [0.1.0-alpha.5] - 2026-08-26

### Added

- Dynatrace MCP can now authenticate with an OAuth client credentials grant (client ID + secret) as
  an alternative to a static bearer token — pick it under Settings → Dynatrace. A config generated
  for an interactive editor client (bare URL, no headers) still won't work as-is, since that implies
  a browser login Vortex can't complete; use a platform token or an OAuth client instead. Pasting a
  config now also recognizes the plain `url` shape those clients produce, not just the `mcp-remote`
  bridge shape.

## [0.1.0-alpha.4] - 2026-08-26

### Added

- Dynatrace production observation can now be fetched over MCP as well as Dynatrace's REST API, for
  teams whose only access to Dynatrace is an internal MCP server (typically reachable only over
  VPN). Configure it under Settings → Dynatrace, then set a service's observation source to
  Dynatrace with transport `mcp`. Operation mix isn't available over this transport yet.

### Changed

- The AI interpretation panel has been redesigned: findings now show a type icon (observation,
  correlation, hypothesis, limitation) and a confidence dot-scale instead of plain text, an evidence
  citation previews the deterministic finding it points to on hover before jumping to it, a running
  analysis shows elapsed time, a failed attempt is shown distinctly with a retry action instead of
  silently looking identical to one that was never requested, and earlier analyses are browsable one
  at a time instead of dumped into a single flat list.
- The local AI assistant is hardened against transient failures and a misbehaving model: automatic
  retry with backoff on connection blips, a circuit breaker that backs off briefly after repeated
  failures, per-call timeouts sized to what each call actually needs, a bound on how many findings
  and recommendations one analysis can carry, and a guard against a prompt silently exceeding the
  model's context window. The prompt template was retuned (v6) for the qwen3:8b baseline model.

### Fixed

- A Dynatrace MCP header value submitted unchanged as its masked placeholder now resolves back to
  the stored secret instead of overwriting it with the literal mask; a masked value with nothing to
  resolve against (a new or renamed header) is now rejected instead of silently stored as-is.
- A Dynatrace MCP endpoint must now be `https://` — `http://` is rejected, since headers configured
  for it may carry credentials.
- The Dynatrace MCP connection test now checks only auth, reachability, and tool discovery; it no
  longer runs a telemetry-access stage against a specific service, since that stage never made sense
  before a service is mapped. A passing test validates the shared MCP connection only, not any one
  service's entity id or traffic.

## [0.1.0-alpha.3] - 2026-08-26

### Changed

- The homepage's command strip now resolves each intent against the selected service instead of
  sending three of its four commands to the same page. "Find its limit" and "Validate capacity" go
  straight to the preflight for that service's own breakpoint or average-load workload — chosen by
  the kind of test it is, not by which workload happens to be first — or open the test composer
  already set to that evaluation when none exists yet. "Compare runs" reaches the run list filtered
  to the service, where two runs can actually be picked, rather than a read-only evidence page.
  Every command now states what it will do before it is pressed ("Run breakpoint-ramp", "Set one
  up", "Pick two of 3 recent runs"), says when a run would reach no verdict for want of objectives,
  and explains itself rather than going quiet when it cannot act.
- The selected service is now part of the homepage address, so leaving for a composer or a preflight
  and coming back returns to the service that was being worked on. Declining a run at preflight
  returns where it was launched from instead of always landing on the service page.

### Fixed

- The "Fix" links on the setup checklist for objectives and production traffic now scroll to those
  sections of the configuration page. They had always pointed at anchors the page never defined, so
  they silently landed at the top of it.
- Runs completed before the Yes/No/Undetermined answer prefix was dropped (0.1.0-alpha.2) now get
  that same wording retroactively — a migration backfills the stored answer text for existing runs,
  so history isn't stuck with the old phrasing forever.

## [0.1.0-alpha.2] - 2026-08-25

### Added

- Add Service now discovers a repository's existing `.vortex/vortex.yaml` (or `.yml`) as soon as a
  location is entered: it reports what it found, restores the workloads, objectives, environments and
  operation bindings on submit, and lets the service be renamed before adopting it. A repository
  already registered is recognised rather than duplicated, and an invalid file is explained rather
  than silently skipped or partially imported. `vortex.yaml` can also now record where a service's
  OpenAPI description lives (a repository-relative file or a URL), so a cloned repository is
  self-describing end to end.

### Changed

- Run answers no longer force a redundant Yes/No/Undetermined prefix — the pass/fail badge already
  states the verdict, and the one-line answer now reads as a direct statement of what happened, which
  also fixes the mismatch for test types (Spike, Stress, Breakpoint) whose guiding question isn't
  phrased as yes/no.

## [0.1.0-alpha.1] - 2026-08-25

### Added

- OpenAPI-driven onboarding — import a service's OpenAPI document and Vortex deterministically
  derives its operations, no model involved.
- Workload modelling that keeps arrival rate and concurrency distinct, and divides a total rate
  across operations rather than repeating it.
- Deterministic execution and evaluation engine, backed by k6, producing pass/fail thresholds,
  breakpoints and headroom that never depend on AI.
- Execution targets as a first-class concept, separate from service identity — external endpoints,
  Docker images, and Docker Compose stacks, each with a managed lifecycle
  ([ADR-042](docs/adr/adr-042-execution-targets-are-not-services.adoc)).
- Run comparison gated on experiment identity, so two runs are only compared when they tested the
  same thing.
- Portable test definitions — what to test is written to `.vortex/vortex.yaml` in the service's own
  repository and reviewable in a pull request.
- Optional local-AI interpretation via Ollama, layered on top of measurements Vortex already
  computed — never the other way around.
- Full CLI (`doctor`, `validate`, `run`, `compare`, `export`) with a documented exit-code contract
  for CI gating.
- Report exporters — JSON, Markdown and PDF — generated from one sanitized evidence model.
- React + Mantine web interface, service-centric: service switcher, live run progress, and
  evidence/report views built around a single service rather than a flat run list.
