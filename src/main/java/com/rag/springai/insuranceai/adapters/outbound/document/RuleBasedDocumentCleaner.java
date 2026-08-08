package com.rag.springai.insuranceai.adapters.outbound.document;

import com.rag.springai.insuranceai.domain.document.ExtractedDocument;
import com.rag.springai.insuranceai.domain.document.ExtractedPage;
import com.rag.springai.insuranceai.ports.outbound.DocumentCleaner;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * PoC-grade, rule-based text normalization: collapses repeated whitespace and blank lines and
 * trims each page. Deliberately not a machine-learning capability (brief section 14's guidance
 * against overstating PoC implementations) - header/footer/boilerplate removal is out of scope
 * for FASE 4.
 */
@Component
public class RuleBasedDocumentCleaner implements DocumentCleaner {

    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern REPEATED_BLANK_LINES = Pattern.compile("\\n{3,}");

    @Override
    public ExtractedDocument clean(ExtractedDocument extracted) {
        return new ExtractedDocument(extracted.pages().stream()
                .map(page -> new ExtractedPage(page.pageNumber(), cleanText(page.text())))
                .toList());
    }

    private String cleanText(String text) {
        String withoutRepeatedWhitespace = REPEATED_WHITESPACE.matcher(text).replaceAll(" ");
        String withoutRepeatedBlankLines = REPEATED_BLANK_LINES.matcher(withoutRepeatedWhitespace).replaceAll("\n\n");
        return withoutRepeatedBlankLines.strip();
    }
}
