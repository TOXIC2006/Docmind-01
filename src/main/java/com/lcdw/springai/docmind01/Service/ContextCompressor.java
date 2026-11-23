package com.lcdw.springai.docmind01.Service;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ContextCompressor {
    public String compress(String text, String query, int maxCharacters) {
        if (maxCharacters <= 0) return "";
        if (text.length() <= maxCharacters) return text.strip();
        Set<String> queryTerms = new HashSet<>(Bm25Ranker.tokens(query));
        String[] sentences = text.split("(?<=[.!?。！？])\\s+|\\n+");
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sentences.length; i++) order.add(i);
        order.sort(Comparator.<Integer>comparingLong(i -> Bm25Ranker.tokens(sentences[i]).stream()
                .distinct().filter(queryTerms::contains).count()).reversed().thenComparingInt(i -> i));
        TreeMap<Integer, String> selected = new TreeMap<>();
        int remaining = maxCharacters;
        for (int index : order) {
            String sentence = sentences[index].strip();
            if (sentence.isEmpty()) continue;
            int separator = selected.isEmpty() ? 0 : 1;
            if (sentence.length() + separator <= remaining) {
                selected.put(index, sentence);
                remaining -= sentence.length() + separator;
            } else if (selected.isEmpty()) {
                String lower = sentence.toLowerCase(Locale.ROOT);
                int anchor = queryTerms.stream().mapToInt(lower::indexOf).filter(position -> position >= 0).min().orElse(0);
                int start = Math.max(0, Math.min(anchor - remaining / 3, sentence.length() - remaining));
                selected.put(index, sentence.substring(start, start + remaining));
                remaining = 0;
            }
        }
        return String.join("\n", selected.values());
    }
}
