package com.rag.springai.insuranceai.application.rag;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, bounded, optional query expansion (brief FASE 6 section 13/14): looks up each
 * word of the question in a small, explicit, hardcoded synonym map and returns at most {@code
 * maxExpandedTerms} additional terms. Disabled by default ({@code
 * insurance-ai.rag.query-expansion.enabled: false}).
 *
 * <p><b>Not an LLM call</b>, deliberately (brief section 14): an extra LLM round-trip per
 * question would add cost, latency, another failure surface and another prompt-injection
 * surface for a PoC-scale feature. This is why it is a plain application-layer class, not a
 * port - there is currently only one implementation and no external dependency to abstract over;
 * see {@code docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md} for the reasoning and how a
 * smarter (e.g. thesaurus-backed or LLM-backed) expander could be introduced later behind a port
 * if ever justified.
 *
 * <p><b>The synonym map below is a small, illustrative PoC placeholder</b> - not a
 * legally/actuarially reviewed insurance thesaurus (brief section 13 explicitly warns against
 * inventing uncontrolled synonyms). Expanded terms feed only the lexical (PostgreSQL full-text
 * search) branch, never the semantic embedding - see {@code docs/rag/HYBRID_SEARCH.md} for why:
 * real embeddings already generalize past exact wording, so stuffing a query string with
 * synonyms before embedding it risks distorting the vector rather than helping it.
 */
@Component
public class QueryExpander {

    private static final Pattern WORD_PATTERN = Pattern.compile("[a-z0-9]+");

    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "damage", List.of("harm", "loss"),
            "coverage", List.of("covered", "protection"),
            "burst", List.of("broken", "ruptured"),
            "pipe", List.of("plumbing"),
            "theft", List.of("burglary", "robbery"),
            "fire", List.of("flame", "combustion"),
            "flood", List.of("flooding", "inundation"),
            "claim", List.of("claims"));

    /**
     * Returns at most {@code maxExpandedTerms} distinct synonym terms for {@code question}'s
     * words, in a deterministic order; an empty list when {@code enabled} is {@code false},
     * {@code maxExpandedTerms <= 0}, or no word matches the synonym map. Never includes the
     * original question terms themselves - the caller (retrieval) already has those.
     */
    public List<String> expand(String question, boolean enabled, int maxExpandedTerms) {
        if (!enabled || maxExpandedTerms <= 0) {
            return List.of();
        }

        Set<String> expanded = new LinkedHashSet<>();
        Matcher matcher = WORD_PATTERN.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find() && expanded.size() < maxExpandedTerms) {
            for (String synonym : SYNONYMS.getOrDefault(matcher.group(), List.of())) {
                if (expanded.size() >= maxExpandedTerms) {
                    break;
                }
                expanded.add(synonym);
            }
        }
        return List.copyOf(expanded);
    }
}
