# Contributing to Vortex

## Submitting a change

1. Fork the repository and create a branch off `main`.
2. Make your change, following the standards below.
3. Run the checks that apply — `./mvnw clean verify`, `npm run lint && npm test` in `web/` if you
   touched the frontend, `make docs` if you touched `docs/`.
4. Open a pull request. The PR template's checklist mirrors the checks above; fill in what applies
   and remove what doesn't.
5. For a bug or a feature idea, an issue is welcome but not required before a PR — use the templates
   under `.github/ISSUE_TEMPLATE/` if you'd rather discuss first.

## Getting a build

```bash
git clone <this-repository> && cd vortex
./mvnw clean verify
```

You need **JDK 25 or newer**. For native compilation, `JAVA_HOME` must point at GraalVM 25.

k6, Docker and Ollama are all optional for the build — tests that need them skip themselves.

```bash
make help          # everything below, as targets
```

## Running it

```bash
./mvnw -pl examples/demo-service spring-boot:run  # sample service on :8080
./mvnw -pl modules/app spring-boot:run            # Vortex on 127.0.0.1:7717
```

Or as a jar:

```bash
./mvnw -pl modules/app -am package -DskipTests
java -jar modules/app/target/vortex.jar
```

## Repository layout

```
modules/   Backend Vortex modules
web/       Vortex Workbench frontend
examples/  Sample systems and Vortex configurations
docs/      Architecture and product documentation
scripts/   Development and repository tooling
```

## Architecture in one page

Read [docs/02-architecture/architecture.adoc](docs/02-architecture/architecture.adoc) before
making structural changes. The two things most likely to surprise you:

**`vortex-core` has zero compile dependencies.** No Spring, no Jackson, nothing but the JDK. The
build enforces it. If you need a library in the domain, you almost certainly want it in an adapter
instead — and if you genuinely do not, that needs an ADR.

**Execution and analysis are separate lifecycles.** A run completes and has a verdict before any
model is consulted. Do not add an `ANALYZING` state; see
[ADR-015](docs/adr/adr-015-separate-execution-and-analysis-lifecycles.adoc).

## Coding standards

- **Modern Java.** Records for value objects, sealed interfaces where the case set is closed,
  pattern matching in switches, virtual threads for blocking I/O.
- **Constructor injection.** No field injection, no setters.
- **No Lombok.** Records covered the cases that mattered.
- **Invariants in constructors**, not annotations. A `Concurrency` of zero should be
  impossible, not merely invalid.
- **`Optional` on accessors that can legitimately be absent**, named `xIfPresent()`.
- **No `null` in collections.** Empty collections instead.
- **UTC for anything stored.** Local time only at render.
- **Errors carry remedies.** Every user-facing failure answers: what happened, why it might have
  happened, what to do next. `"Process exited 1"` is not an error message.

Avoid: god services, `Utils` classes, static mutable state, reflection-heavy magic, deep
inheritance, abstract-factory ceremony, and
`DTO → command → service → manager → handler → executor` chains.

## Comments

Explain **why**, not what. The code says what it does.

Comments earn their place when they capture a decision, a constraint, or a trap:

```java
// See the class comment: `passes` counts failures for this Rate metric.
long httpFailures = ...

// Resolved through the mapping the plan recorded, never by re-sanitising the tag: workload keys
// are lossy renderings of operation ids, and matching by similarity would attribute one
// operation's latency to another in exactly the case the suffix exists to prevent.
```

## Testing

```bash
./mvnw test                       # everything, in a few seconds
./mvnw -pl modules/core test      # one module
```

### Layers

| Layer | What it covers |
|---|---|
| Unit | Calculators, state machines, allocation, safety, masking, fingerprinting, parsing |
| Architecture | ArchUnit: dependency directions, the core's framework-free constraint |
| Persistence | Real SQLite temp files. SQLite is embedded — mocking it would only prove the mock behaves |
| Contract | k6 output fixtures: success, threshold violation, process failure, truncation, invalid output |
| Engine integration | Real `k6 inspect` against generated workloads. Auto-skipped when k6 is absent |
| Web | MockMvc slice tests asserting semantic structure, not whole-page snapshots |

### What to test

**Failure paths first.** A tool that produces a confident bottleneck diagnosis from telemetry it
never collected is worse than one with no AI at all, because the diagnosis looks like evidence.

**Assert the property, not the artefact.** An early injection test asserted that hostile text was
*absent* from a generated script. It passed for the wrong reason: the text legitimately appears
inside a quoted string literal, where it is inert. The test now asserts that the number of request
statements is unchanged — which is the property that actually matters.

**Do not require a language model.** Real inference is non-deterministic; a suite that depended on it
would fail for reasons unrelated to the code and would teach everyone to ignore it. Use
`FakePerformanceAssistant`.

**Do not require a specific measured value.** The demo service's breakpoint moves with machine speed,
JIT and background load. Assert the qualitative behaviour — baseline stable, stress eventually
violating the objective, acquisition wait rising — and record what was measured.

### Tests that depend on external tools

There are no opt-in Maven profiles. Anything that needs an external tool detects it and skips
itself, so `./mvnw clean verify` is green on a machine that has neither k6 nor Ollama.

`K6EngineIntegrationTest` runs `k6 inspect` — which parses a script without generating any traffic
— and is annotated `@EnabledIf("k6IsInstalled")`. When k6 is absent those tests report as skipped
rather than passing, so a green build never overstates what was checked.

There is deliberately **no automated test against a live Ollama**. Real inference is
non-deterministic, and a suite that depended on it would fail for reasons unrelated to the code.
Degradation and validation behaviour is covered by `FakePerformanceAssistant`; live behaviour is
verified by hand:

```bash
ollama serve
./mvnw -pl modules/app spring-boot:run
```

Open the app — the top bar's readiness status should report a model. Then request an analysis from
the Result page, stop Ollama, and request another — the verdict and every measured number must be
identical either way.

## Common tasks

### Adding a database migration

Add `V<n>__<description>.sql` to `modules/persistence/src/main/resources/db/migration/`. Never edit an
applied migration. Flyway runs them at startup.

### Adding an AI capability

1. Write the prompt as a resource in `modules/ai/src/main/resources/ai/`
2. Bump `PromptLibrary.VERSION` if the change could alter response shape or substance
3. Add a method to `PerformanceAssistant`
4. Extend `FakePerformanceAssistant` with the failure modes it introduces
5. If it produces findings, they must cite `EvidenceIds` — the validator will discard them otherwise

The rule that constrains everything here: **the model interprets figures Vortex has already
calculated.** If you find yourself asking a model for a number, stop.

### Adding an observability provider

Implement `ObservabilityProvider`. Return only what you actually measured — a metric the service does
not publish must be absent, not defaulted, because absence flows through to the analysis as missing
telemetry and that is the honest outcome.

Two rules beyond that, both from [ADR-033](docs/adr/adr-033-derived-evidence-is-labelled.adoc):

- **Say why something is missing.** `collect` returns observations *and* gaps. Classify honestly:
  `NO_DATA` when the query matched nothing, `UNSUPPORTED` when the provider does not publish it,
  `UNAUTHORIZED` when the credential was refused, `UNREACHABLE`, `MALFORMED`. "Unavailable" for all
  five wastes all five afternoons.
- **Enrichment must never cost a measurement.** If your provider can also mark a run in its own
  timeline, attempt it and let it fail quietly — reading metrics and writing events are usually
  different permissions. Degrade to `QUERY_ONLY`, record a gap, and keep every measurement you got.

### Adding a production observation source

Implement `ProductionObservationSource` — a different port from `ObservabilityProvider`, and
deliberately so: that one watches the service under test while a run is in progress, this one asks
what production did over the last month. See
[ADR-031](docs/adr/adr-031-production-observation-has-its-own-port.adoc).

Three rules the existing adapters follow:

- **Your query language stops at your adapter.** You are handed operations as `OperationId`, method
  and path template; hand back a mix keyed by the same ids. A PromQL expression has no business on a
  `Workload` — put it in `ObservationProvenance`, where a reader can check the figure.
- **Say what a statistic is a statistic of.** A peak from one-minute samples and a peak from hourly
  samples are different claims about the same traffic, so return the `sampleResolution` you used and
  take it from `ObservationResolution` rather than choosing your own.
- **Never overstate coverage.** Drop a series you cannot attribute — never invent an operation for
  it — but count it, and return an `OperationMixCoverage`. Narrowing the evidence is acceptable;
  making a partial view look like the whole service is not. If you genuinely cannot establish a
  total, leave coverage absent rather than assuming it complete.

Fail with `NotRetrieved`, not an exception: an unreachable endpoint, a rejected token and an unknown
service are ordinary outcomes of asking, and each has a different remedy.

### Changing what evidence is written

`EvidenceJsonWriter` and `EvidenceMarkdownWriter` (`com.acltabontabon.vortex.app.evidence`) write every completed
run's evidence into its artifact directory. Both take a `RunEvidence` and nothing else — reaching
for the execution or the plan reaches around `EvidenceSanitizer`, which is the only gate between a
stored configuration and a written document, and an ArchUnit rule in `ApplicationArchitectureTest`
forbids it. Add a case to `SecretsNeverExportTest` for anything new: every writer is checked there,
because a writer added later that forgot to sanitise would be exactly the one nobody thought to
check.

See [ADR-028](docs/adr/adr-028-run-evidence-is-a-first-class-model.adoc).

### Adding a report section

The section belongs in `core.evidence` and in a fragment in `templates/evidence.html`. The result
page and the printable report both compose those fragments, and both evidence writers read the same
model, so a section added in one place appears everywhere and cannot disagree with itself.

### Building a native image

```bash
JAVA_HOME=/path/to/graalvm-25 ./mvnw -Pnative -pl modules/app native:compile
```

**Nobody has run this yet.** If you do, record the outcome in
[docs/02-architecture/architecture.adoc](docs/02-architecture/architecture.adoc#native-image) — the failures are more useful
than the successes.

## Making changes that affect the product's claims

Some behaviour is load-bearing for Vortex's credibility. Changing it needs a matching change to the
documentation, and probably an ADR:

- Anything that could let an AI finding survive without resolvable evidence
- Anything that could report an unevaluated objective as passed
- Anything that could produce a capacity or headroom figure without its conditions
- Anything that could let a run start against a non-local target without explicit confirmation
- Anything that could put a resolved secret into a plan, artifact, log or prompt
- Anything that could conflate an arrival rate with a concurrency, or drop a level's unit
- Anything that could attribute one operation's measurements to another

Each of these has tests. If your change makes one fail, the test is probably right.

## Decisions

Consequential decisions get an [ADR](docs/adr/index.adoc). Trivial ones do not.

Write one when the decision would be expensive to reverse, or when someone would reasonably ask "why
on earth is it like that?". Record what it costs, not only what it gains — an ADR with no
consequences section is a sales pitch.

## Documentation

Documentation lives under `docs/` as AsciiDoc, starting from
[docs/index.adoc](docs/index.adoc). Render it to check it before submitting a change:

```bash
make docs                    # or: ./scripts/docs-build.sh
```

This requires [Asciidoctor](https://asciidoctor.org) (`gem install asciidoctor`) and writes HTML to
`build/docs/`, which is not committed. The script fails on any Asciidoctor warning, so a broken
cross-reference or malformed table is caught here rather than by a reader.
