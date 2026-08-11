package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentHashTest {

    @Test
    void computesTheKnownSha256DigestOfEmptyContent() {
        ContentHash hash = ContentHash.of(new byte[0]);

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash.value());
    }

    @Test
    void computesTheKnownSha256DigestOfKnownContent() {
        ContentHash hash = ContentHash.of("abc".getBytes(StandardCharsets.UTF_8));

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash.value());
    }

    @Test
    void isDeterministicForIdenticalContent() {
        byte[] content = "Home Premium Policy v3.2".getBytes(StandardCharsets.UTF_8);

        ContentHash first = ContentHash.of(content);
        ContentHash second = ContentHash.of(content);

        assertEquals(first, second);
    }

    @Test
    void differsForDifferentContent() {
        ContentHash first = ContentHash.of("content A".getBytes(StandardCharsets.UTF_8));
        ContentHash second = ContentHash.of("content B".getBytes(StandardCharsets.UTF_8));

        assertNotEquals(first, second);
    }

    @Test
    void ofHexNormalizesToLowercase() {
        ContentHash hash = ContentHash
                .ofHex("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855".toLowerCase());

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash.value());
    }

    @Test
    void rejectsAValueThatIsNotA64CharacterHexDigest() {
        assertThrows(IllegalArgumentException.class, () -> ContentHash.ofHex("not-a-valid-hash"));
    }

    @Test
    void rejectsNullContent() {
        assertThrows(NullPointerException.class, () -> ContentHash.of((byte[]) null));
    }
}
