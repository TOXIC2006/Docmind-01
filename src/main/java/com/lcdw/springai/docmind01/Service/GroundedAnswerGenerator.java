package com.lcdw.springai.docmind01.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.DTO.CitationDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@lombok.extern.slf4j.Slf4j
@Service
public class GroundedAnswerGenerator {
    private final ChatClient client;
    private final ObjectMapper mapper;
    private final RagGuardrails guardrails;
    private final String instructions;
    private final PromptBudget budget;
    private final com.lcdw.springai.docmind01.Confi.Appproperties properties;

    public GroundedAnswerGenerator(ChatClient client, ObjectMapper mapper, RagGuardrails guardrails,
            @Value("classpath:CHAT_PROMPT_TEMPLATE.st") Resource template, PromptBudget budget,
            com.lcdw.springai.docmind01.Confi.Appproperties properties) throws IOException {
        this.client = client;
        this.mapper = mapper;
        this.guardrails = guardrails;
        this.instructions = template.getContentAsString(StandardCharsets.UTF_8);
        this.budget = budget;
        this.properties = properties;
    }

    public record Answer(String text, boolean supported) {}

    public record Generated(Answer answer, List<CitationDto> sources, com.lcdw.springai.docmind01.DTO.TokenUsage usage) {}

    public Generated generate(String query, List<CitationDto> sources) {
        if (sources.isEmpty()) return new Generated(new Answer(RagGuardrails.ABSTENTION, false), List.of(),
                com.lcdw.springai.docmind01.DTO.TokenUsage.noModelCall());
        var prepared = budget.prepare(instructions, query, sources);
        if (prepared.sources().isEmpty()) return new Generated(new Answer(RagGuardrails.ABSTENTION, false), List.of(),
                new com.lcdw.springai.docmind01.DTO.TokenUsage(prepared.before(), prepared.after(), 0, 0, 0));
        int completionLimit = properties.getTokens().getMaxCompletionTokens();
        var response = client.prompt().system(instructions).user(prepared.input())
                .options(ChatOptions.builder().temperature(0.0).maxTokens(completionLimit).build()).call().chatResponse();
        String raw = response.getResult().getOutput().getText();
        Answer answer = validate(raw, prepared.sources());
        if (!answer.supported()) {
            log.warn("Answer did not pass grounding validation; finishReason={}, outputCharacters={}",
                    response.getResult().getMetadata().getFinishReason(), raw == null ? 0 : raw.length());
        }
        var usage = response.getMetadata().getUsage();
        return new Generated(answer, prepared.sources(), new com.lcdw.springai.docmind01.DTO.TokenUsage(
                prepared.before(), prepared.after(), usage == null || usage instanceof org.springframework.ai.chat.metadata.EmptyUsage ? null : usage.getPromptTokens(),
                usage == null || usage instanceof org.springframework.ai.chat.metadata.EmptyUsage ? null : usage.getCompletionTokens(), completionLimit));
    }

    Answer validate(String raw, List<CitationDto> sources) {
        try {
            if (raw == null || raw.length() > 12000) return new Answer(RagGuardrails.ABSTENTION, false);
            String json = raw.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) return new Answer(RagGuardrails.ABSTENTION, false);
            String answer = root.path("answer").asText("");
            JsonNode references = root.path("citations");
            Set<String> allowed = new HashSet<>();
            sources.forEach(c -> allowed.add(c.getCitationId()));
            if (!root.path("supported").asBoolean(false) || answer.isBlank() || !references.isArray()
                    || references.isEmpty() || guardrails.unsafe(answer)) {
                log.warn("Structured answer rejected: supported={}, blankAnswer={}, citationCount={}, unsafe={}",
                        root.path("supported").asBoolean(false), answer.isBlank(), references.size(), guardrails.unsafe(answer));
                return new Answer(RagGuardrails.ABSTENTION, false);
            }
            Set<String> declared = new HashSet<>();
            for (JsonNode ref : references) {
                if (!allowed.contains(ref.asText())) {
                    log.warn("Structured answer contains an unknown citation ID");
                    return new Answer(RagGuardrails.ABSTENTION, false);
                }
                declared.add(ref.asText());
            }
            Set<String> inline = new HashSet<>();
            var matcher = Pattern.compile("\\[(S\\d+)\\]").matcher(answer);
            while (matcher.find()) inline.add(matcher.group(1));
            if (!inline.equals(declared)) {
                log.warn("Structured answer citation mismatch: inlineCount={}, declaredCount={}", inline.size(), declared.size());
                return new Answer(RagGuardrails.ABSTENTION, false);
            }
            return new Answer(answer, true);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Structured answer is not valid JSON");
            return new Answer(RagGuardrails.ABSTENTION, false);
        }
    }
}
