package dev.vortex.core.data;

/**
 * Where one request value comes from.
 *
 * <p>A request needs data: an account id in the path, a tenant header, an idempotency key, a body
 * field. Vortex's position is that a user should describe <em>what data a request needs</em> and
 * Vortex should decide how to supply it during execution — not that the user should learn to write
 * a load-generation script. So a value is a declaration with a source, never an expression.
 *
 * <p>There is deliberately one abstraction for every position a value can occupy — header, path
 * parameter, query parameter, body field. A {@code DynamicHeader} separate from a
 * {@code DynamicQueryParameter} would be four implementations of one idea, and the fourth would
 * behave subtly differently from the first.
 *
 * <h2>The closed set</h2>
 * <ul>
 *   <li>{@link FixedValue} — a literal the user supplied.</li>
 *   <li>{@link GeneratedValue} — Vortex produces it, from a closed set of generators.</li>
 *   <li>{@link DatasetValue} — a field of the current row of an uploaded dataset.</li>
 *   <li>{@link EnvironmentValue} — resolved from the process environment when the load generator is
 *       launched, and nowhere else. This is how a secret participates without being written down.</li>
 * </ul>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>There is no expression, template, script or callback case, and adding one is not a small
 * change of mind: it is the difference between configuration a person can review in a pull request
 * and a program somebody has to debug. The set is sealed so that adding a case is a decision taken
 * once, in the open.
 *
 * <p>A fifth case — {@code CapturedValue}, taking its value from a prerequisite request's response —
 * is designed for but not implemented. It needs a place to run before the workload starts, which is
 * k6's {@code setup()}, and that seam is shared with the {@code PER_RUN} lifecycle. Both arrive
 * together or not at all; half of a workflow engine is worse than none. See ADR-036.
 */
public sealed interface RequestValue
        permits FixedValue, GeneratedValue, DatasetValue, EnvironmentValue {

    /**
     * A short, sanitised description of where this value comes from, for evidence and the interface.
     *
     * <p>Never the value itself for a source that could carry a secret — see
     * {@code RequestValueOrigin}, which is what actually reaches a report.
     */
    String describeSource();

    /** Whether this value differs between requests, VUs or runs rather than being a constant. */
    boolean isDynamic();
}
