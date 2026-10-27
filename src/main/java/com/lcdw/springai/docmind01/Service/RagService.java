package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.DTO.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    @Value("classpath:CHAT_PROMPT_TEMPLATE.st")
    private Resource promptTemplateResource;

    private String chatPromptTemplate;

    @PostConstruct
    private void loadPromptTemplate() throws IOException {
        chatPromptTemplate = promptTemplateResource.getContentAsString(StandardCharsets.UTF_8);
    }


    private final VectorStore vectorStore;
    private final Appproperties appproperties;
    private final ChatClient chatClient;

    public ChatResponseDTO chat(ChatRequestDTO chatRequestDTO) {

        List<Document> matchdocs = reterivedoumnet(
                chatRequestDTO.getDocumentId(),
                chatRequestDTO.getQuery(),
                chatRequestDTO.getTopK(),
                chatRequestDTO.getSimilarityThreshold()
        );

        List<CitationDto> citations = toCitations(matchdocs);

        String answer = chatClient.prompt()
                .user(u -> u.text(chatPromptTemplate)
                        .param("context", buildContext(matchdocs))
                        .param("query", chatRequestDTO.getQuery()))
                .call()
                .content();

        return ChatResponseDTO.builder()
                .query(chatRequestDTO.getQuery())
                .answer(answer)
                .totalmatches(citations.size())
                .matches(citations)
                .build();
    }
    public Flux<String> streamChat(ChatRequestDTO chatRequestDTO) {
        return chatClient.prompt()
                .user(u -> u.text(chatPromptTemplate)
                        .param("context", buildContext(reterivedoumnet(
                                chatRequestDTO.getDocumentId(),
                                chatRequestDTO.getQuery(),
                                chatRequestDTO.getTopK(),
                                chatRequestDTO.getSimilarityThreshold()
                        )))
                        .param("query", chatRequestDTO.getQuery()))
                .stream().content();
    }

    public SearchanswerDTO searchanswer(SearcRequestDTO searcRequestDTO) {

        List<Document> matchdocs = reterivedoumnet(
                searcRequestDTO.getDocumentId(),
                searcRequestDTO.getQuery(),
                searcRequestDTO.getTopK(),
                searcRequestDTO.getSimilarityThreshold()
        );

        List<CitationDto> citations = toCitations(matchdocs);

        return SearchanswerDTO.builder()
                .query(searcRequestDTO.getQuery())
                .totalmatches(citations.size())
                .matches(citations)
                .build();
    }

    private List<CitationDto> toCitations(List<Document> matchdocs) {
        return matchdocs.stream()
                .map(this::mapToCitation)
                .toList();
    }

    private String buildContext(List<Document> matchdocs) {
        if (matchdocs.isEmpty()) {
            return "NO ANSWER FOUND IN DOCUMENTS";
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < matchdocs.size(); i++) {
            Document document = matchdocs.get(i);
            Map<String, Object> metadata = document.getMetadata();

            context.append("--- passage ").append(i + 1).append(" [")
                    .append(metadata.getOrDefault("fileName", "unknown"));

            Object pageNumber = metadata.get("pageNumber");
            if (pageNumber != null) {
                context.append(", page ").append(pageNumber);
            }

            context.append("] ---\n")
                    .append(document.getText())
                    .append("\n\n");
        }
        return context.toString().strip();
    }

    private CitationDto mapToCitation(Document document) {

        Map<String, Object> metadata = document.getMetadata();

        Object documentId = metadata.get("documentId");
        Object fileName = metadata.get("fileName");

        return CitationDto.builder()
                .documentId(documentId != null
                        ? documentId.toString()
                        : null)
                .fileName(fileName != null
                        ? fileName.toString()
                        : null)
                .chunkIndex(toInteger(metadata.get("chunkIndex")))
                .pageNumber(toInteger(metadata.get("pageNumber")))
                .snippet(document.getText())
                .similarityScore(document.getScore())
                .metadata(metadata)
                .build();
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return (int) Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("could not read '{}' as a number, leaving it null", value);
            return null;
        }
    }

    private List<Document> reterivedoumnet(
            String documentId,
            String query,
            Integer topK,
            Double similarityThreshold
    ) {

        int effectiveTopK = (topK != null && topK > 0)
                ? topK
                : appproperties.getRag().getTopK();

        double effectiveSimilarity = (similarityThreshold != null)
                ? similarityThreshold
                : appproperties.getRag().getSimilarityThreshold();

        SearchRequest.Builder searchRequestBuilder = SearchRequest
                .builder()
                .query(query)
                .topK(effectiveTopK);

        if (effectiveSimilarity > 0.0) {
            searchRequestBuilder.similarityThreshold(effectiveSimilarity);
        }

        if (documentId != null && !documentId.isBlank()) {

            FilterExpressionBuilder builder =
                    new FilterExpressionBuilder();

            searchRequestBuilder.filterExpression(
                    builder.eq("documentId", documentId).build()
            );
        }

        try {
            List<Document> documents = vectorStore.similaritySearch(
                    searchRequestBuilder.build()
            );

            log.info("Retrieved {} chunks for query: '{}' (documentId: {})",
                    documents.size(),
                    query,
                    documentId);

            return documents;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to retrieve relevant document chunks",
                    e
            );
        }
    }
}
