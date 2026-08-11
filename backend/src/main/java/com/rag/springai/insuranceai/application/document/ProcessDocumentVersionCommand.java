package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

public record ProcessDocumentVersionCommand(DocumentId documentId, DocumentVersionId documentVersionId) {
}
