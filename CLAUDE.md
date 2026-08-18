# CLAUDE.md

Operating manual for this repository. Read fully before writing code.
This file is normative: when a request conflicts with a rule here, surface the conflict
instead of silently deviating.

---

## 1. What this project is

A Retrieval-Augmented Generation (RAG) service over event/exhibitor data.
Structured JSON is ingested, split into chunks, embedded, and stored in Postgres with
`pgvector`. User questions are embedded, matched by vector similarity, and answered in
Portuguese by an LLM constrained to the retrieved context.

**Hard constraint (eliminatory):** a clean Linux/macOS machine must be able to run
`git clone && cp .env.example .env && docker compose up` and reach a working system.
Any change that breaks cold-start reproducibility is a defect, regardless of how well it
works locally.

---

## 2. Stack and versions

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 21 (LTS) | Records, sealed types, pattern matching are expected, not exotic |
| Framework | Spring Boot 4.1.0 | Recent; some third-party docs are stale. Verify against official docs, not tutorials |
| Persistence | Spring Data JPA + Hibernate | ORM only for CRUD/lifecycle. Vector search uses native SQL |
| Database | PostgreSQL + `pgvector` | `vector` column type, cosine distance, HNSW index |
| Build | Maven via `./mvnw` | Wrapper is authoritative. Never assume a globally installed `mvn` |
| LLM/Embeddings | Google Gemini API | Single provider for embeddings + generation (+ future multimodal) |
| Frontend | React | Consumes the REST API only. No business logic in the client |
| Runtime | Docker + Docker Compose | Dev happens inside WSL2 on Windows machines, never on the Windows host |

Pin every version explicitly (`pom.xml`, image tags, embedding model name). No `latest`,
no floating ranges. Version drift is the single most likely cause of a failed evaluation.

---

## 3. Architecture

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

### 3.1 Package layout

```
br.com.<org>.<app>
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

### 3.2 OOP rules that are enforced, not suggested

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
8. **No static state.** No singletons hand-rolled, no static mutable fields, no
   service locators.
9. **Persistence entities are not domain models.** `ChunkJpaEntity` (with `@Entity`,
   getters/setters, no-arg constructor) is an adapter concern. The adapter maps to and from
   `KnowledgeChunk`. Do not leak JPA annotations into `domain`.
10. **Sealed hierarchies where the set is closed.** Use `sealed interface` + `record`
    for things like ingestion outcomes so exhaustive `switch` catches new cases at compile time.

### 3.3 Fail loudly

Silent degradation is forbidden. Concretely:

- No empty `catch`. No `catch (Exception e) { log.warn(...); return Optional.empty(); }`.
- No default/fallback values that mask a broken dependency (an embedding call that fails
  must not return a zero vector).
- Configuration is validated at startup (`@ConfigurationProperties` + Jakarta Validation).
  A missing `GEMINI_API_KEY` fails the boot with a clear message, not the first request.
- Invariant violations throw a typed exception, never return `null`.

Exception hierarchy:

```java
public abstract sealed class DomainException extends RuntimeException
    permits ValidationException, EmbeddingDimensionMismatchException,
            KnowledgeSourceException, RetrievalException { ... }
```

Infrastructure failures get their own hierarchy in the adapter layer and are translated at
the boundary. `GlobalExceptionHandler` maps exception type → HTTP status → RFC 7807
`ProblemDetail`. Stack traces never reach the client; correlation IDs do.

---

## 4. Domain decisions (fixed — changing these requires an ADR)

- **Chunking:** deterministic and pure. Same JSON input → byte-identical chunks. Target
  size and overlap live in `ChunkingPolicy`, not scattered constants.
- **Embedding dimension:** fixed at the model's configured dimension and asserted on every
  write. A dimension mismatch is an exception, never a truncation.
- **Similarity:** cosine distance (`<=>` in pgvector). Vectors are stored normalized;
  normalization happens once, in the domain, at construction time.
- **Index:** HNSW with `vector_cosine_ops`. Created by migration, not by Hibernate DDL.
- **Schema management:** Flyway. `spring.jpa.hibernate.ddl-auto=validate` in every profile
  except unit tests. The `CREATE EXTENSION vector` statement lives in `V1__init.sql`.
- **Grounding:** the prompt instructs the model to answer strictly from the supplied
  context and to state explicitly when the context is insufficient. Hallucinated coverage
  is worse than an admitted gap. `PromptAssembler` is pure and unit-tested against fixtures.
- **Language:** answers in Portuguese (pt-BR). Code, identifiers, comments, commit messages,
  and this documentation in English.

---

## 5. Implementation stages

Work is consolidated in stages. **Do not start stage N+1 while stage N is red.**
Each stage ends with: green tests, updated docs, one focused commit (or small series),
and a working `docker compose up`.

| Stage | Scope | Definition of done |
|---|---|---|
| 0 | Skeleton | `docker compose up` starts Postgres+pgvector and the app; `/actuator/health` is UP; Flyway `V1` applied; CI runs `./mvnw verify` |
| 1 | Domain core | `KnowledgeChunk`, `Embedding`, `ChunkContent`, `Chunker`, exception hierarchy. 100% unit-tested, zero Spring on the classpath of these tests |
| 2 | Persistence adapter | `ChunkPersistenceAdapter` implements `ChunkRepositoryPort`: save, batch save, top-k cosine search. Integration-tested with Testcontainers against real pgvector |
| 3 | Embedding adapter | `GeminiEmbeddingAdapter` implements `EmbeddingPort`: batching, retry with backoff, timeout, typed errors. Tested against a stubbed HTTP server — never the live API |
| 4 | Ingestion use case | `IngestKnowledgeService` wires source → chunker → embedder → repository. Idempotent: re-ingesting the same source does not duplicate rows |
| 5 | Retrieval + generation | `AnswerQuestionService` + `GeminiChatAdapter`; `POST /api/chat` returns a grounded answer with source references |
| 6 | Web layer hardening | DTO validation, `ProblemDetail` errors, request correlation ID, structured logging, rate limiting on the chat endpoint |
| 7 | Frontend | React consumes `/api/chat`; loading, error, and empty-context states are all handled visibly |
| 8 | Multimodal (optional) | Image upload → Gemini vision. Only after 0–7 are green |

Rule: an adapter is never written before the port it implements, and a port is never
written before a use case needs it. No speculative interfaces.

---

## 6. Test bank

Tests are a deliverable, not an afterthought. The suite is the contract.

### 6.1 Layout

```
src/test/java/...
├── unit/            domain + application services. No Spring context. Milliseconds.
├── integration/     Testcontainers (Postgres+pgvector), @DataJpaTest, WireMock for Gemini
├── contract/        Port implementations verified against a shared abstract test class
└── e2e/             Full @SpringBootTest + Testcontainers, real HTTP, stubbed LLM
src/test/resources/fixtures/   deterministic JSON + expected chunk/embedding fixtures
```

### 6.2 Non-negotiable rules

1. **No network in tests.** Gemini is always stubbed (WireMock/MockWebServer). A test that
   fails when the API key is absent is a broken test. CI runs offline.
2. **No mocked database.** Persistence is tested against real Postgres + pgvector via
   Testcontainers. Mocking a repository proves nothing about `<=>` or the HNSW index.
3. **Deterministic fixtures.** Embeddings in tests come from a `DeterministicEmbeddingPort`
   (hash → fixed-dimension normalized vector), not from random values. Same input, same
   vector, every run. Seeded randomness where randomness is unavoidable.
4. **Contract tests for ports.** Each out-port has an abstract test class defining its
   contract; every implementation (real + fake) extends it. This is what keeps the fake
   used in unit tests honest.
5. **One behavior per test.** Name as `methodUnderTest_condition_expectedOutcome`.
   Given/When/Then structure, blank-line separated.
6. **Failure paths are tested first-class.** Dimension mismatch, empty retrieval, malformed
   source JSON, Gemini 429/500/timeout, oversized input. If it can fail loudly, prove it does.
7. **No assertions on log output** as a substitute for behavioral assertions.
8. **Coverage floors** (JaCoCo, enforced in `verify`): `domain` ≥ 90% branch,
   `application` ≥ 85%, overall ≥ 75%. Coverage is a floor, not a goal; an uncovered branch
   is a question, not a number to game.
9. **Mutation testing** (PIT) on `domain` and `application` at each stage boundary.
   Line coverage with no assertions is caught here.
10. **`./mvnw verify` must be green before any commit is pushed.** No `@Disabled` left in
    `main`; a skipped test is either deleted or fixed.

### 6.3 Fakes vs mocks

Prefer in-memory fakes (`InMemoryChunkRepository`, `DeterministicEmbeddingPort`) over
Mockito for out-ports — they are reusable, contract-tested, and don't couple tests to call
sequences. Reserve Mockito for verifying genuine interactions (e.g. "retry called exactly
three times").

---

## 7. Configuration and secrets

- All configuration through environment variables, bound to typed
  `@ConfigurationProperties` records with Jakarta Validation. No `@Value` scattered in
  classes.
- `.env` at repo root, never committed. `.env.example` lists every required variable with
  a description and a safe placeholder — it is part of the cold-start contract and must be
  updated in the same commit that introduces a new variable.
- Secrets never logged, never in exception messages, never in test resources.
- Profiles: `dev` (compose), `test` (Testcontainers), `prod`. Differences confined to
  `application-*.yml`; no `if (profile == ...)` in Java.

---

## 8. Working agreement for Claude

**Before writing code**
- State which stage (§5) the work belongs to and which ports/classes it touches.
- If the change crosses a layer boundary or adds a dependency, say so and justify it.
- If a requirement is ambiguous, ask one focused question rather than assuming.

**While writing code**
- Test first where the behavior is specifiable; test alongside otherwise. Never after.
- Small units of work: one class + its test per step, verified before moving on.
- Run `./mvnw verify` (or the narrowest relevant test command) and report the actual output.
  Never claim a test passes without having run it.
- Touch only the files the task requires. No opportunistic refactors bundled into a
  feature commit.

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

## 9. Git and delivery

- Trunk-based: short-lived branches `feat/<issue>-<slug>`, `fix/…`, `chore/…`.
- Conventional Commits, imperative mood, scoped to a package: `feat(domain): add Embedding
  cosine similarity`.
- Small, reviewable commits. A commit that mixes a refactor and a feature gets split.
- Every PR: linked issue, green CI, updated tests, and a one-line note on how a reviewer
  verifies the change by hand.
- Every issue documents input, output, and dependencies — a contributor should be able to
  pick it up without reconstructing the rest of the system.

---

## 10. Cold-start verification (run before every milestone)

```bash
git clone <repo> /tmp/coldstart && cd /tmp/coldstart
cp .env.example .env          # fill GEMINI_API_KEY
docker compose up --build -d
curl -fsS localhost:8080/actuator/health
curl -fsS -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"Quais expositores atuam com tecnologia?"}'
docker compose down -v
```

If any step requires a manual fix not written in `README.md`, the `README.md` is wrong —
fix the documentation in the same commit.

---

## 11. ADRs

Decisions that are expensive to reverse (embedding model, chunking policy, index type,
provider swap, transaction boundaries) are recorded in `docs/adr/NNNN-title.md`:
context, options considered, decision, consequences. Short. One page. If a future change
contradicts an ADR, write a new ADR that supersedes it rather than editing history.