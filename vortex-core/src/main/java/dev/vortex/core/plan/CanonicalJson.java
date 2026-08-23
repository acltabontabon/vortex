package dev.vortex.core.plan;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic JSON rendering, used only as the input to plan fingerprinting.
 *
 * <p>Hashing a configuration file's raw text does not work: these two are the same plan, but hash
 * differently.
 *
 * <pre>{@code
 * arrivalRate: 20      duration: 10m
 * duration: 10m        arrivalRate: 20
 * }</pre>
 *
 * <p>So Vortex hashes a canonical rendering of the <em>resolved</em> plan instead. The rules are
 * fixed and documented, because a fingerprint whose definition drifts silently is worse than no
 * fingerprint at all:
 *
 * <ul>
 *   <li>object keys are sorted by Unicode code point;</li>
 *   <li>no insignificant whitespace;</li>
 *   <li>numbers are rendered in plain (non-scientific) notation with trailing zeros stripped;</li>
 *   <li>strings are Unicode-normalised to NFC and escaped minimally;</li>
 *   <li>{@code null} values are omitted entirely, so adding an unset optional field does not change
 *       an existing fingerprint.</li>
 * </ul>
 *
 * <p>This is a private hashing format, not a public serialisation format. Artifacts written for
 * humans are produced by the persistence adapter with a normal JSON library.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String render(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> writeObject(map, out);
            case Iterable<?> iterable -> writeArray(iterable, out);
            case CharSequence text -> writeString(text.toString(), out);
            case Boolean bool -> out.append(bool);
            case BigDecimal decimal -> out.append(plain(decimal));
            case Number number -> out.append(plain(new BigDecimal(number.toString())));
            case Enum<?> constant -> writeString(constant.name(), out);
            default -> writeString(value.toString(), out);
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        Map<String, Object> sorted = new TreeMap<>();
        map.forEach((key, value) -> {
            if (value != null) {
                sorted.put(String.valueOf(key), value);
            }
        });
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(entry.getKey(), out);
            out.append(':');
            write(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(Iterable<?> values, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(value, out);
        }
        out.append(']');
    }

    private static void writeString(String raw, StringBuilder out) {
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String plain(BigDecimal decimal) {
        BigDecimal stripped = decimal.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    /** Convenience for building canonical maps in declaration order before sorting. */
    public static Map<String, Object> map(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected alternating keys and values");
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return map;
    }

    public static List<Object> list(List<?> values) {
        return List.copyOf(values);
    }
}
