package com.rag.springai.insuranceai.adapters.outbound.document;

import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import com.rag.springai.insuranceai.domain.document.ExtractedDocument;
import com.rag.springai.insuranceai.domain.document.ExtractedPage;
import com.rag.springai.insuranceai.ports.outbound.PdfTextExtractor;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PDF text extraction backed by Apache PDFBox, via Spring AI's {@link PagePdfDocumentReader}
 * (already a project dependency - brief section 58: no new dependency needed for this). One
 * PDF page per {@link ExtractedPage} ({@code pagesPerDocument(1)}), which is what lets
 * {@code DocumentChunker} preserve page numbers for later citations (brief section 7).
 */
@Component
public class PdfBoxTextExtractor implements PdfTextExtractor {

    @Override
    public ExtractedDocument extract(byte[] pdfBytes) {
        try {
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(1)
                    .build();
            PagePdfDocumentReader reader = new PagePdfDocumentReader(new ByteArrayResource(pdfBytes), config);
            List<org.springframework.ai.document.Document> pages = reader.get();

            List<ExtractedPage> extractedPages = pages.stream()
                    .map(page -> new ExtractedPage(pageNumberOf(page), page.getText() == null ? "" : page.getText()))
                    .toList();

            return new ExtractedDocument(extractedPages);
        } catch (RuntimeException e) {
            throw new PermanentProcessingException("PDF_EXTRACTION_FAILED",
                    "Could not extract text from the provided PDF content", e);
        }
    }

    private static int pageNumberOf(org.springframework.ai.document.Document page) {
        // Empirically verified (PdfBoxTextExtractorTest): METADATA_START_PAGE_NUMBER is
        // already 1-based, matching ExtractedPage's own 1-based numbering - no offset needed.
        Object startPage = page.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
        if (startPage instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        return 1;
    }
}
