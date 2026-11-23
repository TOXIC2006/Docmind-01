# DocMind 02 — what changed, why, and how to use it

## 1. Project analysis and scope

The top-level project is a Java 17 / Spring Boot 3.4.11 application using Spring AI 1.0.0, PineconeVectorStore, PostgreSQL for accounts/metadata/lexical text, Ollama embeddings, an OpenAI-compatible chat provider, Spring Security and JWT authentication. PDF/Tika readers parse uploads and a token splitter creates chunks.

The original retrieval path only performed vector similarity search. A document ID was an optional filter, but document metadata and vector retrieval had no account ownership boundary. The original prompt permitted general-knowledge answers when evidence was missing. The supplied folder contained no frontend code, so this upgrade adds a new integrated frontend rather than modifying an unavailable React project.

All changes are in the top-level `D:/Docmind-02` project. The nested `docmind-01/` copy is a separate Git repository and is untouched. The actual ignored development YAML now uses Pinecone environment placeholders; other credentials were preserved. No commits or deployments are made.

## 2. Pipeline at a glance

```text
Authenticated principal
  -> validate request + resolve document ownership
  -> selected-document exact/semantic cache lookup (revalidate scope before returning hit)
  -> on miss, load only indexed, owner-scoped current-revision text chunks
  -> remove recognized unsafe passages
  -> optionally rewrite question, preserving original query
  -> semantic search + BM25 over the same allowed scope
  -> reciprocal rank fusion (RRF)
  -> deterministic relevance reranking
  -> extractive context compression, deduplication and prompt-token budgeting
  -> evidence-only answer generation with a completion cap
  -> validate citations and output, or abstain
  -> cache supported selected-document answers under their current revision
```

The search endpoint stops before answer generation, but query rewriting itself can call the chat model. Set `rewriteQuery=false` for model-free rewriting behavior; `retrievalMode=bm25` plus rewriting off requires neither embeddings nor a chat-model call for search.

## 3. Hybrid search: semantic + BM25

**Why:** embeddings find related meanings, but can miss exact error codes, names, product IDs and uncommon terms. BM25 is strong at exact lexical matching but cannot reliably recognize paraphrases by itself.

**Implementation:** `Service/Bm25Ranker.java`, `Service/HybridRetriever.java`, and `Repo/ScopedChunkRepository.java`.

- Semantic retrieval uses Spring AI `PineconeVectorStore` with the existing 1024-dimensional Ollama embedding model and a dedicated `docmind-02-documents` namespace.
- BM25 uses `docmind_chunks`, a relational text-only corpus (no vectors). It joins document ownership, indexed status and `vectorRevision`, so partial indexing and obsolete revisions are never searchable. Text/IDs are recorded before cloud upserts; the revision is published only after success. It computes term frequency, document frequency, length normalization and inverse document frequency over the selected user's allowed corpus.
- Tokenization normalizes Unicode and case, keeps identifiers with `_` and `-`, and removes a small English stop-word set. There is no stemming or learned multilingual lexical analyzer.
- BM25 defaults: `k1=1.5`, `b=0.75`.
- Each ranked list contributes `1 / (60 + rank)` to RRF, with rank starting at 1. Results are deduplicated by chunk ID.
- Both original and rewritten queries contribute when rewriting changes the query.
- RRF is necessary because BM25 scores and cosine similarities have different scales; raw scores cannot sensibly be added.

**Tradeoff:** this is exact, in-process BM25 scoring over a bounded corpus, not a persistent Lucene inverted index or PostgreSQL `ts_rank` mislabeled as BM25. The default cap is 10,000 allowed chunks. An oversized scope returns an actionable error, never silently searches a partial corpus. For large deployments, replace the lexical backend with a tenant-filtered inverted index and evaluate recall and latency again.

## 4. Query rewriting

**Why:** typos or poorly phrased questions can reduce both vector and lexical recall.

**Implementation:** `Service/QueryRewriter.java`.

The configured chat model is asked to return one concise search query in the original language, preserving names, numbers and identifiers. The output is bounded to 500 characters and rejected if blank or matched by the guardrail patterns. On a rejected rewrite or provider failure, the original query remains in use and a diagnostic warning is returned. The original query is also searched alongside every accepted rewrite, limiting the effect of intent drift.

This implementation does not invent conversation history or resolve follow-up pronouns using a chat-memory database. Every question is standalone. Rewriting adds latency and a model call; disable it when comparing retrieval baselines or searching exact identifiers.

## 5. Post-retrieval reranking

**Why:** initial retrieval favors recall. The final context should prioritize the most relevant passages rather than simply including every candidate.

**Implementation:** the reranking stage in `Service/HybridRetriever.java`.

RRF selects up to `candidate-k` candidates, then a deterministic reranker computes:

```text
hybrid = 0.50 * clamped semantic similarity
       + 0.30 * normalized BM25 score
       + 0.20 * distinct query-token coverage
```

For single-branch baselines, the selected branch gets weight 0.80 and token coverage gets 0.20. Ties use fusion score and chunk ID for reproducibility. A missing semantic match has no invented cosine score; the response represents it as null.

**Important:** this is a transparent feature-based reranker, not a neural cross-encoder and not a separate LLM judge. It avoids adding a reranking provider, credentials or another model dependency. A cross-encoder can be evaluated later, but no neural reranking quality claim is made here. Rank scores are relevance signals, not probabilities or factual-confidence scores.

## 6. Context compression

**Why:** large retrieved passages waste context space and can distract the model with unrelated material.

**Implementation:** `Service/ContextCompressor.java`.

The compressor scores sentences by overlap with query terms, selects text within a per-passage budget and returns it in original document order. If a passage fits, it is retained. An oversized unbroken sentence uses a bounded verbatim window around a matching term (or its beginning when no term matches). The context uses at most 6,000 passage characters by default, with at most 1,600 per passage. Citation snippets are exactly the compressed evidence sent to the answer model.

This is extractive compression: it selects existing text rather than generating a summary that might introduce a new claim. It can still omit necessary context; source review remains important. `PromptBudget` then deduplicates passages and counts the system instructions, question, JSON framing and retained evidence using CL100K token estimates plus a safety margin. Lower-ranked passages that exceed the budget are omitted, never partially cut into misleading fragments. Only passages actually sent are returned as answer citations. Token estimates are not the Groq model's exact tokenizer; provider-reported answer input/output usage is reported separately, without claiming monetary savings.

## 7. Document and account isolation

**Why:** authentication alone does not prevent a signed-in account from reading another account's documents.

**Implementation:** `Entity/DocumentMetadata.java`, `Repo/DocumnetMetadatarepo.java`, `Service/DocumentServiceimpl.java`, ingestion metadata, the scoped chunk repository and retrieval filters.

- Upload ownership is taken exclusively from the authenticated `AppUserPrincipal`.
- Metadata gains `ownerId`; every new vector chunk also receives `ownerId`.
- Listing, metadata lookup and deletion use owner-scoped repository methods.
- Asking for a foreign or nonexistent document returns the same 404 response, before rewriting or searching.
- Semantic queries filter by owner and optional document ID before top-K retrieval.
- BM25 SQL binds all user values as parameters, checks ownership in both document metadata and stored text chunks, and joins only the current revision of documents with `indexed` status.
- Semantic results are intersected with the allowed corpus before use. Orphaned, failed, deleted, legacy ownerless, and out-of-scope hits cannot become source passages.
- Vector deletion uses exact owner-scoped chunk IDs in batches of 1,000, then removes lexical text, document metadata and cached answers. Spring AI 1.0's Pinecone filter-deletion implementation uses a capped similarity query, so it is intentionally not used.
- No request-body `ownerId` is used for authorization. Empty document scope means **all my documents**, never all users' documents.
- The browser clears tokens and private state on logout/401 and cancels in-flight requests. Tokens live in sessionStorage, not localStorage.

### Existing data / migration behavior

With the existing development `spring.jpa.hibernate.ddl-auto=update`, Hibernate adds the nullable `owner_id` column on startup. Existing rows intentionally retain null ownership and become invisible to normal users. Existing vectors also lack owner metadata. They are **not** automatically assigned to the next person who logs in.

Re-upload original documents while signed in to rebuild owner-tagged chunks. Do not simply assign all existing rows to one user. If retaining historical data is necessary, an administrator must separately establish each document's actual owner, update metadata and vector ownership consistently, and verify cross-account tests before exposing the data. Back up the database before migration. No historical rows were deleted by this upgrade.

These are application-level boundaries, not PostgreSQL row-level security. Database administrators remain privileged. Production deployments should consider RLS, quotas, rate limits, explicit schema migrations and retention policies.

### Pinecone migration and semantic caching (2026-11-18)

The active vector store is `spring-ai-starter-vector-store-pinecone`. Keep Java 17, Boot 3.4.11 and Spring AI 1.0.0. The existing `docminf-01` index is ready/cosine/1024-dimensional but uses Pinecone's newer document schema: a real upsert was rejected because Spring AI 1.0 uses the vectors API, not the 2026-07 documents API. It was left untouched. The compatible target is the separately approved standard dense index `docmind-02-vectors`, with namespace `docmind-02-documents`. The application never automatically creates or deletes an index. PostgreSQL remains required for authentication, metadata and `docmind_chunks` lexical text, but normal retrieval no longer queries pgvector. Existing pgvector data is retained for deliberate migration, not used as a fallback.

Set `PINECONE_API_KEY` in the backend process environment; it is never sent to the frontend. `PINECONE_INDEX_NAME` and `PINECONE_NAMESPACE` can override the defaults. A pasted chat key should be rotated before production. The Pinecone key was not written into source, YAML, environment files or reports. Existing other secrets remain in ignored development configuration.

Back up the relational database before migrating historical documents. An explicit, restartable migration reads only each named document's existing owner-tagged chunks, verifies the count, re-embeds with Ollama, upserts the same IDs in Pinecone in batches of 100, and publishes a new revision after success. It never deletes legacy vectors or assigns missing owners. Stop application writes during migration. Repeat the argument for more document IDs:

```sh
mvn -f D:/Docmind-02/pom.xml spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=dev,pinecone-migrate --server.address=127.0.0.1 --server.port=0 --migration.document-id=YOUR_DOCUMENT_ID"
```

The migration process closes when complete. Already-migrated documents are skipped. Ownerless documents require separately established ownership or a normal signed-in re-upload; they are never silently assigned. Queries containing unmigrated owned documents fail with a migration message rather than returning misleading empty results.

`DocumentSemanticCache` stores only citation-validated, supported answers for **one selected document**. Keys include authenticated owner, document ID, persisted vector revision, top-K, semantic threshold, retrieval mode and rewriting setting. Database ownership/status/revision checks happen before use and again before returning a hit or storing an answer. Document deletion evicts its entries; revision changes make old entries unreachable. All-document queries deliberately bypass caching.

- Exact repeat: no embedding, rewrite, retrieval or answer-model call.
- Semantic repeat: one Ollama query embedding, cosine similarity at least 0.98, then no retrieval/rewrite/answer-model call. Numbers, identifiers and common negation/comparison terms must also match.
- Cache miss: normal retrieval and generation; only supported answers are saved. Embedding failure disables caching for that request without hiding errors from actual retrieval.
- Bounded in-process LRU: at most 256 entries, 900-second TTL checked on access, defensive copies. Restart clears it; it is not a distributed or persistent cache.
- Semantic similarity is not a proof of question equivalence. Threshold and TTL should be evaluated on real questions; no cache-accuracy guarantee is made. Separate JVMs do not share hits.

Chat responses add `cache: {status, similarity, ageSeconds}` with `MISS`, `EXACT_HIT`, `SEMANTIC_HIT`, `BYPASS` or `UNAVAILABLE`. They also include `tokenUsage`: estimated prompt before/after budgeting, provider-reported answer prompt/completion tokens (null if unreported), and completion limit. Hits report zero **new** answer tokens rather than replaying the original usage. These fields exclude query-rewriting and embedding usage. Retrieval diagnostics on hits explicitly show retrieval was skipped; source scores describe the cached evidence.

Token settings under `app.tokens`: `max-prompt-tokens=3000`, `max-completion-tokens=4096`, `max-rewrite-tokens=512`, `safety-margin-tokens=128`. The prompt limit includes estimated instructions, question and JSON overhead; oversize questions fail before the answer-model call, and no retained evidence means abstention. The 4096 completion ceiling deliberately leaves headroom for the configured reasoning model. Change it based on measured usage, not an assumed output length.

## 8. Guardrails and grounded answers

**Why:** retrieval can encounter instructions embedded in documents, irrelevant evidence, secrets, or unsupported questions. A model must not be encouraged to fill gaps from general knowledge.

**Implementation:** `Service/RagGuardrails.java`, `Service/GroundedAnswerGenerator.java`, and `CHAT_PROMPT_TEMPLATE.st`.

1. Requests are limited to a nonblank question of at most 2,000 characters, top-K 1–20, a finite threshold in [0,1] and a supported retrieval mode.
2. Known prompt-injection and credential patterns are checked in the question and passages. Recognized unsafe queries return 400; recognized unsafe passages are excluded.
3. Questions and retrieved evidence are encoded as JSON in a user message. The grounding rules are in a separate system message, not string-interpolated into user-controlled prompt templates.
4. No evidence means immediate abstention without calling the answer model.
5. The model must return JSON containing `answer`, `supported`, and `citations`; every factual sentence is instructed to cite a supplied source ID.
6. Output validation rejects malformed JSON, unsupported answers, missing citations, unknown citation IDs, inconsistencies between listed and inline IDs, and recognized unsafe output.
7. Answer generation has a 4,096-token completion budget and rewriting has 512 tokens, leaving room for the configured reasoning model; rejected answers log only the finish reason and output length, not document content or credentials. A larger budget does not guarantee valid or correct model output.
8. `/fluxchat` buffers and validates the answer before returning it, rather than streaming unchecked tokens. It is a compatibility route, not token-by-token streaming now.
9. Uploads accept nonempty PDF, DOCX, TXT and Markdown files up to 25 MB, with bounded filenames and no path separators. Scanned image-only documents need OCR before upload; OCR was not added.
10. Generic exceptions do not expose internal exception strings in HTTP responses. Model/database availability errors return 503 where recognized.

**Limits:** regex filtering is heuristic, can have false positives, and is not a complete prompt-injection, malware, DLP or PII solution. Valid source IDs prove references exist, not that each sentence is entailed by those references. `grounded=true` means the structured answer passed the implemented support/citation checks; it is not a factual correctness guarantee. Review evidence for consequential decisions. Model output is rendered as text in the browser, not executable HTML.

## 9. Frontend

The integrated UI is `src/main/resources/static/index.html`, `app.css`, `app.js` and `favicon.svg`. Spring Boot serves it from the same origin as the protected API; no npm build, frontend secrets, third-party UI CDN or separate CORS configuration is required.

Features:
- Login, registration, verification-link handling, resend verification, and sign-out.
- A research desk with chat/search modes, selectable owned-document scope and optional retrieval settings.
- A private document library with upload, filter, status, document-specific questions and confirmed deletion.
- Source cards with page/chunk information, compressed text, source IDs and separate semantic/BM25/fusion/rerank scores.
- Retrieval diagnostics, rewrite warnings, context reduction and retrieval latency.
- Cache-hit badges, selected-document cache status/similarity/age, estimated prompt reduction and provider-reported answer token usage.
- Empty, loading, failure and insufficient-evidence states.
- Responsive layouts, semantic forms, keyboard focus, reduced-motion support and text-only rendering of untrusted content.

The workspace does not claim to persist chat sessions. Registration still requires the existing email service, and login still requires a verified account. No authentication bypass was added for the real application.

## 10. Run the real application

Use the **top-level project**, not `docmind-01/`.

Prerequisites:
- Java 17 or newer and Maven (or the included Maven wrapper).
- PostgreSQL for accounts/metadata/text, matching your datasource configuration (localhost:5434). The existing pgvector extension/table is needed only when migrating old data.
- A ready 1024-dimensional cosine Pinecone index and `PINECONE_API_KEY` in the backend environment.
- Ollama running with the configured embedding model, normally `mxbai-embed-large`, and a vector dimension matching that model (1024 in the example).
- A valid configured OpenAI-compatible chat provider key.
- SMTP credentials for account registration/verification; an existing verified account can log in without sending mail.

Keep secrets in your ignored local configuration or environment. The actual `src/main/resources/application-dev.yml` was inspected and corrected: Ollama uses `spring.ai.ollama.embedding.options.model: mxbai-embed-large`, its base URL defaults to localhost:11434, the upload limit is 25 MB, and the verification frontend URL defaults to `http://localhost:8081` with a `FRONTEND_URL` override. Existing credentials and the stable JWT secret were retained; do not commit this file or share the locally built JAR, which packages development resources.

Start Docker Desktop first. The existing `dock-mind-postgres` container uses `resources_ytpdata` and is shared with the earlier DocMind project. Reuse it with `docker start dock-mind-postgres` if stopped; do not remove/recreate its volume. It exposes PostgreSQL on 5434 and already contains the `vector` extension and the `docmind_vectors` table. The compose healthcheck now reads the configured database user/name instead of hardcoding `postgres`. Run `ollama serve` if Ollama is not running, and confirm `ollama list` includes `mxbai-embed-large`.

The existing Brevo configuration reaches its SMTP server with TLS, but authentication currently fails with **525 5.7.1 Unauthorized IP address**. The account owner must authorize this machine's outgoing public IP in Brevo's SMTP/API authorized-IP settings; this is not a pgvector or email-template failure. Do not disable TLS, change authentication requirements, or bypass email verification. After that provider-side change, retry registration once. No external verification email was sent during testing.

```sh
mvn -f D:/Docmind-02/pom.xml spring-boot:run
```

The default port remains 8081. In the repair session, earlier JVM restarts were blocked, so the rebuilt application is launched separately on **http://localhost:8083** with `--server.port=8083 --app.frontend-url=http://localhost:8083`; the isolated SMTP-test instance uses 8084. Existing processes were not forcibly terminated. The system's first `java` PATH entry (Oracle javapath) failed to launch; Maven's `JAVA_HOME` executable at `C:/Program Files/Java/jdk-21.0.10/bin/java.exe` works. Use Maven or that JDK executable rather than the broken launcher.

Open the active application URL, sign in, and upload a small text-based document. Ask a question whose answer is present, inspect its sources, then test an unsupported question. With two separate accounts, verify that each sees only its own documents and that guessing the other account's document ID returns 404.

Key settings in `application.yml`:

| Property | Default | Purpose |
|---|---:|---|
| `app.rag.candidate-k` | 30 | Candidates before final reranking/top-K |
| `app.rag.max-corpus-chunks` | 10000 | Explicit per-scope BM25 memory/work cap |
| `app.rag.context-max-chars` | 6000 | Total compressed passage text budget |
| `app.rag.passage-max-chars` | 1600 | Maximum text from one passage |
| `app.rag.bm25-k1` | 1.5 | Term-frequency saturation |
| `app.rag.bm25-b` | 0.75 | Document-length normalization |

The Java default semantic threshold is 0.25, but existing profile settings override it. The UI sends 0.25 explicitly. Threshold applies only to semantic search, not BM25. Use the same embedding model/dimension as existing vectors; changing embedding models requires a deliberate reindex.

## 11. API additions

Both `/api/v1/search` and `/api/v1/chat` accept:

```json
{
  "query": "What fixes ERR_502A?",
  "documentId": null,
  "topK": 5,
  "similarityThreshold": 0.25,
  "rewriteQuery": true,
  "retrievalMode": "hybrid"
}
```

`retrievalMode` is `hybrid`, `semantic` or `bm25`. Omitted mode defaults to hybrid; omitted rewrite flag enables rewriting. Bearer authentication is required. Search retains its existing `{success, message, timestamp, data}` response envelope; chat returns its DTO directly.

Citation additions: `citationId`, `chunkId`, `bm25Score`, `fusionScore`, `rerankScore`. Original file, page, chunk, snippet and semantic similarity fields remain. Arbitrary vector metadata, especially ownership metadata, is not exposed as a source payload.

Both responses include `diagnostics`. Chat also includes `grounded`, `abstained`, `cache` and `tokenUsage`. `elapsedMs` measures retrieval, rewriting and compression, not answer-generation latency. Evaluation measures end-to-end HTTP latency separately.

## 12. RAG evaluation: what and why

**Why:** a plausible-looking answer is not an evaluation. Regressions need repeatable questions, known relevant passages, expected outcomes and measured results.

### Offline regression suite

```sh
mvn -f D:/Docmind-02/pom.xml test
python -m unittest discover -s D:/Docmind-02/evals -p "test_*.py"
```

Java tests exercise BM25 arithmetic, exact identifiers, Unicode, length normalization, compression limits, scoped ownership and vector filters, unsafe passage removal, citation validation, no-evidence abstention, query rewriting failures and SQL parameterization. The default suite uses mocks for infrastructure; it does not assert live embedding/LLM quality. The existing Spring Boot context test is opt-in with `DOCMIND_LIVE_TESTS=true`, because it requires the configured database/models.

Python tests verify metric formulas, duplicate handling, missing relevance labels, citation references and answer token F1.

### Live end-to-end evaluation

1. Start the real backend and sign into a dedicated evaluation account.
2. Upload `evals/eval-operations.txt`, `evals/eval-refunds.txt` and `evals/eval-onboarding.txt` using their original filenames. These are tiny smoke fixtures, not a representative production benchmark.
3. Set `DOCMIND_EVAL_TOKEN` in the shell to that account's current token. Do not put tokens in JSONL, source code, reports or command arguments.
4. Run the script; choose a fresh output filename for every run:

```sh
python D:/Docmind-02/evals/run_eval.py --output D:/Docmind-02/target/eval-hybrid.json --chat --min-recall 0.8
python D:/Docmind-02/evals/run_eval.py --modes semantic,bm25,hybrid --no-rewrite --output D:/Docmind-02/target/eval-baselines.json
```

The first command evaluates answers and retrieval, including abstention. The second compares retrieval strategies with rewriting disabled. Rewriting and answer generation incur configured provider usage. The runner never uploads/deletes documents automatically and never includes tokens in reports. It rejects existing output files and does not follow redirects with a bearer token. Use HTTPS for nonlocal servers.

### Real application smoke test with a local SMTP inbox

This test uses actual Spring Boot endpoints, Pinecone vector persistence, PostgreSQL lexical/metadata storage, Ollama embeddings and Groq inference. Set `PINECONE_API_KEY` and `PINECONE_INDEX_HOST` in the test process environment for the independent native-vector fetch check; the host is the index host from Pinecone, without `https://`. Only outbound email is captured locally, because Brevo's IP allowlist currently blocks this machine. It does not use the UI fixture server, mock retrieval, or bypass account verification.

Run the inbox in one terminal:

```sh
python D:/Docmind-02/evals/smtp_sink.py
```

Run a separate real application instance in another terminal, preserving normal port 8081/Brevo settings:

```sh
mvn -f D:/Docmind-02/pom.xml spring-boot:run "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8082 --app.frontend-url=http://localhost:8082 --spring.mail.host=127.0.0.1 --spring.mail.port=1025 --spring.mail.properties.mail.smtp.auth=false --spring.mail.properties.mail.smtp.starttls.enable=false --spring.mail.properties.mail.smtp.starttls.required=false"
```

After the app reports `Started Docmind01Application`, run:

```sh
python D:/Docmind-02/evals/live_smoke.py
```

The browser dependency described below must already be installed. If 8082 is occupied, start the test instance on another free port with a matching `app.frontend-url` override, and set `DOCMIND_TEST_BASE_URL` to that instance's loopback URL when running the test. The test creates two unique `example.test` accounts, captures their actual SMTP messages, follows single-use verification links, checks unverified login rejection, indexes documents, exercises all three retrieval modes, calls the real rewriter/answer model, checks cross-account isolation, checks compression, inspects persisted vector dimensions and drives Chrome through real UI flows. It makes chargeable Groq calls. Test documents are removed through the authenticated API afterward; test accounts remain and are identified in the report. Existing documents/vectors are not deleted or reassigned. The loopback SMTP inbox accepts only `example.test` recipients and never forwards mail externally; shut it down after testing.

Reports use fresh `target/live-smoke-*.json` filenames, and real browser screenshots use `target/docmind-live-answer.png` and `target/docmind-live-mobile.png`. No passwords, JWTs or verification tokens are saved in reports.

### Metrics

| Metric | Meaning / caveat |
|---|---|
| Recall@K | Fraction of human-labeled relevant chunks retrieved |
| Precision@K | Relevant retrieved chunks divided by requested K, including unfilled positions |
| MRR | Reciprocal rank of the first relevant result |
| nDCG@K | Rank-sensitive gain with binary relevance labels |
| Citation validity | Fraction of distinct cited IDs that exist in supplied sources; **not faithfulness** |
| Answer token F1 | Lexical overlap with a reference answer after stripping citation markers; paraphrases can score poorly |
| Abstention correctness | Whether `abstained` matches the case's expected outcome |
| Scope leakage | Any result not owned by the caller, outside a requested document, or explicitly forbidden |
| Context reduction | Fraction of passage characters removed by compression |
| Latency | End-to-end HTTP latency for search and optional chat |

Cases live in `evals/cases.example.jsonl`. Relevant labels use `fileName` and zero-based `chunkIndex`; unique filenames and verified chunk labels are required. Add realistic questions, ambiguous questions, near-duplicate documents, adversarial passages, multilingual questions and negative examples. Do not tune and report on the same examples without disclosing it.

For explicit cross-user tests, add a case with another account's `documentId` and `expectedStatus:404`. `forbiddenDocumentIds` checks accidental leakage in otherwise valid queries. For production, add expert-scored factual entailment/answer completeness or a calibrated external judge with human review; this implementation does not label token F1 as hallucination detection.

The runner exits nonzero for unexpected HTTP results, leakage, invalid supported citations, unexpected abstention, or recall below the requested threshold. Without `--chat`, answer/abstention checks are not run. No benchmark improvement percentage is claimed until live runs have been collected.

## 13. UI-only preview and verification boundaries

When PostgreSQL/models are unavailable, an isolated local fixture server can exercise the frontend:

```sh
python D:/Docmind-02/evals/preview_server.py
```

Open `http://127.0.0.1:5182`. Fixture credentials are `researcher@example.test` / `preview-only`. This server binds only to loopback and has **no real authentication, model calls, email or persistence**. The retrieval trace labels responses as fixtures. Never deploy it or use it to judge retrieval quality. Upload/deletion only changes in-memory fixture metadata; restart resets it.

For automated UI fixture tests, install the isolated test-only dependency and run the browser script while the fixture server is running:

```sh
corepack pnpm --dir D:/Docmind-02/target/browser-tools add -D playwright-core
node D:/Docmind-02/evals/browser_test.cjs
```

Create `target/browser-tools` first if it does not exist. The default executable is Windows Google Chrome; set `BROWSER_EXECUTABLE` for another installed Chromium browser. The script uses a fresh temporary browser profile, checks login, chat/search, scopes, errors, upload/delete, XSS-safe rendering, mobile/landscape layouts, reduced motion and session cleanup, and writes screenshots to `target/`. All responses in this test come from the fixture server, not the real backend.

The initial fixture-only verification was insufficient. During the runtime repair on 2026-11-17, Docker Desktop and Ollama were started, the actual development YAML was corrected, and the existing PostgreSQL store was used without resetting its volume. Live testing also exposed a BM25 tie caused by question filler words and a misleading citation-format example; common question words are now excluded from BM25 tokens, and the prompt demonstrates both inline and declared citations correctly while validation remains strict.

Historical pgvector verification (2026-11-17, before the Pinecone migration): **54 offline Java tests, 1 separately enabled live Spring context test, 6 metric tests, 34 live backend checks and 12 real browser checks passed**. The earlier 22 fixture-browser checks remain a separate UI-only suite. Live checks covered actual SMTP message generation into a loopback inbox, single-use email verification, authenticated uploads, 1024-dimensional persisted pgvector rows, semantic/BM25/hybrid retrieval, Groq rewriting and cited answers, long-document compression, rejection/abstention, cross-account isolation and desktop/mobile upload/search/delete flows. The real browser exercised the rebuilt main application on port 8083; the registration-only SMTP override ran on port 8084. Report: `target/live-smoke-387d41ef.json`. Screenshots: `target/docmind-live-answer.png` and `target/docmind-live-mobile.png`.

The original 6 documents and 344 vector rows were preserved during that earlier run; only documents created by the test runs were removed. Brevo external delivery is **not verified or working yet**: its current 525 IP-allowlist rejection requires an account-owner action. No real external email was sent. These small smoke checks demonstrate functioning integration, not production-scale retrieval quality or a benchmark improvement.

### Pinecone live verification — 2026-11-18

- Final build: 79 offline Java tests passed; the opt-in context test was skipped. Six Python metric tests passed. Real application startup and behavior were checked separately below.
- Four currently owned documents / 187 chunks migrated to the approved standard dense `docmind-02-vectors` index. Every migrated ID was independently fetched from Pinecone and checked for 1024 dimensions plus ownership/revision metadata.
- All 531 existing pgvector rows and six ownerless documents were retained. The incompatible `docminf-01` document-schema index was not modified.
- `target/live-smoke-c3278a08.json`: 38 real backend checks passed, including exact and semantic document-cache hits, zero new answer tokens on a hit, provider-reported answer usage on a miss, native Pinecone vector persistence, rewriting, hybrid search, ownership, guardrails and compression.
- 14 real Chrome checks passed: login, cited answers, visible cache/token diagnostics, source navigation, real search, upload/delete cancellation and confirmation, mobile layout and logout. No JavaScript errors. Screenshots: `target/docmind-live-answer.png`, `target/docmind-live-mobile.png`.
- The isolated test app on 8084 used the loopback-only SMTP inbox. Those test services were stopped after verification; test documents were deleted via their authenticated accounts. Two unique test accounts remain, as listed in the report.
- The Pinecone key was supplied only to child-process environments. Future restarts require `PINECONE_API_KEY`; it is intentionally not persisted. Normal app startup keeps the existing Brevo settings; verified users can log in without sending mail.

