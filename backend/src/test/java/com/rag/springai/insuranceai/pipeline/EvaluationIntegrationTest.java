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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end AI Evaluation test (brief FASE 10 section 10) against real Testcontainers-provided
 * PostgreSQL: ingests the 34-document corpus {@code InsuranceEvaluationDataset}'s in-scope cases
 * expect, then runs the built-in 106-case dataset through the real {@code
 * AskInsuranceKnowledgeUseCase} - proving the evaluation runner measures the actual pipeline, not
 * a mocked one, and that a run is genuinely persisted and re-readable.
 *
 * <p><b>Why the corpus is written the way it is.</b> Every {@code @SpringBootTest} runs with
 * {@code insurance-ai.ai.provider: fake}, so retrieval here is driven by {@code
 * FakeEmbeddingModelAdapter} - a feature-hashing bag-of-words vector, not a real embedding model
 * (see its Javadoc). Cosine similarity therefore reduces to plain <em>vocabulary overlap</em>,
 * which imposes two hard design rules on the documents below:
 *
 * <ul>
 * <li>Each document owns a <b>distinctive topical vocabulary</b> (ransomware/forensic for cyber,
 * windscreen/bodywork for collision, piste/avalanche for winter sports, ...) that no other
 * document reuses. Two documents sharing a distinctive term would let a question about one cite
 * the other, and {@code recallAtK} would drop even though the answer still looked reasonable.</li>
 * <li>Each document stays <b>short</b> (two to three sentences) and avoids piling up repeated
 * stopwords. Cosine over an L2-normalized bag of words falls as a document lengthens, and a
 * document repeating "the" four times becomes a magnet that any stopword-heavy out-of-scope
 * question partially matches - which would silently break the {@code NO_ANSWER} cases rather than
 * the {@code GROUNDED} ones.</li>
 * </ul>
 *
 * <p>This is a property of the offline test provider, not of the production pipeline: with real
 * embeddings the same dataset would be scored on meaning rather than on shared tokens. The
 * production {@code insurance-ai.rag.semantic.similarity-threshold} of {@code 0.75} is untouched -
 * only {@code application-test.yaml} lowers it to {@code 0.5}, for the fake provider's own
 * similarity distribution.
 *
 * <p>The assertions below are deliberately absolute (1.0 grounding rate, 1.0 no-answer accuracy,
 * 1.0 recall@k). A dataset that needed a relaxed bar to pass would measure nothing.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(DatabaseCleanupExtension.class)
class EvaluationIntegrationTest {

    private static final float FONT_SIZE = 12f;
    private static final float LEADING = 14f;
    private static final float LEFT_MARGIN = 50f;

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
                "Claims must be submitted within 30 days of the incident, together with the official "
                + "claim form.");
        ingest("Travel Insurance Policy",
                "Travel medical coverage begins only after a waiting period of 14 days from the policy "
                + "start date.");
        ingest("Motor Collision Cover",
                "Collision cover repairs the bodywork of a car after a road accident. A cracked "
                + "windscreen is replaced by an approved garage at no extra charge.");
        ingest("Motor Excess Schedule",
                "Compulsory excess is 350 EUR on every motor repair invoice. A voluntary excess of 200 "
                + "EUR may be chosen by any driver when a vehicle is first insured.");
        ingest("Cyber Liability Addendum",
                "Ransomware attacks are covered by the cyber liability addendum, including forensic "
                + "investigation and data breach notification. The addendum pays the cost of restoring "
                + "encrypted files.");
        ingest("Pet Health Cover",
                "Pet health cover pays veterinary fees for a dog or a cat after an illness. Routine "
                + "vaccination and neutering are not paid by this cover.");
        ingest("Marine Cargo Insurance",
                "Marine cargo insurance protects a shipment of containers carried by vessel between two "
                + "ports. Loss overboard during heavy weather at sea is included.");
        ingest("Legal Expenses Cover",
                "Legal expenses cover pays a solicitor to defend an employment dispute before a tribunal. "
                + "Court fees are reimbursed when the case is won.");
        ingest("Life Assurance Terms",
                "Life assurance pays a lump sum death benefit to the named beneficiary. A nominee may be "
                + "changed in writing at any time by the assured person.");
        ingest("Household Contents Valuation",
                "Jewellery and antiques must be listed separately with an independent valuation. Receipts "
                + "are required for any single item worth more than 2000 EUR.");
        ingest("Dental Treatment Benefit",
                "Dental treatment benefit reimburses a filling, a crown or a root canal. Orthodontic "
                + "braces for an adult patient are reimbursed at half the dentist invoice.");
        ingest("Optical Benefit Schedule",
                "The optical benefit pays for one eye test each year and a pair of spectacles. Contact "
                + "lenses supplied by a registered optician are also paid.");
        ingest("Maternity Benefit Rules",
                "Maternity benefit covers antenatal appointments, childbirth in hospital and a postnatal "
                + "check. A pregnancy already confirmed before the member joined is not covered.");
        ingest("Mental Health Support",
                "Mental health support offers counselling sessions with a psychologist and a telephone "
                + "helpline. Residential psychiatric treatment requires prior approval from the case "
                + "manager.");
        ingest("Prescription Drug Formulary",
                "The formulary lists generic drugs that a pharmacy may dispense against a prescription. A "
                + "branded drug is dispensed only when no generic equivalent exists.");
        ingest("Hospital Room Entitlement",
                "Members are entitled to a private hospital room with a private bathroom. An upgrade to a "
                + "suite is charged to the member at the hotel rate.");
        ingest("Premium Payment Terms",
                "The premium may be paid yearly by bank transfer or monthly by direct debit. A monthly "
                + "instalment plan adds a small administration surcharge.");
        ingest("Policy Renewal Notice",
                "A renewal notice is sent 21 days before the anniversary date. The renewal offer lapses "
                + "if the invitation is not accepted before that anniversary.");
        ingest("Cancellation And Cooling Off",
                "A cooling off right allows the buyer to cancel within 28 days and receive a full refund. "
                + "After that, cancellation gives only a pro rata refund.");
        ingest("No Claims Discount Ladder",
                "A no claims discount rises by one step for each year without a fault claim. The discount "
                + "ladder is reset to zero when a fault claim is paid.");
        ingest("Fraud Investigation Policy",
                "An exaggerated or invented loss is treated as fraud and referred to the investigation "
                + "unit. A fraudulent file is voided and the money already paid is recovered.");
        ingest("Subrogation Rights",
                "After paying a loss an insurer takes over rights of subrogation and may sue whichever "
                + "party was responsible. Members must cooperate fully and must never settle privately with "
                + "that party.");
        ingest("Complaints And Ombudsman",
                "A written complaint receives an acknowledgement in 5 working days and a final reply in 8 "
                + "weeks. An unhappy complainant may then escalate to the financial ombudsman.");
        ingest("Data Protection And Consent",
                "Personal data is processed under consent given in an application form, then erased once "
                + "a retention period ends. Members may request rectification of inaccurate records at any "
                + "time.");
        ingest("Broker Commission Disclosure",
                "An intermediary receives commission of 12 percent on first year contributions. "
                + "Commission earned by that intermediary must be disclosed in writing to every client "
                + "before any distribution agreement is signed.");
        ingest("Business Interruption Cover",
                "Business interruption cover replaces lost gross profit while a factory is shut after a "
                + "fire. The indemnity period runs for a maximum of twelve months.");
        ingest("Public Liability Limits",
                "Public liability protects the insured against a third party bodily injury award of up to "
                + "one million EUR. Defence costs are payable in addition to that award.");
        ingest("Earthquake And Subsidence Rider",
                "The earthquake and subsidence rider covers structural cracking of walls and foundations. "
                + "Gradual settlement of a newly built house is specifically excluded from the rider.");
        ingest("Roadside Assistance Benefit",
                "Roadside assistance sends a mechanic to a breakdown at the kerbside and tows the vehicle "
                + "to the nearest workshop. A replacement hire car is provided for three days.");
        ingest("Ski And Winter Sports Extension",
                "The winter sports extension covers ski equipment hire, a piste rescue and an avalanche "
                + "closure. Off piste skiing without a qualified guide is not covered.");
        ingest("Baggage And Delay Compensation",
                "Compensation is paid when checked baggage is delayed more than six hours at the airport. "
                + "A missed connection caused by a strike is also compensated.");
        ingest("Home Emergency Helpline",
                "The home emergency helpline arranges an approved plumber, an electrician or a locksmith "
                + "at any hour. Callout labour for the first two hours is paid by the insurer.");

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

    /**
     * Writes {@code text} as a single wrapped paragraph. The wrapping is not cosmetic: Spring AI's
     * {@code PagePdfDocumentReader} extracts through {@code PDFLayoutTextStripperByArea}, which
     * reads only the rectangle inside the page's media box - so anything drawn past the right
     * margin is silently dropped, and a document whose second sentence fell off the page would
     * simply never be retrievable. Lines are therefore measured with the real font metrics and
     * broken on word boundaries, and {@link #LEADING} is kept close to the font size so the
     * stripper emits single newlines rather than the blank lines {@code
     * StructureAwareDocumentChunker} would treat as paragraph breaks (each document must stay one
     * chunk for the dataset's one-document-per-question labelling to mean anything).
     */
    private byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float maxWidth = page.getMediaBox().getWidth() - 2 * LEFT_MARGIN;
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(font, FONT_SIZE);
                contentStream.setLeading(LEADING);
                contentStream.newLineAtOffset(LEFT_MARGIN, 700);
                for (String line : wrap(text, font, maxWidth)) {
                    contentStream.showText(line);
                    contentStream.newLine();
                }
                contentStream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private List<String> wrap(String text, PDType1Font font, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && font.getStringWidth(candidate) / 1000 * FONT_SIZE > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
            else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }
}
