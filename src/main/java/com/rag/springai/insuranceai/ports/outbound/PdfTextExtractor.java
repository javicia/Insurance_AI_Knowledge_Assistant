package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.ExtractedDocument;

/**
 * Outbound port for extracting raw text from a PDF's bytes, page by page. Implemented by a
 * Tika/PDFBox-based adapter (FASE 4, {@code adapters.outbound.document}) - the domain and
 * application layers never see Tika or PDFBox types.
 */
public interface PdfTextExtractor {

    ExtractedDocument extract(byte[] pdfBytes);
}
