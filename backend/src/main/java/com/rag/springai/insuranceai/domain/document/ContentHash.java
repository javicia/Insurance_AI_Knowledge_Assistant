package com.rag.springai.insuranceai.domain.document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SHA-256 content digest of a document version's raw bytes (brief section 6/34). Centralizes
 * the hashing algorithm in a single domain type instead of scattering
 * {@link MessageDigest} calls across services, and is the mechanism ingestion idempotency
 * (brief section 34) is built on: re-uploading identical bytes always yields the same
 * {@code ContentHash}.
 *
 * <p>Uses {@code java.security.MessageDigest}, part of the JDK itself (not a framework or
 * infrastructure dependency), so this remains valid pure-domain code per ADR-001.
 */
public record ContentHash(String value) {

    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public ContentHash {
        Objects.requireNonNull(value, "value must not be null");
        if (!SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be a 64-character lowercase hexadecimal SHA-256 digest");
        }
    }

    public static ContentHash of(byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(content);
            return new ContentHash(HexFormat.of().formatHex(hashBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in this JVM", e);
        }
    }

    public static ContentHash ofHex(String hex) {
        Objects.requireNonNull(hex, "hex must not be null");
        return new ContentHash(hex.toLowerCase(Locale.ROOT));
    }
}
