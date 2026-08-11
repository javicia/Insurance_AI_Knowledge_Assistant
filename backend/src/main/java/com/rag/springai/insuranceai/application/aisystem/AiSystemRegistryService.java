package com.rag.springai.insuranceai.application.aisystem;

import com.rag.springai.insuranceai.domain.aisystem.AiSystem;
import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.aisystem.HumanOversightRequirement;
import com.rag.springai.insuranceai.domain.aisystem.RiskClassification;
import com.rag.springai.insuranceai.ports.outbound.AiSystemRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Use cases for the AI System Registry (brief FASE 9 section 10): register, activate/retire,
 * look up. One cohesive service rather than one class per operation - these are CRUD-adjacent
 * registry operations on a single aggregate, not independent multi-step business workflows (the
 * distinction the rest of this codebase's one-class-per-use-case convention exists for - see
 * {@code RegisterDocumentUseCase} for the multi-step case this is deliberately not).
 */
@Service
public class AiSystemRegistryService {

    private final AiSystemRepository aiSystemRepository;

    public AiSystemRegistryService(AiSystemRepository aiSystemRepository) {
        this.aiSystemRepository = Objects.requireNonNull(aiSystemRepository, "aiSystemRepository must not be null");
    }

    public AiSystem register(String name, String purpose, String owner, String intendedUse, String prohibitedUse,
            RiskClassification riskClassification, HumanOversightRequirement humanOversight) {
        AiSystem aiSystem = AiSystem.register(name, purpose, owner, intendedUse, prohibitedUse, riskClassification,
                humanOversight);
        aiSystemRepository.save(aiSystem);
        return aiSystem;
    }

    public AiSystem activate(AiSystemId id) {
        AiSystem aiSystem = get(id);
        aiSystem.activate();
        aiSystemRepository.save(aiSystem);
        return aiSystem;
    }

    public AiSystem retire(AiSystemId id) {
        AiSystem aiSystem = get(id);
        aiSystem.retire();
        aiSystemRepository.save(aiSystem);
        return aiSystem;
    }

    public AiSystem get(AiSystemId id) {
        return aiSystemRepository.findById(id)
                .orElseThrow(() -> new AiSystemNotFoundException(id));
    }

    public Optional<AiSystem> findByName(String name) {
        return aiSystemRepository.findByName(name);
    }

    public List<AiSystem> list() {
        return aiSystemRepository.findAll();
    }
}
