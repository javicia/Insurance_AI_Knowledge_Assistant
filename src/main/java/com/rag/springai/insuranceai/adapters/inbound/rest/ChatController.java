package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeCommand;
import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeUseCase;
import com.rag.springai.insuranceai.application.rag.RagAnswer;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Basic RAG chat endpoint (brief section 39/40). Returns {@link RagAnswer} directly - an
 * application-layer type, never a Spring AI type.
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
        RagAnswer answer = askInsuranceKnowledgeUseCase
                .ask(new AskInsuranceKnowledgeCommand(request.question(), currentTraceId()));
        return ResponseEntity.ok(answer);
    }

    private TraceId currentTraceId() {
        String value = MDC.get(TraceId.MDC_KEY);
        return value != null ? TraceId.of(value) : TraceId.generate();
    }
}
