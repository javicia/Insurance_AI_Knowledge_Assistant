package com.rag.springai.insuranceai.domain.document;

import java.util.Objects;

/**
 * Raw text extracted from a single page of a document, before cleaning or chunking. Page
 * numbers are 1-based, matching how they are cited back to employees (brief section 22).
 */
public record ExtractedPage(int pageNumber, String text) {

    public ExtractedPage {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be at least 1");
        }
        Objects.requireNonNull(text, "text must not be null");
    }
}
