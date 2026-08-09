package com.rag.springai.insuranceai.pipeline;

import com.rag.springai.insuranceai.TestcontainersConfiguration;
import com.rag.springai.insuranceai.application.document.RegisterDocumentCommand;
import com.rag.springai.insuranceai.application.document.RegisterDocumentUseCase;
import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeCommand;
import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeUseCase;
import com.rag.springai.insuranceai.application.rag.GroundingStatus;
import com.rag.springai.insuranceai.application.rag.RagAnswer;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.shared.TraceId;
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
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Basic RAG test (brief section 21) against real Testcontainers-provided PostgreSQL
 * and Kafka, using {@code insurance-ai.ai.provider: fake} - deterministic, offline embedding
 * and LLM adapters (brief section 16/17/18: proves the pipeline mechanics, not a real OpenAI or
 * Anthropic call. See the FASE 5 report for a separate, honest statement of what manual
 * validation against real providers did or did not cover.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = { "insurance-ai.ai.provider=fake",
        // The fake embedding model is a crude word-overlap heuristic (brief section 17/18), not a
        // real semantic embedding: even a clearly on-topic passage/question pair only reaches
        // ~0.60 cosine similarity under it. insurance-ai.rag.similarity-threshold=0.75 is tuned for
        // real embeddings (see RAG_DESIGN.md section 4) and would reject that pair here, which
        // would test the fake adapter's vocabulary limits rather than the pipeline mechanics this
        // test exists to prove (brief section 21). Lowered only for this test, not production.
        "insurance-ai.rag.similarity-threshold=0.5" })
class RagPipelineIntegrationTest {

    @Autowired
    private RegisterDocumentUseCase registerDocumentUseCase;

    @Autowired
    private AskInsuranceKnowledgeUseCase askInsuranceKnowledgeUseCase;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void fullPipelineFromPdfUploadToGroundedAnswerWithCitations() throws IOException {
        byte[] pdf = createPdf("Water damage caused by a burst pipe is covered up to the policy limit of 5000 EUR.");
        RegisterDocumentCommand command = new RegisterDocumentCommand("Home Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "home-premium.pdf"), pdf,
                Instant.parse("2026-01-01T00:00:00Z"));

        Document registered = registerDocumentUseCase.register(command);
        DocumentVersionId versionId = registered.versions().get(0).id();

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Document reloaded = documentRepository.findById(registered.id()).orElseThrow();
            assertEquals(DocumentStatus.EMBEDDED, reloaded.version(versionId).orElseThrow().status());
        });

        RagAnswer answer = askInsuranceKnowledgeUseCase
                .ask(new AskInsuranceKnowledgeCommand("Is water damage from a burst pipe covered?", TraceId.generate()));

        assertEquals(GroundingStatus.GROUNDED, answer.grounding().status());
        assertFalse(answer.sources().isEmpty());
        assertEquals("Home Premium Policy", answer.sources().get(0).document());
        assertEquals("1.0", answer.sources().get(0).version());
        assertTrue(answer.answer().contains("burst pipe"),
                "the fake LLM adapter echoes the retrieved passage, proving real retrieved content reached the LLM step");
    }

    @Test
    void unrelatedQuestionProducesTheNoAnswerResponseWithoutAnyCitation() throws IOException {
        byte[] pdf = createPdf("Life insurance beneficiaries must be named explicitly in the policy documents.");
        RegisterDocumentCommand command = new RegisterDocumentCommand("Life Basic Policy", DocumentType.POLICY,
                new DocumentMetadata("life", "ES", "en", DocumentClassification.INTERNAL, "life-basic.pdf"), pdf,
                Instant.parse("2026-01-01T00:00:00Z"));
        Document registered = registerDocumentUseCase.register(command);
        DocumentVersionId versionId = registered.versions().get(0).id();

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Document reloaded = documentRepository.findById(registered.id()).orElseThrow();
            assertEquals(DocumentStatus.EMBEDDED, reloaded.version(versionId).orElseThrow().status());
        });

        RagAnswer answer = askInsuranceKnowledgeUseCase.ask(new AskInsuranceKnowledgeCommand(
                "What is the maximum altitude a commercial airliner can safely cruise at?", TraceId.generate()));

        assertEquals(GroundingStatus.NOT_GROUNDED, answer.grounding().status());
        assertTrue(answer.sources().isEmpty());
        assertTrue(answer.answer().contains("do not have sufficient information"));
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
