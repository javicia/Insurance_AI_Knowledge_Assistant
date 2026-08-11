package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

record RegisterModelRequest(String provider, String modelIdentifier, String version, String capabilities,
        String intendedPurpose) {
}
