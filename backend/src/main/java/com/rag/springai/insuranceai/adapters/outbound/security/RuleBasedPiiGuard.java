package com.rag.springai.insuranceai.adapters.outbound.security;

import com.rag.springai.insuranceai.domain.security.PiiAssessment;
import com.rag.springai.insuranceai.domain.security.PiiMatch;
import com.rag.springai.insuranceai.domain.security.PiiType;
import com.rag.springai.insuranceai.ports.outbound.PiiGuardPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic, offline, non-ML {@link PiiGuardPort} implementation (brief FASE 8 section
 * 9/26): fixed regexes for email, phone (Spanish national format - this PoC's documents are
 * all {@code country: ES}, brief section 9's own example data), IBAN (Spanish {@code ES}
 * format) and Spanish national ID (DNI/NIE).
 *
 * <p><b>This is a PoC rule-based detector, not a production-grade PII scanner</b> (e.g.
 * Microsoft Presidio, AWS Comprehend) - it recognizes only these four narrow, country-specific
 * patterns and will miss names, addresses, and any format outside them (brief section 9/60).
 * {@link #scan} never returns raw matched text, only a masked representation
 * ({@link PiiMatch#maskedValue()}) - data minimization applies to the detector's own output,
 * not just to what gets logged downstream.
 */
@Component
public class RuleBasedPiiGuard implements PiiGuardPort {

    private static final Map<PiiType, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put(PiiType.EMAIL, Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        PATTERNS.put(PiiType.IBAN, Pattern.compile("\\bES\\d{2}\\s?(?:\\d{4}\\s?){4}\\d{4}\\b"));
        PATTERNS.put(PiiType.NATIONAL_ID, Pattern.compile("\\b(?:\\d{8}|[XYZxyz]\\d{7})[A-Za-z]\\b"));
        PATTERNS.put(PiiType.PHONE, Pattern.compile("\\b(?:\\+34\\s?)?[6-9]\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{3}\\b"));
    }

    @Override
    public PiiAssessment scan(String text) {
        List<PiiMatch> matches = new ArrayList<>();
        for (Map.Entry<PiiType, Pattern> entry : PATTERNS.entrySet()) {
            Matcher matcher = entry.getValue().matcher(text);
            while (matcher.find()) {
                matches.add(new PiiMatch(entry.getKey(), mask(entry.getKey(), matcher.group())));
            }
        }
        return new PiiAssessment(!matches.isEmpty(), matches);
    }

    @Override
    public String redact(String text) {
        String redacted = text;
        for (Map.Entry<PiiType, Pattern> entry : PATTERNS.entrySet()) {
            redacted = entry.getValue().matcher(redacted).replaceAll("[REDACTED:" + entry.getKey() + "]");
        }
        return redacted;
    }

    private String mask(PiiType type, String value) {
        return switch (type) {
            case EMAIL -> {
                int at = value.indexOf('@');
                yield value.charAt(0) + "***" + value.substring(at);
            }
            case IBAN -> "ES**...".concat(value.substring(value.length() - 4));
            case NATIONAL_ID -> "*".repeat(value.length() - 1) + value.charAt(value.length() - 1);
            case PHONE -> "*".repeat(Math.max(0, value.length() - 2)) + value.substring(value.length() - 2);
        };
    }
}
