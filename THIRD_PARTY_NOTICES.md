# Third-party notices

Vortex depends on open-source software, and cooperates with external tools it does not embed. The
distinction matters legally and is stated explicitly below.

---

## External tools — invoked, not embedded

These are **separate programs** that Vortex runs as subprocesses or calls over HTTP. Their code is
not included in, linked into, or distributed with Vortex, and users install them themselves.

| Tool | Licence | How Vortex uses it |
|---|---|---|
| **[k6](https://github.com/grafana/k6)** | AGPL-3.0 | Executed as a subprocess. Vortex generates a JavaScript file and runs `k6 run`. No k6 code is linked into Vortex. |
| **[Ollama](https://github.com/ollama/ollama)** | MIT | Called over HTTP at a user-configured endpoint. |
| **Docker / Docker Compose** | Apache-2.0 | Optional. Invoked as a subprocess. |

### On k6 and the AGPL

k6 is licensed under the AGPL-3.0. Vortex **executes** it as a separate process, communicating
through the filesystem and command-line arguments. It does not incorporate, link against, or
redistribute k6 source or binaries.

Running a program is not the same as creating a derivative work of it, and the AGPL's obligations
attach to distributing modified versions or conveying the program itself — neither of which Vortex
does. Users install k6 themselves, under its own licence, and interact with it through its own
documented command-line interface.

The optional Docker runner pulls `grafana/k6` from a public registry at the user's request; Vortex
does not redistribute the image.

If you intend to distribute Vortex commercially, take your own legal advice rather than relying on
this paragraph.

---

## Compile and runtime dependencies

All are permissively licensed, except OpenPDF — see the licence note below.

| Dependency | Licence | Used by |
|---|---|---|
| [Spring Boot](https://github.com/spring-projects/spring-boot) 4.1 | Apache-2.0 | app, persistence, ai, demo |
| [Spring Framework](https://github.com/spring-projects/spring-framework) | Apache-2.0 | (transitive) |
| [Spring AI](https://github.com/spring-projects/spring-ai) 2.0 | Apache-2.0 | `vortex-ai` |
| [Thymeleaf](https://github.com/thymeleaf/thymeleaf) | Apache-2.0 | `vortex-app` |
| [htmx](https://github.com/bigskysoftware/htmx) | BSD-2-Clause | `vortex-app` (webjar) |
| [Jackson](https://github.com/FasterXML/jackson) | Apache-2.0 | k6, persistence, ai, app |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) | Apache-2.0 | (via Jackson YAML) |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | Apache-2.0 | `vortex-persistence` |
| [SQLite](https://www.sqlite.org/) | Public domain | (bundled in sqlite-jdbc) |
| [Flyway](https://github.com/flyway/flyway) | Apache-2.0 | `vortex-persistence` |
| [HikariCP](https://github.com/brettwooldridge/HikariCP) | Apache-2.0 | `vortex-persistence` |
| [swagger-parser](https://github.com/swagger-api/swagger-parser) | Apache-2.0 | `vortex-openapi` |
| [swagger-core](https://github.com/swagger-api/swagger-core) | Apache-2.0 | (transitive) |
| [SLF4J](https://github.com/qos-ch/slf4j) | MIT | all modules |
| [Logback](https://github.com/qos-ch/logback) | EPL-1.0 / LGPL-2.1 | (runtime, via Boot) |
| [Micrometer](https://github.com/micrometer-metrics/micrometer) | Apache-2.0 | app, demo |
| [Tomcat](https://github.com/apache/tomcat) | Apache-2.0 | (embedded, via Boot) |
| [picocli](https://github.com/remkop/picocli) | Apache-2.0 | `vortex-app` |
| [OpenPDF](https://github.com/LibrePDF/OpenPDF) 2.2 | LGPL-2.1-or-later OR MPL-2.0 | `vortex-report` |

## Test-scope dependencies

| Dependency | Licence |
|---|---|
| [JUnit 5](https://github.com/junit-team/junit5) | EPL-2.0 |
| [AssertJ](https://github.com/assertj/assertj) | Apache-2.0 |
| [ArchUnit](https://github.com/TNG/ArchUnit) | Apache-2.0 |
| [Mockito](https://github.com/mockito/mockito) | MIT |

## Build tooling

| Tool | Licence |
|---|---|
| [Apache Maven](https://maven.apache.org/) | Apache-2.0 |
| [GraalVM Native Build Tools](https://github.com/graalvm/native-build-tools) | UPL-1.0 |

## No GPL or AGPL code is embedded

Vortex's own source and its compiled artifacts contain no GPL- or AGPL-licensed code. Every embedded
dependency above is either permissively licensed — Apache-2.0, MIT, BSD, UPL or public
domain — or dual-licensed under a weak copyleft licence and used unmodified as a library, which
those licences permit without conditions on the surrounding work.

Two dependencies fall in the second category.

Logback is dual-licensed EPL-1.0 / LGPL-2.1; it is used unmodified as a runtime dependency, which the
EPL permits without restriction on the surrounding work.

OpenPDF is dual-licensed LGPL-2.1-or-later / MPL-2.0. Vortex links against an unmodified release and
redistributes it unchanged, which both licences permit without conditions on Vortex itself. Modifying
OpenPDF, or vendoring parts of it, would change that analysis — see
`docs/adr/adr-029-openpdf-quarantined-in-a-report-module.adoc`. Its own four runtime dependencies
(ICU4J, BouncyCastle, Apache FOP) are all optional and none is pulled in.

## Generating an up-to-date list

```bash
./mvnw license:aggregate-third-party-report
```

## Vortex's own licence

Not yet chosen. This is a pre-1.0 internal project; a licence will be selected before any external
distribution.
