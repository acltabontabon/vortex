# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What Vortex is

A local-first performance engineering workbench: it turns an OpenAPI description and a stated
traffic goal into a k6 workload, runs it, and produces deterministic pass/fail evidence (thresholds,
breakpoints, headroom) with an optional local-AI (Ollama) interpretation layered on top — never the
other way around. Read [README.md](README.md) for the product model (Service → Workload →
Evaluation → Run → Evidence) before touching domain code; it explains *why* the domain is shaped the
way it is, not just what it does.

## Commands

```bash
./mvnw clean verify                        # full build + test suite (Java 25 required)
./mvnw test                                # tests only, all modules
./mvnw -pl vortex-core test                # tests for one module
./mvnw -pl vortex-core test -Dtest=ClassName#methodName   # a single test
./mvnw -pl vortex-app -am package -DskipTests             # build the runnable jar
./mvnw -pl vortex-app spring-boot:run      # run Vortex on 127.0.0.1:7717
./mvnw -pl vortex-demo-service spring-boot:run             # sample service (has a deliberate bottleneck) on :8080
make help                                  # same targets as convenience wrappers
```

Frontend (`vortex-web/`, builds into `vortex-app/src/main/resources/static/app/`):

```bash
cd vortex-web
npm run dev        # Vite dev server on :5173, proxies /api to :7717
npm run build       # tsc -b && vite build — output baked into the Spring Boot jar
npm run lint         # oxlint
npm test            # vitest run
npm run test:watch
```

k6, Docker and Ollama are optional for the build — tests that need them auto-skip (`@EnabledIf`).
There are no opt-in Maven profiles for this.

Render docs (AsciiDoc under `docs/`) before submitting doc changes; it fails on any warning:

```bash
make docs           # or ./scripts/docs-build.sh — needs `gem install asciidoctor`
```

If that reports `asciidoctor not found`, check whether it is installed but off `PATH` before
installing it again — Homebrew's Ruby puts gem binaries in
`/opt/homebrew/lib/ruby/gems/<version>/bin`, which is not on `PATH` by default.

## Architecture

Modular monolith, one process, one jar. Full diagram and rationale:
[docs/02-architecture/architecture.adoc](docs/02-architecture/architecture.adoc).

```
vortex-core        domain, application services, ports, deterministic calculators — ZERO compile
                    dependencies (Maven-enforced banned-dependencies rule). No Spring, no Jackson,
                    nothing but the JDK. If you need a library in the domain, it belongs in an
                    adapter instead.
vortex-openapi      OpenAPI import (quarantines swagger-parser)
vortex-k6           workload generation, process execution, k6 output parsing (quarantines
                    ProcessBuilder and k6 wire formats)
vortex-ai           assistant, prompts, response handling (quarantines Spring AI / Ollama)
vortex-persistence  SQLite, Flyway migrations, repositories, artifacts, vortex.yaml (quarantines
                    sqlite-jdbc, Flyway, YAML)
vortex-app          composition root: web (React SPA, served over a JSON API), SSE, small
                    adapters (Docker, Actuator, HTTP probe) — the only module depending on Spring Boot
vortex-demo-service sample service with a deliberate, documented bottleneck for demos/tests
vortex-web          React + TypeScript + Mantine SPA, built by Vite, compiled into
                    vortex-app/src/main/resources/static/app/ and shipped inside the same jar
```

Everything external to the domain sits behind a port in `dev.vortex.core.port` (`PerformanceEngine`,
`PerformanceAssistant`, `ObservabilityProvider`, `ProductionObservationSource`,
`TelemetryCollector`, `ServiceCatalogImporter`, `ConfigurationStore`, `ArtifactStore`, `LocalLab`,
`Clock`, repositories). The web UI is the only supported interface, and it calls application
services directly — there is no separate "headless mode" implementation to keep in sync.

### The two lifecycles (do not conflate them)

Execution (`CREATED → VALIDATING → READY → STARTING → RUNNING → COLLECTING → EVALUATING →
COMPLETED`, with `FAILED`/`CANCELLED` branches) is deterministic and never blocked by AI. Analysis
(`NOT_REQUESTED → PENDING → RUNNING → COMPLETED/FAILED`) is a separate resource that only starts
once a run already has a verdict. Do not add an `ANALYZING` execution state — see
[ADR-015](docs/adr/adr-015-separate-execution-and-analysis-lifecycles.adoc).

### Evidence hierarchy

`RAW EVIDENCE → NORMALIZED MEASUREMENTS → DETERMINISTIC FINDINGS → INTERPRETATION`. Each tier is
reachable from the one above it; reports must keep them visibly distinct so a reader can tell a
measurement from an opinion. See [docs/02-architecture/execution-and-evidence.adoc](docs/02-architecture/execution-and-evidence.adoc).

### Storage

`vortex.yaml` (portable test intent, belongs in the *service's* repo) vs `~/.vortex/vortex.db`
(local index) vs `~/.vortex/executions/<id>/` (immutable per-run evidence). The database is never
the source of truth for what to test, and nothing needed to reproduce a run lives only in it.

### Frontend

The interface is React + Mantine ([ADR-035](docs/adr/adr-035-react-and-mantine-as-the-interface.adoc)),
end to end — the earlier Thymeleaf/htmx server-rendered UI ([ADR-004](docs/adr/adr-004-thymeleaf-and-htmx.adoc),
now superseded) has been fully removed, dependency and all. `SpaController` forwards every route not
owned elsewhere to the SPA's `index.html`; React owns the app shell (top bar, service switcher,
runtime status, command palette) and every page. Every `vortex-app` REST controller returns JSON
only — there is no view-rendering controller left, and no Thymeleaf dependency in `vortex-app/pom.xml`.

## Coding standards

- Modern Java: records for value objects, sealed interfaces where the case set is closed, pattern
  matching in switches, virtual threads for blocking I/O (the engine subprocess, telemetry sampling,
  background analysis — a 15-minute test holds no platform thread).
- Constructor injection only — no field injection, no setters, no Lombok.
- Invariants belong in constructors, not annotations (a `Concurrency` of zero should be impossible,
  not merely invalid). No `null` in collections — use empty collections. `Optional` only on
  accessors that can legitimately be absent, named `xIfPresent()`. UTC for anything stored, local
  time only at render.
- Every user-facing error states what happened, why, and what to do next — `"Process exited 1"` is
  not acceptable.
- Avoid: god services, `Utils` classes, static mutable state, reflection-heavy magic, deep
  inheritance, `DTO → command → service → manager → handler → executor` chains.
- Comments explain *why* (a decision, a constraint, a trap), never *what* — the code already says that.

## Non-negotiable invariants

These are tested; if a change makes one of these tests fail, the test is probably right, not the change:

- An AI finding must never survive without resolvable evidence (`EvidenceIds`) — see the AI
  capability workflow below.
- An unevaluated objective must never be reported as passed.
- A capacity or headroom figure must never be produced detached from the conditions that produced it
  (version, environment, dependency mode, workload model, operation mix, objectives, duration).
- A run must never start against a non-local target without an explicit, typed confirmation of the
  target environment.
- A resolved secret must never reach a plan, artifact, log, or prompt.
- Arrival rate and concurrency must never be conflated, and a workload level must never drop its unit.
- One operation's measurements must never be attributed to another.

## Common extension points

- **Evidence writer**: `EvidenceJsonWriter`/`EvidenceMarkdownWriter` in `dev.vortex.app.evidence`
  write every completed run's evidence to its artifact directory. Add a case to
  `SecretsNeverExportTest`. A writer only ever takes a `RunEvidence` — never reach around
  `EvidenceSanitizer` for the execution or plan (ArchUnit-enforced).
- **AI capability**: prompt as a resource under `vortex-ai/src/main/resources/ai/`, bump
  `PromptLibrary.VERSION` if response shape/substance could change, add a `PerformanceAssistant`
  method, extend `FakePerformanceAssistant` with its failure modes, and if it produces findings they
  must cite `EvidenceIds` or the validator discards them. Never ask a model to compute a number
  Vortex could calculate deterministically.
- **Observability provider**: implement `ObservabilityProvider`; return only what was actually
  measured (absence, not a default). Classify gaps honestly (`NO_DATA`, `UNSUPPORTED`,
  `UNAUTHORIZED`, `UNREACHABLE`, `MALFORMED`) — see [ADR-033](docs/adr/adr-033-derived-evidence-is-labelled.adoc).
- **Production observation source**: implement `ProductionObservationSource` (a distinct port from
  `ObservabilityProvider` by design — [ADR-031](docs/adr/adr-031-production-observation-has-its-own-port.adoc)).
  Speak only in `OperationId`/method/path; put query-language specifics in `ObservationProvenance`;
  report `sampleResolution` and `OperationMixCoverage` honestly rather than overstating coverage.
- **DB migration**: add `V<n>__<description>.sql` under
  `vortex-persistence/src/main/resources/db/migration/`; never edit an applied one; Flyway runs at startup.
- **Validity reason code**: add to `ValidityReason` *only* if a measurement Vortex already collects
  produces it, add its rule to `RunQualityAssessor`, and put the threshold it compares against in
  `ValidityPolicy` rather than in the rule. Every finding must state the number it crossed, cite
  evidence ids that resolve, and — the test that matters — not fire when its measurement is absent.
- **Report section**: belongs in `core.evidence` plus a fragment in `templates/evidence.html` — the
  result page, printable report, and both evidence writers read the same model, so it stays
  consistent everywhere.

## Testing notes

- Assert the property, not the artefact (e.g. "the number of request statements is unchanged", not
  "hostile text is absent from the script" — the latter can pass for the wrong reason).
- Never require a live language model — use `FakePerformanceAssistant`; live Ollama behavior is
  verified by hand (see [CONTRIBUTING.md](CONTRIBUTING.md)).
- Never assert a specific measured value from the demo service (its breakpoint moves with machine
  speed) — assert qualitative behavior instead.
- Web tests are MockMvc slice tests asserting semantic structure, not whole-page snapshots.

## Decisions

Consequential, hard-to-reverse, or "why on earth is it like that?" changes get an ADR under
[docs/adr/](docs/adr/index.adoc) — including what it costs, not just what it gains. Trivial decisions don't.
