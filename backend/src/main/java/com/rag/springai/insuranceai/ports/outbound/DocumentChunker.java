package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.ChunkCandidate;
import com.rag.springai.insuranceai.domain.document.ExtractedDocument;

import java.util.List;

/**
 * Outbound port for splitting cleaned, page-structured text into retrievable chunks, keeping
 * page/section/chapter/paragraph structure (brief section 7) - never a plain
 * {@code text.substring(...)} split. Implemented by a structure-aware adapter in FASE 4.
 */
public interface DocumentChunker {

    List<ChunkCandidate> chunk(ExtractedDocument document);
}
