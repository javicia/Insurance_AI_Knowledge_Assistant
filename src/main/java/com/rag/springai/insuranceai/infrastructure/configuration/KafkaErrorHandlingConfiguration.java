package com.rag.springai.insuranceai.infrastructure.configuration;

import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka consumer retry policy (brief section 33/34,
 * {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}): transient failures are
 * retried with exponential backoff; once retries are exhausted (or for a failure type known to
 * never be worth retrying), the record is published to a {@code <topic>.DLT} dead-letter topic
 * instead of being silently dropped, so an operator can inspect and decide whether to replay
 * it. {@code PermanentProcessingException} is excluded from retries entirely - the application
 * layer already handles it by marking the document version {@code FAILED} and returning
 * normally (see {@code ProcessDocumentVersionUseCase}), so it should not usually reach here,
 * but is excluded defensively in case a future consumer forgets to catch it.
 */
@Configuration
public class KafkaErrorHandlingConfiguration {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxElapsedTime(10_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(PermanentProcessingException.class);
        return errorHandler;
    }
}
