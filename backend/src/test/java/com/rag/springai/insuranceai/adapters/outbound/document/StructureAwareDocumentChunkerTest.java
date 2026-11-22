package com.rag.springai.insuranceai.adapters.outbound.document;

import com.rag.springai.insuranceai.domain.document.ChunkCandidate;
import com.rag.springai.insuranceai.domain.document.ExtractedDocument;
import com.rag.springai.insuranceai.domain.document.ExtractedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAwareDocumentChunkerTest {

    private final StructureAwareDocumentChunker chunker = new StructureAwareDocumentChunker();

    @Test
    void splitsAPageIntoOneChunkPerParagraph() {
        ExtractedDocument document = new ExtractedDocument(
                List.of(new ExtractedPage(1, "First paragraph.\n\nSecond paragraph.")));

        List<ChunkCandidate> chunks = chunker.chunk(document);

        assertEquals(2, chunks.size());
        assertEquals("First paragraph.", chunks.get(0).content().value());
        assertEquals("Second paragraph.", chunks.get(1).content().value());
    }

    @Test
    void assignsSequentialZeroBasedChunkIndexesAcrossPages() {
        ExtractedDocument document = new ExtractedDocument(List.of(
                new ExtractedPage(1, "Page one paragraph."),
                new ExtractedPage(2, "Page two paragraph.")));

        List<ChunkCandidate> chunks = chunker.chunk(document);

        assertEquals(0, chunks.get(0).index().value());
        assertEquals(1, chunks.get(1).index().value());
    }

    @Test
    void tagsEachChunkWithItsPageAndParagraphNumber() {
        ExtractedDocument document = new ExtractedDocument(
                List.of(new ExtractedPage(37, "Only paragraph on page 37.")));

        List<ChunkCandidate> chunks = chunker.chunk(document);

        assertEquals(37, chunks.get(0).metadata().page());
        assertEquals(1, chunks.get(0).metadata().paragraph());
    }

    @Test
    void detectsANumberedHeadingAsASectionAndTagsSubsequentChunksWithIt() {
        ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(37,
                "7.2 Water Damage Coverage\n\nBurst pipes are covered up to the policy limit.")));

        List<ChunkCandidate> chunks = chunker.chunk(document);

        assertEquals(1, chunks.size(), "the heading itself must not become its own chunk");
        assertEquals("7.2 Water Damage Coverage", chunks.get(0).metadata().section());
        assertEquals("Burst pipes are covered up to the policy limit.", chunks.get(0).content().value());
    }

    @Test
    void splitsAnOverlongParagraphIntoMultiplePiecesOnWordBoundaries() {
        String longWord = "word ".repeat(400); // well over the 1000-character limit
        ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(1, longWord.strip())));

        List<ChunkCandidate> chunks = chunker.chunk(document);

        assertTrue(chunks.size() > 1);
        chunks.forEach(chunk -> assertTrue(chunk.content().value().length() <= 1000));
    }

    @Test
    void ignoresBlankParagraphs() {
        ExtractedDocument document = new ExtractedDocument(
                List.of(new ExtractedPage(1, "First.\n\n   \n\nSecond.")));

        List<ChunkCandidate> chunks = chunker.chunk(document);

        assertEquals(2, chunks.size());
    }
}
