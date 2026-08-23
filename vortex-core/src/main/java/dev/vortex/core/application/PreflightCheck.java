package dev.vortex.core.application;

import java.util.Objects;

/**
 * One verification performed before a test runs.
 *
 * <p>Every failure carries a remedy. "Process exited 1" is not an error message; "Vortex expected
 * to find k6 on your PATH but the executable was not available — install k6, or configure its
 * location under Settings → Execution engine" is.
 *
 * @param name   what was checked
 * @param status the outcome
 * @param detail what was found
 * @param remedy what to do about it, when something is wrong
 */
public record PreflightCheck(String name, Status status, String detail, String remedy) {

    public enum Status {
        PASS("Pass"),
        WARN("Warning"),
        FAIL("Failed"),
        SKIPPED("Skipped");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public PreflightCheck {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(name, "name");
        detail = detail == null ? "" : detail;
        remedy = remedy == null ? "" : remedy;
    }

    public static PreflightCheck pass(String name, String detail) {
        return new PreflightCheck(name, Status.PASS, detail, "");
    }

    public static PreflightCheck warn(String name, String detail, String remedy) {
        return new PreflightCheck(name, Status.WARN, detail, remedy);
    }

    public static PreflightCheck fail(String name, String detail, String remedy) {
        return new PreflightCheck(name, Status.FAIL, detail, remedy);
    }

    public static PreflightCheck skipped(String name, String detail) {
        return new PreflightCheck(name, Status.SKIPPED, detail, "");
    }

    public boolean isFailure() {
        return status == Status.FAIL;
    }
}
