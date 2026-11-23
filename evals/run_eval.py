"""Evaluate a running DocMind instance against human-labeled JSONL cases."""
import argparse
import collections
import json
import math
import os
import re
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def ranking_metrics(retrieved, relevant, k):
    ranked = list(dict.fromkeys(retrieved))[:k]
    relevant = set(relevant)
    hits = [int(item in relevant) for item in ranked]
    dcg = sum(hit / math.log2(i + 2) for i, hit in enumerate(hits))
    ideal = sum(1 / math.log2(i + 2) for i in range(min(len(relevant), k)))
    return {
        "precision_at_k": sum(hits) / k,
        "recall_at_k": sum(hits) / len(relevant) if relevant else None,
        "mrr": next((1 / (i + 1) for i, hit in enumerate(hits) if hit), 0),
        "ndcg_at_k": dcg / ideal if ideal else None,
    }


def token_f1(answer, reference):
    tokens = lambda text: re.findall(r"\w+", re.sub(r"\[S\d+\]", "", text, flags=re.IGNORECASE).lower())
    actual, expected = collections.Counter(tokens(answer)), collections.Counter(tokens(reference))
    overlap = sum((actual & expected).values())
    if not actual or not expected:
        return float(actual == expected)
    precision, recall = overlap / sum(actual.values()), overlap / sum(expected.values())
    return 2 * precision * recall / (precision + recall) if overlap else 0


def citation_validity(answer, sources):
    cited = set(re.findall(r"\[(S\d+)\]", answer))
    available = {source["citationId"] for source in sources}
    return len(cited & available) / len(cited) if cited else None


def source_key(source):
    return f"{source['fileName']}#{source['chunkIndex']}"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(base_url, token, path, body=None):
    headers = {"Authorization": f"Bearer {token}"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(base_url + path, data=data, headers=headers)
    start = time.perf_counter()
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=180) as response:
            return response.status, json.load(response), round((time.perf_counter() - start) * 1000)
    except urllib.error.HTTPError as error:
        return error.code, {}, round((time.perf_counter() - start) * 1000)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.example.jsonl"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--modes", default="hybrid")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--no-rewrite", action="store_true")
    parser.add_argument("--chat", action="store_true", help="Also measure answers; makes additional model calls")
    parser.add_argument("--min-recall", type=float, default=0.0)
    args = parser.parse_args()
    token = os.environ.get("DOCMIND_EVAL_TOKEN")
    if not token:
        parser.error("Set DOCMIND_EVAL_TOKEN to your own authenticated account token.")
    parsed = urllib.parse.urlparse(args.base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        parser.error("Provide an HTTP(S) base URL without credentials, query, or fragment.")
    if parsed.scheme == "http" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("Use HTTPS when sending an authentication token to a non-local server.")
    if args.output.exists():
        parser.error("Output exists; choose a new filename to preserve previous runs.")
    if not args.output.parent.is_dir():
        parser.error("The output parent directory must exist.")
    modes = args.modes.split(",")
    if not set(modes) <= {"hybrid", "semantic", "bm25"} or not 1 <= args.top_k <= 20 or not 0 <= args.min_recall <= 1:
        parser.error("Invalid mode, top-k (1–20), or min-recall (0–1).")
    cases = [json.loads(line) for line in args.cases.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not cases or len({case["id"] for case in cases}) != len(cases):
        parser.error("Provide non-empty cases with unique IDs.")
    status, documents, _ = request(args.base_url.rstrip("/"), token, "/api/v1/documents")
    if status != 200:
        parser.error(f"Cannot read the account's documents (HTTP {status}).")
    owned = {document["id"] for document in documents}
    names = {document["fileName"] for document in documents if document["status"] == "indexed"}
    required = {source["fileName"] for case in cases for source in case.get("relevantSources", [])}
    if required - names:
        parser.error("Upload and index the labeled fixture files first: " + ", ".join(sorted(required - names)))
    results = []
    for mode in modes:
        for case in cases:
            body = {"query": case["query"], "documentId": case.get("documentId"), "topK": args.top_k,
                    "similarityThreshold": case.get("similarityThreshold", 0.25), "retrievalMode": mode,
                    "rewriteQuery": not args.no_rewrite}
            expected = case.get("expectedStatus", 200)
            status, response, latency = request(args.base_url.rstrip("/"), token, "/api/v1/search", body)
            row = {"id": case["id"], "mode": mode, "http_status": status, "search_latency_ms": latency,
                   "passed": status == expected}
            if status == 200:
                data = response["data"]
                sources = data["matches"]
                relevant = [source_key(source) for source in case.get("relevantSources", [])]
                if relevant:
                    row.update(ranking_metrics([source_key(source) for source in sources], relevant, args.top_k))
                    row["passed"] &= row["recall_at_k"] >= args.min_recall
                forbidden = set(case.get("forbiddenDocumentIds", []))
                leakage = any(source["documentId"] not in owned or source["documentId"] in forbidden
                              or body["documentId"] and source["documentId"] != body["documentId"] for source in sources)
                row["scope_leakage"] = leakage
                row["passed"] &= not leakage
                trace = data["diagnostics"]
                before, after = trace["contextCharactersBefore"], trace["contextCharactersAfter"]
                row["context_reduction"] = 1 - after / before if before else 0
                if args.chat and expected == 200:
                    chat_status, answer, chat_latency = request(args.base_url.rstrip("/"), token, "/api/v1/chat", body)
                    row["chat_latency_ms"] = chat_latency
                    row["passed"] &= chat_status == 200
                    if chat_status == 200:
                        row["abstained"] = answer["abstained"]
                        validity = citation_validity(answer["answer"], answer["matches"])
                        row["citation_validity"] = validity
                        row["passed"] &= answer["abstained"] or validity == 1
                        chat_leak = any(source["documentId"] not in owned or source["documentId"] in forbidden
                                        or body["documentId"] and source["documentId"] != body["documentId"] for source in answer["matches"])
                        row["passed"] &= not chat_leak
                        row["scope_leakage"] |= chat_leak
                        if "expectAbstain" in case:
                            row["passed"] &= answer["abstained"] == case["expectAbstain"]
                        if "referenceAnswer" in case:
                            row["answer_token_f1"] = token_f1(answer["answer"], case["referenceAnswer"])
            elif status != expected:
                row["error"] = f"Expected HTTP {expected}; received {status}"
            results.append(row)
            print(f"{mode}/{case['id']}: {'PASS' if row['passed'] else 'FAIL'}")
    metric_names = ["precision_at_k", "recall_at_k", "mrr", "ndcg_at_k", "answer_token_f1", "citation_validity", "context_reduction", "search_latency_ms", "chat_latency_ms"]
    summary = {}
    for mode in modes:
        subset = [row for row in results if row["mode"] == mode]
        summary[mode] = {metric: statistics.mean(values) for metric in metric_names
                         if (values := [row[metric] for row in subset if row.get(metric) is not None])}
    report = {"top_k": args.top_k, "rewrite": not args.no_rewrite, "chat_evaluated": args.chat,
              "limitations": "Citation validity is not factual faithfulness. Token F1 is lexical, not semantic answer quality. Use expert review for entailment and safety.",
              "summary": summary, "cases": results}
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, indent=2)
    print(json.dumps(summary, indent=2))
    return 0 if all(row["passed"] for row in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
