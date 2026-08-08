package com.rag.springai.insuranceai.pipeline;

import com.rag.springai.insuranceai.TestcontainersConfiguration;
import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentTopics;
import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentUploadedEvent;
import com.rag.springai.insuranceai.application.document.RegisterDocumentCommand;
import com.rag.springai.insuranceai.application.document.RegisterDocumentUseCase;
import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.ports.outbound.DocumentChunkRepository;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the real FASE 4 pipeline against Testcontainers-provided PostgreSQL and
 * Kafka (brief section 10 acceptance test): upload -&gt; SHA-256 -&gt; Kafka
 * ({@code insurance.document.uploaded}) -&gt; PDF extraction -&gt; cleaning -&gt; chunking -&gt;
 * persistence, exercising the real {@code @Service}/{@code @Component} beans, not test doubles.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DocumentIngestionPipelineIntegrationTest {

    @Autowired
    private RegisterDocumentUseCase registerDocumentUseCase;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void uploadingARealPdfEndsUpProcessedAndChunkedViaKafka() throws IOException {
        byte[] pdf = createPdf("Water damage is covered up to the policy limit of 5000 EUR.");
        RegisterDocumentCommand command = new RegisterDocumentCommand("Home Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "home-premium.pdf"), pdf,
                Instant.parse("2026-01-01T00:00:00Z"));

        Document registered = registerDocumentUseCase.register(command);
        DocumentVersionId versionId = registered.versions().get(0).id();

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Document reloaded = documentRepository.findById(registered.id()).orElseThrow();
            assertEquals(DocumentStatus.PROCESSED, reloaded.version(versionId).orElseThrow().status());
        });

        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentVersionId(versionId);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).content().value().replaceAll("\\s+", " ").contains("Water damage"));
    }

    @Test
    void reRegisteringIdenticalContentDoesNotCreateASecondDocumentOrReprocess() throws IOException {
        byte[] pdf = createPdf("Claims must be reported within 30 days of the incident.");
        RegisterDocumentCommand command = new RegisterDocumentCommand("Claims Management Procedure",
                DocumentType.CLAIMS_PROCEDURE,
                new DocumentMetadata(null, "ES", "en", DocumentClassification.INTERNAL, "claims-procedure.pdf"), pdf,
                Instant.parse("2026-01-01T00:00:00Z"));

        Document first = registerDocumentUseCase.register(command);
        DocumentVersionId versionId = first.versions().get(0).id();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Document reloaded = documentRepository.findById(first.id()).orElseThrow();
            assertEquals(DocumentStatus.PROCESSED, reloaded.version(versionId).orElseThrow().status());
        });
        int chunkCountAfterFirstUpload = documentChunkRepository.findByDocumentVersionId(versionId).size();

        Document second = registerDocumentUseCase.register(command);

        assertEquals(first.id(), second.id());
        assertEquals(1, second.versions().size(), "identical content must never create a second version");
        assertEquals(chunkCountAfterFirstUpload, documentChunkRepository.findByDocumentVersionId(versionId).size());
    }

    @Test
    void aRedeliveredKafkaUploadedEventForAnAlreadyProcessedVersionDoesNotDuplicateChunks() throws IOException {
        byte[] pdf = createPdf("Life insurance beneficiaries must be named explicitly.");
        RegisterDocumentCommand command = new RegisterDocumentCommand("Life Basic Policy", DocumentType.POLICY,
                new DocumentMetadata("life", "ES", "en", DocumentClassification.INTERNAL, "life-basic.pdf"), pdf,
                Instant.parse("2026-01-01T00:00:00Z"));
        Document registered = registerDocumentUseCase.register(command);
        DocumentVersionId versionId = registered.versions().get(0).id();

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Document reloaded = documentRepository.findById(registered.id()).orElseThrow();
            assertEquals(DocumentStatus.PROCESSED, reloaded.version(versionId).orElseThrow().status());
        });
        int chunkCountAfterFirstProcessing = documentChunkRepository.findByDocumentVersionId(versionId).size();
        ContentHash contentHash = registered.versions().get(0).contentHash();

        // Simulates Kafka's at-least-once redelivery of the exact same message.
        kafkaTemplate.send(DocumentTopics.DOCUMENT_UPLOADED, versionId.toString(),
                new DocumentUploadedEvent(registered.id().toString(), versionId.toString(), contentHash.value()));

        Awaitility.await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertEquals(chunkCountAfterFirstProcessing,
                        documentChunkRepository.findByDocumentVersionId(versionId).size(),
                        "a duplicate delivery of an already-processed event must not duplicate chunks"));
    }

    private byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText(text);
                contentStream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
