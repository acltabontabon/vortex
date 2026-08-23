package dev.vortex.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Guards the property that makes every other architectural claim in Vortex credible: the domain is
 * plain Java.
 *
 * <p>Maven enforces most of this already — {@code vortex-core} declares no compile-scope
 * dependencies at all, so a framework import would not compile. These rules add the reasoning
 * behind the constraint, and catch the case where someone relaxes the build before the design
 * discussion has happened.
 */
@AnalyzeClasses(
        packages = "dev.vortex.core",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CoreArchitectureTest {

    @ArchTest
    static final ArchRule domain_does_not_depend_on_spring =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                    .because("""
                            The domain layer is plain Java. Annotations such as @Service and @Component \
                            turn a model into framework metadata, and wiring belongs in the composition \
                            root (vortex-app), not in the objects that express the business rules. \
                            See docs/adr/adr-013-core-has-no-framework-dependencies.adoc""");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_jackson =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("com.fasterxml..")
                    .because("""
                            Serialisation annotations spread across a domain model make the persisted \
                            shape and the modelled shape impossible to change independently. Adapters \
                            own serialisation; PlanFingerprint uses a small canonical-JSON writer \
                            inside core instead.""");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_persistence_technology =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("java.sql..", "javax.sql..", "org.sqlite..", "org.flywaydb..")
                    .because("""
                            The domain must not know how it is stored. Persistence lives behind the \
                            repository ports in dev.vortex.core.port.""");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_the_execution_engine =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("io.k6..", "dev.vortex.k6..")
                    .because("""
                            Vortex is not a load-generation engine and must not become coupled to one. \
                            k6 sits behind the PerformanceEngine port so a second engine, or \
                            distributed execution through the k6 Operator, changes nothing in core.""");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_the_ai_provider =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.ai..", "dev.vortex.ai..")
                    .because("""
                            AI is downstream of performance truth. Core computes verdicts, breakpoints \
                            and headroom without any model; the assistant sits behind the \
                            PerformanceAssistant port.""");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_web_technology =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("jakarta.servlet..", "org.thymeleaf..")
                    .because("The same application services must serve the UI, the CLI and CI equally.");

    @ArchTest
    static final ArchRule domain_does_not_read_the_clock_directly =
            noClasses().that().resideOutsideOfPackage("dev.vortex.core.port..")
                    .should().callMethod(java.time.Instant.class, "now")
                    .orShould().callMethod(System.class, "currentTimeMillis")
                    .because("""
                            Time is injected through the Clock port so that time-dependent behaviour \
                            is testable and execution timestamps are deterministic.""");

    @ArchTest
    static final ArchRule domain_does_not_print_to_the_console =
            noClasses().should().accessField(System.class, "out")
                    .orShould().accessField(System.class, "err")
                    .because("Core communicates through return values; presentation belongs to adapters.");

    /**
     * Vocabulary that names a transport rather than a measurement.
     *
     * <p>Deliberately short. The point is not to ban a word list — it is that the reasoning model
     * has to be one a second transport can populate, and a type called {@code HttpStatusClass} is
     * proof it cannot.
     */
    private static final String[] TRANSPORT_NAMES =
            {"http", "https", "rest", "grpc", "kafka", "amqp", "sqs", "websocket", "soap"};

    @ArchTest
    static final ArchRule the_reasoning_model_names_no_transport =
            noClasses().that().resideInAnyPackage(
                            "dev.vortex.core.metrics..",
                            "dev.vortex.core.analysis..",
                            "dev.vortex.core.resource..",
                            "dev.vortex.core.validity..",
                            "dev.vortex.core.capacity..")
                    .should(namesATransport())
                    .because("""
                            Phase 7 reuses this model for asynchronous workloads, or forces a \
                            redesign of both at once. A measurement and validity model that names \
                            a transport is a model for one transport: a queue consumer has offered \
                            load, achieved load, generation failure, resource limits, run quality \
                            and capacity, and none of that should need a parallel vocabulary. \
                            Protocol-specific mapping belongs in the engine adapter, which is why \
                            k6's status codes and error-code bands live in vortex-k6. Measurements \
                            that genuinely are HTTP — a TLS handshake phase — may be carried and \
                            rendered here under a name that does not claim to be the general case.""");

    private static ArchCondition<JavaClass> namesATransport() {
        return new ArchCondition<>("name a transport") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String simpleName = item.getSimpleName().toLowerCase(java.util.Locale.ROOT);
                for (String transport : TRANSPORT_NAMES) {
                    if (simpleName.contains(transport)) {
                        events.add(SimpleConditionEvent.satisfied(item,
                                item.getName() + " names the transport '" + transport + "'"));
                        return;
                    }
                }
                events.add(SimpleConditionEvent.violated(item, item.getName() + " names no transport"));
            }
        };
    }
}
