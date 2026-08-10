package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.prompt.Prompt;
import com.rag.springai.insuranceai.domain.prompt.PromptId;

import java.util.List;
import java.util.Optional;

public interface PromptRepository {

    void save(Prompt prompt);

    Optional<Prompt> findById(PromptId id);

    /** The prompt {@code AskInsuranceKnowledgeUseCase} actually uses for a given key. */
    Optional<Prompt> findActiveByKey(String promptKey);

    List<Prompt> findByKey(String promptKey);

    List<Prompt> findByAiSystemId(AiSystemId aiSystemId);
}
