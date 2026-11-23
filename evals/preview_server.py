"""Local UI-only fixture server. No real authentication, retrieval, email, or persistence."""
import argparse
import json
import re
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, unquote

STATIC = Path(__file__).resolve().parents[1] / "src/main/resources/static"
DOCUMENTS = [
    {"id": "fixture-refund", "fileName": "Customer refund policy.pdf", "fileSize": 243920, "chunksCreated": 12, "status": "indexed"},
    {"id": "fixture-operations", "fileName": "Operations handbook.docx", "fileSize": 842300, "chunksCreated": 28, "status": "indexed"},
    {"id": "fixture-onboarding", "fileName": "Employee onboarding.md", "fileSize": 4812, "chunksCreated": 4, "status": "indexed"},
]


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(STATIC), **kwargs)

    def log_message(self, format, *args):
        pass

    def reply(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def authorized(self):
        if self.headers.get("Authorization") != "Bearer ui-fixture-token":
            self.reply(401, {"message": "Fixture session expired."})
            return False
        return True

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/docmind/auth/verify/email":
            return self.reply(200, {"message": "Fixture email verified. No email was sent."})
        if path == "/docmind/auth/me":
            if self.authorized():
                self.reply(200, {"id": "fixture-user", "username": "Preview researcher", "email": "researcher@example.test", "role": "USER"})
        elif path == "/api/v1/documents":
            if self.authorized():
                self.reply(200, DOCUMENTS)
        else:
            if path == "/verify":
                self.path = "/index.html"
            super().do_GET()

    def do_POST(self):
        path = urlparse(self.path).path
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if path == "/api/v1/upload_document":
            if not self.authorized():
                return
            match = re.search(br'filename="([^"\r\n]+)"', raw)
            filename = match.group(1).decode() if match else "fixture-upload.txt"
            doc = {"id": f"fixture-upload-{len(DOCUMENTS)}", "fileName": filename, "fileSize": len(raw), "chunksCreated": 1, "status": "indexed"}
            DOCUMENTS.append(doc)
            return self.reply(201, doc)
        data = json.loads(raw or b"{}")
        if path == "/docmind/auth/login":
            if data.get("email") != "researcher@example.test" or data.get("password") != "preview-only":
                return self.reply(401, {"message": "Fixture login: researcher@example.test / preview-only"})
            return self.reply(200, {"token": "ui-fixture-token"})
        if path in {"/docmind/auth/register", "/docmind/auth/resend"}:
            return self.reply(200, {"message": "Fixture only. No account created and no email sent."})
        if not self.authorized():
            return
        if path not in {"/api/v1/search", "/api/v1/chat"}:
            return self.reply(404, {"message": "Not found"})
        query = data.get("query", "")
        if "ignore previous" in query.lower():
            return self.reply(400, {"message": "This request contains instructions that cannot be used for document search."})
        if "unavailable" in query.lower():
            return self.reply(503, {"message": "An AI service is unavailable. Check the backend services and retry."})
        if "slow" in query.lower():
            time.sleep(2)
        scope = data.get("documentId")
        if scope and scope not in {doc["id"] for doc in DOCUMENTS}:
            return self.reply(404, {"message": "Document not found."})
        docs = [doc for doc in DOCUMENTS if not scope or doc["id"] == scope]
        empty = "planet" in query.lower() or not docs
        sources = [] if empty else [{"citationId": "S1", "chunkId": "fixture-chunk-1", "documentId": docs[0]["id"],
                "fileName": docs[0]["fileName"], "chunkIndex": 0, "pageNumber": 2,
                "snippet": "Customers can request a refund within 30 days of purchase. Refunds are returned to the original payment method. A valid receipt is required.",
                "similarityScore": 0.86, "bm25Score": 3.71, "fusionScore": 0.0328, "rerankScore": 0.89}]
        trace = {"rewrittenQuery": query, "rewriteApplied": False, "retrievalMode": data.get("retrievalMode", "hybrid"),
                 "semanticCandidates": 0 if empty else 8, "lexicalCandidates": 0 if empty else 6, "fusedCandidates": 0 if empty else 10,
                 "contextCharactersBefore": 0 if empty else 1980, "contextCharactersAfter": sum(len(source["snippet"]) for source in sources),
                 "elapsedMs": 142, "warnings": ["UI fixture response — not a live RAG result."]}
        result = {"query": query, "totalmatches": len(sources), "matches": sources, "diagnostics": trace}
        if path.endswith("/chat"):
            result.update({"answer": "I couldn't find sufficient evidence in your selected documents." if empty else
                "You can request a refund within 30 days of purchase. [S1]\n\nKeep your receipt handy. The refund will go back to your original payment method. [S1]",
                "grounded": not empty, "abstained": empty})
        self.reply(200, {"success": True, "data": result} if path.endswith("/search") else result)

    def do_DELETE(self):
        if not self.authorized():
            return
        id = unquote(self.path.rsplit("/", 1)[-1])
        DOCUMENTS[:] = [doc for doc in DOCUMENTS if doc["id"] != id]
        self.reply(200, {"message": "Fixture document removed."})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=5182)
    args = parser.parse_args()
    print(f"UI FIXTURE ONLY on http://127.0.0.1:{args.port}; login researcher@example.test / preview-only", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
