# AGENTS.md

Guidance for any coding agent — or human contributor — working in this repository. It applies
regardless of which tool you are: these are the rules of the codebase, not the conventions of one
assistant.

## What Vortex is

A local-first performance engineering workbench: it turns an OpenAPI description and a stated
traffic goal into a k6 workload, runs it, and produces deterministic pass/fail evidence (thresholds,
breakpoints, headroom) with an optional local-AI (Ollama) interpretation layered on top — never the
other way around. Read [docs/02-architecture/interface.adoc](docs/02-architecture/interface.adoc)
for the product model (Service, Operation, Workload, Evaluation, Quality gate, Run, Evidence) before
touching domain code; it explains *why* the domain is shaped the way it is, not just what it does.

k6 is the execution engine, not the product. Vortex owns orchestration, safety, evidence,
interpretation boundaries, execution history, and the developer workflow around it. Deterministic
evidence belongs in core logic; AI may interpret that evidence but must never fabricate or replace a
deterministic measurement.

## Commands

```bash
./mvnw clean verify                        # full build + test suite (Java 25 required)
./mvnw test                                # tests only, all modules
./mvnw -pl modules/core test               # tests for one module
./mvnw -pl modules/core test -Dtest=ClassName#methodName  # a single test
./mvnw -pl modules/app -am package -DskipTests             # build the runnable jar
./mvnw -pl modules/app spring-boot:run     # run Vortex on 127.0.0.1:7717
./mvnw -pl examples/demo-service spring-boot:run            # sample service (has a deliberate bottleneck) on :8080
make help                                  # same targets as convenience wrappers
```

Frontend (`web/`, builds into `modules/app/src/main/resources/static/app/`):

```bash
cd web
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

## Architecture

Modular monolith, one process, one jar. Full diagram and rationale:
[docs/02-architecture/architecture.adoc](docs/02-architecture/architecture.adoc).

Backend modules live under `modules/`, the frontend lives under `web/`, and the demo/sample system
lives under `examples/demo-service/` (paired with its Vortex config at `examples/checkout-service/`).

```
vortex-core        domain, application services, ports, deterministic calculators — ZERO compile
                    dependencies (Maven-enforced banned-dependencies rule). No Spring, no Jackson,
                    nothing but the JDK. If you need a library in the domain, it belongs in an
                    adapter instead.
vortex-openapi      OpenAPI import (quarantines swagger-parser)
vortex-k6           workload generation, process execution, k6 output parsing (quarantines
                    ProcessBuilder and k6 wire formats)
vortex-ai           assistant, prompts, response handling (quarantines Spring AI / Ollama)
vortex-dynatrace    production-observation and telemetry integration via Dynatrace MCP (quarantines
                    the MCP client and Dynatrace's wire formats)
vortex-persistence  SQLite, Flyway migrations, repositories, artifacts, vortex.yaml (quarantines
                    sqlite-jdbc, Flyway, YAML)
vortex-app          composition root: web (React SPA, served over a JSON API), SSE, small
                    adapters (Docker, Actuator, HTTP probe) — the only module depending on Spring Boot
vortex-demo-service sample service with a deliberate, documented bottleneck for demos/tests
vortex-web          React + TypeScript + Mantine SPA, built by Vite, compiled into
                    modules/app/src/main/resources/static/app/ and shipped inside the same jar
```

Everything external to the domain sits behind a port in `com.acltabontabon.vortex.core.port`
(`PerformanceEngine`, `PerformanceAssistant`, `ObservabilityProvider`, `ProductionObservationSource`,
`TelemetryCollector`, `ServiceCatalogImporter`, `ConfigurationStore`, `ArtifactStore`, `DatasetStore`,
`TargetExecutor`, `LoadGeneratorBudgetProvider`, `HostInformation`, `ProjectDetector`, `LocalLab`,
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
measurement from an opinion. See
[docs/02-architecture/execution-and-evidence.adoc](docs/02-architecture/execution-and-evidence.adoc).

### The AI boundary

```
Measurements → deterministic evaluation → evidence → optional AI interpretation
```

never

```
Measurements → a language model → truth
```

An AI finding must always cite `EvidenceIds` that resolve against measurements Vortex actually
collected; one that cannot is discarded before a user sees it. AI is never the pass/fail authority —
thresholds, breakpoints, headroom and regression deltas are computed by ordinary code, and a model
only interprets figures Vortex has already calculated.

### Storage

`vortex.yaml` (portable test intent, belongs in the *service's* repo) vs `~/.vortex/vortex.db`
(local index) vs `~/.vortex/executions/<id>/` (immutable per-run evidence). The database is never
the source of truth for what to test, and nothing needed to reproduce a run lives only in it.

## Engineering principles

- **Local-first.** No server-side deployment; the workbench runs on the developer's own machine
  against services they control.
- **Evidence over vibes.** Every claim traces back to a measurement, with the conditions that
  produced it attached.
- **Deterministic core, adapters around external systems.** Thresholds, breakpoints and headroom are
  ordinary code, not model output; anything that talks to k6, Ollama, SQLite, Docker or an
  observability provider sits behind a port.
- **Safe by default.** A run must never start against a non-local target without an explicit, typed
  confirmation of the target environment, and mutation operations are never silently selected.
- **No shell command construction from untrusted input.** Workload generation and process
  invocation never interpolate unsanitised strings into a command line.
- **Secrets are referenced, never persisted.** A resolved secret must never reach a plan, artifact,
  log, or prompt — only its source reference does.
- **Observability degradation is surfaced, not swallowed.** A gap is reported as `NO_DATA`,
  `UNSUPPORTED`, `UNAUTHORIZED`, `UNREACHABLE` or `MALFORMED`, never silently treated as zero or
  omitted.
- **Configuration is portable.** What to test lives in the service's own repository, not locked
  inside Vortex's local database.
- **Failures are diagnosable.** Every user-facing error states what happened, why, and what to do
  next.
- **Tests validate behavior, not implementation trivia.** Assert the property that matters, not the
  shape of the artefact that happens to demonstrate it today.
- **Boring, maintainable code over cleverness.** No speculative abstractions, no god services, no
  `Utils` classes, no reflection-heavy magic, no `DTO → command → service → manager → handler →
  executor` chains.

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
- Comments explain *why* (a decision, a constraint, a trap), never *what* — the code already says
  that.

## Non-negotiable invariants

These are tested; if a change makes one of these tests fail, the test is probably right, not the
change:

- An AI finding must never survive without resolvable evidence (`EvidenceIds`).
- An unevaluated objective must never be reported as passed.
- A capacity or headroom figure must never be produced detached from the conditions that produced it
  (version, environment, dependency mode, workload model, operation mix, objectives, duration).
- A run must never start against a non-local target without an explicit, typed confirmation of the
  target environment.
- A resolved secret must never reach a plan, artifact, log, or prompt.
- Arrival rate and concurrency must never be conflated, and a workload level must never drop its unit.
- One operation's measurements must never be attributed to another.

## Rules for making changes

- Understand the module you're editing before you edit it — read
  [docs/02-architecture/architecture.adoc](docs/02-architecture/architecture.adoc) and the module
  boundary table above.
- Preserve public API compatibility unless the change is intentionally about breaking it.
- Update tests alongside behavior, and documentation alongside behavior, configuration or
  architecture changes — a change that alters what a user sees or configures is not done until the
  relevant doc under `docs/` (or the README) reflects it.
- Avoid drive-by refactoring unrelated to the task at hand.
- Avoid adding a dependency without justification — `vortex-core` in particular has zero compile
  dependencies, enforced by Maven; that is a deliberate constraint, not an oversight.
- Follow existing conventions rather than introducing a new pattern for something the codebase
  already does one way.
- Preserve the security boundaries above — do not weaken target-safety confirmation, secret
  handling, or the AI/deterministic-evidence boundary to make a feature easier to build.
- Run the appropriate verification (build, tests, lint, docs build) before declaring work complete.

## Things agents must not do

- Do not make AI responsible for a deterministic pass/fail decision.
- Do not weaken the non-local-target confirmation or any other safety control to simplify a change.
- Do not log a resolved secret, or let one reach an artifact, evidence file, or prompt.
- Do not introduce a hidden network call — anything that talks to an external system belongs behind
  an explicit port with a documented, user-visible reason.
- Do not assume an external target is safe to run traffic against; the target-confirmation flow
  exists because that assumption has real consequences.
- Do not introduce vendor lock-in without justification, or add a dependency `vortex-core` would
  have to carry.
- Do not silently swallow a telemetry or observability failure — classify and surface the gap.
- Do not commit local state or generated artifacts (build output, `.vortex/` workspace data, local
  databases) unless the repository already tracks that exact kind of file deliberately.
- Do not add tool-specific assumptions (about Claude Code, Cursor, Copilot, or any other single
  assistant) to shared project documentation — this file included.

## Common extension points

- **Evidence writer**: `EvidenceJsonWriter`/`EvidenceMarkdownWriter` in
  `com.acltabontabon.vortex.app.evidence` write every completed run's evidence to its artifact
  directory. Add a case to `SecretsNeverExportTest`. A writer only ever takes a `RunEvidence` —
  never reach around `EvidenceSanitizer` for the execution or plan (ArchUnit-enforced).
- **AI capability**: prompt as a resource under `modules/ai/src/main/resources/ai/`, bump
  `PromptLibrary.VERSION` if response shape/substance could change, add a `PerformanceAssistant`
  method, extend `FakePerformanceAssistant` with its failure modes, and if it produces findings they
  must cite `EvidenceIds` or the validator discards them. Never ask a model to compute a number
  Vortex could calculate deterministically.
- **Observability provider**: implement `ObservabilityProvider`; return only what was actually
  measured (absence, not a default). Classify gaps honestly (`NO_DATA`, `UNSUPPORTED`,
  `UNAUTHORIZED`, `UNREACHABLE`, `MALFORMED`) — see
  [ADR-033](docs/adr/adr-033-derived-evidence-is-labelled.adoc).
- **Production observation source**: implement `ProductionObservationSource` (a distinct port from
  `ObservabilityProvider` by design — see
  [ADR-031](docs/adr/adr-031-production-observation-has-its-own-port.adoc)). Speak only in
  `OperationId`/method/path; put query-language specifics in `ObservationProvenance`; report
  `sampleResolution` and `OperationMixCoverage` honestly rather than overstating coverage.
- **DB migration**: add `V<n>__<description>.sql` under
  `modules/persistence/src/main/resources/db/migration/`; never edit an applied one; Flyway runs at
  startup.
- **Validity reason code**: add to `ValidityReason` *only* if a measurement Vortex already collects
  produces it, add its rule to `RunQualityAssessor`, and put the threshold it compares against in
  `ValidityPolicy` rather than in the rule. Every finding must state the number it crossed, cite
  evidence ids that resolve, and — the test that matters — not fire when its measurement is absent.
- **Report section**: belongs in `core.evidence` plus a fragment in `templates/evidence.html` — the
  result page, printable report, and both evidence writers read the same model, so it stays
  consistent everywhere.
- **Project detector**: implement `ProjectDetector`, returning `Finding`s with evidence and a
  `Confidence` — never persist anything (ArchUnit-enforced) and never throw for "not found," only
  for "could not finish," which `ProjectDiscoveryService` turns into a partial-failure message
  rather than failing the whole scan. A detector needing only the JDK belongs in
  `core.discovery.detectors`; one needing a library (YAML, for instance) is an adapter in
  `app.discovery` instead — see
  [ADR-063](docs/adr/adr-063-project-discovery-is-synchronous-and-stateless.adoc).

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
[docs/adr/](docs/adr/index.adoc) — including what it costs, not just what it gains. Trivial decisions
don't. ADRs are historical records: when a decision is superseded, link forward to what replaced it
rather than rewriting what was actually decided at the time.
