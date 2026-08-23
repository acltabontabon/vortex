package com.acltabontabon.vortex.core.data;

import java.util.Objects;

/**
 * Something wrong with a dataset, said in a way somebody can act on.
 *
 * <p>Same shape as the configuration problems Vortex already reports: what is wrong, where, and what
 * to do about it. "Invalid dataset" tells a user they have a problem and nothing else; naming the
 * row, the column and the fix tells them how their afternoon goes.
 *
 * @param location where the problem is — a column name, {@code row 41}, or the dataset itself
 * @param message  what is wrong
 * @param remedy   what to do about it
 */
public record DatasetProblem(String location, String message, String remedy) {

    public DatasetProblem {
        Objects.requireNonNull(message, "message");
        location = location == null ? "" : location;
        remedy = remedy == null ? "" : remedy;
    }

    public static DatasetProblem of(String location, String message, String remedy) {
        return new DatasetProblem(location, message, remedy);
    }

    /** The problem as one sentence, for a log line or an API response. */
    public String describe() {
        StringBuilder described = new StringBuilder();
        if (!location.isBlank()) {
            described.append(location).append(": ");
        }
        described.append(message);
        if (!remedy.isBlank()) {
            described.append(' ').append(remedy);
        }
        return described.toString();
    }
}
