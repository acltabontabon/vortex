package com.acltabontabon.vortex.dynatrace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;

/**
 * Guards the security property this module exists to uphold: nothing here spawns a process from a
 * pasted MCP config, or any other input, except the one class the user explicitly opted into.
 *
 * <p>{@link DynatraceMcpConfigImport} parses a provided MCP config and extracts a remote URL from
 * it — never a command. {@link DynatraceMcpBridgeTelemetryClient} is the sole, deliberate exception:
 * when a user explicitly selects the local npx/mcp-remote bridge connection mode under Settings, it
 * is the one class permitted to launch {@code npx mcp-remote <url>} and speak MCP over its stdio —
 * see docs/adr/adr-051-dynatrace-mcp-local-npx-bridge.adoc. Every other class in this module —
 * config import, the direct-HTTP client, settings, the connection test's own orchestration — must
 * stay provably unable to spawn anything. {@link StdioClientTransport}/{@link ServerParameters} are
 * named explicitly (not just {@link ProcessBuilder}/{@link Runtime}) because the SDK builds its
 * {@code ProcessBuilder} inside its own class, invisible to a dependency check against Vortex's
 * classes otherwise — a class could reference only the SDK's transport and still evade the intent
 * of this rule.
 */
@AnalyzeClasses(
        packages = "com.acltabontabon.vortex.dynatrace",
        importOptions = ImportOption.DoNotIncludeTests.class)
class DynatraceModuleArchitectureTest {

    @ArchTest
    static final ArchRule only_the_named_local_bridge_may_spawn_a_process =
            noClasses()
                    .that(DescribedPredicate.not(JavaClass.Predicates.simpleName("DynatraceMcpBridgeTelemetryClient")))
                    .should().dependOnClassesThat().belongToAnyOf(
                            ProcessBuilder.class, Runtime.class, StdioClientTransport.class, ServerParameters.class)
                    .because("""
                            Vortex normally connects to the Dynatrace MCP endpoint directly over HTTPS and \
                            never spawns a process. DynatraceMcpBridgeTelemetryClient is a deliberate, \
                            narrow exception: when the user explicitly selects the local npx/mcp-remote \
                            bridge connection mode, it is the one class permitted to launch \
                            `npx mcp-remote <url>` and speak MCP over its stdio — see \
                            docs/adr/adr-051-dynatrace-mcp-local-npx-bridge.adoc. Every other class in \
                            this module must stay unable to spawn anything.""");
}
