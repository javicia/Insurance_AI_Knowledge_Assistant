package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.ExtractedDocument;

/**
 * Outbound port for normalizing raw extracted text (whitespace, boilerplate/header-footer
 * artifacts, hyphenation) before it is chunked. Implemented by a rule-based adapter in FASE 4
 * (documented as such - not a machine-learning capability, see brief section 14's guidance on
 * not overstating PoC-grade implementations).
 */
public interface DocumentCleaner {

    ExtractedDocument clean(ExtractedDocument extracted);
}
