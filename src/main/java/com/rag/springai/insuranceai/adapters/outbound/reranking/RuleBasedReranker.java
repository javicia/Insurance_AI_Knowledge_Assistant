package com.rag.springai.insuranceai.adapters.outbound.reranking;

import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import com.rag.springai.insuranceai.ports.outbound.RerankerPort;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic, offline, non-ML {@link RerankerPort} implementation (brief FASE 6 section
 * 11): re-scores each fused candidate by adding a small keyword-coverage boost - the fraction of
 * the question's distinct keywords literally present in the candidate's content - on top of its
 * existing RRF {@code fusionScore}.
 *
 * <p><b>This is a PoC rule-based reranker, not a cross-encoder or any other ML reranking
 * model</b>, and must never be presented as one (brief section 11). It exists to make reranking
 * a real, observable, testable pipeline stage - one whose effect on candidate order can differ
 * from plain fusion order - without adding a new external model dependency in this phase. {@link
 * com.rag.springai.insuranceai.ports.outbound.RerankerPort} is designed so a future {@code
 * CrossEncoderReranker} adapter can replace this one without any change to {@code
 * HybridRetrievalService} (brief section 12).
 */
@Component
public class RuleBasedReranker implements RerankerPort {

    /** Weight applied to the [0,1] keyword-coverage signal before adding it to fusionScore. */
    private static final double KEYWORD_COVERAGE_BOOST_WEIGHT = 0.1;

    private static final Pattern WORD_PATTERN = Pattern.compile("[a-z0-9]+");

    @Override
    public List<HybridRetrievalResult> rerank(String question, List<HybridRetrievalResult> candidates,
            int finalTopK) {
        Set<String> questionKeywords = keywordsOf(question);

        return candidates.stream()
                .map(candidate -> candidate.withRerankerScore(
                        candidate.fusionScore() + KEYWORD_COVERAGE_BOOST_WEIGHT * keywordCoverage(questionKeywords,
                                candidate.content())))
                .sorted(Comparator.comparingDouble(HybridRetrievalResult::rerankerScore)
                        .reversed()
                        .thenComparing(result -> result.chunkId().toString()))
                .limit(finalTopK)
                .toList();
    }

    private double keywordCoverage(Set<String> questionKeywords, String content) {
        if (questionKeywords.isEmpty()) {
            return 0.0;
        }
        Set<String> contentKeywords = keywordsOf(content);
        long matched = questionKeywords.stream().filter(contentKeywords::contains).count();
        return (double) matched / questionKeywords.size();
    }

    private Set<String> keywordsOf(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            keywords.add(matcher.group());
        }
        return keywords;
    }
}
