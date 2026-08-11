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
 * A deterministic, offline, non-ML {@link PiiGuardPort} implementation (brief FASE 8/24 section
 * 9/26): fixed regexes for email, phone (Spanish national format - this PoC's documents are
 * all {@code country: ES}, brief section 9's own example data), IBAN (Spanish {@code ES}
 * format), Spanish national ID (DNI/NIE), credit card numbers (Luhn-validated, see below),
 * and three secret shapes (API keys, JWTs, inline password/secret assignments).
 *
 * <p><b>This is a PoC rule-based detector, not a production-grade PII scanner</b> (e.g.
 * Microsoft Presidio, AWS Comprehend) - it recognizes only these eight narrow patterns and will
 * miss names, addresses, non-Spanish ID/phone formats, and any secret shape not listed above
 * (brief section 9/60). Known false-positive/false-negative behaviour per pattern is documented
 * in {@code docs/security/PII.md}, not just asserted here.
 *
 * <p>{@link #scan} never returns raw matched text, only a masked representation
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
        // 13-19 digits, optionally grouped by spaces/dashes (the real-world range across
        // Visa/MasterCard/Amex/Discover) - the regex alone is deliberately loose; every candidate
        // is Luhn-validated in scan()/redact() before being reported, cutting false positives on
        // arbitrary long digit runs (invoice numbers, phone extensions, etc.) that are not
        // Luhn-valid card numbers.
        PATTERNS.put(PiiType.CREDIT_CARD, Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b"));
        PATTERNS.put(PiiType.API_KEY, Pattern.compile(
                "\\b(?:AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|sk-[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})\\b"));
        PATTERNS.put(PiiType.JWT, Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*\\b"));
        PATTERNS.put(PiiType.CREDENTIAL, Pattern.compile(
                "(?i)\\b(?:password|passwd|pwd|secret|api[_-]?key|token)\\s*[:=]\\s*\\S+"));
    }

    @Override
    public PiiAssessment scan(String text) {
        List<PiiMatch> matches = new ArrayList<>();
        for (Map.Entry<PiiType, Pattern> entry : PATTERNS.entrySet()) {
            Matcher matcher = entry.getValue().matcher(text);
            while (matcher.find()) {
                String value = matcher.group();
                if (entry.getKey() == PiiType.CREDIT_CARD && !isLuhnValid(value)) {
                    continue;
                }
                matches.add(new PiiMatch(entry.getKey(), mask(entry.getKey(), value)));
            }
        }
        return new PiiAssessment(!matches.isEmpty(), matches);
    }

    @Override
    public String redact(String text) {
        String redacted = text;
        for (Map.Entry<PiiType, Pattern> entry : PATTERNS.entrySet()) {
            if (entry.getKey() == PiiType.CREDIT_CARD) {
                Matcher matcher = entry.getValue().matcher(redacted);
                StringBuilder rebuilt = new StringBuilder();
                int lastEnd = 0;
                while (matcher.find()) {
                    rebuilt.append(redacted, lastEnd, matcher.start());
                    rebuilt.append(isLuhnValid(matcher.group()) ? "[REDACTED:CREDIT_CARD]" : matcher.group());
                    lastEnd = matcher.end();
                }
                rebuilt.append(redacted, lastEnd, redacted.length());
                redacted = rebuilt.toString();
                continue;
            }
            redacted = entry.getValue().matcher(redacted).replaceAll("[REDACTED:" + entry.getKey() + "]");
        }
        return redacted;
    }

    /** The standard Luhn checksum (ISO/IEC 7812) - the same algorithm every real card issuer
     *  validates against, used here purely as a false-positive filter, not as proof a number is
     *  a real/active card. */
    private boolean isLuhnValid(String candidate) {
        String digitsOnly = candidate.replaceAll("[ -]", "");
        if (digitsOnly.length() < 13 || digitsOnly.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digitsOnly.length() - 1; i >= 0; i--) {
            int digit = digitsOnly.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
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
            case CREDIT_CARD -> {
                String digitsOnly = value.replaceAll("[ -]", "");
                yield "*".repeat(digitsOnly.length() - 4) + digitsOnly.substring(digitsOnly.length() - 4);
            }
            case API_KEY, JWT, CREDENTIAL -> {
                // Secrets are masked entirely, not partially - unlike a phone/card number, even
                // the last few characters of an API key/JWT/password are not safe to retain,
                // since they narrow a brute-force/lookup search space for the rest.
                int visible = Math.min(3, value.length());
                yield value.substring(0, visible) + "*".repeat(Math.max(0, value.length() - visible));
            }
        };
    }
}
