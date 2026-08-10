package com.rag.springai.insuranceai.application.audit;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.audit.AuditOutcome;
import com.rag.springai.insuranceai.domain.audit.AuditRecord;
import com.rag.springai.insuranceai.domain.rag.RetrievalOutcome;
import com.rag.springai.insuranceai.ports.outbound.AuditRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditServiceTest {

    private final AuditRepository repository = mock(AuditRepository.class);
    private final AuditService service = new AuditService(repository);

    @Test
    void recordPersistsAnAuditRecordWithTheGivenFields() {
        AiSystemId aiSystemId = AiSystemId.generate();

        service.record("trace-1", aiSystemId, "openai", "insurance-rag-system-prompt", 1, RetrievalOutcome.HYBRID, 3,
                2, 4, "GROUNDED", false, false, false, 120L, AuditOutcome.GROUNDED_ANSWER, null);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(repository).save(captor.capture());
        AuditRecord saved = captor.getValue();
        assertEquals("trace-1", saved.traceId());
        assertEquals(aiSystemId, saved.aiSystemId());
        assertEquals("openai", saved.provider());
        assertEquals(AuditOutcome.GROUNDED_ANSWER, saved.outcome());
        assertEquals(120L, saved.latencyMs());
    }

    @Test
    void recordNeverReceivesRawQuestionOrAnswerParameters() {
        // Structural test: AuditService#record's signature has no String parameter that could
        // plausibly carry raw question/answer text - only identifiers, counts and flags (data
        // minimization, brief section 18/26). A parameter named "question" or "answer" appearing
        // here would be the regression this guards against.
        var methods = AuditService.class.getDeclaredMethods();
        boolean hasRecordMethod = false;
        for (var method : methods) {
            if (method.getName().equals("record")) {
                hasRecordMethod = true;
                for (var parameter : method.getParameters()) {
                    if (parameter.getType() != String.class) {
                        continue;
                    }
                    String name = parameter.getName().toLowerCase(java.util.Locale.ROOT);
                    assertFalse(name.contains("question") || name.contains("answer") || name.contains("content"),
                            "AuditService#record must never accept a String parameter for raw question/answer/"
                                    + "content text: " + name);
                }
            }
        }
        assertEquals(true, hasRecordMethod);
    }
}
