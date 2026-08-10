package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.domain.aisystem.AiSystem;
import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.aisystem.HumanOversightRequirement;
import com.rag.springai.insuranceai.domain.aisystem.RiskClassification;
import com.rag.springai.insuranceai.domain.governance.RiskAssessment;
import com.rag.springai.insuranceai.domain.governance.RiskAssessmentStatus;
import com.rag.springai.insuranceai.ports.outbound.AiSystemRepository;
import com.rag.springai.insuranceai.ports.outbound.RiskAssessmentRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskAssessmentServiceTest {

    private final RiskAssessmentRepository riskAssessmentRepository = mock(RiskAssessmentRepository.class);
    private final AiSystemRepository aiSystemRepository = mock(AiSystemRepository.class);
    private final RiskAssessmentService service = new RiskAssessmentService(riskAssessmentRepository,
            aiSystemRepository);

    @Test
    void approveMarksTheAssessmentApprovedAndReclassifiesTheOwningAiSystem() {
        AiSystem aiSystem = AiSystem.register("name", "purpose", "owner", "intended", "prohibited",
                RiskClassification.MINIMAL, HumanOversightRequirement.always("team"));
        RiskAssessment assessment = RiskAssessment.draft(aiSystem.id(), RiskClassification.LIMITED, "rationale",
                "controls", "residual", "reviewer");
        when(riskAssessmentRepository.findById(assessment.id())).thenReturn(Optional.of(assessment));
        when(aiSystemRepository.findById(aiSystem.id())).thenReturn(Optional.of(aiSystem));

        RiskAssessment approved = service.approve(assessment.id());

        assertEquals(RiskAssessmentStatus.APPROVED, approved.status());
        assertEquals(RiskClassification.LIMITED, aiSystem.riskClassification());
        verify(aiSystemRepository).save(aiSystem);
    }

    @Test
    void draftPersistsANewAssessment() {
        AiSystemId aiSystemId = AiSystemId.generate();

        RiskAssessment assessment = service.draft(aiSystemId, RiskClassification.HIGH, "rationale", "controls",
                "residual", "reviewer");

        assertEquals(RiskAssessmentStatus.DRAFT, assessment.status());
        verify(riskAssessmentRepository).save(assessment);
    }
}
