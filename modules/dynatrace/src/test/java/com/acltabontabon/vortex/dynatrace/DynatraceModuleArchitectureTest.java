package com.acltabontabon.vortex.dynatrace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Guards the one security property this module exists to uphold: it never executes anything a
 * pasted MCP config, or any other input, describes.
 *
 * <p>{@link DynatraceMcpConfigImport} parses a provided MCP config and extracts a remote URL from
 * it — never a command. This rule makes that a build-time guarantee rather than a convention
 * someone could accidentally erode with a future "helper" that shells out.
 */
@AnalyzeClasses(
        packages = "com.acltabontabon.vortex.dynatrace",
        importOptions = ImportOption.DoNotIncludeTests.class)
class DynatraceModuleArchitectureTest {

    @ArchTest
    static final ArchRule this_module_never_spawns_a_process =
            noClasses().should().dependOnClassesThat().belongToAnyOf(ProcessBuilder.class, Runtime.class)
                    .because("""
                            Vortex connects to the Dynatrace MCP endpoint directly over HTTPS and never \
                            runs the npx/mcp-remote command a provided config may describe. Spawning a \
                            local process from pasted, externally-supplied configuration is exactly the \
                            arbitrary-command-execution risk this module is designed to avoid — see \
                            docs/adr/adr-049-dynatrace-mcp-is-a-parallel-transport.adoc.""");
}
