package dev.vortex.core.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A content hash of a resolved test plan, used to tell whether two executions ran the same test.
 *
 * <p>Computed over {@link CanonicalJson} of the plan's canonical form, so that reordering
 * configuration keys, reformatting YAML or changing comments does not change the fingerprint, while
 * changing a rate, a threshold or an operation does.
 *
 * <h2>What is excluded, and why</h2>
 * <ul>
 *   <li><strong>Secret values</strong> — never included, and never resolvable from the hash. Only
 *       the reference name participates.</li>
 *   <li><strong>Timestamps and execution identifiers</strong> — two runs of the same test are the
 *       same test.</li>
 *   <li><strong>Tool versions</strong> — recorded separately on the execution, because a k6 upgrade
 *       does not change what was asked for, only what carried it out.</li>
 * </ul>
 */
public record PlanFingerprint(String algorithm, String hash) {

    public static final String ALGORITHM = "SHA-256";

    public PlanFingerprint {
        Objects.requireNonNull(algorithm, "algorithm");
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("fingerprint hash must not be blank");
        }
    }

    public static PlanFingerprint of(Object canonicalForm) {
        return ofCanonicalJson(CanonicalJson.render(canonicalForm));
    }

    public static PlanFingerprint ofCanonicalJson(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hashed = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return new PlanFingerprint(ALGORITHM, HexFormat.of().formatHex(hashed));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required but unavailable", e);
        }
    }

    /** Short form for display, e.g. {@code a1b2c3d4}. */
    public String shortHash() {
        return hash.substring(0, Math.min(8, hash.length()));
    }

    @Override
    public String toString() {
        return algorithm + ":" + hash;
    }
}
