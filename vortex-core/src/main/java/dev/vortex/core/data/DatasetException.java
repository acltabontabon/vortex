package dev.vortex.core.data;

import java.util.List;

/** A dataset could not be read, stored or resolved. Carries the problems, not just a message. */
public class DatasetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<DatasetProblem> problems;

    public DatasetException(String message, List<DatasetProblem> problems) {
        super(message);
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public DatasetException(String message, Throwable cause) {
        super(message, cause);
        this.problems = List.of();
    }

    public List<DatasetProblem> problems() {
        return problems;
    }
}
