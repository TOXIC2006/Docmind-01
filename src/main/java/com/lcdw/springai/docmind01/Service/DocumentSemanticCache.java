package com.lcdw.springai.docmind01.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.DTO.*;
import com.lcdw.springai.docmind01.Entity.Document_Status;
import com.lcdw.springai.docmind01.Execption.ResourceNotFound_EXE;
import com.lcdw.springai.docmind01.Repo.DocumnetMetadatarepo;
import com.lcdw.springai.docmind01.Service.Security.CurrentUser;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class DocumentSemanticCache {
    private final DocumnetMetadatarepo documents;
    private final EmbeddingModel embeddings;
    private final ObjectMapper mapper;
    private final Appproperties properties;
    private final Clock clock;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private static final Pattern SENSITIVE_TERMS = Pattern.compile(
            "[\\p{L}\\p{N}_-]*[\\d_-][\\p{L}\\p{N}_-]*|\\b(?:no|not|never|without|except|excluding|before|after|less|more|only|cannot|can't|isn't|doesn't|नहीं|बिना)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Autowired
    public DocumentSemanticCache(DocumnetMetadatarepo documents, EmbeddingModel embeddings, ObjectMapper mapper, Appproperties properties) {
        this(documents, embeddings, mapper, properties, Clock.systemUTC());
    }

    DocumentSemanticCache(DocumnetMetadatarepo documents, EmbeddingModel embeddings, ObjectMapper mapper, Appproperties properties, Clock clock) {
        this.documents = documents;
        this.embeddings = embeddings;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    public record Scope(String ownerId, String documentId, String revision, int topK, double threshold, boolean rewrite, String mode) {}
    private record Key(Scope scope, String query) {}
    private record Entry(float[] vector, String response, long createdAt) {}
    public record Lookup(Scope scope, String query, float[] vector, ChatResponseDTO response, CacheDiagnostics diagnostics) {}

    public Scope scope(ChatRequestDTO request) {
        String owner = CurrentUser.get().getId();
        if (request.getDocumentId() == null || request.getDocumentId().isBlank()) return null;
        var document = documents.findByIdAndOwnerId(request.getDocumentId(), owner)
                .orElseThrow(() -> new ResourceNotFound_EXE("Document not found."));
        if (document.getStatus() != Document_Status.indexed || document.getVectorRevision() == null) return null;
        var rag = properties.getRag();
        return new Scope(owner, document.getId(), document.getVectorRevision(),
                request.getTopK() == null ? Math.min(20, Math.max(1, rag.getTopK())) : request.getTopK(),
                request.getSimilarityThreshold() == null ? rag.getSimilarityThreshold() : request.getSimilarityThreshold(),
                !Boolean.FALSE.equals(request.getRewriteQuery()), request.getRetrievalMode() == null ? "hybrid" : request.getRetrievalMode());
    }

    public Lookup lookup(Scope scope, String query) {
        String normalized = query.strip().replaceAll("\\s+", " ");
        if (scope == null) return new Lookup(null, normalized, null, null, new CacheDiagnostics("BYPASS", null, 0));
        Key exact = new Key(scope, normalized);
        synchronized (entries) {
            expire();
            Entry entry = entries.get(exact);
            if (entry != null) return hit(scope, normalized, entry, "EXACT_HIT", 1.0);
        }
        float[] vector;
        try {
            vector = embeddings.embed(normalized);
            if (cosine(vector, vector) < 0.99) throw new IllegalStateException("Invalid embedding");
        } catch (RuntimeException e) {
            return new Lookup(scope, normalized, null, null, new CacheDiagnostics("UNAVAILABLE", null, 0));
        }
        synchronized (entries) {
            expire();
            Key best = null;
            double similarity = properties.getCache().getSimilarityThreshold();
            for (var candidate : entries.entrySet()) {
                if (!candidate.getKey().scope().equals(scope)
                        || !sensitiveTerms(candidate.getKey().query()).equals(sensitiveTerms(normalized))) continue;
                double score = cosine(vector, candidate.getValue().vector());
                if (score >= similarity) {
                    similarity = score;
                    best = candidate.getKey();
                }
            }
            if (best != null) return hit(scope, normalized, entries.get(best), "SEMANTIC_HIT", similarity);
        }
        return new Lookup(scope, normalized, vector, null, new CacheDiagnostics("MISS", null, 0));
    }

    public void store(Lookup lookup, ChatResponseDTO response) {
        if (lookup.scope() == null || lookup.vector() == null || !response.isGrounded() || response.isAbstained()
                || response.getMatches().isEmpty() || response.getMatches().stream()
                .anyMatch(c -> !lookup.scope().documentId().equals(c.getDocumentId()))) return;
        try {
            Entry entry = new Entry(lookup.vector().clone(), mapper.writeValueAsString(response), clock.millis());
            synchronized (entries) {
                expire();
                entries.put(new Key(lookup.scope(), lookup.query()), entry);
                while (entries.size() > properties.getCache().getMaxEntries()) entries.remove(entries.keySet().iterator().next());
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to cache the answer.", e);
        }
    }

    public void evictDocument(String owner, String documentId) {
        synchronized (entries) {
            entries.keySet().removeIf(key -> key.scope().ownerId().equals(owner) && key.scope().documentId().equals(documentId));
        }
    }

    private Lookup hit(Scope scope, String query, Entry entry, String status, double similarity) {
        try {
            return new Lookup(scope, query, null, mapper.readValue(entry.response(), ChatResponseDTO.class),
                    new CacheDiagnostics(status, similarity, Math.max(0, (clock.millis() - entry.createdAt()) / 1000)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read the cached answer.", e);
        }
    }

    private void expire() {
        long cutoff = clock.millis() - properties.getCache().getTtlSeconds() * 1000;
        entries.values().removeIf(entry -> entry.createdAt() <= cutoff);
    }

    private static Set<String> sensitiveTerms(String query) {
        Set<String> terms = new HashSet<>();
        var matcher = SENSITIVE_TERMS.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) terms.add(matcher.group());
        return terms;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return -1;
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return -1;
            dot += (double) a[i] * b[i];
            aa += (double) a[i] * a[i];
            bb += (double) b[i] * b[i];
        }
        return aa == 0 || bb == 0 ? -1 : Math.min(1, dot / Math.sqrt(aa * bb));
    }
}
