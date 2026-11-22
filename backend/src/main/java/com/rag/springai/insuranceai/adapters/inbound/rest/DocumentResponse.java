package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;

import java.util.List;

record DocumentResponse(String id, String name, String type, List<VersionResponse> versions) {

    record VersionResponse(String id, String versionNumber, String status, String contentHash) {

        static VersionResponse from(DocumentVersion version) {
            return new VersionResponse(version.id().toString(), version.versionNumber().toString(),
                    version.status().name(), version.contentHash().value());
        }
    }

    static DocumentResponse from(Document document) {
        return new DocumentResponse(document.id().toString(), document.name(), document.type().name(),
                document.versions().stream().map(VersionResponse::from).toList());
    }
}
