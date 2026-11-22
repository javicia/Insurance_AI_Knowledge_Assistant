package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

public record EmbedDocumentVersionCommand(DocumentId documentId, DocumentVersionId documentVersionId) {
}
