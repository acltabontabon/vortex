package dev.vortex.k6;

import dev.vortex.core.data.Generator;
import java.util.EnumMap;
import java.util.Map;

/**
 * The JavaScript behind each {@link Generator}.
 *
 * <p>Plain functions over what k6 already provides. No imported library, no jslib URL, nothing
 * fetched at run time — a generated script has to work on a machine with no network and be readable
 * by somebody who has never used Vortex, and a remote import fails both tests.
 *
 * <p>Only the helpers a plan actually uses are emitted, so a script that generates one UUID does not
 * carry a phone-number formatter it never calls.
 */
final class K6Generators {

    /**
     * A version 4 UUID.
     *
     * <p>Prefers k6's own Web Crypto implementation, which is available from k6 1.0 and is a proper
     * CSPRNG. The fallback exists because Vortex does not control which k6 is on the machine and
     * refusing to run on an older one would be a poor trade for a value that is usually an
     * idempotency key. It is marked as what it is rather than presented as equivalent.
     */
    private static final String UUID = """
            function vortexUuid() {
              if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
                return crypto.randomUUID();
              }
              // Fallback for k6 before 1.0, which has no Web Crypto. Well-formed, but seeded from
              // Math.random rather than a cryptographic source — fine for a request identifier,
              // not fine for anything that needs to be unguessable.
              return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
                const r = (Math.random() * 16) | 0;
                return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
              });
            }
            """;

    private static final String TIMESTAMP = """
            function vortexTimestamp() {
              return new Date().toISOString();
            }
            """;

    private static final String DATE = """
            function vortexDate() {
              return new Date().toISOString().slice(0, 10);
            }
            """;

    private static final String RANDOM_INTEGER = """
            function vortexInt(min, max) {
              return Math.floor(Math.random() * (max - min + 1)) + min;
            }
            """;

    private static final String RANDOM_STRING = """
            function vortexString(length) {
              const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
              let out = '';
              for (let i = 0; i < length; i++) {
                out += alphabet.charAt(Math.floor(Math.random() * alphabet.length));
              }
              return out;
            }
            """;

    // example.com is reserved by RFC 2606 precisely so that generated addresses cannot reach a real
    // mailbox. A load test that emails somebody is a load test that made an enemy.
    private static final String EMAIL = """
            function vortexEmail() {
              return 'vortex-' + vortexString(8) + '@example.com';
            }
            """;

    // Shaped like a phone number and deliberately not one: the 555 exchange is reserved for
    // fiction in the North American plan, and nothing here is dialled by the test.
    private static final String PHONE = """
            function vortexPhone() {
              return '+1555' + String(vortexInt(1000000, 9999999));
            }
            """;

    private static final Map<Generator, String> HELPERS = new EnumMap<>(Generator.class);

    static {
        HELPERS.put(Generator.UUID, UUID);
        HELPERS.put(Generator.TIMESTAMP, TIMESTAMP);
        HELPERS.put(Generator.DATE, DATE);
        HELPERS.put(Generator.RANDOM_INTEGER, RANDOM_INTEGER);
        HELPERS.put(Generator.RANDOM_STRING, RANDOM_STRING);
        HELPERS.put(Generator.EMAIL, EMAIL);
        HELPERS.put(Generator.PHONE, PHONE);
        // SEQUENCE has no helper: it compiles to k6's own iteration counter.
    }

    private K6Generators() {
    }

    /** The helper function this generator needs, or empty when it needs none. */
    static String helperFor(Generator generator) {
        return HELPERS.getOrDefault(generator, "");
    }

    /**
     * Generators this one depends on.
     *
     * <p>An email is a random string with an at-sign, and a phone number is a random integer with a
     * country code. Emitting the caller without the callee produces a script that passes review and
     * fails on its first request.
     */
    static Generator[] dependenciesOf(Generator generator) {
        return switch (generator) {
            case EMAIL -> new Generator[] {Generator.RANDOM_STRING};
            case PHONE -> new Generator[] {Generator.RANDOM_INTEGER};
            default -> new Generator[0];
        };
    }
}
