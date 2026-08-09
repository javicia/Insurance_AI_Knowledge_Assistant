package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.document.ChunkContent;
import com.rag.springai.insuranceai.domain.document.ChunkIndex;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.SimilarityMetric;
import com.rag.springai.insuranceai.ports.outbound.DocumentChunkRepository;
import com.rag.springai.insuranceai.ports.outbound.DocumentEventPublisher;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
import com.rag.springai.insuranceai.ports.outbound.VectorIndexPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbedDocumentVersionUseCaseTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentChunkRepository documentChunkRepository = mock(DocumentChunkRepository.class);
    private final EmbeddingModelPort embeddingModelPort = mock(EmbeddingModelPort.class);
    private final VectorIndexPort vectorIndexPort = mock(VectorIndexPort.class);
    private final DocumentEventPublisher documentEventPublisher = mock(DocumentEventPublisher.class);

    private final EmbedDocumentVersionUseCase useCase = new EmbedDocumentVersionUseCase(documentRepository,
            documentChunkRepository, embeddingModelPort, vectorIndexPort, documentEventPublisher);

    private Document processedDocument() {
        Document document = Document.register("Home Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "home-premium.pdf"));
        DocumentVersion version = DocumentVersion.upload(document.id(), VersionNumber.of(1, 0),
                ContentHash.of("content".getBytes()), EffectivePeriod.startingAt(Instant.parse("2026-01-01T00:00:00Z")));
        document.addVersion(version);
        version.startProcessing();
        version.markProcessed();
        return document;
    }

    @Test
    void embedsEveryChunkAndTransitionsTheVersionToEmbedded() {
        Document document = processedDocument();
        DocumentVersion version = document.versions().get(0);
        DocumentChunk chunk = DocumentChunk.create(document.id(), version.id(), new ChunkIndex(0),
                new ChunkContent("chunk text"), ChunkMetadata.empty());

        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(documentChunkRepository.findByDocumentVersionId(version.id())).thenReturn(List.of(chunk));
        EmbeddingModelDescriptor descriptor = new EmbeddingModelDescriptor("openai", "text-embedding-3-small", null,
                1536, SimilarityMetric.COSINE);
        when(embeddingModelPort.descriptor()).thenReturn(descriptor);
        when(embeddingModelPort.embed("chunk text")).thenReturn(new EmbeddingVector(List.of(0.1f, 0.2f)));

        useCase.embed(new EmbedDocumentVersionCommand(document.id(), version.id()));

        assertEquals(DocumentStatus.EMBEDDED, version.status());
        verify(vectorIndexPort).index(eq(chunk), any(), eq(descriptor));
        verify(documentEventPublisher).publishDocumentEmbedded(document.id(), version.id(), 1);
    }

    @Test
    void aRedeliveredEventForAnAlreadyEmbeddedVersionIsANoOp() {
        Document document = processedDocument();
        DocumentVersion version = document.versions().get(0);
        version.markEmbedded();
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));

        useCase.embed(new EmbedDocumentVersionCommand(document.id(), version.id()));

        verify(documentChunkRepository, never()).findByDocumentVersionId(any());
        verify(documentEventPublisher, never()).publishDocumentEmbedded(any(), any(), anyInt());
    }

    @Test
    void aVersionWithNoChunksIsMarkedFailedRatherThanEmbedded() {
        Document document = processedDocument();
        DocumentVersion version = document.versions().get(0);
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(documentChunkRepository.findByDocumentVersionId(version.id())).thenReturn(List.of());

        useCase.embed(new EmbedDocumentVersionCommand(document.id(), version.id()));

        assertEquals(DocumentStatus.FAILED, version.status());
        verify(documentEventPublisher, never()).publishDocumentEmbedded(any(), any(), anyInt());
    }

    @Test
    void embedsAllChunksWhenThereAreSeveral() {
        Document document = processedDocument();
        DocumentVersion version = document.versions().get(0);
        DocumentChunk chunk1 = DocumentChunk.create(document.id(), version.id(), new ChunkIndex(0),
                new ChunkContent("first"), ChunkMetadata.empty());
        DocumentChunk chunk2 = DocumentChunk.create(document.id(), version.id(), new ChunkIndex(1),
                new ChunkContent("second"), ChunkMetadata.empty());

        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(documentChunkRepository.findByDocumentVersionId(version.id())).thenReturn(List.of(chunk1, chunk2));
        when(embeddingModelPort.descriptor()).thenReturn(
                new EmbeddingModelDescriptor("openai", "text-embedding-3-small", null, 1536, SimilarityMetric.COSINE));
        when(embeddingModelPort.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f)));

        useCase.embed(new EmbedDocumentVersionCommand(document.id(), version.id()));

        verify(vectorIndexPort, times(2)).index(any(), any(), any());
        verify(documentEventPublisher).publishDocumentEmbedded(document.id(), version.id(), 2);
    }
}
