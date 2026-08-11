package com.rag.springai.insuranceai.domain.document;

import java.util.List;
import java.util.Objects;

/**
 * The result of extracting a document's raw text (via {@code PdfTextExtractor}) or of cleaning
 * that text (via {@code DocumentCleaner}) - both outbound ports produce/consume this same
 * shape, one page at a time, so structure (page boundaries) survives both steps and is still
 * available to {@code DocumentChunker} (brief section 7: page/section/chapter/paragraph must
 * be preserved for later citations).
 */
public record ExtractedDocument(List<ExtractedPage> pages) {

    public ExtractedDocument {
        Objects.requireNonNull(pages, "pages must not be null");
        pages = List.copyOf(pages);
    }

    public boolean hasNoExtractableText() {
        return pages.stream().allMatch(page -> page.text().isBlank());
    }
}
