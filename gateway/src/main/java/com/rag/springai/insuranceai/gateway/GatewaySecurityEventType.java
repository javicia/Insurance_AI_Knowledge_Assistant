package com.rag.springai.insuranceai.gateway;

/** The subset of the backend's {@code SecurityEventType} enum that the gateway itself
 *  is in a position to observe directly (see {@link GatewaySecurityEvent}'s Javadoc). */
public enum GatewaySecurityEventType {
    RATE_LIMIT_EXCEEDED
}
