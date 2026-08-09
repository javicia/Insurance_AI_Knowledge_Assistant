package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.document.ChunkContent;
import com.rag.springai.insuranceai.domain.document.ChunkIndex;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.RetrievalDiagnostics;
import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;
import com.rag.springai.insuranceai.domain.rag.RetrievalOutcome;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.infrastructure.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code AskInsuranceKnowledgeUseCase}'s own responsibility only (brief FASE 6
 * section 17): the no-answer policy, citation building and LLM invocation - {@link
 * HybridRetrievalService} is mocked here. Pipeline orchestration (semantic/lexical fusion,
 * reranking, query expansion, partial failure) is covered separately by {@code
 * HybridRetrievalServiceTest}.
 */
class AskInsuranceKnowledgeUseCaseTest {

    private static final double SEMANTIC_THRESHOLD = 0.75;

    private final HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
    private final LlmProvider llmProvider = mock(LlmProvider.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final InsuranceAiProperties properties = properties(SEMANTIC_THRESHOLD);

    private final AskInsuranceKnowledgeUseCase useCase = new AskInsuranceKnowledgeUseCase(hybridRetrievalService,
            llmProvider, documentRepository, properties);

    private static InsuranceAiProperties properties(double semanticThreshold) {
        InsuranceAiProperties.Rag rag = new InsuranceAiProperties.Rag(
                new InsuranceAiProperties.Rag.Semantic(8, semanticThreshold), new InsuranceAiProperties.Rag.Lexical(8),
                new InsuranceAiProperties.Rag.Hybrid(20, 8, 60.0), new InsuranceAiProperties.Rag.Reranking(true),
                new InsuranceAiProperties.Rag.QueryExpansion(false, 3), new InsuranceAiProperties.Rag.Context(6000));
        return new InsuranceAiProperties(rag,
                new InsuranceAiProperties.Security(new InsuranceAiProperties.Security.PromptInjection(true),
                        new InsuranceAiProperties.Security.Pii(true)),
                new InsuranceAiProperties.Governance(new InsuranceAiProperties.Governance.Audit(true)),
                new InsuranceAiProperties.Ai(InsuranceAiProperties.SupportedAiProvider.FAKE));
    }

    private Document homePremiumPolicy() {
        Document document = Document.register("Home Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "home-premium.pdf"));
        DocumentVersion version = DocumentVersion.upload(document.id(), VersionNumber.of(3, 2),
                ContentHash.of("home premium content".getBytes()),
                EffectivePeriod.startingAt(Instant.parse("2026-01-01T00:00:00Z")));
        document.addVersion(version);
        return document;
    }

    private HybridRetrievalResult candidateOf(Document document, String content, int page, String section,
            Double semanticScore, Double lexicalScore) {
        DocumentVersion version = document.versions().get(0);
        DocumentChunk chunk = DocumentChunk.create(document.id(), version.id(), new ChunkIndex(0),
                new ChunkContent(content), new ChunkMetadata(page, null, section, 1));
        Integer semanticRank = semanticScore != null ? 1 : null;
        Integer lexicalRank = lexicalScore != null ? 1 : null;
        return new HybridRetrievalResult(chunk.id(), chunk.documentId(), chunk.documentVersionId(), content,
                chunk.metadata(), semanticScore, semanticRank, lexicalScore, lexicalRank, 0.5, null);
    }

    private HybridRetrievalOutcome outcomeOf(RetrievalOutcome retrievalOutcome, HybridRetrievalResult... candidates) {
        List<HybridRetrievalResult> list = List.of(candidates);
        return new HybridRetrievalOutcome(list,
                new RetrievalDiagnostics(retrievalOutcome, list.size(), list.size(), list.size(), list.size(),
                        list.size()));
    }

    @Test
    void relevantQuestionReturnsAGroundedAnswerWithCitations() {
        Document document = homePremiumPolicy();
        HybridRetrievalResult candidate = candidateOf(document, "Water damage is covered up to 5000 EUR.", 37,
                "7.2 Water Damage Coverage", 0.9, null);

        when(hybridRetrievalService.retrieve(any(), any())).thenReturn(outcomeOf(RetrievalOutcome.HYBRID, candidate));
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(llmProvider.complete(any())).thenReturn(new LlmCompletion("Water damage is covered."));

        RagAnswer answer = useCase.ask(new AskInsuranceKnowledgeCommand("What coverage for water damage?",
                TraceId.generate()));

        assertEquals("Water damage is covered.", answer.answer());
        assertEquals(GroundingStatus.GROUNDED, answer.grounding().status());
        assertEquals(1, answer.sources().size());
        SourceReference source = answer.sources().get(0);
        assertEquals("Home Premium Policy", source.document());
        assertEquals("3.2", source.version());
        assertEquals(37, source.page());
        assertEquals("7.2 Water Damage Coverage", source.section());
        assertEquals(candidate.chunkId().toString(), source.chunkId());
    }

    @Test
    void multipleRelevantChunksAllBecomeSources() {
        Document document = homePremiumPolicy();
        HybridRetrievalResult candidate1 = candidateOf(document, "Water damage covered.", 37, "7.2", 0.9, null);
        HybridRetrievalResult candidate2 = candidateOf(document, "Exclusions apply for gradual leaks.", 38, "7.3", 0.8,
                null);

        when(hybridRetrievalService.retrieve(any(), any()))
                .thenReturn(outcomeOf(RetrievalOutcome.HYBRID, candidate1, candidate2));
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(llmProvider.complete(any())).thenReturn(new LlmCompletion("answer"));

        RagAnswer answer = useCase.ask(new AskInsuranceKnowledgeCommand("question", TraceId.generate()));

        assertEquals(2, answer.sources().size());
    }

    @Test
    void aLexicalOnlyMatchIsSufficientForGrounding() {
        Document document = homePremiumPolicy();
        HybridRetrievalResult candidate = candidateOf(document, "Theft coverage excludes unattended vehicles.", 12,
                "9.1", null, 0.42);

        when(hybridRetrievalService.retrieve(any(), any())).thenReturn(outcomeOf(RetrievalOutcome.LEXICAL_ONLY, candidate));
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(llmProvider.complete(any())).thenReturn(new LlmCompletion("answer"));

        RagAnswer answer = useCase.ask(new AskInsuranceKnowledgeCommand("theft coverage", TraceId.generate()));

        assertEquals(GroundingStatus.GROUNDED, answer.grounding().status());
    }

    @Test
    void aSemanticCandidateBelowTheThresholdAloneDoesNotGround() {
        Document document = homePremiumPolicy();
        // Below SEMANTIC_THRESHOLD (0.75) and no lexical match - must not ground, even though a
        // candidate technically exists after fusion/reranking (brief section 21/23).
        HybridRetrievalResult candidate = candidateOf(document, "Loosely related passage.", 1, null, 0.5, null);

        when(hybridRetrievalService.retrieve(any(), any())).thenReturn(outcomeOf(RetrievalOutcome.SEMANTIC_ONLY, candidate));

        RagAnswer answer = useCase.ask(new AskInsuranceKnowledgeCommand("unrelated question", TraceId.generate()));

        assertEquals(GroundingStatus.NOT_GROUNDED, answer.grounding().status());
        assertTrue(answer.sources().isEmpty());
        verify(llmProvider, never()).complete(any());
    }

    @Test
    void noRelevantContextReturnsNoAnswerWithoutCallingTheLlm() {
        when(hybridRetrievalService.retrieve(any(), any())).thenReturn(outcomeOf(RetrievalOutcome.HYBRID));

        RagAnswer answer = useCase.ask(new AskInsuranceKnowledgeCommand("unrelated question", TraceId.generate()));

        assertEquals(GroundingStatus.NOT_GROUNDED, answer.grounding().status());
        assertTrue(answer.sources().isEmpty());
        assertTrue(answer.answer().contains("do not have sufficient information"));
        verify(llmProvider, never()).complete(any());
    }

    @Test
    void passesTheTraceIdThrough() {
        when(hybridRetrievalService.retrieve(any(), any())).thenReturn(outcomeOf(RetrievalOutcome.HYBRID));
        TraceId traceId = TraceId.generate();

        RagAnswer answer = useCase.ask(new AskInsuranceKnowledgeCommand("question", traceId));

        assertEquals(traceId.value(), answer.traceId());
    }

    @Test
    void passesTheFilterThroughToHybridRetrieval() {
        RetrievalFilter filter = new RetrievalFilter(null, null, DocumentType.POLICY, null, null, null);
        when(hybridRetrievalService.retrieve(any(), any())).thenReturn(outcomeOf(RetrievalOutcome.HYBRID));

        useCase.ask(new AskInsuranceKnowledgeCommand("question", filter, TraceId.generate()));

        verify(hybridRetrievalService).retrieve("question", filter);
    }
}
