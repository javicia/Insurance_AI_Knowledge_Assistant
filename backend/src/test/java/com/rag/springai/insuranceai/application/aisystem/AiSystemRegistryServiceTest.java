package com.rag.springai.insuranceai.application.aisystem;

import com.rag.springai.insuranceai.domain.aisystem.AiSystem;
import com.rag.springai.insuranceai.domain.aisystem.AiSystemStatus;
import com.rag.springai.insuranceai.domain.aisystem.HumanOversightRequirement;
import com.rag.springai.insuranceai.domain.aisystem.RiskClassification;
import com.rag.springai.insuranceai.ports.outbound.AiSystemRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSystemRegistryServiceTest {

    private final AiSystemRepository repository = mock(AiSystemRepository.class);
    private final AiSystemRegistryService service = new AiSystemRegistryService(repository);

    private HumanOversightRequirement oversight() {
        return HumanOversightRequirement.always("Claims team");
    }

    @Test
    void registerCreatesADraftSystemAndPersistsIt() {
        AiSystem system = service.register("Insurance Knowledge Assistant", "purpose", "owner", "intended use",
                "prohibited use", RiskClassification.LIMITED, oversight());

        assertEquals(AiSystemStatus.DRAFT, system.status());
        verify(repository).save(system);
    }

    @Test
    void activateTransitionsAnExistingSystemToActive() {
        AiSystem system = AiSystem.register("name", "purpose", "owner", "intended", "prohibited",
                RiskClassification.LIMITED, oversight());
        when(repository.findById(system.id())).thenReturn(Optional.of(system));

        AiSystem activated = service.activate(system.id());

        assertEquals(AiSystemStatus.ACTIVE, activated.status());
        verify(repository, times(1)).save(any());
    }

    @Test
    void activatingAnUnknownSystemThrows() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThrows(AiSystemNotFoundException.class,
                () -> service.activate(com.rag.springai.insuranceai.domain.aisystem.AiSystemId.generate()));
    }
}
