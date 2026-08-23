package dev.vortex.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.environment.SecretReferences;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * An export is the moment a run's details leave the machine that produced them.
 *
 * <p>Everything else in Vortex keeps secrets out by construction: a plan holds
 * {@code ${VORTEX_AUTH_TOKEN}} and the engine resolves it at process launch and nowhere else. The
 * sanitiser exists so the property is <em>checked</em> at the boundary rather than merely
 * inherited, and these tests are what make that check worth having.
 *
 * <p>The credentials below are assembled from pieces rather than written as literals, so a scan of
 * this repository for leaked secrets does not flag its own secret-leak test.
 */
class EvidenceSanitizerTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9." + "eyJzdWIiOiIxMjM0NTY3ODkwIn0."
            + "dBjftJeZ4CVPmB92K27uhbUJU1p1r" + "wW1gFWFOEjXk";

    private static final String API_KEY = "sk-" + "liveKey0123456789abcdefXYZ";

    private final EvidenceSanitizer sanitizer = new EvidenceSanitizer();

    @Nested
    @DisplayName("headers")
    class Headers {

        @Test
        @DisplayName("a pure reference survives, because it reveals nothing")
        void pureReferencesArePreserved() {
            Map<String, String> sanitised =
                    sanitizer.headers(Map.of("X-Tenant", "${VORTEX_TENANT_ID}"));

            assertThat(sanitised.get("X-Tenant")).isEqualTo("${VORTEX_TENANT_ID}");
        }

        @Test
        @DisplayName("a reference with a prefix is masked whole: it is no longer only a reference")
        void referenceWithAPrefixIsMasked() {
            // "Bearer ${TOKEN}" leaves "Bearer " once the reference is removed, so the house rule
            // in SecretReferences treats it as a value that might contain a literal. Conservative,
            // and an export is the last place to relax it.
            Map<String, String> sanitised =
                    sanitizer.headers(Map.of("Authorization", "Bearer ${VORTEX_AUTH_TOKEN}"));

            assertThat(sanitised.get("Authorization")).isEqualTo(SecretReferences.MASK);
        }

        @Test
        @DisplayName("the variables a run depends on are named, so a masked header is still reproducible")
        void referencedVariablesAreRecoverable() {
            // Masking costs the reader the one thing they need to run this again. The names give it
            // back without printing anything that could be a credential.
            List<String> names = sanitizer.secretReferences(
                    Map.of("Authorization", "Bearer ${VORTEX_AUTH_TOKEN}"));

            assertThat(names).containsExactly("VORTEX_AUTH_TOKEN");
        }

        @Test
        @DisplayName("a resolved credential in a header never survives")
        void resolvedCredentialsAreMasked() {
            Map<String, String> sanitised =
                    sanitizer.headers(Map.of("Authorization", "Bearer " + TOKEN));

            assertThat(sanitised.get("Authorization"))
                    .isEqualTo(SecretReferences.MASK)
                    .doesNotContain(TOKEN);
        }

        @Test
        @DisplayName("a sensitive header is masked whatever its value looks like")
        void sensitiveNamesAreMaskedRegardless() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Api-Key", "not-obviously-a-secret");
            headers.put("Cookie", "session=abc");

            Map<String, String> sanitised = sanitizer.headers(headers);

            assertThat(sanitised.get("X-Api-Key")).isEqualTo(SecretReferences.MASK);
            assertThat(sanitised.get("Cookie")).isEqualTo(SecretReferences.MASK);
        }

        @Test
        @DisplayName("header names are kept: that a run sent one is part of how it was carried out")
        void namesArePreserved() {
            assertThat(sanitizer.headers(Map.of("Authorization", TOKEN)))
                    .containsKey("Authorization");
        }

        @Test
        void anOrdinaryHeaderIsUntouched() {
            assertThat(sanitizer.headers(Map.of("Content-Type", "application/json")))
                    .containsEntry("Content-Type", "application/json");
        }
    }

    @Nested
    @DisplayName("free text")
    class FreeText {

        @Test
        @DisplayName("a credential pasted into a workload description is masked")
        void credentialShapesInProse() {
            String sanitised = sanitizer.text("Retried with " + API_KEY + " and it worked");

            assertThat(sanitised).doesNotContain(API_KEY).contains(SecretReferences.MASK);
        }

        @Test
        void jsonWebTokensAreMasked() {
            assertThat(sanitizer.text("token=" + TOKEN)).doesNotContain(TOKEN);
        }

        @Test
        @DisplayName("ordinary prose is left alone, so findings stay readable")
        void ordinaryProseSurvives() {
            String answer = "Yes - the service sustained 120 requests/sec with p95 at 281 ms.";

            assertThat(sanitizer.text(answer)).isEqualTo(answer);
        }

        @Test
        @DisplayName("control characters are removed, because they corrupt a PDF content stream")
        void controlCharactersAreStripped() {
            assertThat(sanitizer.text("before\u0007after")).isEqualTo("before after");
        }

        @Test
        void newlinesAndTabsSurvive() {
            assertThat(sanitizer.text("one\ntwo\tthree")).isEqualTo("one\ntwo\tthree");
        }
    }

    @Nested
    @DisplayName("urls")
    class Urls {

        @Test
        @DisplayName("credentials embedded in a target url are removed, the host is kept")
        void userinfoIsStripped() {
            String sanitised = sanitizer.url("https://admin:hunter2@checkout.internal:8080/api");

            assertThat(sanitised)
                    .isEqualTo("https://checkout.internal:8080/api")
                    .doesNotContain("hunter2");
        }

        @Test
        @DisplayName("which service was tested is not a secret and must survive")
        void ordinaryUrlsAreUntouched() {
            assertThat(sanitizer.url("http://localhost:8080")).isEqualTo("http://localhost:8080");
        }
    }

    @Nested
    @DisplayName("engine options")
    class Options {

        @Test
        void allowlistedOptionsSurvive() {
            assertThat(sanitizer.options(Map.of("rps", "500"))).containsEntry("rps", "500");
        }

        @Test
        @DisplayName("an unrecognised option is masked rather than guessed at")
        void unknownOptionsAreMasked() {
            // An allowlist, because these are arbitrary user-supplied keys and a denylist over an
            // open set is a guess about what somebody will type next.
            assertThat(sanitizer.options(Map.of("authToken", API_KEY)))
                    .containsEntry("authToken", SecretReferences.MASK);
        }

        @Test
        void optionNamesAreNormalisedBeforeMatching() {
            assertThat(sanitizer.options(Map.of("no-connection-reuse", "true")))
                    .containsEntry("no-connection-reuse", "true");
        }
    }

    @Test
    void nullsAndEmptiesAreHandledWithoutCeremony() {
        assertThat(sanitizer.text(null)).isEmpty();
        assertThat(sanitizer.url(null)).isEmpty();
        assertThat(sanitizer.headers(null)).isEmpty();
        assertThat(sanitizer.options(null)).isEmpty();
        assertThat(sanitizer.lines(null)).isEmpty();
        assertThat(sanitizer.lines(List.of("a", "b"))).containsExactly("a", "b");
    }
}
