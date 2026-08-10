package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.aisystem.RiskClassification;
import com.rag.springai.insuranceai.domain.governance.RiskAssessment;
import com.rag.springai.insuranceai.domain.governance.RiskAssessmentId;
import com.rag.springai.insuranceai.ports.outbound.AiSystemRepository;
import com.rag.springai.insuranceai.ports.outbound.RiskAssessmentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Use cases for Risk Assessment (brief FASE 9 section 10). {@link #approve} also updates the
 * owning {@link com.rag.springai.insuranceai.domain.aisystem.AiSystem}'s risk classification -
 * an approved assessment's classification becomes the system's current classification, so the
 * two never silently drift apart.
 */
@Service
public class RiskAssessmentService {

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final AiSystemRepository aiSystemRepository;

    public RiskAssessmentService(RiskAssessmentRepository riskAssessmentRepository,
            AiSystemRepository aiSystemRepository) {
        this.riskAssessmentRepository = Objects.requireNonNull(riskAssessmentRepository,
                "riskAssessmentRepository must not be null");
        this.aiSystemRepository = Objects.requireNonNull(aiSystemRepository, "aiSystemRepository must not be null");
    }

    public RiskAssessment draft(AiSystemId aiSystemId, RiskClassification classification, String rationale,
            String controls, String residualRisk, String reviewer) {
        RiskAssessment assessment = RiskAssessment.draft(aiSystemId, classification, rationale, controls,
                residualRisk, reviewer);
        riskAssessmentRepository.save(assessment);
        return assessment;
    }

    public RiskAssessment approve(RiskAssessmentId id) {
        RiskAssessment assessment = riskAssessmentRepository.findById(id)
                .orElseThrow(() -> new RiskAssessmentNotFoundException(id));
        assessment.approve();
        riskAssessmentRepository.save(assessment);

        aiSystemRepository.findById(assessment.aiSystemId()).ifPresent(aiSystem -> {
            aiSystem.reclassifyRisk(assessment.classification());
            aiSystemRepository.save(aiSystem);
        });

        return assessment;
    }

    public List<RiskAssessment> listByAiSystem(AiSystemId aiSystemId) {
        return riskAssessmentRepository.findByAiSystemId(aiSystemId);
    }
}
