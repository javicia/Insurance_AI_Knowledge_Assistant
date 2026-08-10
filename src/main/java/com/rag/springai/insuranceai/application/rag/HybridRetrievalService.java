package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import com.rag.springai.insuranceai.domain.rag.LexicalSearchResult;
import com.rag.springai.insuranceai.domain.rag.RetrievalDiagnostics;
import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;
import com.rag.springai.insuranceai.domain.rag.RetrievalOutcome;
import com.rag.springai.insuranceai.domain.rag.RetrievedChunk;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
import com.rag.springai.insuranceai.ports.outbound.LexicalSearchPort;
import com.rag.springai.insuranceai.ports.outbound.RerankerPort;
import com.rag.springai.insuranceai.ports.outbound.VectorSearchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the Advanced RAG retrieval pipeline (brief FASE 6 section 49): query expansion,
 * parallel semantic + lexical retrieval, RRF fusion, candidate pooling, reranking and context
 * selection. Kept separate from {@code AskInsuranceKnowledgeUseCase} so that class stays focused
 * on the top-level "no evidence -&gt; no LLM call, otherwise build prompt and answer" flow
 * (brief section 17: don't mix retrieval, prompt construction and LLM invocation in one class).
 *
 * <p><b>Parallelism</b> (brief section 28): semantic and lexical retrieval are independent, so
 * they run concurrently via two {@link CompletableFuture}s on the common pool - no custom
 * executor or reactive stack, matching the brief's "correctness/clarity over micro-optimization"
 * guidance.
 *
 * <p><b>Partial failure</b> (brief section 29/30): if one branch's future fails, retrieval
 * degrades to the other branch alone ({@link RetrievalOutcome#SEMANTIC_ONLY}/{@link
 * RetrievalOutcome#LEXICAL_ONLY}) rather than failing the whole request. If <em>both</em> fail,
 * this throws {@link TransientProcessingException} - a real infrastructure outage must not be
 * silently reinterpreted as "no relevant documentation found" (a valid, very different, 200 OK
 * no-answer outcome).
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final EmbeddingModelPort embeddingModelPort;
    private final VectorSearchPort vectorSearchPort;
    private final LexicalSearchPort lexicalSearchPort;
    private final RerankerPort rerankerPort;
    private final ScoreFusion scoreFusion;
    private final QueryExpander queryExpander;
    private final ContextSelector contextSelector;
    private final InsuranceAiProperties properties;

    public HybridRetrievalService(EmbeddingModelPort embeddingModelPort, VectorSearchPort vectorSearchPort,
            LexicalSearchPort lexicalSearchPort, RerankerPort rerankerPort, ScoreFusion scoreFusion,
            QueryExpander queryExpander, ContextSelector contextSelector, InsuranceAiProperties properties) {
        this.embeddingModelPort = Objects.requireNonNull(embeddingModelPort, "embeddingModelPort must not be null");
        this.vectorSearchPort = Objects.requireNonNull(vectorSearchPort, "vectorSearchPort must not be null");
        this.lexicalSearchPort = Objects.requireNonNull(lexicalSearchPort, "lexicalSearchPort must not be null");
        this.rerankerPort = Objects.requireNonNull(rerankerPort, "rerankerPort must not be null");
        this.scoreFusion = Objects.requireNonNull(scoreFusion, "scoreFusion must not be null");
        this.queryExpander = Objects.requireNonNull(queryExpander, "queryExpander must not be null");
        this.contextSelector = Objects.requireNonNull(contextSelector, "contextSelector must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public HybridRetrievalOutcome retrieve(String question, RetrievalFilter filter) {
        InsuranceAiProperties.Rag config = properties.rag();

        List<String> expandedTerms = queryExpander.expand(question, config.queryExpansion().enabled(),
                config.queryExpansion().maxExpandedTerms());
        String lexicalQuery = expandedTerms.isEmpty() ? question
                : question + " OR " + String.join(" OR ", expandedTerms);

        CompletableFuture<BranchResult<RetrievedChunk>> semanticFuture = CompletableFuture
                .supplyAsync(() -> {
                    EmbeddingVector questionEmbedding = embeddingModelPort.embed(question);
                    return vectorSearchPort.search(questionEmbedding, config.semantic().topK(),
                            config.semantic().similarityThreshold(), filter);
                })
                .handle((results, error) -> toBranchResult("semantic", results, error));

        CompletableFuture<BranchResult<LexicalSearchResult>> lexicalFuture = CompletableFuture
                .supplyAsync(() -> lexicalSearchPort.search(lexicalQuery, config.lexical().topK(), filter))
                .handle((results, error) -> toBranchResult("lexical", results, error));

        BranchResult<RetrievedChunk> semantic = semanticFuture.join();
        BranchResult<LexicalSearchResult> lexical = lexicalFuture.join();

        if (!semantic.succeeded() && !lexical.succeeded()) {
            throw new TransientProcessingException("HYBRID_RETRIEVAL_FAILED",
                    "Both semantic and lexical retrieval failed", semantic.error());
        }

        RetrievalOutcome outcome = retrievalOutcome(semantic.succeeded(), lexical.succeeded());
        List<RetrievedChunk> semanticResults = semantic.succeeded() ? semantic.results() : List.of();
        List<LexicalSearchResult> lexicalResults = lexical.succeeded() ? lexical.results() : List.of();

        List<HybridRetrievalResult> fused = scoreFusion.fuse(semanticResults, lexicalResults, config.hybrid().rrfK());
        List<HybridRetrievalResult> candidatePool = fused.stream().limit(config.hybrid().candidatePoolSize()).toList();

        List<HybridRetrievalResult> ranked = (config.reranking().enabled() && !candidatePool.isEmpty())
                ? rerankerPort.rerank(question, candidatePool, config.hybrid().finalTopK())
                : candidatePool.stream().limit(config.hybrid().finalTopK()).toList();

        List<HybridRetrievalResult> finalCandidates = contextSelector.select(ranked, config.context().maxCharacters());

        RetrievalDiagnostics diagnostics = new RetrievalDiagnostics(outcome, semanticResults.size(),
                lexicalResults.size(), fused.size(), ranked.size(), finalCandidates.size());
        log.info(
                "Hybrid retrieval diagnostics: outcome={} semanticCandidates={} lexicalCandidates={} "
                        + "fusedCandidates={} rerankedCandidates={} finalCandidates={}",
                diagnostics.outcome(), diagnostics.semanticCandidateCount(), diagnostics.lexicalCandidateCount(),
                diagnostics.fusedCandidateCount(), diagnostics.rerankedCandidateCount(),
                diagnostics.finalCandidateCount());

        return new HybridRetrievalOutcome(finalCandidates, diagnostics);
    }

    private static RetrievalOutcome retrievalOutcome(boolean semanticSucceeded, boolean lexicalSucceeded) {
        if (semanticSucceeded && lexicalSucceeded) {
            return RetrievalOutcome.HYBRID;
        }
        return semanticSucceeded ? RetrievalOutcome.SEMANTIC_ONLY : RetrievalOutcome.LEXICAL_ONLY;
    }

    private static <T> BranchResult<T> toBranchResult(String branchName, List<T> results, Throwable error) {
        if (error != null) {
            log.warn("{} retrieval branch failed - degrading to the other branch if it succeeded", branchName,
                    error);
            return new BranchResult<>(null, error);
        }
        return new BranchResult<>(results, null);
    }

    private record BranchResult<T>(List<T> results, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }
}
