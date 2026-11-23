import unittest
from run_eval import ranking_metrics, token_f1, citation_validity


class MetricsTest(unittest.TestCase):
    def test_perfect_ranking(self):
        scores = ranking_metrics(["a", "b"], ["a", "b"], 2)
        self.assertTrue(all(value == 1 for value in scores.values()))

    def test_duplicates_cannot_inflate_recall(self):
        scores = ranking_metrics(["a", "a", "b"], ["a", "c"], 3)
        self.assertEqual(scores["recall_at_k"], 0.5)
        self.assertAlmostEqual(scores["precision_at_k"], 1 / 3)

    def test_no_relevance_is_not_perfect_recall(self):
        self.assertIsNone(ranking_metrics([], [], 5)["recall_at_k"])
        self.assertEqual(ranking_metrics(["a"], ["b"], 5)["mrr"], 0)

    def test_mrr_tracks_first_relevant_position(self):
        self.assertEqual(ranking_metrics(["x", "a"], ["a"], 2)["mrr"], 0.5)

    def test_f1_removes_citation_markers(self):
        self.assertEqual(token_f1("Refunds take 30 days [S1].", "Refunds take 30 days."), 1)
        self.assertEqual(token_f1("Oranges", "Apples"), 0)

    def test_fabricated_citation_is_invalid(self):
        sources = [{"citationId": "S1"}]
        self.assertEqual(citation_validity("Fact [S1] and another [S9]", sources), 0.5)
        self.assertIsNone(citation_validity("No evidence", sources))


if __name__ == "__main__":
    unittest.main()
