package com.lcdw.springai.docmind01.Service;

import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class RagGuardrails {
    public static final String ABSTENTION = "I couldn't find sufficient evidence in your selected documents. Try a more specific question or upload a relevant document.";
    private static final Pattern INJECTION = Pattern.compile(
            "(?is)(ignore|disregard|override|forget).{0,50}(previous|above|system|developer|instructions)"
            + "|(reveal|print|show|repeat|expose).{0,50}(system prompt|developer message|api.?key|password|secret key)"
            + "|you are now|<\\|(?:system|assistant|im_start)\\|>|\\[INST\\]|(?:^|\\n)\\s*(system|developer)\\s*:");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(?:sk-[a-z0-9_-]{16,}|gsk_[a-z0-9]{16,}|AKIA[A-Z0-9]{16})\\b"
            + "|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
            + "|\\b(?:password|api[_ -]?key|secret[_ -]?key|access[_ -]?token)\\s*[:=]\\s*\\S+");

    public void validateQuery(String query, Integer topK, Double threshold, String mode) {
        if (query == null || query.isBlank() || query.length() > 2000) {
            throw new IllegalArgumentException("Enter a question between 1 and 2000 characters.");
        }
        if (topK != null && (topK < 1 || topK > 20)) {
            throw new IllegalArgumentException("topK must be between 1 and 20.");
        }
        if (threshold != null && (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)) {
            throw new IllegalArgumentException("similarityThreshold must be between 0 and 1.");
        }
        if (mode != null && !java.util.Set.of("hybrid", "semantic", "bm25").contains(mode)) {
            throw new IllegalArgumentException("retrievalMode must be hybrid, semantic, or bm25.");
        }
        if (unsafe(query)) {
            throw new IllegalArgumentException("This request contains instructions or credentials that cannot be used for document search.");
        }
    }

    public boolean unsafe(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cf}]", "").toLowerCase(Locale.ROOT);
        return INJECTION.matcher(normalized).find() || SECRET.matcher(text).find();
    }
}
