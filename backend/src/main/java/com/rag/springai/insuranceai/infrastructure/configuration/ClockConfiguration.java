package com.rag.springai.insuranceai.infrastructure.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Exposes the system clock as a bean so that time-dependent application components can be unit
 * tested at a chosen instant instead of waiting for one (FASE 29).
 *
 * <p>Introduced for {@code GeneralAiBudgetLedger}, whose daily and monthly spend windows roll
 * over on a date boundary. Verifying that rollover against {@code Clock.systemDefaultZone()}
 * would mean either waiting until midnight or asserting nothing, so the ledger takes a
 * {@link Clock} and its test supplies a fixed one.
 *
 * <p>{@link ConditionalOnMissingBean} so that a test slice declaring its own fixed clock
 * overrides this without a bean-definition conflict.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
