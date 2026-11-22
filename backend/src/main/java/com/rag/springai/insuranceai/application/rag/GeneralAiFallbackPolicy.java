package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides whether a question with no sufficient documentary evidence may be answered from the
 * language model's general knowledge (FASE 28, brief section 8).
 *
 * <p><b>Fail-closed by construction.</b> {@link #evaluate} returns a refusal unless every
 * condition for allowing the fallback is positively met, and the feature ships disabled
 * ({@code insurance-ai.rag.general-ai-fallback.enabled: false}) so that enabling it is an
 * explicit, auditable deployment decision taken after reviewing cost and data-processing
 * implications - not something that silently starts happening on upgrade.
 *
 * <p><b>The contractual-question rule is the load-bearing control.</b> An insurance assistant
 * answering "is this covered?" from general knowledge is not a degraded answer, it is a wrong
 * answer about a contract the company is liable for, and the disclaimer on the response does not
 * undo it. So questions that ask for a determination about a specific policy, entitlement or
 * amount are refused <em>regardless of configuration</em>: there is no property that enables
 * them. General/educational questions ("what is a deductible?", "how does bonus-malus work?")
 * are the only thing the fallback is for.
 *
 * <p><b>This is a deterministic keyword rule, not a classifier, and that is a deliberate
 * trade-off with real limits.</b> It recognizes two signals that must BOTH be read in Spanish,
 * accent-insensitively (employees type without accents - see
 * {@code V8__spanish_full_text_search.sql} for the same finding on the retrieval side):
 * <ol>
 *   <li>a first-person/possessive marker ("mi", "tengo", "me corresponde") or a
 *       coverage-determination phrasing ("esta cubierto", "tengo derecho"), and</li>
 *   <li>an insurance-contract term ("cobertura", "franquicia", "indemnizacion", ...).</li>
 * </ol>
 * A determination phrasing alone is enough; a contract term alone is not, because "what is a
 * deductible" is exactly the educational case this feature exists to serve. Known limitation: it
 * will not catch a contractual question phrased without any of these markers, and it will refuse
 * some harmless questions that happen to contain them. It is tuned to err toward refusing, and
 * it is not a substitute for the human-oversight requirement recorded on the AI System (see
 * {@code docs/governance/HUMAN_OVERSIGHT.md}). See
 * {@code docs/rag/ESTRATEGIA_FALLBACK_IA_ES.md}.
 *
 * <p>Note what is <em>not</em> this class's job: a blocked question never reaches it at all
 * ({@code AskInsuranceKnowledgeUseCase} returns {@link AnswerMode#BLOCKED} before retrieval), so
 * "a guardrail hit must not be rescued by the fallback" is guaranteed by control flow rather
 * than by a rule here that could be edited out.
 */
@Component
public class GeneralAiFallbackPolicy {

    /**
     * First-person ownership and entitlement markers: the question is about <em>this</em>
     * employee's or customer's situation rather than about how insurance works in general.
     */
    private static final Pattern SPECIFIC_SITUATION_MARKERS = Pattern.compile(
            "\\b(mi|mis|mio|mia|me|conmigo|nuestro|nuestra|nuestros|nuestras|tengo|tenemos|"
                    + "mi poliza|mi seguro|mi coche|mi vehiculo)\\b");

    /**
     * Phrasings that ask for a coverage/entitlement determination outright. Any one of these is
     * sufficient on its own - they are already a request for a contractual decision even without
     * a possessive, as in "esta cubierto el granizo?".
     */
    private static final Pattern DETERMINATION_MARKERS = Pattern.compile(
            "\\b(esta cubierto|estan cubiertos|esta cubierta|estan cubiertas|queda cubierto|"
                    + "tengo derecho|tenemos derecho|me corresponde|nos corresponde|me cubre|nos cubre|"
                    + "me indemniza|me indemnizan|me pagan|me devuelven|puedo reclamar|podemos reclamar|"
                    + "cuanto me|cuanto cobro|cuanto cobrar|me deniegan|me cubren)\\b");

    /** Insurance-contract vocabulary - only meaningful in combination with the markers above. */
    private static final List<String> CONTRACT_TERMS = List.of("cobertura", "coberturas", "cubre", "cubren",
            "cubierto", "cubierta", "exclusion", "exclusiones", "excluido", "excluida", "franquicia", "franquicias",
            "indemnizacion", "indemnizaciones", "indemniza", "prima", "primas", "siniestro", "siniestros",
            "reclamacion", "reclamaciones", "poliza", "polizas", "asegurado", "asegurada", "capital asegurado",
            "limite", "limites", "elegible", "elegibilidad", "deducible", "bonificacion", "recargo", "clausula",
            "clausulas", "garantia", "garantias");

    private final InsuranceAiProperties properties;

    public GeneralAiFallbackPolicy(InsuranceAiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Returns the reason the fallback must not be used, or {@link Optional#empty()} when it may
     * be. Returning the refusal rather than a bare boolean is what lets the caller audit and
     * meter <em>why</em> a fallback did not happen, which is the difference between a feature
     * that is off and a feature that is quietly broken.
     */
    public Optional<GeneralAiFallbackRefusal> evaluate(String question, boolean piiDetectedInQuestion) {
        Objects.requireNonNull(question, "question must not be null");
        InsuranceAiProperties.Rag.GeneralAiFallback config = properties.rag().generalAiFallback();

        if (!config.enabled()) {
            return Optional.of(GeneralAiFallbackRefusal.DISABLED);
        }
        if (piiDetectedInQuestion && !config.allowQuestionsWithPii()) {
            return Optional.of(GeneralAiFallbackRefusal.PII_IN_QUESTION);
        }
        if (asksForAContractualDetermination(question)) {
            return Optional.of(GeneralAiFallbackRefusal.CONTRACTUAL_QUESTION);
        }
        return Optional.empty();
    }

    private static boolean asksForAContractualDetermination(String question) {
        String normalized = normalize(question);
        if (DETERMINATION_MARKERS.matcher(normalized).find()) {
            return true;
        }
        return SPECIFIC_SITUATION_MARKERS.matcher(normalized).find() && mentionsAContractTerm(normalized);
    }

    private static boolean mentionsAContractTerm(String normalizedQuestion) {
        for (String term : CONTRACT_TERMS) {
            if (Pattern.compile("\\b" + Pattern.quote(term) + "\\b").matcher(normalizedQuestion).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lowercases and strips diacritics so the patterns above match whether or not the employee
     * typed accents - "¿está cubierto mi vehículo?" and "esta cubierto mi vehiculo?" must reach
     * the same decision. A policy that can be bypassed by omitting an accent is not a policy.
     */
    private static String normalize(String question) {
        String decomposed = Normalizer.normalize(question.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
