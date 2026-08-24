# Changelog

All notable changes to Vortex are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Vortex has not made a release yet, so everything to date is recorded under
[Unreleased](#unreleased).

## Unreleased

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
