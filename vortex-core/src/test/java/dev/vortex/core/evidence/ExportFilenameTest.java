package dev.vortex.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An exported file has to identify its run without being opened, because by the time somebody is
 * looking at it in a ticket or an archive, every system that could have told them is gone.
 *
 * <p>The name is also why the export endpoint needs no header escaping: the output is ASCII-only
 * and quote-free by construction, so nothing survives that could inject a header or require RFC
 * 5987 encoding. These tests are what that claim rests on.
 */
class ExportFilenameTest {

    @Test
    void anOrdinaryNameIsLowercasedAndHyphenated() {
        assertThat(ExportFilename.segment("checkout-service")).isEqualTo("checkout-service");
        assertThat(ExportFilename.segment("Production Peak")).isEqualTo("production-peak");
    }

    @Test
    @DisplayName("accents are folded rather than discarded, so a name stays recognisable")
    void accentsAreFolded() {
        // Simply dropping non-ASCII would give "pr-fung", which helps nobody.
        assertThat(ExportFilename.segment("Zahlungsdienst Prufung"))
                .isEqualTo("zahlungsdienst-prufung");
        assertThat(ExportFilename.segment("Prüfung")).isEqualTo("prufung");
    }

    @Test
    @DisplayName("a name with nothing representable still yields a usable segment")
    void unrepresentableNames() {
        assertThat(ExportFilename.segment("決済")).isEqualTo("unknown");
        assertThat(ExportFilename.segment("")).isEqualTo("unknown");
        assertThat(ExportFilename.segment(null)).isEqualTo("unknown");
        assertThat(ExportFilename.segment("///")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("path traversal cannot survive into a filename")
    void pathTraversalIsNeutralised() {
        assertThat(ExportFilename.segment("../../etc/passwd"))
                .isEqualTo("etc-passwd")
                .doesNotContain("..")
                .doesNotContain("/");
    }

    @Test
    @DisplayName("quotes and newlines cannot survive, which is what makes header escaping unnecessary")
    void headerInjectionIsImpossible() {
        String segment = ExportFilename.segment("evil\"; rm -rf /\r\nX-Injected: yes");

        assertThat(segment).matches("[a-z0-9-]+");
    }

    @Test
    void longNamesAreTruncatedWithoutATrailingSeparator() {
        String segment = ExportFilename.segment("a-very-long-workload-name-".repeat(5));

        assertThat(segment).hasSizeLessThanOrEqualTo(40).doesNotEndWith("-");
    }

    @Test
    void runsOfSeparatorsCollapse() {
        assertThat(ExportFilename.segment("a   ///   b")).isEqualTo("a-b");
    }
}
