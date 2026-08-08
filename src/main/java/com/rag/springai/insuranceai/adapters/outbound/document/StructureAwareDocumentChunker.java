package com.rag.springai.insuranceai.adapters.outbound.document;

import com.rag.springai.insuranceai.domain.document.ChunkCandidate;
import com.rag.springai.insuranceai.domain.document.ChunkContent;
import com.rag.springai.insuranceai.domain.document.ChunkIndex;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.ExtractedDocument;
import com.rag.springai.insuranceai.domain.document.ExtractedPage;
import com.rag.springai.insuranceai.ports.outbound.DocumentChunker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Structure-aware chunker: never a plain {@code text.substring(...)} split (brief section 7).
 * Splits each page on paragraph boundaries (blank lines), tags every chunk with its
 * {@code page} and 1-based {@code paragraph} number, and heuristically detects short
 * numbered/heading-like lines (e.g. {@code "7.2 Water Damage Coverage"}) as section markers -
 * every subsequent chunk on the same page carries that {@code section} until the next heading,
 * so citations (brief section 22) can reference "section 7.2" the way brief section 13's
 * example question expects. This is a heuristic, not guaranteed structural parsing - documented
 * here rather than presented as a stronger capability than it is (brief section 14).
 *
 * <p>Paragraphs longer than {@link #MAX_CHUNK_LENGTH} are split further on word boundaries so
 * no single chunk becomes pathologically large for embedding/retrieval in FASE 5.
 */
@Component
public class StructureAwareDocumentChunker implements DocumentChunker {

    private static final int MAX_CHUNK_LENGTH = 1000;
    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\\n{2,}");
    private static final Pattern SECTION_HEADING = Pattern
            .compile("^(\\d+(\\.\\d+)*\\s+\\S.{0,78}|[A-Z][A-Z0-9 ,.'\\-]{4,78})$");

    @Override
    public List<ChunkCandidate> chunk(ExtractedDocument document) {
        List<ChunkCandidate> candidates = new ArrayList<>();
        int chunkIndex = 0;
        String currentSection = null;

        for (ExtractedPage page : document.pages()) {
            String[] paragraphs = PARAGRAPH_BOUNDARY.split(page.text());
            int paragraphNumber = 0;

            for (String rawParagraph : paragraphs) {
                String paragraph = rawParagraph.strip();
                if (paragraph.isBlank()) {
                    continue;
                }
                paragraphNumber++;

                if (isSectionHeading(paragraph)) {
                    currentSection = paragraph;
                    continue;
                }

                for (String piece : splitIntoMaxLength(paragraph)) {
                    ChunkMetadata metadata = new ChunkMetadata(page.pageNumber(), null, currentSection,
                            paragraphNumber);
                    candidates.add(new ChunkCandidate(new ChunkIndex(chunkIndex++), new ChunkContent(piece),
                            metadata));
                }
            }
        }
        return candidates;
    }

    private boolean isSectionHeading(String paragraph) {
        return !paragraph.contains("\n") && SECTION_HEADING.matcher(paragraph).matches();
    }

    private List<String> splitIntoMaxLength(String text) {
        if (text.length() <= MAX_CHUNK_LENGTH) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!current.isEmpty() && current.length() + word.length() + 1 > MAX_CHUNK_LENGTH) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }
}
