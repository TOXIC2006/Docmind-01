package com.lcdw.springai.docmind01.DTO;

public record TokenUsage(int estimatedPromptTokensBefore, int estimatedPromptTokensAfter,
        Integer promptTokens, Integer completionTokens, int completionTokenLimit) {
    public static TokenUsage noModelCall() {
        return new TokenUsage(0, 0, 0, 0, 0);
    }
}
