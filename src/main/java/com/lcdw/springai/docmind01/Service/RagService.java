package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.DTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class RagService {
    private final HybridRetriever retriever;
    private final GroundedAnswerGenerator answerGenerator;
    private final DocumentSemanticCache cache;
    private final RagGuardrails guardrails;

    public ChatResponseDTO chat(ChatRequestDTO request) {
        long start = System.nanoTime();
        guardrails.validateQuery(request.getQuery(), request.getTopK(), request.getSimilarityThreshold(), request.getRetrievalMode());
        var scope = cache.scope(request);
        var lookup = cache.lookup(scope, request.getQuery());
        if (lookup.response() != null) {
            requireUnchangedScope(request, scope);
            ChatResponseDTO response = lookup.response();
            response.setQuery(request.getQuery());
            response.setCache(lookup.diagnostics());
            response.setTokenUsage(TokenUsage.noModelCall());
            response.setDiagnostics(new RetrievalDiagnostics(request.getQuery(), false, scope.mode(),
                    0, 0, 0, 0, 0, (System.nanoTime() - start) / 1_000_000,
                    java.util.List.of("Cached answer reused; retrieval, rewriting and answer generation were skipped.")));
            return response;
        }
        HybridRetriever.Result result = retriever.retrieve(request.getQuery(), request.getDocumentId(), request.getTopK(),
                request.getSimilarityThreshold(), request.getRewriteQuery(), request.getRetrievalMode());
        var generated = answerGenerator.generate(request.getQuery(), result.sources());
        var answer = generated.answer();
        ChatResponseDTO response = ChatResponseDTO.builder().query(request.getQuery()).answer(answer.text()).matches(generated.sources())
                .totalmatches(generated.sources().size()).diagnostics(result.diagnostics())
                .grounded(answer.supported()).abstained(!answer.supported()).cache(lookup.diagnostics()).tokenUsage(generated.usage()).build();
        requireUnchangedScope(request, scope);
        cache.store(lookup, response);
        return response;
    }

    private void requireUnchangedScope(ChatRequestDTO request, DocumentSemanticCache.Scope before) {
        if (!java.util.Objects.equals(before, cache.scope(request))) {
            throw new IllegalStateException("The document changed while answering. Retry the question.");
        }
    }

    public Flux<String> streamChat(ChatRequestDTO request) {
        // Validate the whole answer before exposing any generated tokens.
        return Flux.just(chat(request).getAnswer());
    }

    public SearchanswerDTO searchanswer(SearcRequestDTO request) {
        HybridRetriever.Result result = retriever.retrieve(request.getQuery(), request.getDocumentId(), request.getTopK(),
                request.getSimilarityThreshold(), request.getRewriteQuery(), request.getRetrievalMode());
        return SearchanswerDTO.builder().query(request.getQuery()).matches(result.sources())
                .totalmatches(result.sources().size()).diagnostics(result.diagnostics()).build();
    }
}
