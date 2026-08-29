# Vortex

[![CI](https://github.com/acltabontabon/vortex/actions/workflows/ci.yml/badge.svg)](https://github.com/acltabontabon/vortex/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**A local-first performance engineering workbench for figuring out what your service can actually
handle.**

Point Vortex at your service's OpenAPI description, say what traffic you're worried about, and it
turns that into a real k6 workload, runs it on your machine, and hands back evidence — not a wall of
terminal output. Know your numbers before you pay to prove them.

[Getting started](docs/01-product/getting-started.adoc) ·
[Documentation](docs/index.adoc) ·
[Roadmap](docs/01-product/roadmap.adoc)

![Vortex service workspace showing configured workload profiles, seven tests, and recent runs for the demo service](site/images/docs/docs-workspace-light.webp)

---

## Why Vortex exists

Running a load test isn't the hard part. [k6](https://k6.io) already does that extremely well.

The hard part is everything around it: how much traffic your service actually receives, what
"normal" and "peak" look like, what operation mix represents production, whether 50 requests/sec or
5,000 is the number worth trying, what kind of test even answers your question, what thresholds
matter — and, once it's run, whether the *service* failed or your *load generator* did.

Answering that usually means jumping between an observability dashboard, an API spec, a k6 script, a
spreadsheet of guesses, last week's terminal output, and your own memory of how any of it fit
together. The honest answer that survives that process is often "probably enough."

That's not evidence. It's a shrug with a chart attached.

```text
Production traffic
      ↓
Representative workload
      ↓
Performance question
      ↓
Test
      ↓
Evidence
      ↓
Decision
```

Vortex exists to make that chain coherent — not by replacing k6, but by owning the engineering
workflow k6 was never meant to own. *Adopt the ecosystem. Own the engineering workflow.*

---

## What Vortex helps you do

**Start from your actual API.** Import an OpenAPI document and Vortex parses it deterministically —
paths, methods, parameters, schemas — instead of you hand-rolling test boilerplate.

**Model traffic that looks like production.** Weighted operation mixes, arrival rate and concurrency
modelled as the different things they are, and — where you have Prometheus or Dynatrace — workloads
calibrated from what your service actually receives instead of a number someone guessed.

**Run it locally, safely.** Real k6, real execution, with a preflight that shows the exact target,
environment class, and traffic split before anything is sent, and a typed confirmation before a
non-local target sees any traffic at all.

**Get a verdict, not a wall of metrics.** Deterministic pass/fail against your latency and error
objectives, the point where objectives stopped holding, which resource ran out first, and whether the
run itself was even valid.

**Keep evidence, not memory.** Execution history, capacity and headroom over time, and comparisons
that only run when two experiments actually tested the same thing.

**Ask a local model to interpret it — never to decide it.** An optional [Ollama](https://ollama.com)-backed
assistant explains findings Vortex already computed. It cannot invent a workload or change a number.

---

## Local first. Production informed.

Vortex runs on your machine, against your service, before anyone provisions a performance
environment. That's the point, not a limitation you tolerate until "real" testing starts.

```text
Guess
  ↓
Local evidence
  ↓
Refine
  ↓
Volume / performance environment
  ↓
Validate
```

A laptop is not production. Simulated dependencies are not your real ones. Vortex will never tell
you a run against Docker containers on your machine proves your service handles Black Friday — every
capacity figure carries the conditions it was measured under, so nobody downstream mistakes a local
number for a production one.

What it will do is replace guessing with evidence *before* you spend money proving it: whether the
service behaves correctly under load, roughly where it starts struggling, what's likely to run out
first, and whether this build regressed against the last one. That's a far better input to an
expensive environment test than a number picked because it sounded round.

**Vortex reduces guessing before expensive testing. It does not eliminate the need for it.**

---

## What Vortex is not

**Not another load generator.** k6 generates the traffic. Vortex does not reimplement virtual users,
arrival-rate scheduling or HTTP load generation, and it never will.

**Not an APM.** Prometheus, Dynatrace and similar systems remain the observability platforms. Vortex
consumes their telemetry as evidence; it doesn't replace them.

**Not a production-capacity oracle.** A run gives you evidence under known, stated conditions. When
the question genuinely needs a production-like or volume-testing environment, that's still where you
go.

See the full [scope and non-goals](docs/01-product/overview.adoc#scope-and-non-goals) for everything
else Vortex deliberately isn't.

---

## What a Vortex run gives you

![Vortex run result showing a pass verdict, evidence quality, and per-objective latency, error rate, and resource figures for a spike test against the demo service](site/images/docs/docs-run-result-light.webp)

A completed run answers, in one place:

* Did the objectives hold?
* What throughput or concurrency was actually achieved?
* Where did objectives stop holding?
* Which resource approached its limit first?
* Was the run itself valid — or did the load generator saturate before the service did?
* How much headroom does this establish?
* Is this comparable to a previous run?

The output is evidence, not terminal confetti. See
[interpreting a Vortex result](docs/03-performance-model/interpreting-results.adoc) for what every
number on that page means.

Every one of those claims stays honest by construction: pass/fail, breakpoints, headroom and
regressions are computed by deterministic code, never by a language model, and a capacity figure is
never reported detached from the conditions that produced it. That discipline is documented in depth
in [Experiments](docs/03-performance-model/experiments.adoc).

---

## Documentation

| | |
|---|---|
| [Getting started](docs/01-product/getting-started.adoc) | Build it, run it, a five-minute tour of the workflow |
| [Product overview](docs/01-product/overview.adoc) | Why Vortex exists, its principles, scope and vocabulary |
| [Understanding results](docs/03-performance-model/interpreting-results.adoc) | Every number on a result page, explained |
| [Configuration reference](docs/04-reference/configuration.adoc) | Every key in `vortex.yaml` |
| [Architecture](docs/02-architecture/architecture.adoc) | How it's built |
| [Roadmap](docs/01-product/roadmap.adoc) | Where it's going |
| [Contributing](CONTRIBUTING.md) | Building, testing, extending |

The full documentation set — including architecture decision records — lives under
[docs/](docs/index.adoc).

---

## Status

**Early alpha.** The core local workflow — import, model, run, evaluate, compare — works end to end
against a real service with real k6, including production-informed calibration against Prometheus
and Dynatrace. Configuration, APIs and UX are still expected to change while the model gets hardened;
this is not yet stable production software.

See [the roadmap](docs/01-product/roadmap.adoc) for what exists, what's next, and what's deliberately
not built yet.

---

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test, and
extend Vortex.

## Acknowledgements

Vortex generates traffic with [k6](https://k6.io) — the execution engine this entire product is
built around — and, optionally, interprets results with a local model through
[Ollama](https://ollama.com). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the full
dependency and licence list.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
