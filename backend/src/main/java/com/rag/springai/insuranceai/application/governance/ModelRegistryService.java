package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.model.AiModel;
import com.rag.springai.insuranceai.domain.model.ModelId;
import com.rag.springai.insuranceai.ports.outbound.ModelRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Use cases for the Model Registry (brief FASE 9 section 10) - see {@code AiSystemRegistryService}'s Javadoc for why one cohesive service. */
@Service
public class ModelRegistryService {

    private final ModelRepository modelRepository;

    public ModelRegistryService(ModelRepository modelRepository) {
        this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository must not be null");
    }

    public AiModel register(AiSystemId aiSystemId, String provider, String modelIdentifier, String version,
            String capabilities, String intendedPurpose) {
        AiModel model = AiModel.register(aiSystemId, provider, modelIdentifier, version, capabilities,
                intendedPurpose);
        modelRepository.save(model);
        return model;
    }

    public AiModel deactivate(ModelId id) {
        AiModel model = modelRepository.findById(id).orElseThrow(() -> new ModelNotFoundException(id));
        model.deactivate();
        modelRepository.save(model);
        return model;
    }

    public List<AiModel> listByAiSystem(AiSystemId aiSystemId) {
        return modelRepository.findByAiSystemId(aiSystemId);
    }
}
