package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.prompt.Prompt;
import com.rag.springai.insuranceai.domain.prompt.PromptId;
import com.rag.springai.insuranceai.domain.prompt.PromptStatus;
import com.rag.springai.insuranceai.ports.outbound.PromptRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Use cases for the Prompt Registry (brief FASE 9 section 10/24) - see {@code
 * AiSystemRegistryService}'s Javadoc for why one cohesive service.
 *
 * <p>{@link #activate} enforces "at most one {@code ACTIVE} prompt per key" - a cross-aggregate
 * invariant {@link Prompt} itself cannot see (it only knows its own status), so it belongs here,
 * not in the domain entity (brief section 2's "don't hide an incorrect prior decision" applies
 * in reverse too: this is a genuinely use-case-level rule, not a missed domain invariant).
 */
@Service
public class PromptRegistryService {

    private final PromptRepository promptRepository;

    public PromptRegistryService(PromptRepository promptRepository) {
        this.promptRepository = Objects.requireNonNull(promptRepository, "promptRepository must not be null");
    }

    public Prompt draft(AiSystemId aiSystemId, String promptKey, String content, String author,
            String changeReason) {
        int nextVersion = promptRepository.findByKey(promptKey).stream()
                .mapToInt(Prompt::version)
                .max()
                .orElse(0) + 1;
        Prompt prompt = Prompt.draft(aiSystemId, promptKey, nextVersion, content, author, changeReason);
        promptRepository.save(prompt);
        return prompt;
    }

    public Prompt activate(PromptId id) {
        Prompt prompt = promptRepository.findById(id).orElseThrow(() -> new PromptNotFoundException(id));
        promptRepository.findByKey(prompt.promptKey()).stream()
                .filter(existing -> existing.status() == PromptStatus.ACTIVE)
                .forEach(existing -> {
                    existing.retire();
                    promptRepository.save(existing);
                });
        prompt.activate();
        promptRepository.save(prompt);
        return prompt;
    }

    public Optional<Prompt> findActive(String promptKey) {
        return promptRepository.findActiveByKey(promptKey);
    }

    public List<Prompt> listByKey(String promptKey) {
        return promptRepository.findByKey(promptKey);
    }

    public List<Prompt> listByAiSystem(AiSystemId aiSystemId) {
        return promptRepository.findByAiSystemId(aiSystemId);
    }
}
