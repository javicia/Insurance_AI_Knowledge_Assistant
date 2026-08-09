package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.document.ChunkContent;
import com.rag.springai.insuranceai.domain.document.ChunkIndex;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.RetrievedChunk;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import com.rag.springai.insuranceai.ports.outbound.VectorSearchPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AskInsuranceKnowledgeUseCaseTest {

    private final EmbeddingModelPort embeddingModelPort = mock(EmbeddingModelPort.class);
    private final VectorSearchPort vectorSearchPort = mock(VectorSearchPort.class);
    private final LlmProvider llmProvider = mock(LlmProvider.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);

    private final AskInsuranceKnowledgeUseCase useCase = new AskInsuranceKnowledgeUseCase(embeddingModelPort,
            vectorSearchPort, llmProvider, documentRepository, 8, 0.75);

    private Document homePremiumPolicy() {
        Document document = Document.register("Home Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "home-premium.pdf"));
        DocumentVersion version = DocumentVersion.upload(document.id(), VersionNumber.of(3, 2),
                ContentHash.of("home premium content".getBytes()),
                EffectivePeriod.startingAt(Instant.parse("2026-01-01T00:00:00Z")));
        document.addVersion(version);
        return document;
    }

    private RetrievedChunk chunkOf(Document document, String content, int page, String section) {
        DocumentVersion version = document.versions().get(0);
        DocumentChunk chunk = DocumentChunk.create(document.id(), version.id(), new ChunkIndex(0),
                new ChunkContent(content), new ChunkMetadata(page, null, section, 1));
        return new RetrievedChunk(chunk.id(), chunk.documentId(), chunk.documentVersionId(), content,
                chunk.metadata(), 0.9);
    }

    @Test
    void relevantQuestionReturnsAGroundedAnswerWithCitations() {
        Document document = homePremiumPolicy();
        RetrievedChunk chunk = chunkOf(document, "Water damage is covered up to 5000 EUR.", 37,
                "7.2 Water Damage Coverage");

        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f, 0.2f)));
        when(vectorSearchPort.search(any(), anyInt(), anyDouble())).thenReturn(List.of(chunk));
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
        assertEquals(chunk.chunkId().toString(), source.chunkId());
    }

    @Test
    void multipleRelevantChunksAllBecomeSources() {
        Document document = homePremiumPolicy();
        RetrievedChunk chunk1 = chunkOf(document, "Water damage covered.", 37, "7.2");
        RetrievedChunk chunk2 = chunkOf(document, "Exclusions apply for gradual leaks.", 38, "7.3");

        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), anyDouble())).thenReturn(List.of(chunk1, chunk2));
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(llmProvider.complete(any())).thenReturn(new LlmCompletion("answer"));

        RagAnswer answer = useCase.ask(new AskInsuranceKnowledgeCommand("question", TraceId.generate()));

        assertEquals(2, answer.sources().size());
    }

    @Test
    void noRelevantContextReturnsNoAnswerWithoutCallingTheLlm() {
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), anyDouble())).thenReturn(List.of());

        RagAnswer answer = useCase.ask(new AskInsuranceKnowledgeCommand("unrelated question", TraceId.generate()));

        assertEquals(GroundingStatus.NOT_GROUNDED, answer.grounding().status());
        assertTrue(answer.sources().isEmpty());
        assertTrue(answer.answer().contains("do not have sufficient information"));
        verify(llmProvider, never()).complete(any());
    }

    @Test
    void passesTheTraceIdThrough() {
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));
        when(vectorSearchPort.search(any(), anyInt(), anyDouble())).thenReturn(List.of());
        TraceId traceId = TraceId.generate();

        RagAnswer answer = useCase.ask(new AskInsuranceKnowledgeCommand("question", traceId));

        assertEquals(traceId.value(), answer.traceId());
    }
}
