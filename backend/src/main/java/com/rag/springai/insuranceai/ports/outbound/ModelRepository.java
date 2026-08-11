package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.model.AiModel;
import com.rag.springai.insuranceai.domain.model.ModelId;

import java.util.List;
import java.util.Optional;

public interface ModelRepository {

    void save(AiModel model);

    Optional<AiModel> findById(ModelId id);

    List<AiModel> findByAiSystemId(AiSystemId aiSystemId);
}
