# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This file is normative: when a request conflicts with a rule here, surface the conflict
instead of silently deviating. Sections 1–4 describe **what exists today**; sections 5+
describe **the target design** the project is converging on. Do not describe target-state
code as if it were implemented.

---

## 1. What this project is

A Retrieval-Augmented Generation (RAG) service over event data (an AI/business conference:
agenda, speakers, articles, press coverage). Structured JSON is ingested, split into
chunks, embedded, and stored in Postgres with `pgvector`. User questions are embedded,
matched by vector similarity, and answered in Portuguese by an LLM constrained to the
retrieved context.

**Hard constraint (eliminatory):** a clean Linux/macOS machine must be able to run
`git clone && cp .env.example .env && docker compose up` and reach a working system.
Any change that breaks cold-start reproducibility is a defect, regardless of how well it
works locally. (This contract is **not yet met** — see §4.)

---

## 2. Commands

All Maven commands run from `backend/`. **`mvnw` is not executable** in a fresh clone —
invoke it as `sh ./mvnw` (or `chmod +x mvnw` once). Never assume a globally installed `mvn`.

```bash
# Build / verify
cd backend
sh ./mvnw clean verify          # full build + tests
sh ./mvnw -DskipTests package   # jar only
sh ./mvnw spring-boot:run       # run app against localhost:5432

# Tests
sh ./mvnw test                                   # all tests
sh ./mvnw test -Dtest=BackendApplicationTests    # single class
sh ./mvnw test -Dtest=IngestionServiceTest#ingestFromJsonFile_emptyDatabase_embedsAndSavesEveryChunk  # single method

# Database (the only service in compose that currently builds)
docker compose up -d db
docker compose logs -f db
docker compose down -v          # -v wipes the pgdata volume

# Exercise the API
curl -X POST 'localhost:8080/api/ingest?path=data/evento.json'
curl localhost:8080/api/chunks
```

Frontend commands run from `frontend/` (Node + npm; no wrapper, no lockfile-pinned toolchain):

```bash
cd frontend
npm install
npm run dev      # Vite dev server on :5173 — NOT the :3000 that compose publishes (see §4)
npm run lint     # oxlint, config in .oxlintrc.json
npm run build    # production bundle into dist/
```

There is no frontend test runner and no CI workflow in the repo — `sh ./mvnw verify` is the
only automated gate that exists today.

Note: the suite is 94 executable cases. Exactly one — `BackendApplicationTests.contextLoads` —
needs PostgreSQL, because it boots the full context and Flyway runs against a real database;
start `docker compose up -d db` before a full run. The other 93 are offline: Gemini is always
stubbed and no API key is required.

---

## 3. Current implementation

Package root `com.seuprojeto.backend`, plain layered Spring MVC (**not** the hexagonal
layout of §6 yet):

```text
backend/src/main/java/com/seuprojeto/backend/
├── BackendApplication.java
├── config/GeminiProperties.java               @ConfigurationProperties("gemini"), validated
│                                              (hand-rolled in the compact constructor — see §3.1.15)
├── config/RetrievalProperties.java            @ConfigurationProperties("rag")
├── config/WebProperties.java                  CORS allowed origins
├── config/RateLimitProperties.java            per-client and global-daily budgets
├── config/CorsConfig.java                     WebMvcConfigurer for /api/**
├── web/RateLimiter.java                       in-memory sliding window
├── web/RateLimitFilter.java                   429 + Retry-After before any controller runs
├── config/GeminiClientConfig.java             two RestClient.Builder beans (see §3.1)
├── controller/KnowledgeChunkController.java   GET /api/chunks, POST /api/ingest?path=...
├── controller/ChatController.java             POST /api/chat  (wire field is `message`)
├── service/EmbeddingService.java              float[] embed(String) via Gemini embedContent
├── service/GenerationService.java             String generate(system, user) via generateContent
├── service/ChatService.java                   the RAG use case
├── service/PromptAssembler.java               pure; the grounding prompt lives here
├── service/EnumerationIntent.java             pure; routes "list every X" to a type filter
├── service/IngestionService.java              the pipeline; toDrafts() is pure
├── service/IngestionResult.java               record(created, skipped, total)
├── repository/KnowledgeChunkRepository.java   + findAllContentHashes(), findNearest()
├── repository/ChunkMatch.java                 projection: id/type/titleRef/content/distance
├── model/KnowledgeChunk.java                  @Entity: id, type, titleRef, content,
│                                              embedding vector(768), contentHash
├── model/ChunkDraft.java                      record(type, titleRef, content) + contentHash()
├── error/EmbeddingException.java
├── error/GenerationException.java             generation failed or returned an unusable answer
├── error/ConcurrentIngestionException.java    lost a race with a parallel ingest -> 409
├── error/GlobalExceptionHandler.java          RFC 7807 ProblemDetail mapping
├── error/TransientAiException.java            retryable failure (429/5xx/timeout)
├── dto/ChatRequest.java, ChatResponse.java, SourceRef.java
├── dto/EventDataDTO.java                      Jackson mirror of data/evento.json
└── dto/gemini/{EmbedContent,GenerateContent}{Request,Response}
backend/data/evento.json                       the source corpus -> 23 chunks
backend/src/main/resources/db/migration/V1__init.sql   extension + table + HNSW index
```

The ingestion pipeline is the core of what exists — `IngestionService.ingestFromJsonFile`
runs three stages:

1. **texto** — `toDrafts()` (pure, static, package-private) flattens each top-level array
   into one `ChunkDraft` per record, hand-formatting a Portuguese sentence into `content`
   and tagging it with a `type` discriminator (`evento` | `agenda` | `palestrante` |
   `artigo` | `materia`). `titleRef` is the human-readable handle. One chunk per record —
   no size-based splitting yet.
2. **embeddings** — each *new* draft goes through `EmbeddingService.embed`, giving a
   `float[768]`.
3. **banco** — one `saveAll` writes everything; `saveAll`'s own transaction is the atomic
   unit, so a mid-run embedding failure leaves the table untouched.

The query side (`ChatService.answer`) mirrors it: embed the question → `findNearest` by
cosine distance → drop anything past `rag.max-distance` → `PromptAssembler` builds a pt-BR
prompt that forbids outside knowledge → `GenerationService` calls Gemini. **If nothing clears
the distance threshold the model is never called** and the endpoint says it does not know —
grounding enforced structurally, not just by prompt wording. Both guards are live: measured
distances are ~0.18 for a direct hit and ~0.53 for an unrelated question.

Listing questions take a second path. `EnumerationIntent` spots "quais artigos", "programação",
"quem são os palestrantes" and switches retrieval to `findNearestByType`, returning every chunk
of that type with no distance filter. Similarity ranking cannot promise it saw the whole
category — that is what made listings silently partial before (see `TESTES.md` Achado 1).

Idempotency comes from `ChunkDraft.contentHash()` (SHA-256 of type + titleRef + content,
NUL-separated) backed by a `UNIQUE` constraint. The hash is checked **before** embedding,
so re-ingesting costs no API quota, and `knownHashes` grows as the loop runs so duplicates
*within* one file collapse instead of hitting the constraint. Measured: 23 chunks in ~12s
on first run, ~13ms and zero API calls on the second.

`EventDataDTO` uses snake_case Java field names (`tema_da_palestra`, `titulo_traduzido`)
to match the JSON verbatim and is annotated `@JsonIgnoreProperties(ignoreUnknown = true)`
at every level, so adding fields to `evento.json` will not break ingestion.

### 3.1 Non-obvious decisions — do not "fix" these without reading why

Each of these looks like a mistake and is not. They were arrived at by hitting the failure.

1. **`IngestionService` constructs its own `ObjectMapper` instead of injecting one.**
   Boot 4.1 auto-configures a *Jackson 3* (`tools.jackson`) mapper; this project parses with
   *Jackson 2* (`com.fasterxml`). There is no Jackson-2 `ObjectMapper` bean, so adding it as a
   constructor parameter fails context startup with `No qualifying bean of type
   'com.fasterxml.jackson.databind.ObjectMapper'`. Fix the dependency duplication (§4) before
   converting this to injection.
2. **`@JsonIgnore` on `KnowledgeChunk.getEmbedding()`.** Without it `GET /api/chunks` serializes
   768 floats per row. The annotation is `com.fasterxml.jackson.annotation.JsonIgnore` and is
   honored by the Jackson 3 serializer because both stacks share the annotations artifact.
3. **`@Array(length = 768)` duplicates `gemini.embedding-dimensions`.** Annotation values must be
   compile-time constants, so the column width cannot read configuration. `IngestionService`'s
   constructor compares the two and refuses to start if they disagree — that check is the only
   thing keeping them honest.
4. **`docker-compose.yml` uses `${GEMINI_API_KEY:-}`, not `${GEMINI_API_KEY:?...}`.** Compose
   interpolates the whole file for every command, so a `:?` guard breaks even
   `docker compose up db` when no `.env` exists. The app's startup validation is the right place
   to enforce the key, and it does.
5. **`spring.config.import=optional:file:./.env[.properties],optional:file:../.env[.properties]`.**
   Spring Boot does not read `.env` natively (only Compose does). Without this, `mvnw
   spring-boot:run` fails the key check even with a populated `.env` sitting in the repo root.
   Two paths because the working directory is `backend/` locally and `/` in the container.
6. **Boot 4 splits auto-configuration into per-technology modules.** `flyway-core` on its own
   does nothing — migrations are silently skipped and Hibernate then fails with
   `missing table [knowledge_chunk]`. `org.springframework.boot:spring-boot-flyway` is what
   actually wires it up. Same pattern as `spring-boot-jackson`. Suspect this first whenever a
   library is on the classpath but appears inert.
7. **Two `RestClient.Builder` beans, injected by `@Qualifier`.** Generation takes ~4-6s while
   embedding takes under a second; sharing one 20s read timeout made *every* chat request die
   with `HttpTimeoutException`. `geminiChatRestClientBuilder` uses `gemini.chat-read-timeout`
   (60s). Because two beans of the same type exist, both services must qualify their injection.
8. **`GenerationService` rejects any `finishReason` other than `STOP`.** A `MAX_TOKENS`
   response contains real text and looks like a valid answer; serving it would silently hand
   back a truncated answer as if it were complete.
9. **The chat model is pinned empirically, not by reputation.** Verified on this key:
   `gemini-3.7-flash` returned 503 (high demand), `gemini-2.5-flash` returned 404 despite
   being listed by `GET /v1beta/models`, `gemini-3.6-flash` answers in ~4s. Re-check with a
   real call before changing `gemini.chat-model`; being listed does not mean being callable.
10. **`@ConfigurationPropertiesScan` on `BackendApplication`** registers every properties
    record. Adding a new `@ConfigurationProperties` class needs no further wiring — but
    without the scan it fails at startup with `No qualifying bean`.
11. **Chunk text is prefixed with its type in words** ("Artigo: …", "Palestrante: …"). This is
    load-bearing, not cosmetic: the `type` column is invisible to the embedding model, so
    without the prefix a question about "artigos" ranks articles below unrelated agenda items.
    Changing these prefixes changes every `content_hash` and requires a full re-ingestion.
12. **`gemini.chat-max-output-tokens` is 4096, not a "generous 1024".** Thinking-capable models
    bill reasoning tokens against the same budget, and an 8-item agenda listing hit
    `finishReason: MAX_TOKENS` at 1024 — which correctly becomes a 502 rather than half an
    agenda. Raise this before blaming the model when listings start failing.
13. **A `Filter` bean joins `@WebMvcTest` slices.** `RateLimitFilter` is picked up by every
    `@WebMvcTest`, so those tests must supply `RateLimiter` plus the properties records
    (see `ChatControllerTest`) or the context fails to load. Filters also need exactly one
    constructor, or Spring reports `No default constructor found`.
14. **Property validation is hand-rolled, not Jakarta Validation.** All four
    `@ConfigurationProperties` records (`Gemini`, `Retrieval`, `RateLimit`, `Web`) throw
    `IllegalArgumentException` from their compact constructors. `spring-boot-starter-validation`
    is **not** on the classpath, so adding `@NotBlank`/`@Positive` to one of these records would
    compile only after adding the dependency — and without it the annotation is silently inert.
    Follow the existing style unless you add the starter deliberately (§11 forbids adding
    dependencies unasked). §6.3 and §10 describe Jakarta Validation as the *target*, not today.
15. **Retry only covers `TransientAiException`.** `GenerationService.isTransient` classifies
    429/5xx as retryable; everything else — a 403 bad key, `MAX_TOKENS`, a safety block —
    fails on the first attempt on purpose. Widening this would burn the daily quota 3x on
    errors that cannot succeed.

### 3.2 Frontend (React 19 + Vite 8, `frontend/`)

```text
frontend/
├── index.html, vite.config.js (plugin-react only — no proxy, no port override)
├── .oxlintrc.json                oxlint, react + oxc plugins
└── src/main.jsx → App.jsx → Chat.jsx   the whole app; styling is inline + App.css
```

`Chat.jsx` is the only component with behaviour, and it holds the one fact worth knowing:
**`const MOCK_MODE = true` at the top of the file short-circuits the fetch** and returns a
canned Portuguese string after a 600 ms delay. The real branch (`fetch("http://localhost:8080/api/chat")`)
is written and matches `API_CONTRACT.md`, but it is dead code until that flag flips. Nothing
in the UI surfaces the difference, so a demo can look healthy with the backend switched off.
The flag is also a hardcoded absolute URL — there is no `VITE_API_URL` environment binding.

`frontend/README.md` is the untouched `create-vite` template and describes nothing about this
project; do not treat it as documentation.

---

The HTTP contract is specified in `API_CONTRACT.md` at the repo root, with payloads captured
from a running server. **Any change to a request/response shape must update that file in the
same commit** — the frontend treats it as authoritative and does not read this code.

---

## 4. Known gaps between this document and the code

Read this before claiming anything works. Closing these gaps *is* the current work:

- **No Dockerfiles.** `docker-compose.yml` declares `build: ./backend` and
  `build: ./frontend`, but neither directory has a `Dockerfile`. `docker compose up`
  fails; only `docker compose up db` works. The cold-start contract in §1 is unmet.
  Two traps when writing them: the top-level `docker/` directory is empty (`.gitkeep`) and is
  **not** where compose looks — the build contexts are `backend/` and `frontend/`; and compose
  publishes the frontend on `3000` while Vite's dev server defaults to `5173` and binds to
  localhost, so the image must run it as `vite --host 0.0.0.0 --port 3000` (or the port mapping
  must change). `app.web.cors-allowed-origins` already permits both ports.
- **Database credentials are still hardcoded** in `application.properties` and
  `docker-compose.yml`. Only `GEMINI_API_KEY` is externalized so far (`.env.example`).
- **Enumeration detection is keyword-based.** `EnumerationIntent` fixed listings (verified 8/8
  agenda slots, 3/3 articles) but it is a heuristic: an unusually phrased listing question still
  falls through to similarity search and can return a partial list. 18 unit tests pin the
  common phrasings.
- **Gemini free tier allows 20 `generateContent` requests per day, per model.** Verified by
  exhausting it: `429 ... generate_content_free_tier_requests, limit: 20`, still blocked after
  75s, and switching model restored service. The global daily rate limit (18) now sits just
  under it so the failure is a clean 429 instead of scattered 502s — but that caps the demo, it
  does not create quota. Billing is still a decision someone has to make.
- **`sources` is what was retrieved, not what was cited.** When the model refuses for lack of
  context but chunks did clear `rag.max-distance`, the response still lists those chunks. A
  client rendering "Fontes:" under a refusal looks wrong. Fixing it properly means structured
  output (ask the model to report whether it answered), not string-matching the refusal text.
- **`rag.max-distance=0.8` is tuned on one corpus of 23 chunks.** Measured: direct hit ~0.18,
  unrelated ~0.53. It has not been validated against a real question set.
- **The endpoints are unauthenticated.** Rate limiting caps the damage but is not access
  control; anything that can reach the port can use the API.
- **Rate limit state is in-memory and per-instance.** A restart resets the daily counter, and a
  second instance would double the real spend. Moving it to the database or a cache is the fix
  if this ever runs more than single-node.
- **The `vector(768)` round-trip has no automated test.** It was verified by hand against
  real pgvector (23 rows, `vector_dims` 768, cosine neighbours sane). Automating it needs
  Testcontainers, which is not a dependency — see §9.2.
- **The frontend is scaffolded but not wired to the backend.** `frontend/` is a React 19 +
  Vite 8 app with a working chat UI, but `Chat.jsx` runs with `MOCK_MODE = true` (§3.2), so it
  has never exercised `POST /api/chat`. It also has no loading/error/empty-context states beyond
  a "Bot está digitando..." line, and an unchecked `res.json()` — a 429 from the rate limiter or
  a `ProblemDetail` 502 would render as `undefined`. Stage 7 (§8) is not done.
- **Two Jackson stacks on the classpath.** Boot 4.1 ships Jackson 3
  (`tools.jackson.core:jackson-databind:3.1.4`) and serializes HTTP responses with it, while
  commit `fb51511` added Jackson 2 (`com.fasterxml...:2.21.4`), which `IngestionService` uses to
  parse the source JSON. Harmless today — the `com.fasterxml.jackson.annotation` annotations are
  shared by both — but the Jackson 2 dependency is redundant and should be dropped.
- **`POST /api/ingest?path=` takes an arbitrary filesystem path** from an unauthenticated
  request. Constrain it to a known resource before this goes anywhere real.
- **No integration or e2e tests.** 94 tests pass across 11 classes (`ChunkDraft`, `Ingestion`,
  `Embedding`, `Generation`, `Chat`, `PromptAssembler`, `EnumerationIntent`, `ChatController`,
  `RateLimiter`, `RateLimitFilter`, plus `contextLoads`) — all unit or `@WebMvcTest` slices. The
  §9 layout (integration / contract / e2e), Testcontainers, JaCoCo floors and PIT are not in
  place, and no test package follows the `unit/ integration/ contract/ e2e/` split yet.
- `pom.xml` has empty `<name>`, `<description>`, `<license>`, `<developer>`, `<scm>` stubs.

---

## 5. Stack and versions

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 21 (LTS) | Records, sealed types, pattern matching are expected, not exotic |
| Framework | Spring Boot 4.1.0 | Recent; most third-party docs are stale. Verify against official docs, not tutorials |
| Persistence | Spring Data JPA + Hibernate | ORM only for CRUD/lifecycle. Vector search uses native SQL |
| Database | PostgreSQL 16 + `pgvector` | `vector` column type, cosine distance, HNSW index |
| Build | Maven 3.9.16 via the wrapper | The wrapper is authoritative |
| LLM/Embeddings | Google Gemini API | Single provider for embeddings + generation (+ future multimodal) |
| Frontend | React | Consumes the REST API only. No business logic in the client |
| Runtime | Docker + Docker Compose | Dev happens inside WSL2 on Windows machines, never on the Windows host |

Pin every version explicitly (`pom.xml`, image tags, embedding model name). No `latest`,
no floating ranges. Version drift is the single most likely cause of a failed evaluation.

---

## 6. Target architecture

Ports & Adapters (hexagonal). The dependency rule is absolute:

```
adapter.in.web ──┐
                 ├──> application ──> domain
adapter.out.* ───┘         │
                           └──> application.port.out (interfaces)
```

- `domain` imports **nothing** from Spring, Hibernate, Jackson, or the Gemini SDK.
- `application` imports `domain` and its own port interfaces. Never a concrete adapter.
- `adapter.*` imports `application` and implements ports. Adapters never import each other.

Rationale: the evaluator's environment, the LLM provider, and the persistence layer are
all volatile. The domain is not. Isolating volatility is the whole point.

### 6.1 Target package layout

```text
com.seuprojeto.backend
├── domain
│   ├── model/            KnowledgeChunk, ChunkContent, Embedding, SourceRef, Question, Answer
│   ├── service/          Chunker, RelevanceRanker, PromptAssembler (pure, deterministic)
│   └── error/            DomainException hierarchy
├── application
│   ├── port
│   │   ├── in/           IngestKnowledgeUseCase, AnswerQuestionUseCase
│   │   └── out/          EmbeddingPort, ChunkRepositoryPort, ChatCompletionPort, KnowledgeSourcePort
│   └── service/          IngestKnowledgeService, AnswerQuestionService  (implement in-ports)
└── adapter
    ├── in
    │   └── web/          ChatController, IngestionController, dto/, GlobalExceptionHandler
    └── out
        ├── persistence/  ChunkJpaEntity, ChunkJpaRepository, ChunkPersistenceAdapter
        ├── ai/           GeminiEmbeddingAdapter, GeminiChatAdapter, dto/
        └── source/       JsonFileKnowledgeSourceAdapter
```

Today's Gemini code is flat: `config/GeminiProperties` + `config/GeminiClientConfig`,
`dto/gemini/EmbedContent{Request,Response}`, `error/EmbeddingException`, and
`service/EmbeddingService`. `EmbeddingService` is what becomes
`adapter.out.ai.GeminiEmbeddingAdapter` behind `EmbeddingPort` when the split happens —
its public shape (`float[] embed(String)`) was fixed by issue #33.

Today's `model/KnowledgeChunk` is a JPA entity doing double duty as a domain object. When
the split happens it becomes `adapter.out.persistence.ChunkJpaEntity` plus a separate,
annotation-free `domain.model.KnowledgeChunk`.

### 6.2 OOP rules that are enforced, not suggested

1. **No anemic domain.** If a rule can be expressed on the object that owns the data, it
   lives there. `Embedding.cosineSimilarity(other)`, not `EmbeddingUtils.cosine(a, b)`.
2. **Value objects over primitives.** `ChunkContent`, `EmbeddingModel`, `SourceRef` are
   types, not `String`. Constructors validate; an invalid instance must be unconstructable.
   Use `record` with a compact constructor for validation.
3. **Immutability by default.** Domain objects are `final`, fields `final`, collections
   defensively copied and returned unmodifiable. Mutability requires written justification.
4. **Depend on abstractions (DIP).** `AnswerQuestionService` knows `EmbeddingPort`, never
   `GeminiEmbeddingAdapter`. Swapping Gemini for a local model must touch one package.
5. **Constructor injection only.** No `@Autowired` on fields, no setter injection. A class
   that cannot be constructed with its dependencies in a plain unit test is misdesigned.
6. **Single responsibility, measured concretely.** A class with two reasons to change gets
   split. "Service" is not a responsibility.
7. **Interface segregation.** Ports are narrow: `EmbeddingPort` embeds, it does not also
   generate text. Two Gemini calls with different purposes get two ports.
8. **No static state.** No hand-rolled singletons, no static mutable fields, no service locators.
9. **Persistence entities are not domain models.** `@Entity` classes with getters/setters
   and a no-arg constructor are an adapter concern; the adapter maps to and from the domain
   model. Do not leak JPA annotations into `domain`.
10. **Sealed hierarchies where the set is closed.** `sealed interface` + `record` for
    things like ingestion outcomes, so an exhaustive `switch` catches new cases at compile time.

### 6.3 Fail loudly

Silent degradation is forbidden. Concretely:

- No empty `catch`. No `catch (Exception e) { log.warn(...); return Optional.empty(); }`.
- No default/fallback values that mask a broken dependency (a failed embedding call must
  not return a zero vector).
- Configuration is validated at startup (`@ConfigurationProperties` + Jakarta Validation).
  A missing `GEMINI_API_KEY` fails the boot with a clear message, not the first request.
- Invariant violations throw a typed exception, never return `null`.

```java
public abstract sealed class DomainException extends RuntimeException
    permits ValidationException, EmbeddingDimensionMismatchException,
            KnowledgeSourceException, RetrievalException { ... }
```

Infrastructure failures get their own hierarchy in the adapter layer and are translated at
the boundary. `GlobalExceptionHandler` maps exception type → HTTP status → RFC 7807
`ProblemDetail`. Stack traces never reach the client; correlation IDs do.

---

## 7. Domain decisions (fixed — changing these requires an ADR)

- **Chunking:** deterministic and pure. Same JSON input → byte-identical chunks. Target
  size and overlap live in `ChunkingPolicy`, not scattered constants.
- **Embedding dimension:** fixed at the model's configured dimension and asserted on every
  write. A dimension mismatch is an exception, never a truncation.
- **Similarity:** cosine distance (`<=>` in pgvector). Vectors are stored normalized;
  normalization happens once, in the domain, at construction time.
- **Index:** HNSW with `vector_cosine_ops`. Created by migration, not by Hibernate DDL.
- **Schema management:** Flyway. `spring.jpa.hibernate.ddl-auto=validate` in every profile
  except unit tests. `CREATE EXTENSION vector` lives in `V1__init.sql`.
- **Grounding:** the prompt instructs the model to answer strictly from the supplied
  context and to state explicitly when the context is insufficient. Hallucinated coverage
  is worse than an admitted gap. `PromptAssembler` is pure and unit-tested against fixtures.
- **Language:** answers in Portuguese (pt-BR). Code, identifiers, comments, commit messages,
  and this documentation in English. (Existing code has Portuguese comments and
  Portuguese-derived DTO field names — the field names must stay, they mirror the JSON.)

---

## 8. Implementation stages

Work is consolidated in stages. **Do not start stage N+1 while stage N is red.**
Each stage ends with: green tests, updated docs, one focused commit (or small series),
and a working `docker compose up`.

| Stage | Scope | Definition of done |
|---|---|---|
| 0 | Skeleton | `docker compose up` starts Postgres+pgvector and the app; `/actuator/health` is UP; Flyway `V1` applied; CI runs `sh ./mvnw verify` |
| 1 | Domain core | `KnowledgeChunk`, `Embedding`, `ChunkContent`, `Chunker`, exception hierarchy. 100% unit-tested, zero Spring on the classpath of these tests |
| 2 | Persistence adapter | `ChunkPersistenceAdapter` implements `ChunkRepositoryPort`: save, batch save, top-k cosine search. Integration-tested with Testcontainers against real pgvector |
| 3 | Embedding adapter | `GeminiEmbeddingAdapter` implements `EmbeddingPort`: batching, retry with backoff, timeout, typed errors. Tested against a stubbed HTTP server — never the live API |
| 4 | Ingestion use case | `IngestKnowledgeService` wires source → chunker → embedder → repository. Idempotent: re-ingesting the same source does not duplicate rows |
| 5 | Retrieval + generation | `AnswerQuestionService` + `GeminiChatAdapter`; `POST /api/chat` returns a grounded answer with source references |
| 6 | Web layer hardening | DTO validation, `ProblemDetail` errors, request correlation ID, structured logging, rate limiting on the chat endpoint |
| 7 | Frontend | React consumes `/api/chat`; loading, error, and empty-context states are all handled visibly |
| 8 | Multimodal (optional) | Image upload → Gemini vision. Only after 0–7 are green |

Current position: stages 3, 4 and 5 are **implemented but not delivered**, and stage 6 is
largely implemented (`ProblemDetail` errors, rate limiting, CORS; no correlation ID or
structured logging yet). Stage 7 has a UI but no live wiring (§3.2). The functionality works — retry/backoff with timeouts, idempotent
ingestion, retrieval, grounded generation — but §8 gates every stage on a working
`docker compose up`, and that still fails for want of Dockerfiles (§4). No stage is done until
that gate passes. All of it is built directly
on JPA and concrete services in `service/` rather than the §6 hexagonal layout. Expect to
restructure rather than extend.

Rule: an adapter is never written before the port it implements, and a port is never
written before a use case needs it. No speculative interfaces.

---

## 9. Test bank

Tests are a deliverable, not an afterthought. The suite is the contract.

```
src/test/java/...
├── unit/            domain + application services. No Spring context. Milliseconds.
├── integration/     Testcontainers (Postgres+pgvector), @DataJpaTest, WireMock for Gemini
├── contract/        Port implementations verified against a shared abstract test class
└── e2e/             Full @SpringBootTest + Testcontainers, real HTTP, stubbed LLM
src/test/resources/fixtures/   deterministic JSON + expected chunk/embedding fixtures
```

1. **No network in tests.** Gemini is always stubbed (WireMock/MockWebServer). A test that
   fails when the API key is absent is a broken test. CI runs offline.
2. **No mocked database.** Persistence is tested against real Postgres + pgvector via
   Testcontainers. Mocking a repository proves nothing about `<=>` or the HNSW index.
3. **Deterministic fixtures.** Test embeddings come from a `DeterministicEmbeddingPort`
   (hash → fixed-dimension normalized vector), not random values. Seeded randomness where
   randomness is unavoidable.
4. **Contract tests for ports.** Each out-port has an abstract test class defining its
   contract; every implementation (real + fake) extends it. This is what keeps the fakes honest.
5. **One behavior per test.** Name as `methodUnderTest_condition_expectedOutcome`.
   Given/When/Then structure, blank-line separated.
6. **Failure paths are tested first-class.** Dimension mismatch, empty retrieval, malformed
   source JSON, Gemini 429/500/timeout, oversized input. If it can fail loudly, prove it does.
7. **No assertions on log output** as a substitute for behavioral assertions.
8. **Coverage floors** (JaCoCo, enforced in `verify`): `domain` ≥ 90% branch,
   `application` ≥ 85%, overall ≥ 75%. A floor, not a goal; an uncovered branch is a
   question, not a number to game.
9. **Mutation testing** (PIT) on `domain` and `application` at each stage boundary.
10. **`mvnw verify` must be green before any push.** No `@Disabled` in `main`; a skipped
    test is either deleted or fixed.

Prefer in-memory fakes (`InMemoryChunkRepository`, `DeterministicEmbeddingPort`) over
Mockito for out-ports — reusable, contract-tested, and not coupled to call sequences.
Reserve Mockito for genuine interaction assertions ("retry called exactly three times").

---

## 10. Configuration and secrets

- All configuration through environment variables bound to typed
  `@ConfigurationProperties` records with Jakarta Validation. No `@Value` scattered around.
- `.env` at repo root, never committed. `.env.example` lists every required variable with a
  description and a safe placeholder — it is part of the cold-start contract and must be
  updated in the same commit that introduces a new variable.
- Secrets never logged, never in exception messages, never in test resources.
- Profiles: `dev` (compose), `test` (Testcontainers), `prod`. Differences confined to
  `application-*.yml`; no `if (profile == ...)` in Java. Compose already sets
  `SPRING_PROFILES_ACTIVE=dev`, but no `application-dev` file exists yet.

---

## 11. Working agreement for Claude

**Before writing code**
- State which stage (§8) the work belongs to and which ports/classes it touches.
- If the change crosses a layer boundary or adds a dependency, say so and justify it.
- If a requirement is ambiguous, ask one focused question rather than assuming.

**While writing code**
- Test first where the behavior is specifiable; test alongside otherwise. Never after.
- Small units of work: one class + its test per step, verified before moving on.
- Run `sh ./mvnw verify` (or the narrowest relevant test command) and report the actual
  output. Never claim a test passes without having run it.
- Touch only the files the task requires. No opportunistic refactors in a feature commit.

**After writing code**
- Report honestly: what works, what is untested, what was stubbed, what is a known
  limitation. An accurately-disclosed gap is acceptable; an overclaimed one is not.
- Update `.env.example`, `README.md`, and the compose file when the contract changes.

**Explicitly forbidden**
- Adding a dependency, framework, or abstraction layer without asking.
- Introducing an interface with exactly one implementation and no test double.
- Generating code that cannot be run in the current stage's environment.
- Weakening or deleting a failing test to make the build green.
- `System.out.println` for logging; use SLF4J with structured key-value context.
- Lombok on domain classes (records and explicit constructors instead).

---

## 12. Git and delivery

- Trunk-based: short-lived branches `feat/<issue>-<slug>`, `fix/…`, `chore/…`.
- Conventional Commits, imperative mood, scoped to a package: `feat(domain): add Embedding
  cosine similarity`. (History to date uses Portuguese subjects without accents; English is
  the going-forward rule.)
- Small, reviewable commits. A commit mixing a refactor and a feature gets split.
- Every PR: linked issue, green CI, updated tests, and a one-line note on how a reviewer
  verifies the change by hand.
- Every issue documents input, output, and dependencies.

---

## 13. Cold-start verification (run before every milestone)

```bash
git clone <repo> /tmp/coldstart && cd /tmp/coldstart
cp .env.example .env          # fill GEMINI_API_KEY
docker compose up --build -d
curl -fsS localhost:8080/actuator/health
curl -fsS -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Quais expositores atuam com tecnologia?"}'
docker compose down -v
```

If any step requires a manual fix not written in `README.md`, the `README.md` is wrong —
fix the documentation in the same commit. As of now this script fails at
`docker compose up --build` (no Dockerfiles) — see §4.

---

## 14. ADRs

Decisions that are expensive to reverse (embedding model, chunking policy, index type,
provider swap, transaction boundaries) are recorded in `docs/adr/NNNN-title.md`:
context, options considered, decision, consequences. Short. One page. If a future change
contradicts an ADR, write a new ADR that supersedes it rather than editing history.
