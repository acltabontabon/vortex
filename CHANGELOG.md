# Changelog

All notable changes to Vortex are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Vortex has not made a release yet, so everything to date is recorded under
[Unreleased](#unreleased).

## Unreleased

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
