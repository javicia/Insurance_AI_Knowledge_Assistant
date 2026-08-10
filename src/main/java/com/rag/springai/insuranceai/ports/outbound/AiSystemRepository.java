package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.aisystem.AiSystem;
import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;

import java.util.List;
import java.util.Optional;

public interface AiSystemRepository {

    void save(AiSystem aiSystem);

    Optional<AiSystem> findById(AiSystemId id);

    Optional<AiSystem> findByName(String name);

    List<AiSystem> findAll();
}
