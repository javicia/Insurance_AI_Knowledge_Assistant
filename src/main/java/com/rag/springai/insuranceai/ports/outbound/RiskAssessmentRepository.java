package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.governance.RiskAssessment;
import com.rag.springai.insuranceai.domain.governance.RiskAssessmentId;

import java.util.List;
import java.util.Optional;

public interface RiskAssessmentRepository {

    void save(RiskAssessment riskAssessment);

    Optional<RiskAssessment> findById(RiskAssessmentId id);

    List<RiskAssessment> findByAiSystemId(AiSystemId aiSystemId);
}
