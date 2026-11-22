package com.rag.springai.insuranceai.domain.document;

import java.util.Objects;

/**
 * A document version identifier such as {@code 3.2} (brief section 11). Distinct from
 * {@link DocumentVersionId}: the version number is a business-meaningful, human-facing,
 * comparable value chosen by whoever publishes a new version, while {@code DocumentVersionId}
 * is an opaque surrogate identity used internally.
 */
public record VersionNumber(int major, int minor) implements Comparable<VersionNumber> {

    public VersionNumber {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("major and minor must not be negative");
        }
    }

    public static VersionNumber of(int major, int minor) {
        return new VersionNumber(major, minor);
    }

    public static VersionNumber parse(String value) {
        Objects.requireNonNull(value, "value must not be null");
        String normalized = value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("version number must be in 'major.minor' format, got: " + value);
        }
        try {
            return new VersionNumber(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("version number must be in 'major.minor' format, got: " + value, e);
        }
    }

    @Override
    public int compareTo(VersionNumber other) {
        int majorComparison = Integer.compare(this.major, other.major);
        return majorComparison != 0 ? majorComparison : Integer.compare(this.minor, other.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
