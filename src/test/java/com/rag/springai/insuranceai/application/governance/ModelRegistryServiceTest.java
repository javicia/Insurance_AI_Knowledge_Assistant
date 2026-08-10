package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.model.AiModel;
import com.rag.springai.insuranceai.domain.model.ModelStatus;
import com.rag.springai.insuranceai.ports.outbound.ModelRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRegistryServiceTest {

    private final ModelRepository repository = mock(ModelRepository.class);
    private final ModelRegistryService service = new ModelRegistryService(repository);

    @Test
    void registerPersistsAnActiveModel() {
        AiModel model = service.register(AiSystemId.generate(), "openai", "gpt-4o-mini", "1.0", "chat",
                "production");

        assertEquals(ModelStatus.ACTIVE, model.status());
        verify(repository).save(model);
    }

    @Test
    void deactivateMarksTheModelDeactivated() {
        AiModel model = AiModel.register(AiSystemId.generate(), "openai", "gpt-4o-mini", "1.0", "chat", "production");
        when(repository.findById(model.id())).thenReturn(Optional.of(model));

        AiModel deactivated = service.deactivate(model.id());

        assertEquals(ModelStatus.DEACTIVATED, deactivated.status());
    }
}
