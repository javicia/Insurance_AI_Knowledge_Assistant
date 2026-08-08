package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.application.document.RegisterDocumentCommand;
import com.rag.springai.insuranceai.application.document.RegisterDocumentUseCase;
import com.rag.springai.insuranceai.application.document.exception.DocumentNotFoundException;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;

/**
 * Minimal document upload/lookup API (brief section 39) - just enough to trigger and observe
 * the FASE 4 ingestion pipeline end to end. Returns the {@code Document} immediately after
 * registration; processing (extraction/cleaning/chunking) happens asynchronously via
 * {@code insurance.document.uploaded}, so {@code GET /api/documents/{id}} is how a caller
 * observes the version's status moving from {@code UPLOADED} to {@code PROCESSED}.
 */
@RestController
@RequestMapping("/api/documents")
class DocumentController {

    private final RegisterDocumentUseCase registerDocumentUseCase;
    private final DocumentRepository documentRepository;

    DocumentController(RegisterDocumentUseCase registerDocumentUseCase, DocumentRepository documentRepository) {
        this.registerDocumentUseCase = registerDocumentUseCase;
        this.documentRepository = documentRepository;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file,
            @RequestParam("name") String name, @RequestParam("type") DocumentType type,
            @RequestParam(value = "product", required = false) String product,
            @RequestParam(value = "country", required = false) String country,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam("classification") DocumentClassification classification) throws IOException {
        DocumentMetadata metadata = new DocumentMetadata(product, country, language, classification,
                file.getOriginalFilename());
        RegisterDocumentCommand command = new RegisterDocumentCommand(name, type, metadata, file.getBytes(),
                Instant.now());

        Document document = registerDocumentUseCase.register(command);

        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.from(document));
    }

    @GetMapping("/{id}")
    ResponseEntity<DocumentResponse> get(@PathVariable String id) {
        Document document = documentRepository.findById(DocumentId.of(id))
                .orElseThrow(() -> new DocumentNotFoundException(DocumentId.of(id)));

        return ResponseEntity.ok(DocumentResponse.from(document));
    }
}
