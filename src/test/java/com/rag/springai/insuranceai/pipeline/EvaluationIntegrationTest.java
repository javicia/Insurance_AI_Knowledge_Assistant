package com.rag.springai.insuranceai.pipeline;

import com.rag.springai.insuranceai.DatabaseCleanupExtension;
import com.rag.springai.insuranceai.TestcontainersConfiguration;
import com.rag.springai.insuranceai.application.document.RegisterDocumentCommand;
import com.rag.springai.insuranceai.application.document.RegisterDocumentUseCase;
import com.rag.springai.insuranceai.application.evaluation.EvaluationRunnerService;
import com.rag.springai.insuranceai.application.evaluation.InsuranceEvaluationDataset;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRun;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunId;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunStatus;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end AI Evaluation test (brief FASE 10 section 10) against real Testcontainers-provided
 * PostgreSQL: ingests the four documents {@code InsuranceEvaluationDataset}'s in-scope cases
 * expect, then runs the built-in dataset through the real {@code AskInsuranceKnowledgeUseCase} -
 * proving the evaluation runner measures the actual pipeline, not a mocked one, and that a run is
 * genuinely persisted and re-readable.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(DatabaseCleanupExtension.class)
class EvaluationIntegrationTest {

    @Autowired
    private RegisterDocumentUseCase registerDocumentUseCase;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private EvaluationRunnerService evaluationRunnerService;

    @Test
    void runningTheBuiltInDatasetAgainstItsMatchingDocumentsProducesAPassedRunWithPerfectRecall() throws IOException {
        ingest("Home Insurance Policy",
                "Water damage caused by a burst pipe is covered up to the policy limit of 5000 EUR.");
        ingest("Policy Exclusions",
                "Flood damage from a natural disaster is excluded from coverage under this policy.");
        ingest("Claims Procedure",
                "Claims must be submitted within 30 days of the incident, together with the official claim form.");
        ingest("Travel Insurance Policy", "Travel medical coverage begins only after a waiting period of 14 days "
                + "from the policy start date.");

        EvaluationRun run = evaluationRunnerService.runBuiltInDataset();

        assertEquals(InsuranceEvaluationDataset.NAME, run.datasetName());
        assertEquals(InsuranceEvaluationDataset.CASES.size(), run.results().size());
        assertEquals(1.0, run.metrics().groundingRate(), "every in-scope case must be answered");
        assertEquals(1.0, run.metrics().noAnswerAccuracy(), "every out-of-scope case must be refused");
        assertEquals(1.0, run.metrics().recallAtK(), "every grounded case must cite the one expected document");
        assertEquals(EvaluationRunStatus.PASSED, run.status());

        Optional<EvaluationRun> reloaded = evaluationRunnerService.findById(run.id());
        assertTrue(reloaded.isPresent(), "a completed run must be genuinely persisted and re-readable");
        assertEquals(run.id(), reloaded.get().id());
        assertEquals(run.results().size(), reloaded.get().results().size());
    }

    @Test
    void findRecentReturnsAtLeastTheJustPersistedRun() {
        EvaluationRunId id = evaluationRunnerService.run("empty-smoke-dataset", java.util.List.of()).id();

        assertTrue(evaluationRunnerService.findRecent(10).stream().anyMatch(run -> run.id().equals(id)));
    }

    private void ingest(String documentName, String content) throws IOException {
        byte[] pdf = createPdf(content);
        RegisterDocumentCommand command = new RegisterDocumentCommand(documentName, DocumentType.POLICY,
                new DocumentMetadata("evaluation", "ES", "en", DocumentClassification.INTERNAL,
                        documentName.toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + ".pdf"),
                pdf, Instant.parse("2026-01-01T00:00:00Z"));
        Document registered = registerDocumentUseCase.register(command);
        DocumentVersionId versionId = registered.versions().get(0).id();

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Document reloaded = documentRepository.findById(registered.id()).orElseThrow();
            assertEquals(DocumentStatus.EMBEDDED, reloaded.version(versionId).orElseThrow().status());
        });
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
