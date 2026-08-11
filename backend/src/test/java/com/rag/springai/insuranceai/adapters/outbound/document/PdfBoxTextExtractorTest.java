package com.rag.springai.insuranceai.adapters.outbound.document;

import com.rag.springai.insuranceai.domain.document.ExtractedDocument;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxTextExtractorTest {

    private final PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();

    @Test
    void extractsTextFromEachPdfPageWithOneBasedPageNumbers() throws IOException {
        byte[] pdf = createPdf("Page one water damage coverage", "Page two claims procedure");

        ExtractedDocument extracted = extractor.extract(pdf);

        assertEquals(2, extracted.pages().size());
        assertEquals(1, extracted.pages().get(0).pageNumber());
        assertEquals(2, extracted.pages().get(1).pageNumber());
        // The underlying layout-aware stripper reconstructs whitespace from glyph positions,
        // so exact spacing is not guaranteed here - RuleBasedDocumentCleaner normalizes it
        // downstream in the real pipeline. Normalize before comparing, same as that adapter.
        assertTrue(normalizeWhitespace(extracted.pages().get(0).text())
                .contains("Page one water damage coverage"));
        assertTrue(normalizeWhitespace(extracted.pages().get(1).text())
                .contains("Page two claims procedure"));
    }

    @Test
    void rejectsContentThatIsNotAValidPdf() {
        byte[] notAPdf = "this is definitely not a PDF file".getBytes(StandardCharsets.UTF_8);

        assertThrows(PermanentProcessingException.class, () -> extractor.extract(notAPdf));
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private byte[] createPdf(String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    contentStream.newLineAtOffset(50, 700);
                    contentStream.showText(text);
                    contentStream.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
