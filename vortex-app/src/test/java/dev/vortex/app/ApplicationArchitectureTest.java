package dev.vortex.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Dependency rules that Maven cannot enforce.
 *
 * <p>Most of Vortex's structure is guaranteed by the build: {@code vortex-core} has no compile
 * dependencies at all, and adapters cannot see each other because their POMs do not declare each
 * other. What is left are the boundaries *inside* {@code vortex-app}, which is a single Maven module
 * and therefore has to be held together by tests.
 */
@AnalyzeClasses(
        packages = "dev.vortex",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ApplicationArchitectureTest {

    @ArchTest
    static final ArchRule the_domain_never_reaches_into_adapters =
            noClasses().that().resideInAPackage("dev.vortex.core..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "dev.vortex.app..", "dev.vortex.k6..", "dev.vortex.ai..",
                            "dev.vortex.persistence..", "dev.vortex.openapi..")
                    .because("""
                            Dependencies point inward. The domain defines ports; adapters implement \
                            them. A single reference the other way would make the domain \
                            untestable without the whole application.""");

    @ArchTest
    static final ArchRule swagger_types_never_escape_the_openapi_adapter =
            noClasses().that().resideOutsideOfPackage("dev.vortex.openapi..")
                    .should().dependOnClassesThat().resideInAnyPackage("io.swagger..")
                    .because("""
                            swagger-parser is the main native-image risk and the most likely thing \
                            to be replaced. Keeping its model types inside one adapter means \
                            replacing it changes one module. See \
                            docs/adr/adr-020-native-image-is-a-quality-attribute.adoc""");

    @ArchTest
    static final ArchRule adapters_do_not_depend_on_each_other =
            noClasses().that().resideInAPackage("dev.vortex.k6..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "dev.vortex.ai..", "dev.vortex.persistence..", "dev.vortex.openapi..")
                    .because("Adapters share the domain, never each other.");

    /**
     * Aimed at the JDBC implementations specifically, not at everything in the persistence module.
     *
     * <p>{@code VortexWorkspace} also lives there and is used by the settings page to show where
     * Vortex stores things — which is a perfectly reasonable thing for a settings page to know. The
     * rule that matters is that a controller must not issue its own queries, because the same
     * query then exists twice, slightly differently, and the two drift.
     */
    @ArchTest
    static final ArchRule the_web_layer_does_not_query_the_database_directly =
            noClasses().that().resideInAPackage("dev.vortex.app.web..")
                    .should().dependOnClassesThat().haveSimpleNameStartingWith("Jdbc")
                    .orShould().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                    .because("""
                            Controllers use application services and repository ports. A controller \
                            that writes its own SQL duplicates a query that already exists \
                            somewhere else.""");

    /**
     * The composition root is exempt, because wiring is exactly its job.
     *
     * <p>{@code AiConfiguration} names {@code ChatModel} in order to construct the adapter and hand
     * it back as a {@code PerformanceAssistant}. Everything downstream of that bean sees only the
     * port, which is what makes swapping the provider an adapter-sized change.
     */
    @ArchTest
    static final ArchRule ollama_is_visible_only_to_the_ai_adapter_and_the_wiring =
            noClasses().that().resideOutsideOfPackage("dev.vortex.ai..")
                    .and().resideOutsideOfPackage("dev.vortex.app.config..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..")
                    .because("""
                            Swapping the AI provider must be an adapter change. Only the adapter \
                            and the composition root may name a provider type.""")
                    .allowEmptyShould(true);

    /**
     * An evidence writer sees a {@code RunEvidence} and nothing else: not the execution, not the
     * plan. Evidence is sanitised on the way out of assembly, so a writer that reached past it would
     * be reaching around the only gate between a plan and a written document.
     */
    @ArchTest
    static final ArchRule evidence_writers_do_not_reach_past_the_evidence_model =
            noClasses().that().resideInAPackage("dev.vortex.app.evidence..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("dev.vortex.core.plan.EffectiveTestPlan")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("dev.vortex.core.execution.TestExecution")
                    .because("these are the two aggregates that hold unsanitised configuration. "
                            + "A writer reaching for either is reaching around EvidenceSanitizer. "
                            + "Value types such as ToolVersions and ScriptSource are reachable "
                            + "through RunEvidence and are fine.");
}
