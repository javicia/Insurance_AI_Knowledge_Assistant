package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.prompt.Prompt;
import com.rag.springai.insuranceai.domain.prompt.PromptStatus;
import com.rag.springai.insuranceai.ports.outbound.PromptRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptRegistryServiceTest {

    private final PromptRepository repository = mock(PromptRepository.class);
    private final PromptRegistryService service = new PromptRegistryService(repository);
    private final AiSystemId aiSystemId = AiSystemId.generate();

    @Test
    void draftAssignsTheNextVersionNumberForTheKey() {
        Prompt v1 = Prompt.draft(aiSystemId, "key", 1, "content v1", "author", "reason");
        when(repository.findByKey("key")).thenReturn(List.of(v1));

        Prompt v2 = service.draft(aiSystemId, "key", "content v2", "author", "reason");

        assertEquals(2, v2.version());
    }

    @Test
    void draftStartsAtVersionOneWhenNoPriorVersionExists() {
        when(repository.findByKey("key")).thenReturn(List.of());

        Prompt v1 = service.draft(aiSystemId, "key", "content", "author", "reason");

        assertEquals(1, v1.version());
    }

    @Test
    void activatingARetiresAnyPreviouslyActivePromptWithTheSameKey() {
        Prompt currentlyActive = Prompt.draft(aiSystemId, "key", 1, "content v1", "author", "reason");
        currentlyActive.activate();
        Prompt candidate = Prompt.draft(aiSystemId, "key", 2, "content v2", "author", "reason");
        when(repository.findById(candidate.id())).thenReturn(Optional.of(candidate));
        when(repository.findByKey("key")).thenReturn(List.of(currentlyActive, candidate));

        Prompt activated = service.activate(candidate.id());

        assertEquals(PromptStatus.ACTIVE, activated.status());
        assertEquals(PromptStatus.RETIRED, currentlyActive.status());
        verify(repository, times(1)).save(currentlyActive);
    }

    @Test
    void activatingAnUnknownPromptThrows() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThrows(PromptNotFoundException.class,
                () -> service.activate(com.rag.springai.insuranceai.domain.prompt.PromptId.generate()));
    }

    @Test
    void findActiveDelegatesToTheRepository() {
        Prompt active = Prompt.draft(aiSystemId, "key", 1, "content", "author", "reason");
        active.activate();
        when(repository.findActiveByKey("key")).thenReturn(Optional.of(active));

        Optional<Prompt> found = service.findActive("key");

        assertEquals(active, found.orElseThrow());
    }
}
