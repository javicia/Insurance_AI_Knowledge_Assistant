package com.rag.springai.insuranceai.adapters.outbound.security;

import com.rag.springai.insuranceai.domain.security.PromptInjectionAssessment;
import com.rag.springai.insuranceai.ports.outbound.PromptInjectionGuardPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A deterministic, offline, non-ML {@link PromptInjectionGuardPort} implementation (brief FASE 8
 * section 9): a fixed list of named, case-insensitive regexes for well-known injection phrasing
 * (instruction override, system prompt extraction, role-marker spoofing, jailbreak framing).
 *
 * <p><b>This is a PoC rule-based guard, not an ML/LLM-based prompt injection classifier</b>, and
 * must never be presented as equivalent protection to one (brief section 9/60). It only catches
 * phrasing that matches one of these fixed patterns literally - trivially bypassable by
 * paraphrasing, translation, encoding, or any phrasing not on this list. The real, structural
 * defense against retrieved-document injection remains what FASE 5 already built: retrieved
 * content is always sent as a delimited, explicitly-untrusted {@code UserMessage} section, never
 * merged into the {@code SystemMessage} channel (see {@code LlmMessageFormatter}). This guard
 * adds detection/observability on top of that structural boundary, not a replacement for it.
 */
@Component
public class RuleBasedPromptInjectionGuard implements PromptInjectionGuardPort {

    private static final Map<String, Pattern> PATTERNS = Map.ofEntries(
            Map.entry("ignore_instructions",
                    Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions",
                            Pattern.CASE_INSENSITIVE)),
            Map.entry("disregard_instructions",
                    Pattern.compile("disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions",
                            Pattern.CASE_INSENSITIVE)),
            Map.entry("reveal_system_prompt",
                    Pattern.compile("(reveal|show|print|display)\\s+(me\\s+)?(the\\s+)?system\\s+prompt",
                            Pattern.CASE_INSENSITIVE)),
            Map.entry("developer_or_jailbreak_mode",
                    Pattern.compile("(developer\\s+mode|jailbreak|\\bDAN\\b)", Pattern.CASE_INSENSITIVE)),
            Map.entry("forget_instructions",
                    Pattern.compile("forget\\s+(everything|all)\\s+(you\\s+(were|have\\s+been)\\s+told|instructions)",
                            Pattern.CASE_INSENSITIVE)),
            Map.entry("override_instructions",
                    Pattern.compile("override\\s+(your\\s+|the\\s+)?instructions", Pattern.CASE_INSENSITIVE)),
            Map.entry("new_instructions_marker",
                    Pattern.compile("new\\s+instructions\\s*:", Pattern.CASE_INSENSITIVE)),
            Map.entry("fake_role_marker",
                    Pattern.compile("^\\s*(system|assistant)\\s*:", Pattern.CASE_INSENSITIVE)));

    @Override
    public PromptInjectionAssessment scan(String text) {
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
            if (entry.getValue().matcher(text).find()) {
                matched.add(entry.getKey());
            }
        }
        return new PromptInjectionAssessment(!matched.isEmpty(), matched);
    }
}
