package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import com.rag.springai.insuranceai.domain.rag.LexicalSearchResult;
import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;
import com.rag.springai.insuranceai.domain.rag.RetrievalOutcome;
import com.rag.springai.insuranceai.domain.rag.RetrievedChunk;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
import com.rag.springai.insuranceai.ports.outbound.LexicalSearchPort;
import com.rag.springai.insuranceai.ports.outbound.RerankerPort;
import com.rag.springai.insuranceai.ports.outbound.VectorSearchPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link HybridRetrievalService}'s own orchestration responsibility: fusing semantic and
 * lexical retrieval, degrading gracefully on partial failure, honoring reranking/query-expansion
 * toggles, and propagating the {@link RetrievalFilter} to both branches. Uses the real {@link
 * ScoreFusion}/{@link QueryExpander}/{@link ContextSelector} collaborators (cheap, deterministic,
 * no I/O) and mocks only the outbound ports.
 */
class HybridRetrievalServiceTest {

    private final EmbeddingModelPort embeddingModelPort = mock(EmbeddingModelPort.class);
    private final VectorSearchPort vectorSearchPort = mock(VectorSearchPort.class);
    private final LexicalSearchPort lexicalSearchPort = mock(LexicalSearchPort.class);
    private final RerankerPort rerankerPort = mock(RerankerPort.class);
    private final DocumentId documentId = DocumentId.generate();
    private final DocumentVersionId documentVersionId = DocumentVersionId.generate();

    private HybridRetrievalService serviceWith(boolean rerankingEnabled, boolean queryExpansionEnabled) {
        InsuranceAiProperties.Rag rag = new InsuranceAiProperties.Rag(
                new InsuranceAiProperties.Rag.Semantic(8, 0.75), new InsuranceAiProperties.Rag.Lexical(8, 0.0),
                new InsuranceAiProperties.Rag.Hybrid(20, 8, 60.0),
                new InsuranceAiProperties.Rag.Reranking(rerankingEnabled),
                new InsuranceAiProperties.Rag.QueryExpansion(queryExpansionEnabled, 3),
                new InsuranceAiProperties.Rag.Context(6000));
        InsuranceAiProperties properties = new InsuranceAiProperties(rag,
                new InsuranceAiProperties.Security(new InsuranceAiProperties.Security.PromptInjection(true),
                        new InsuranceAiProperties.Security.Pii(true),
                        new InsuranceAiProperties.Security.OAuth2("http://localhost:19999/realms/test")),
                new InsuranceAiProperties.Governance(new InsuranceAiProperties.Governance.Audit(true)),
                new InsuranceAiProperties.Ai(InsuranceAiProperties.SupportedAiProvider.FAKE),
                new InsuranceAiProperties.Evaluation(
                        new InsuranceAiProperties.Evaluation.Thresholds(1.0, 1.0, 0.75)));
        return new HybridRetrievalService(embeddingModelPort, vectorSearchPort, lexicalSearchPort, rerankerPort,
                new ScoreFusion(), new QueryExpander(), new ContextSelector(), properties,
                new SimpleMeterRegistry());
    }

    private DocumentChunkId chunkId(String suffix) {
        return new DocumentChunkId(UUID.nameUUIDFromBytes(suffix.getBytes()));
    }

    private RetrievedChunk semanticResult(String suffix, double score) {
        return new RetrievedChunk(chunkId(suffix), documentId, documentVersionId, "semantic content " + suffix,
                ChunkMetadata.empty(), score);
    }

    private LexicalSearchResult lexicalResult(String suffix, double score) {
        return new LexicalSearchResult(chunkId(suffix), documentId, documentVersionId, "lexical content " + suffix,
                ChunkMetadata.empty(), score);
    }

    @Test
    void fusesResultsFromBothBranchesWhenBothSucceed() {
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), any(Double.class), any()))
                .thenReturn(List.of(semanticResult("a", 0.9)));
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenReturn(List.of(lexicalResult("b", 0.5)));
        when(rerankerPort.rerank(any(), any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));

        HybridRetrievalOutcome outcome = serviceWith(true, false).retrieve("question", RetrievalFilter.none());

        assertEquals(RetrievalOutcome.HYBRID, outcome.diagnostics().outcome());
        assertEquals(2, outcome.finalCandidates().size());
    }

    @Test
    void semanticOnlySucceedsWhenLexicalReturnsNoMatches() {
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), any(Double.class), any()))
                .thenReturn(List.of(semanticResult("a", 0.9)));
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenReturn(List.of());
        when(rerankerPort.rerank(any(), any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));

        HybridRetrievalOutcome outcome = serviceWith(true, false).retrieve("question", RetrievalFilter.none());

        assertEquals(RetrievalOutcome.HYBRID, outcome.diagnostics().outcome(),
                "both branches executed successfully - HYBRID means both ran, not that both found candidates");
        assertEquals(1, outcome.finalCandidates().size());
        assertEquals(chunkId("a"), outcome.finalCandidates().get(0).chunkId());
    }

    @Test
    void lexicalOnlySucceedsWhenSemanticReturnsNoMatches() {
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), any(Double.class), any())).thenReturn(List.of());
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenReturn(List.of(lexicalResult("b", 0.5)));
        when(rerankerPort.rerank(any(), any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));

        HybridRetrievalOutcome outcome = serviceWith(true, false).retrieve("question", RetrievalFilter.none());

        assertEquals(1, outcome.finalCandidates().size());
        assertEquals(chunkId("b"), outcome.finalCandidates().get(0).chunkId());
    }

    @Test
    void degradesToSemanticOnlyWhenTheLexicalBranchThrows() {
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), any(Double.class), any()))
                .thenReturn(List.of(semanticResult("a", 0.9)));
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenThrow(new RuntimeException("lexical down"));
        when(rerankerPort.rerank(any(), any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));

        HybridRetrievalOutcome outcome = serviceWith(true, false).retrieve("question", RetrievalFilter.none());

        assertEquals(RetrievalOutcome.SEMANTIC_ONLY, outcome.diagnostics().outcome());
        assertEquals(1, outcome.finalCandidates().size());
    }

    @Test
    void degradesToLexicalOnlyWhenTheSemanticBranchThrows() {
        when(embeddingModelPort.embed(any())).thenThrow(new RuntimeException("embedding provider down"));
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenReturn(List.of(lexicalResult("b", 0.5)));
        when(rerankerPort.rerank(any(), any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));

        HybridRetrievalOutcome outcome = serviceWith(true, false).retrieve("question", RetrievalFilter.none());

        assertEquals(RetrievalOutcome.LEXICAL_ONLY, outcome.diagnostics().outcome());
        assertEquals(1, outcome.finalCandidates().size());
    }

    @Test
    void throwsWhenBothBranchesFailRatherThanSilentlyReturningNoAnswer() {
        when(embeddingModelPort.embed(any())).thenThrow(new RuntimeException("embedding provider down"));
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenThrow(new RuntimeException("lexical down"));

        assertThrows(TransientProcessingException.class,
                () -> serviceWith(true, false).retrieve("question", RetrievalFilter.none()));
    }

    @Test
    void skipsRerankingWhenDisabled() {
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), any(Double.class), any()))
                .thenReturn(List.of(semanticResult("a", 0.9)));
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenReturn(List.of());

        HybridRetrievalOutcome outcome = serviceWith(false, false).retrieve("question", RetrievalFilter.none());

        verify(rerankerPort, never()).rerank(any(), any(), anyInt());
        assertEquals(1, outcome.finalCandidates().size());
        assertEquals(null, outcome.finalCandidates().get(0).rerankerScore());
    }

    @Test
    void passesTheFilterThroughToBothBranches() {
        RetrievalFilter filter = new RetrievalFilter(documentId, null, null, null, null, null);
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), any(Double.class), any())).thenReturn(List.of());
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenReturn(List.of());

        serviceWith(true, false).retrieve("question", filter);

        verify(vectorSearchPort).search(any(), anyInt(), any(Double.class), eq(filter));
        verify(lexicalSearchPort).search(any(), anyInt(), eq(filter));
    }

    @Test
    void expandsTheLexicalQueryWithSynonymsWhenQueryExpansionIsEnabled() {
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), any(Double.class), any())).thenReturn(List.of());
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenReturn(List.of());
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);

        serviceWith(true, true).retrieve("burst pipe damage", RetrievalFilter.none());

        verify(lexicalSearchPort).search(queryCaptor.capture(), anyInt(), any());
        assertTrue(queryCaptor.getValue().contains(" OR "), "expanded query must OR in synonym terms");
    }

    @Test
    void doesNotExpandTheLexicalQueryWhenQueryExpansionIsDisabled() {
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), any(Double.class), any())).thenReturn(List.of());
        when(lexicalSearchPort.search(any(), anyInt(), any())).thenReturn(List.of());
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);

        serviceWith(true, false).retrieve("burst pipe damage", RetrievalFilter.none());

        verify(lexicalSearchPort).search(queryCaptor.capture(), anyInt(), any());
        assertEquals("burst pipe damage", queryCaptor.getValue());
    }
}
