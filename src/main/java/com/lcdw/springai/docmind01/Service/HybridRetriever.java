package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.DTO.CitationDto;
import com.lcdw.springai.docmind01.DTO.RetrievalDiagnostics;
import com.lcdw.springai.docmind01.Execption.ResourceNotFound_EXE;
import com.lcdw.springai.docmind01.Repo.DocumnetMetadatarepo;
import com.lcdw.springai.docmind01.Repo.ScopedChunkRepository;
import com.lcdw.springai.docmind01.Service.Security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class HybridRetriever {
    private final ScopedChunkRepository chunks;
    private final DocumnetMetadatarepo documents;
    private final VectorStore vectors;
    private final Bm25Ranker bm25;
    private final QueryRewriter rewriter;
    private final ContextCompressor compressor;
    private final RagGuardrails guardrails;
    private final Appproperties properties;

    public record Result(List<CitationDto> sources, RetrievalDiagnostics diagnostics) {}

    private static class Candidate {
        final Document document;
        Double semantic;
        double lexical;
        double fusion;
        double relevance;
        Candidate(Document document) { this.document = document; }
    }

    public Result retrieve(String query, String documentId, Integer topK, Double threshold, Boolean rewrite, String mode) {
        long start = System.nanoTime();
        String owner = CurrentUser.get().getId();
        guardrails.validateQuery(query, topK, threshold, mode);
        String scope = documentId == null || documentId.isBlank() ? null : documentId;
        if (scope != null) {
            documents.findByIdAndOwnerId(scope, owner)
                    .orElseThrow(() -> new ResourceNotFound_EXE("Document not found."));
        }
        Appproperties.ChunkingProperties settings = properties.getRag();
        int k = topK == null ? Math.min(20, Math.max(1, settings.getTopK())) : topK;
        int candidateK = Math.max(k, settings.getCandidateK());
        double minimumSimilarity = threshold == null ? settings.getSimilarityThreshold() : threshold;
        String retrievalMode = mode == null ? "hybrid" : mode;
        List<String> warnings = new ArrayList<>();
        List<Document> corpus = chunks.load(owner, scope, settings.getMaxCorpusChunks());
        Map<String, Document> safeCorpus = new LinkedHashMap<>();
        for (Document chunk : corpus) {
            if (owner.equals(chunk.getMetadata().get("ownerId"))
                    && (scope == null || scope.equals(chunk.getMetadata().get("documentId")))
                    && !guardrails.unsafe(chunk.getText())) {
                safeCorpus.put(chunk.getId(), chunk);
            }
        }
        if (corpus.size() != safeCorpus.size()) warnings.add("Unsafe or out-of-scope passages were excluded.");
        QueryRewriter.Result rewritten = safeCorpus.isEmpty()
                ? new QueryRewriter.Result(query.strip(), false, null)
                : rewriter.rewrite(query, !Boolean.FALSE.equals(rewrite));
        if (rewritten.warning() != null) warnings.add(rewritten.warning());
        List<String> queries = rewritten.applied() ? List.of(query.strip(), rewritten.query()) : List.of(query.strip());
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Set<String> semanticIds = new HashSet<>();
        Set<String> lexicalIds = new HashSet<>();
        if (!safeCorpus.isEmpty()) {
            for (String searchQuery : queries) {
                if (!retrievalMode.equals("bm25")) {
                    FilterExpressionBuilder filter = new FilterExpressionBuilder();
                    var expression = scope == null ? filter.eq("ownerId", owner).build()
                            : filter.and(filter.eq("ownerId", owner), filter.eq("documentId", scope)).build();
                    List<Document> hits = vectors.similaritySearch(SearchRequest.builder().query(searchQuery)
                            .topK(candidateK).similarityThreshold(minimumSimilarity).filterExpression(expression).build());
                    for (int i = 0; i < hits.size(); i++) {
                        Document hit = hits.get(i);
                        if (!safeCorpus.containsKey(hit.getId())) continue;
                        Map<String, Object> trusted = safeCorpus.get(hit.getId()).getMetadata();
                        if (!Objects.equals(trusted.get("ownerId"), hit.getMetadata().get("ownerId"))
                                || !Objects.equals(trusted.get("documentId"), hit.getMetadata().get("documentId"))
                                || !Objects.equals(trusted.get("vectorRevision"), hit.getMetadata().get("vectorRevision"))) continue;
                        Candidate c = candidates.computeIfAbsent(hit.getId(), id -> new Candidate(safeCorpus.get(id)));
                        double score = hit.getScore() == null ? 0 : hit.getScore();
                        c.semantic = c.semantic == null ? score : Math.max(c.semantic, score);
                        c.fusion += 1.0 / (60 + i + 1);
                        semanticIds.add(hit.getId());
                    }
                }
                if (!retrievalMode.equals("semantic")) {
                    List<Bm25Ranker.Hit> hits = bm25.rank(searchQuery, new ArrayList<>(safeCorpus.values()), candidateK,
                            settings.getBm25K1(), settings.getBm25B());
                    for (int i = 0; i < hits.size(); i++) {
                        Bm25Ranker.Hit hit = hits.get(i);
                        Candidate c = candidates.computeIfAbsent(hit.document().getId(), id -> new Candidate(hit.document()));
                        c.lexical = Math.max(c.lexical, hit.score());
                        c.fusion += 1.0 / (60 + i + 1);
                        lexicalIds.add(hit.document().getId());
                    }
                }
            }
        }
        List<Candidate> fused = candidates.values().stream().sorted(Comparator.comparingDouble((Candidate c) -> c.fusion)
                .reversed().thenComparing(c -> c.document.getId())).limit(candidateK).toList();
        Set<String> queryTokens = new HashSet<>(Bm25Ranker.tokens(String.join(" ", queries)));
        double maxLexical = fused.stream().mapToDouble(c -> c.lexical).max().orElse(1);
        for (Candidate c : fused) {
            Set<String> terms = new HashSet<>(Bm25Ranker.tokens(c.document.getText()));
            double coverage = queryTokens.isEmpty() ? 0 : (double) queryTokens.stream().filter(terms::contains).count() / queryTokens.size();
            double semantic = c.semantic == null ? 0 : Math.max(0, Math.min(1, c.semantic));
            double lexical = maxLexical == 0 ? 0 : c.lexical / maxLexical;
            c.relevance = retrievalMode.equals("bm25") ? 0.8 * lexical + 0.2 * coverage
                    : retrievalMode.equals("semantic") ? 0.8 * semantic + 0.2 * coverage
                    : 0.5 * semantic + 0.3 * lexical + 0.2 * coverage;
        }
        List<Candidate> ranked = fused.stream().sorted(Comparator.comparingDouble((Candidate c) -> c.relevance)
                .reversed().thenComparing(Comparator.comparingDouble((Candidate c) -> c.fusion).reversed())
                .thenComparing(c -> c.document.getId())).limit(k).toList();
        int before = ranked.stream().mapToInt(c -> c.document.getText().length()).sum();
        int remaining = settings.getContextMaxChars();
        List<CitationDto> sources = new ArrayList<>();
        for (Candidate c : ranked) {
            if (remaining <= 0) break;
            String text = compressor.compress(c.document.getText(), String.join(" ", queries), Math.min(remaining, settings.getPassageMaxChars()));
            if (text.isBlank()) continue;
            Map<String, Object> meta = c.document.getMetadata();
            sources.add(CitationDto.builder().citationId("S" + (sources.size() + 1)).chunkId(c.document.getId())
                    .documentId(Objects.toString(meta.get("documentId"), ""))
                    .fileName(Objects.toString(meta.get("fileName"), "Document"))
                    .pageNumber(number(meta.get("pageNumber"))).chunkIndex(number(meta.get("chunkIndex")))
                    .snippet(text).similarityScore(c.semantic).bm25Score(c.lexical).fusionScore(c.fusion)
                    .rerankScore(c.relevance).build());
            remaining -= text.length();
        }
        int after = sources.stream().mapToInt(c -> c.getSnippet().length()).sum();
        return new Result(sources, new RetrievalDiagnostics(rewritten.query(), rewritten.applied(), retrievalMode,
                semanticIds.size(), lexicalIds.size(), fused.size(), before, after,
                (System.nanoTime() - start) / 1_000_000, List.copyOf(warnings)));
    }

    private static Integer number(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try { return Integer.valueOf(value.toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
