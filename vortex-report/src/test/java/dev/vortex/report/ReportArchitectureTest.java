package dev.vortex.report;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The boundaries this module exists to hold.
 *
 * <p>{@code vortex-report} was made a module rather than a package for one reason: to quarantine
 * OpenPDF. That is only true while the PDF library stays inside the one package that renders PDFs,
 * and while nothing here reaches for another adapter instead of asking the evidence model for what
 * it needs.
 *
 * <p>Maven's banned-dependencies rule in this module's POM enforces the module-level half. These
 * rules enforce the half Maven cannot see.
 *
 * <p>Production classes only. The tests deliberately reach for a PDF parser and build plans by
 * hand, which is exactly what they must do to verify the code these rules govern.
 */
@AnalyzeClasses(packages = "dev.vortex.report",
        importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class ReportArchitectureTest {

    /**
     * The whole justification for the module. If a PDF type appears in the JSON exporter or in a
     * shared helper, the dependency is no longer quarantined and the eighth module has stopped
     * earning its place.
     */
    @ArchTest
    static final ArchRule the_pdf_library_stays_inside_the_pdf_package =
            noClasses().that().resideOutsideOfPackage("dev.vortex.report.pdf..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.lowagie..")
                    .because("OpenPDF is what this module exists to quarantine. See "
                            + "docs/adr/adr-029-openpdf-quarantined-in-a-report-module.adoc");

    /**
     * Exporters read a {@code RunEvidence} and nothing else. Reaching past it would reach around
     * the sanitiser, which is the only gate between a plan and a published document.
     */
    @ArchTest
    static final ArchRule renderers_do_not_reach_past_the_evidence_model =
            noClasses().should().dependOnClassesThat()
                    .haveFullyQualifiedName("dev.vortex.core.plan.EffectiveTestPlan")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("dev.vortex.core.execution.TestExecution")
                    .because("these are the two aggregates that hold unsanitised configuration. "
                            + "An exporter reaching for either is reaching around EvidenceSanitizer. "
                            + "Value types such as ToolVersions and ScriptSource are reachable "
                            + "through RunEvidence and are fine.");

    @ArchTest
    static final ArchRule rendering_does_not_depend_on_other_adapters =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("dev.vortex.persistence..", "dev.vortex.k6..",
                            "dev.vortex.ai..", "dev.vortex.openapi..", "dev.vortex.app..")
                    .because("rendering evidence needs no storage, no engine and no assistant");

    @ArchTest
    static final ArchRule rendering_does_not_depend_on_a_framework =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                    .because("an exporter is a pure function from evidence to bytes");
}
