package com.rag.springai.insuranceai.adapters.outbound.document;

import com.rag.springai.insuranceai.domain.document.ExtractedDocument;
import com.rag.springai.insuranceai.domain.document.ExtractedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleBasedDocumentCleanerTest {

    private final RuleBasedDocumentCleaner cleaner = new RuleBasedDocumentCleaner();

    @Test
    void collapsesRepeatedWhitespace() {
        ExtractedDocument extracted = new ExtractedDocument(List.of(new ExtractedPage(1, "Water   damage\tcoverage")));

        ExtractedDocument cleaned = cleaner.clean(extracted);

        assertEquals("Water damage coverage", cleaned.pages().get(0).text());
    }

    @Test
    void collapsesRepeatedBlankLinesToASingleParagraphBreak() {
        ExtractedDocument extracted = new ExtractedDocument(
                List.of(new ExtractedPage(1, "Paragraph one.\n\n\n\n\nParagraph two.")));

        ExtractedDocument cleaned = cleaner.clean(extracted);

        assertEquals("Paragraph one.\n\nParagraph two.", cleaned.pages().get(0).text());
    }

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        ExtractedDocument extracted = new ExtractedDocument(List.of(new ExtractedPage(1, "   surrounded   \n")));

        ExtractedDocument cleaned = cleaner.clean(extracted);

        assertEquals("surrounded", cleaned.pages().get(0).text());
    }

    @Test
    void preservesPageNumbers() {
        ExtractedDocument extracted = new ExtractedDocument(List.of(new ExtractedPage(7, "content")));

        ExtractedDocument cleaned = cleaner.clean(extracted);

        assertEquals(7, cleaned.pages().get(0).pageNumber());
    }
}
