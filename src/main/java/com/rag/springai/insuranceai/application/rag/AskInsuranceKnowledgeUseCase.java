package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.document.exception.DocumentNotFoundException;
import com.rag.springai.insuranceai.application.document.exception.DocumentVersionNotFoundException;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.domain.rag.RetrievedChunk;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import com.rag.springai.insuranceai.ports.outbound.VectorSearchPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Answers an employee's question using only the ingested insurance documentation (brief
 * section 6): embed the question, retrieve similar chunks, and - only if at least one chunk
 * clears the similarity threshold - ask the LLM, grounded strictly in that retrieved text
 * (brief section 11: no relevant context means no LLM call at all).
 *
 * <p>Knows nothing about OpenAI, Anthropic, {@code ChatClient}, {@code PgVectorStore}, JDBC or
 * HTTP - only the four ports it depends on.
 */
@Service
public final class AskInsuranceKnowledgeUseCase {

    private final EmbeddingModelPort embeddingModelPort;
    private final VectorSearchPort vectorSearchPort;
    private final LlmProvider llmProvider;
    private final DocumentRepository documentRepository;
    private final int topK;
    private final double similarityThreshold;

    public AskInsuranceKnowledgeUseCase(EmbeddingModelPort embeddingModelPort, VectorSearchPort vectorSearchPort,
            LlmProvider llmProvider, DocumentRepository documentRepository,
            @Value("${insurance-ai.rag.top-k}") int topK,
            @Value("${insurance-ai.rag.similarity-threshold}") double similarityThreshold) {
        this.embeddingModelPort = Objects.requireNonNull(embeddingModelPort, "embeddingModelPort must not be null");
        this.vectorSearchPort = Objects.requireNonNull(vectorSearchPort, "vectorSearchPort must not be null");
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    public RagAnswer ask(AskInsuranceKnowledgeCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        EmbeddingVector questionEmbedding = embeddingModelPort.embed(command.question());
        List<RetrievedChunk> retrieved = vectorSearchPort.search(questionEmbedding, topK, similarityThreshold);

        if (retrieved.isEmpty()) {
            return RagAnswer.noAnswer(command.traceId().value());
        }

        List<SourceReference> sources = buildSources(retrieved);
        LlmPrompt prompt = new LlmPrompt(InsuranceRagSystemPrompt.TEXT, command.question(),
                retrieved.stream().map(RetrievedChunk::content).toList());
        LlmCompletion completion = llmProvider.complete(prompt);

        return new RagAnswer(completion.text(), sources, new Grounding(GroundingStatus.GROUNDED),
                command.traceId().value());
    }

    private List<SourceReference> buildSources(List<RetrievedChunk> retrieved) {
        Map<DocumentId, Document> documentsById = new HashMap<>();
        List<SourceReference> sources = new ArrayList<>();

        for (RetrievedChunk chunk : retrieved) {
            Document document = documentsById.computeIfAbsent(chunk.documentId(), id -> documentRepository
                    .findById(id)
                    .orElseThrow(() -> new DocumentNotFoundException(id)));
            DocumentVersion version = document.version(chunk.documentVersionId())
                    .orElseThrow(() -> new DocumentVersionNotFoundException(chunk.documentId(),
                            chunk.documentVersionId()));

            sources.add(new SourceReference(document.id().toString(), document.name(),
                    version.versionNumber().toString(), chunk.metadata().page(), chunk.metadata().section(),
                    chunk.chunkId().toString()));
        }
        return sources;
    }
}
