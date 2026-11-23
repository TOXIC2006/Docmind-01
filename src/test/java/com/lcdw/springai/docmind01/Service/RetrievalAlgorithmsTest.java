package com.lcdw.springai.docmind01.Service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RetrievalAlgorithmsTest {
    private final Bm25Ranker ranker = new Bm25Ranker();
    private Document doc(String id, String text) { return Document.builder().id(id).text(text).build(); }

    @Test void exactIdentifiersOutrankGeneralWords() {
        var corpus = List.of(doc("a", "Service error ERR_502A requires rotating the upstream certificate."),
                doc("b", "Service errors are reported in the logs. Service alerts notify operators."));
        var hits = ranker.rank("ERR_502A", corpus, 5, 1.5, 0.75);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).document().getId()).isEqualTo("a");
    }

    @Test void questionFillerDoesNotTieAnExactIdentifierMatch() {
        var hits = ranker.rank("How can I resolve ERR_502A?", List.of(
                doc("a", "Customers can request a refund within 30 days."),
                doc("z", "ERR_502A requires renewal of the upstream TLS certificate.")), 5, 1.5, 0.75);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).document().getId()).isEqualTo("z");
    }

    @Test void bm25MatchesThePublishedFormula() {
        var hits = ranker.rank("apple", List.of(doc("a", "apple apple"), doc("b", "pear plum")), 5, 1.5, 0.75);
        double expected = Math.log(1 + 1.5 / 1.5) * 2 * 2.5 / (2 + 1.5);
        assertThat(hits.get(0).score()).isCloseTo(expected, within(0.0000001));
    }

    @Test void emptyAndUnmatchedCorporaProduceNoHits() {
        assertThat(ranker.rank("x", List.of(), 5, 1.5, 0.75)).isEmpty();
        assertThat(ranker.rank("saturn", List.of(doc("a", "annual revenue")), 5, 1.5, 0.75)).isEmpty();
        assertThat(ranker.rank("the and is", List.of(doc("a", "the")), 5, 1.5, 0.75)).isEmpty();
    }

    @Test void unicodeAndIdentifiersArePreserved() {
        assertThat(Bm25Ranker.tokens("ERR_502A Café 東京 model-v2")).contains("err_502a", "café", "東京", "model-v2");
    }

    @Test void lengthNormalizationPenalizesVerboseMatches() {
        var hits = ranker.rank("refund", List.of(doc("long", "refund " + "unrelated ".repeat(100)), doc("short", "refund policy")), 1, 1.5, 0.75);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).document().getId()).isEqualTo("short");
    }

    @Test void compressionSelectsRelevantVerbatimSentencesWithinBudget() {
        String text = "The office opens daily. Refunds are available within 30 days. Parking is across the street.";
        String compressed = new ContextCompressor().compress(text, "refunds", 50);
        assertThat(compressed).contains("Refunds are available within 30 days.");
        assertThat(compressed.length()).isLessThanOrEqualTo(50);
        for (String line : compressed.split("\\n")) assertThat(text).contains(line);
    }

    @Test void compressionHandlesLongUnbrokenTextAndTinyBudgets() {
        var compressor = new ContextCompressor();
        assertThat(compressor.compress("x".repeat(200), "x", 10)).hasSize(10);
        assertThat(compressor.compress("hello", "hello", 0)).isEmpty();
        assertThat(compressor.compress("same text", "same", 100)).isEqualTo("same text");
    }

    @Test void compressionKeepsAnExactMatchLateInALongSentence() {
        String text = "background ".repeat(100) + "ERR_502A requires certificate renewal " + "details ".repeat(100);
        assertThat(new ContextCompressor().compress(text, "ERR_502A", 80)).contains("ERR_502A requires certificate renewal");
    }

    @Test void compressionRetainsOriginalSentenceOrder() {
        String text = "First refund rule. Unrelated background of little importance. Second refund rule.";
        assertThat(new ContextCompressor().compress(text, "refund", 40)).isEqualTo("First refund rule.\nSecond refund rule.");
    }
}
