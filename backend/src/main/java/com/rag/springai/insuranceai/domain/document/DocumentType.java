package com.rag.springai.insuranceai.domain.document;

/**
 * Business category of a {@link Document}, matching the synthetic corpus layout
 * (policies/, claims/, corporate/ - brief section 10).
 */
public enum DocumentType {
    POLICY,
    CLAIMS_PROCEDURE,
    CORPORATE
}
