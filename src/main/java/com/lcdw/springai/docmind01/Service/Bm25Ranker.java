package com.lcdw.springai.docmind01.Service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.util.*;

@Component
public class Bm25Ranker {
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "is", "are", "was", "were", "of", "to", "in", "for", "and", "or", "on", "it", "what", "how", "does", "do", "with", "my", "this", "that", "me", "about", "please", "i", "you", "we", "can", "could", "would", "should");
    public record Hit(Document document, double score) {}

    public static List<String> tokens(String text) {
        return Arrays.stream(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .split("[^\\p{L}\\p{N}_-]+"))
                .filter(t -> !t.isBlank() && !STOP_WORDS.contains(t)).toList();
    }

    public List<Hit> rank(String query, List<Document> corpus, int limit, double k1, double b) {
        if (corpus.isEmpty()) return List.of();
        List<Map<String, Integer>> frequencies = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        List<Integer> lengths = new ArrayList<>();
        for (Document document : corpus) {
            List<String> terms = tokens(document.getText());
            Map<String, Integer> counts = new HashMap<>();
            terms.forEach(t -> counts.merge(t, 1, Integer::sum));
            counts.keySet().forEach(t -> documentFrequency.merge(t, 1, Integer::sum));
            frequencies.add(counts);
            lengths.add(terms.size());
        }
        double averageLength = Math.max(1, lengths.stream().mapToInt(Integer::intValue).average().orElse(1));
        Set<String> queryTerms = new HashSet<>(tokens(query));
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            double score = 0;
            for (String term : queryTerms) {
                int tf = frequencies.get(i).getOrDefault(term, 0);
                if (tf == 0) continue;
                int df = documentFrequency.get(term);
                double idf = Math.log(1 + (corpus.size() - df + 0.5) / (df + 0.5));
                score += idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * lengths.get(i) / averageLength));
            }
            if (score > 0) hits.add(new Hit(corpus.get(i), score));
        }
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h -> h.document().getId())).limit(limit).toList();
    }
}
