package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;

/**
 * Outbound port for turning text into an {@link EmbeddingVector}. Implemented by a Spring
 * AI-backed adapter (FASE 5, {@code adapters.outbound.embeddings}) - the domain and
 * application layers never see Spring AI's {@code EmbeddingModel} type.
 */
public interface EmbeddingModelPort {

    EmbeddingVector embed(String text);

    /**
     * Identifies exactly which model this port currently produces vectors with (brief
     * section 2/5) - recorded alongside every vector stored via {@link VectorIndexPort}.
     */
    EmbeddingModelDescriptor descriptor();
}
