package com.lcdw.springai.docmind01.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.DTO.CitationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class PromptBudget {
    private final ObjectMapper mapper;
    private final Appproperties properties;
    private final JTokkitTokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    public record Prepared(String input, List<CitationDto> sources, int before, int after) {}

    public Prepared prepare(String instructions, String query, List<CitationDto> sources) {
        int before = estimate(instructions, input(query, sources));
        int limit = properties.getTokens().getMaxPromptTokens();
        List<CitationDto> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String input = input(query, selected);
        if (estimate(instructions, input) > limit) {
            throw new IllegalArgumentException("The question and instructions exceed the prompt token budget. Shorten the question or increase app.tokens.max-prompt-tokens.");
        }
        for (CitationDto source : sources) {
            if (!seen.add(source.getSnippet().strip().replaceAll("\\s+", " "))) continue;
            selected.add(source);
            String candidate = input(query, selected);
            if (estimate(instructions, candidate) > limit) selected.remove(selected.size() - 1);
            else input = candidate;
        }
        return new Prepared(input, List.copyOf(selected), before, estimate(instructions, input));
    }

    private int estimate(String instructions, String input) {
        return estimator.estimate(instructions) + estimator.estimate(input) + properties.getTokens().getSafetyMarginTokens();
    }

    private String input(String query, List<CitationDto> sources) {
        try {
            List<Map<String, String>> passages = sources.stream()
                    .map(c -> Map.of("id", c.getCitationId(), "text", c.getSnippet())).toList();
            return mapper.writeValueAsString(Map.of("question", query, "untrusted_passages", passages));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to prepare the answer request.", e);
        }
    }
}
