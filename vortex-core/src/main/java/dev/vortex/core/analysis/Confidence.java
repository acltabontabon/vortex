package dev.vortex.core.analysis;

/** How strongly an interpretation is being asserted. */
public enum Confidence {

    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    private final String label;

    Confidence(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Confidence parse(String raw) {
        if (raw == null) {
            return LOW;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "HIGH" -> HIGH;
            case "MEDIUM", "MED" -> MEDIUM;
            default -> LOW;
        };
    }
}
