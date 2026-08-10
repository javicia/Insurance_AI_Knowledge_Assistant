package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.document.exception.DocumentNotFoundException;
import com.rag.springai.insuranceai.application.document.exception.DocumentVersionNotFoundException;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Answers an employee's question using only the ingested insurance documentation (brief
 * section 6): run the Advanced RAG hybrid retrieval pipeline ({@link HybridRetrievalService})
 * and - only if at least one final candidate clears its own retrieval branch's relevance bar -
 * ask the LLM, grounded strictly in that retrieved text (brief section 11: no relevant context
 * means no LLM call at all).
 *
 * <p><b>No-answer policy (FASE 6, brief section 21):</b> answer only if at least one final
 * candidate has {@code semanticScore >= insurance-ai.rag.semantic.similarity-threshold} (FASE
 * 5's original criterion, unchanged) <em>or</em> a non-null {@code lexicalScore} (PostgreSQL's
 * {@code @@} text-search operator only ever returns genuine matches - no extra numeric threshold
 * is invented for it, see {@code LexicalSearchPort}). This deliberately never derives a
 * probability from {@code fusionScore} or {@code rerankerScore} (brief section 21/23: rank
 * fusion and reranking are relevance signals, not grounding proof) - it inspects each
 * candidate's original semantic/lexical evidence, which reranking never overwrites (see {@link
 * HybridRetrievalResult#withRerankerScore}).
 *
 * <p>Knows nothing about OpenAI, Anthropic, {@code ChatClient}, {@code PgVectorStore}, {@code
 * tsvector}, JDBC or HTTP - only the ports/collaborators it depends on.
 */
@Service
public final class AskInsuranceKnowledgeUseCase {

    private final HybridRetrievalService hybridRetrievalService;
    private final LlmProvider llmProvider;
    private final DocumentRepository documentRepository;
    private final InsuranceAiProperties properties;

    public AskInsuranceKnowledgeUseCase(HybridRetrievalService hybridRetrievalService, LlmProvider llmProvider,
            DocumentRepository documentRepository, InsuranceAiProperties properties) {
        this.hybridRetrievalService = Objects.requireNonNull(hybridRetrievalService,
                "hybridRetrievalService must not be null");
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public RagAnswer ask(AskInsuranceKnowledgeCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        HybridRetrievalOutcome outcome = hybridRetrievalService.retrieve(command.question(), command.filter());
        List<HybridRetrievalResult> finalCandidates = outcome.finalCandidates();

        if (!hasQualifyingCandidate(finalCandidates)) {
            return RagAnswer.noAnswer(command.traceId().value());
        }

        List<SourceReference> sources = buildSources(finalCandidates);
        LlmPrompt prompt = new LlmPrompt(InsuranceRagSystemPrompt.TEXT, command.question(),
                finalCandidates.stream().map(HybridRetrievalResult::content).toList());
        LlmCompletion completion = llmProvider.complete(prompt);

        return new RagAnswer(completion.text(), sources, new Grounding(GroundingStatus.GROUNDED),
                command.traceId().value());
    }

    private boolean hasQualifyingCandidate(List<HybridRetrievalResult> candidates) {
        double semanticThreshold = properties.rag().semantic().similarityThreshold();
        return candidates.stream()
                .anyMatch(candidate -> (candidate.semanticScore() != null
                        && candidate.semanticScore() >= semanticThreshold) || candidate.lexicalScore() != null);
    }

    private List<SourceReference> buildSources(List<HybridRetrievalResult> candidates) {
        Map<DocumentId, Document> documentsById = new HashMap<>();
        List<SourceReference> sources = new ArrayList<>();

        for (HybridRetrievalResult candidate : candidates) {
            Document document = documentsById.computeIfAbsent(candidate.documentId(), id -> documentRepository
                    .findById(id)
                    .orElseThrow(() -> new DocumentNotFoundException(id)));
            DocumentVersion version = document.version(candidate.documentVersionId())
                    .orElseThrow(() -> new DocumentVersionNotFoundException(candidate.documentId(),
                            candidate.documentVersionId()));

            sources.add(new SourceReference(document.id().toString(), document.name(),
                    version.versionNumber().toString(), candidate.metadata().page(), candidate.metadata().section(),
                    candidate.chunkId().toString()));
        }
        return sources;
    }
}
