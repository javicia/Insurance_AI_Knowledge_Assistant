package com.rag.springai.insuranceai.application.rag;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides what a question is asking for, before any evidence is weighed (FASE 32).
 *
 * <p>Closes the defect FASE 30 and FASE 31 both measured and neither could fix with a threshold:
 * three of the four residual false groundings were a credential request (FT-133), a private-record
 * lookup (FT-242) and an operation (FT-245), each of which earned high similarity because it
 * genuinely talks about insurance. See {@link QueryScope} for why no score can separate them.
 *
 * <h2>Design, and the two traps it has to avoid</h2>
 *
 * <p><b>Trap 1: keyword blocking.</b> The brief forbids refusing on words like "actualizar",
 * "cambiar", "póliza" or "teléfono", and the codebase already paid for learning why: the
 * {@code dan_jailbreak_persona} note in {@code RuleBasedPromptInjectionGuard} records a pattern so
 * loose it refused "¿Me dan coche de sustitución?" as an attack. So no rule here fires on a noun
 * alone. Every rule requires a <em>grammatical shape</em>: who is being asked to do what.
 *
 * <p><b>Trap 2: refusing procedures.</b> "Actualiza mi teléfono" and "¿Cómo actualizo mi
 * teléfono?" share almost every word. The first instructs the assistant; the second asks how a
 * process works, and a procedures manual is exactly where that is documented. The discriminator
 * is therefore <em>interrogative-procedural framing</em>, checked FIRST and winning outright:
 * anything phrased as "¿cómo…?", "¿qué debo hacer para…?", "¿dónde…?", "¿qué documentación…?" is
 * documentary no matter what verbs or nouns follow it.
 *
 * <h2>Fail-open, deliberately</h2>
 *
 * <p>The default is {@link QueryScope#DOCUMENTARY_QUERY}. This classifier only ever <em>removes</em>
 * the right to ground, so a false positive silently refuses a legitimate employee question - a
 * product failure with no error message. A false negative, by contrast, leaves the question
 * exactly where it was before FASE 32: subject to the unchanged grounding rules, which already
 * reject most unsupported claims. Erring open is the cheaper error here, which is the opposite of
 * the choice made in the injection guard, where erring closed is cheaper.
 *
 * <p>Deterministic rules, no model. A local LLM classifier was considered and rejected: it would
 * add a second inference per question on a CPU-only host where embedding already costs ~500 ms,
 * and would make a security-relevant decision non-reproducible. Nothing here can be bypassed by
 * query expansion or by text inside retrieved documents, because it runs on the user's question
 * before retrieval happens at all.
 */
@Component
public class QueryScopeClassifier {

    /**
     * Procedural framing. Checked first and decisive: these ask how something is done, which is
     * documentation's job. Includes the bare "como + verb" form because employees drop the
     * accent and the opening question mark constantly.
     */
    private static final Pattern PROCEDURAL = Pattern.compile(
            "\\b(como|de que (forma|manera)|que (debo|tengo que|hay que|documentacion|documentos|"
                    + "pasos|requisitos|plazo|plazos)|donde|cuando|quien (puede|debe)|"
                    + "cual es el (procedimiento|proceso|tramite|plazo))\\b");

    /**
     * Imperative or declarative instruction aimed at the assistant. Second person imperative
     * ("actualiza", "actualizad", "cambiame") or a first-person declaration of intent to have the
     * system act ("quiero que cambieis", "necesito que deis de baja").
     *
     * <p>Crucially this does NOT match "¿puedo cambiar…?" or "¿cómo cambio…?" - asking whether or
     * how something may be done is a documentary question about the rules.
     */
    private static final Pattern IMPERATIVE_TO_SYSTEM = Pattern.compile(
            "(^|[.;!?]\\s*)\\s*(actualiza|actualizad|actualizame|cambia|cambiad|cambiame|"
                    + "modifica|modificad|modificame|da de baja|dad de baja|dame de baja|dadme de baja|"
                    + "anula|anulad|cancela|cancelad|borra|borrad|elimina|eliminad|"
                    + "registra|registrad|contrata|contratad|paga|pagad|abona|abonad|"
                    + "comprueba|comprobad|consulta|consultad|dime|decidme|envia|enviad|"
                    + "tramita|tramitad|asigna|asignad|apunta|apuntad)\\b");

    /** "quiero/necesito que + 2nd person plural/singular subjunctive" - a request to act. */
    private static final Pattern REQUEST_TO_ACT = Pattern.compile(
            "\\b(quiero|necesito|deseo|exijo|solicito) que (me )?"
                    + "(cambi|actualic|modific|anul|cancel|borr|elimin|deis|des|tramit|registr|asign)");

    /**
     * Values that live only in a customer record system. Deliberately phrases, not nouns: "mi
     * póliza" alone is a perfectly good coverage question ("¿Qué cubre mi póliza?"), whereas
     * "el estado de mi siniestro" is a record lookup. The difference is whether the question asks
     * about the <em>product</em> or about an <em>instance</em>.
     */
    private static final Pattern PERSONAL_RECORD_LOOKUP = Pattern.compile(
            "\\b(estado de (mi|nuestro|la mia|el mio)|en que (estado|situacion) (esta|se encuentra) mi|"
                    + "mi (expediente|historial|saldo|recibo|factura|numero de poliza)|"
                    + "mis (datos|recibos|facturas|siniestros|pagos)|"
                    + "cuanto (me queda|debo|he pagado|llevo pagado)|"
                    + "historial de siniestros (asociado|vinculado|de)|"
                    + "(tengo|tenemos) (algun|algun) (siniestro|parte|recibo) (abierto|pendiente))\\b");

    /**
     * Requests for the VALUE of a credential. "¿Cómo recupero mi contraseña?" is excluded by the
     * procedural check running first - that one is documented and answerable.
     */
    private static final Pattern CREDENTIAL_VALUE = Pattern.compile(
            "\\b((cual|cuales) (es|son) (la|mi|mis|el|las|los)? ?(contrasena|password|clave|"
                    + "credencial|credenciales|pin|usuario y contrasena|token)|"
                    + "(dime|dame|facilitame|necesito|quiero) (mi |la |el )?(contrasena|password|clave|pin|token)|"
                    + "contrasena del area (privada|de clientes)|clave de acceso)\\b");

    /**
     * Phrases that only make sense as part of a documentary question, used to detect mixtures.
     *
     * <p>An interrogative <b>shape</b> is required, not merely insurance vocabulary. An earlier
     * version matched the bare noun {@code cobertura}, which softened
     * "Necesito que deis de baja la cobertura de lunas" - a pure cancellation - into
     * {@link QueryScope#AMBIGUOUS} and so let it ground. Any operation whose object happens to be
     * an insurance noun would have escaped the transactional classification the same way.
     */
    private static final List<Pattern> DOCUMENTARY_MARKERS = List.of(
            Pattern.compile("\\b(que (cubre|cubren|incluye|excluye|exclusiones|franquicia|plazo|"
                    + "requisitos|documentacion|limitaciones)|esta cubiert|estan cubiert|"
                    + "cual es (la|el) (franquicia|plazo|limite|cobertura))\\b"));

    /**
     * Classifies {@code question}. Never throws and never returns {@code null}: an unclassifiable
     * question is {@link QueryScope#DOCUMENTARY_QUERY}, which changes nothing about how it is
     * handled.
     */
    public QueryScope classify(String question) {
        if (question == null || question.isBlank()) {
            return QueryScope.DOCUMENTARY_QUERY;
        }
        String q = normalize(question);

        boolean procedural = PROCEDURAL.matcher(q).find();
        boolean credential = CREDENTIAL_VALUE.matcher(q).find();

        // A credential VALUE is refused even when phrased procedurally: "¿cómo consigo la
        // contraseña del área privada?" is not a documented procedure, it is the same request
        // wearing a question mark. The procedural exemption exists for processes the company
        // documents, not for obtaining secrets.
        if (credential) {
            return QueryScope.ACCOUNT_SECURITY_REQUEST;
        }
        // Procedural framing wins over everything else: it is the signal that separates
        // "actualiza mi teléfono" from "¿cómo actualizo mi teléfono?", which is the single most
        // important distinction this class makes.
        if (procedural) {
            return QueryScope.DOCUMENTARY_QUERY;
        }
        if (IMPERATIVE_TO_SYSTEM.matcher(q).find() || REQUEST_TO_ACT.matcher(q).find()) {
            return mixedWithDocumentary(q) ? QueryScope.AMBIGUOUS : QueryScope.TRANSACTIONAL_REQUEST;
        }
        if (PERSONAL_RECORD_LOOKUP.matcher(q).find()) {
            return mixedWithDocumentary(q) ? QueryScope.AMBIGUOUS : QueryScope.PERSONAL_DATA_QUERY;
        }
        return QueryScope.DOCUMENTARY_QUERY;
    }

    /**
     * Whether an otherwise-refusable question also carries a genuine documentary question. Such a
     * mixture becomes {@link QueryScope#AMBIGUOUS}, which still allows grounding: the documentary
     * half deserves an answer, and the grounding rules remain in force for whatever evidence the
     * retrieval returns.
     */
    private static boolean mixedWithDocumentary(String normalizedQuestion) {
        return DOCUMENTARY_MARKERS.stream().anyMatch(p -> p.matcher(normalizedQuestion).find());
    }

    /**
     * Lowercases and strips diacritics, so every pattern above matches whether or not the
     * employee typed accents. A control that can be bypassed by omitting a tilde is not a
     * control - the same finding that drove the {@code unaccent} dictionary into
     * {@code V8__spanish_full_text_search.sql}.
     */
    private static String normalize(String question) {
        String decomposed = Normalizer.normalize(question.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
