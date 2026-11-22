package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GeneralAiFallbackPolicy} is the only thing standing between "the documentation has no
 * answer" and "a language model answers an insurance question anyway", so its refusals are
 * tested as hard requirements rather than as behaviour that happens to hold today.
 */
class GeneralAiFallbackPolicyTest {

    private static GeneralAiFallbackPolicy policy(boolean enabled, boolean allowPii) {
        InsuranceAiProperties.Rag rag = new InsuranceAiProperties.Rag(
                new InsuranceAiProperties.Rag.Semantic(8, 0.75), new InsuranceAiProperties.Rag.Lexical(8, 0.05, 0.6),
                new InsuranceAiProperties.Rag.Hybrid(20, 8, 60.0), new InsuranceAiProperties.Rag.Reranking(true),
                new InsuranceAiProperties.Rag.QueryExpansion(false, 3), new InsuranceAiProperties.Rag.Context(6000),
                new InsuranceAiProperties.Rag.GeneralAiFallback(enabled, allowPii, new java.math.BigDecimal("100"), new java.math.BigDecimal("1000"), 2000));
        InsuranceAiProperties properties = new InsuranceAiProperties(rag,
                new InsuranceAiProperties.Security(new InsuranceAiProperties.Security.PromptInjection(true),
                        new InsuranceAiProperties.Security.Pii(true),
                        new InsuranceAiProperties.Security.OAuth2("http://localhost:19999/realms/test")),
                new InsuranceAiProperties.Governance(new InsuranceAiProperties.Governance.Audit(true)),
                new InsuranceAiProperties.Ai(InsuranceAiProperties.SupportedAiProvider.FAKE),
                new InsuranceAiProperties.Evaluation(
                        new InsuranceAiProperties.Evaluation.Thresholds(1.0, 1.0, 0.75)));
        return new GeneralAiFallbackPolicy(properties);
    }

    @Test
    void refusesWhenTheFallbackIsDisabled() {
        assertEquals(Optional.of(GeneralAiFallbackRefusal.DISABLED),
                policy(false, false).evaluate("¿Qué es una franquicia?", false));
    }

    @Test
    void theShippedDefaultIsDisabled() {
        // Asserted explicitly because "ships disabled" is a safety property, not a preference:
        // enabling the fallback changes both the data-processing posture and the cost profile,
        // so it must never become the default by accident.
        assertEquals(Optional.of(GeneralAiFallbackRefusal.DISABLED),
                policy(false, false).evaluate("¿Cómo funciona el sistema de bonificación?", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "¿Qué es una franquicia en un seguro?",
            "¿Cómo funciona el sistema de bonus-malus?",
            "¿Qué significa responsabilidad civil?",
            "¿Cuál es la diferencia entre un seguro a terceros y uno a todo riesgo?",
            "¿Qué documentos suelen pedirse al contratar un seguro de coche?" })
    void allowsGeneralEducationalQuestions(String question) {
        assertTrue(policy(true, false).evaluate(question, false).isEmpty(),
                "a general question about how insurance works is exactly what the fallback is for: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "¿Está cubierto el granizo?",
            "¿Cubre mi póliza los daños por inundación?",
            "¿Tengo derecho a un vehículo de sustitución?",
            "¿Cuánto me indemnizan por el robo del coche?",
            "¿Puedo reclamar si el siniestro fue hace dos años?",
            "¿Cuál es mi franquicia?",
            "¿Me cubre el seguro si conduce otra persona?",
            "¿Mi póliza incluye asistencia en carretera?" })
    void refusesQuestionsAskingForAContractualDetermination(String question) {
        assertEquals(Optional.of(GeneralAiFallbackRefusal.CONTRACTUAL_QUESTION),
                policy(true, false).evaluate(question, false),
                "a general model must not decide what a policy covers: " + question);
    }

    @Test
    void refusesContractualQuestionsTypedWithoutAccents() {
        // A control that can be bypassed by omitting an accent is not a control - employees
        // routinely type Spanish without accents (the same finding that drove the unaccent
        // dictionary in V8__spanish_full_text_search.sql).
        assertEquals(Optional.of(GeneralAiFallbackRefusal.CONTRACTUAL_QUESTION),
                policy(true, false).evaluate("esta cubierto mi vehiculo por granizo?", false));
    }

    @Test
    void refusesContractualQuestionsEvenWhenEveryOtherConditionIsSatisfied() {
        // There is deliberately no property that enables contractual answers, so this refusal
        // must hold under the most permissive configuration the application can be given.
        assertEquals(Optional.of(GeneralAiFallbackRefusal.CONTRACTUAL_QUESTION),
                policy(true, true).evaluate("¿Cubre mi seguro los daños del motor?", false));
    }

    @Test
    void refusesQuestionsContainingPiiByDefault() {
        assertEquals(Optional.of(GeneralAiFallbackRefusal.PII_IN_QUESTION),
                policy(true, false).evaluate("¿Qué es una franquicia?", true));
    }

    @Test
    void forwardsQuestionsContainingPiiOnlyWhenExplicitlyAllowed() {
        assertTrue(policy(true, true).evaluate("¿Qué es una franquicia?", true).isEmpty());
    }

    @Test
    void refusesOnPiiBeforeEvaluatingTheQuestionItself() {
        // Ordering matters for the audit trail: a question that is both contractual and contains
        // PII should report the data-protection refusal, which is the more consequential one.
        assertEquals(Optional.of(GeneralAiFallbackRefusal.PII_IN_QUESTION),
                policy(true, false).evaluate("¿Cubre mi póliza el DNI 12345678Z?", true));
    }
}
