package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeCommand;
import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeUseCase;
import com.rag.springai.insuranceai.application.rag.RagAnswer;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RAG chat endpoint (brief section 39/40). Returns {@link RagAnswer} directly - an
 * application-layer type, never a Spring AI type. {@code request.filters()} (FASE 6) is optional
 * and backward-compatible: a plain {@code {"question": "..."}} body still works (brief section
 * 27), translated to {@link RetrievalFilter#none()}.
 */
@RestController
@RequestMapping("/api/chat")
class ChatController {

    private final AskInsuranceKnowledgeUseCase askInsuranceKnowledgeUseCase;

    ChatController(AskInsuranceKnowledgeUseCase askInsuranceKnowledgeUseCase) {
        this.askInsuranceKnowledgeUseCase = askInsuranceKnowledgeUseCase;
    }

    @PostMapping
    ResponseEntity<RagAnswer> ask(@RequestBody ChatRequest request) {
        AskInsuranceKnowledgeCommand command = new AskInsuranceKnowledgeCommand(request.question(),
                toRetrievalFilter(request.filters()), currentTraceId());
        RagAnswer answer = askInsuranceKnowledgeUseCase.ask(command);
        return ResponseEntity.ok(answer);
    }

    private RetrievalFilter toRetrievalFilter(ChatFilterRequest filters) {
        if (filters == null) {
            return RetrievalFilter.none();
        }
        DocumentId documentId = filters.documentId() != null ? DocumentId.of(filters.documentId()) : null;
        DocumentVersionId documentVersionId = filters.documentVersionId() != null
                ? DocumentVersionId.of(filters.documentVersionId()) : null;
        return new RetrievalFilter(documentId, documentVersionId, filters.documentType(),
                filters.documentClassification(), filters.page(), filters.section());
    }

    private TraceId currentTraceId() {
        String value = MDC.get(TraceId.MDC_KEY);
        return value != null ? TraceId.of(value) : TraceId.generate();
    }
}
