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
works locally. **This contract is met as of 2026-08-20**, verified from a wiped volume:
`docker compose down -v && docker compose up --build` brings up Postgres, the backend (which ingests
the 54-chunk corpus by itself on first boot) and the built frontend on `:3000`. Two defects had to be
fixed to get there — see §3.1.31.

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

Frontend commands run from `frontend/` (Node + npm; no wrapper). `package-lock.json` **is**
committed, so `npm ci` is the reproducible install and `npm install` is for changing dependencies.
**Vite 8 needs Node 20.19+ / 22.12+.** On Node 18 every script dies with
`SyntaxError: ... does not provide an export named 'styleText'`, which names neither Node nor the
version — suspect the runtime first. If `npm install` ever ran under the old Node, delete
`node_modules` and reinstall, or `rolldown` keeps a broken native binding and only `vite build`
fails, with a misleading "npm has a bug related to optional dependencies".

```bash
cd frontend
npm install
npm run dev      # Vite dev server on :5173 — NOT the :3000 that compose publishes (see §4)
npm run lint     # oxlint, config in .oxlintrc.json
npm run build    # production bundle into dist/
```

There is no frontend test runner and no CI workflow in the repo — `sh ./mvnw verify` is the
only automated gate that exists today.

Note: the suite is 273 executable cases. Exactly one — `BackendApplicationTests.contextLoads` —
needs PostgreSQL, because it boots the full context and Flyway runs against a real database;
start `docker compose up -d db` before a full run. It does **not** need a key — it passes
`gemini.api-key=placeholder-key-for-context-load` itself, so a reachable database is the only
prerequisite. The other 93 are offline: Gemini is always stubbed and no API key is required.

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
├── config/ChatMemoryProperties.java           TTL, prompt/retrieval turn budgets, purge period
├── config/AuthProperties.java                 session TTL, bcrypt cost, session purge period
├── config/AgendaProperties.java               recommendation pool, distance, slot assumptions
├── config/AnalyticsProperties.java            dashboard window and row cap
├── config/IngestionProperties.java            load the corpus at boot?
├── config/AnalyticsConfig.java                @EnableAsync + the analytics executor
├── config/CorsConfig.java                     WebMvcConfigurer for /api/**
├── config/ChatMemoryConfig.java               @EnableScheduling + registers the purge task
├── config/TimeConfig.java                     the Clock bean (see §3.1.19)
├── config/AuthConfig.java                     schedules the expired-session purge
├── config/PasswordEncoderConfig.java          the BCrypt PasswordEncoder bean
├── web/RateLimiter.java                       in-memory sliding window
├── web/RateLimitFilter.java                   429 + Retry-After before any controller runs
├── web/CurrentUser.java                       who is asking; reads what the auth filter resolved
├── web/AuthenticationFilter.java              Bearer -> identity; 401 on protected paths
├── config/GeminiClientConfig.java             two RestClient.Builder beans (see §3.1)
├── controller/KnowledgeChunkController.java   GET /api/chunks, POST /api/ingest?path=...
├── controller/ChatController.java             POST /api/chat, GET /api/chat/history
├── controller/AuthController.java             POST /api/auth/{register,login,logout}, GET /me
├── controller/AgendaController.java           POST /api/agenda/recommend
├── controller/AnalyticsController.java        GET /api/analytics/interest-summary
├── service/EmbeddingService.java              float[] embed(String) via Gemini embedContent
├── service/GenerationService.java             String generate(system, user) via generateContent
├── service/ChatService.java                   the RAG use case
├── service/ConversationMemory.java            one conversation per account, in Postgres, TTL'd
├── service/AuthService.java                   register, login, logout, authenticate, purge
├── service/PasswordPolicy.java                pure; length rules incl. BCrypt's 72-byte limit
├── service/AgendaRecommendationService.java   interests -> agenda search -> conflict-free itinerary
├── service/AgendaSlot.java                    pure; a session's time, from titleRef or content
├── service/ItineraryPlanner.java              pure; greedy, end-exclusive conflict resolution
├── service/InterestProfilePort.java           port: "what does this account care about?"
├── service/ConversationInterestProfile.java   its only implementation today: recent questions
├── service/InterestAnalyticsService.java      the dashboard aggregate
├── service/RetrievalLogger.java               best-effort, off-thread analytics writes
├── service/StartupIngestion.java              loads the corpus at boot so cold start works
├── service/PortugueseText.java                shared accent/case normalisation for the heuristics
├── service/PromptAssembler.java               pure; the grounding prompt lives here
├── service/EnumerationIntent.java             pure; routes "list every X" to a type filter
├── service/RetrievalQuery.java                pure; expands a follow-up into a searchable query
├── service/IngestionService.java              the pipeline; toDrafts() is pure
├── service/IngestionResult.java               record(created, skipped, total)
├── repository/KnowledgeChunkRepository.java   + findAllContentHashes(), findNearest()
├── repository/ConversationTurnRepository.java findRecent(user, after, limit), deleteOlderThan()
├── repository/AppUserRepository.java          findByEmail(normalised)
├── repository/UserSessionRepository.java      findValid(tokenHash, now), delete by hash/expiry
├── repository/ChunkRetrievalLogRepository.java  + summariseByType(), summariseByTitleRef()
├── repository/InterestSummaryRow.java         projection: groupKey/count/avgScore/distinctSessions
├── repository/ChunkMatch.java                 projection: id/type/titleRef/content/distance
├── model/KnowledgeChunk.java                  @Entity: id, type, titleRef, content,
│                                              embedding vector(768), contentHash
├── model/ChunkDraft.java                      record(type, titleRef, content) + contentHash()
├── model/ConversationTurn.java                @Entity: id, userId, role, content, createdAt
├── model/ConversationMessage.java             record(role, text) — what the prompt/API see
├── model/ChatRole.java                        USER | ASSISTANT (pinned by a CHECK constraint)
├── model/AppUser.java                         @Entity: id, email, passwordHash, createdAt
├── model/UserSession.java                     @Entity: id, tokenHash, userId, created, expires
├── model/EmailAddress.java                    record; trims + lowercases so UNIQUE means one account
├── model/AuthenticatedUser.java               record(id, email) + conversationKey()
├── model/ChunkRetrievalLog.java               @Entity: chunkId, endpoint, score, sessionRef, createdAt
├── model/RetrievalEndpoint.java               chat | agenda_recommend (CHECK-constrained)
├── model/InterestGrouping.java                type | titleRef
├── error/InvalidCredentialsException.java     wrong email or password, never distinguished -> 401
├── error/EmailAlreadyRegisteredException.java registration on a taken address -> 409
├── error/EmbeddingException.java
├── error/GenerationException.java             generation failed or returned an unusable answer
├── error/ConcurrentIngestionException.java    lost a race with a parallel ingest -> 409
├── error/GlobalExceptionHandler.java          RFC 7807 ProblemDetail mapping
├── error/TransientAiException.java            retryable failure (429/5xx/timeout)
├── dto/ChatRequest.java, ChatResponse.java, SourceRef.java, ChatTurn.java
├── dto/RegisterRequest.java, LoginRequest.java   toString() redacts the password
├── dto/AuthResponse.java, AccountResponse.java
├── dto/AgendaRecommendRequest.java, AgendaRecommendResponse.java, ItinerarySlot.java
├── dto/InterestSummaryResponse.java, InterestSummaryEntry.java
├── dto/EventDataDTO.java                      Jackson mirror of data/evento.json
└── dto/gemini/{EmbedContent,GenerateContent}{Request,Response}
backend/data/evento.json                       the source corpus -> 23 chunks
backend/src/main/resources/db/migration/V1__init.sql   extension + table + HNSW index
backend/src/main/resources/db/migration/V2__conversation_turn.sql   chat memory, one row per turn
backend/src/main/resources/db/migration/V3__app_user.sql             accounts
backend/src/main/resources/db/migration/V4__user_session.sql         sessions (token stored hashed)
backend/src/main/resources/db/migration/V5__chunk_retrieval_log.sql  aggregate retrieval analytics
backend/Dockerfile, frontend/Dockerfile        multi-stage: jar + corpus; Vite build behind nginx
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

Authentication is email + password, with no email confirmation: `POST /api/auth/register` creates
an account and returns a usable session immediately, so an address is *claimed*, never verified.
`AuthService` hashes passwords with BCrypt (`spring-security-crypto` — the only piece of this that
is not hand-rolled) and issues a 32-byte `SecureRandom` token whose **SHA-256 is what `user_session`
stores**; the token itself exists only in the response and the client. `AuthenticationFilter` turns
`Authorization: Bearer …` into an `AuthenticatedUser`, refuses `/api/chat`, `/api/chat/history`,
`/api/ingest` and `/api/auth/me` without one, and leaves `GET /api/chunks` open. Verified by hand
end to end: registration, a login using a different casing of the same address, 401 without a
token, a stored `$2a$10$…` hash and a 64-character token hash, and a logout that revoked one
session out of two while leaving the other working.

The corpus loads itself. `StartupIngestion` runs `ingestFromJsonFile` at boot (`app.ingestion.*`),
which is idempotent, so a restart writes nothing and spends no quota. It exists because
`POST /api/ingest` needs an account: without it a clean `docker compose up` would answer "não
encontrei" to everything — a working system that looks broken. A failure there logs at ERROR and lets
the application start, because registration, login and `GET /api/chunks` do not need the corpus and a
chat with no chunks already refuses honestly.

Two endpoints sit beside the chat and reuse its retrieval rather than a copy of it:

`POST /api/agenda/recommend` builds a conflict-free itinerary. `AgendaRecommendationService` resolves
the interest text (explicit first, then whatever `InterestProfilePort` knows), embeds it once,
retrieves agenda chunks through the same `findNearestByType` the listing questions use — with a wider
pool, because conflict resolution throws candidates away — and hands them to `ItineraryPlanner`, which
walks them best-first and keeps what fits. No generation call at all. `AgendaSlot` reads each session's
time from the chunk and treats ends as exclusive, so the corpus's touching slots (`08:15–09:00` then
`09:00–09:10`) are not read as a clash. Verified against the running stack: three sessions,
chronological, none overlapping.

`GET /api/analytics/interest-summary` answers "what is the audience actually curious about". Every
chunk that reaches the model is recorded by `RetrievalLogger` into `chunk_retrieval_log` — with no
question text and no user id — and the endpoint aggregates by type or by item. `InterestProfilePort`
exists because there is still no interest-profile table: `ConversationInterestProfile` derives one
from recent questions, so a real profile store would replace one class and nothing else.

Conversations are remembered server-side, one per account. `ChatService.answer(userId, question)`
takes the account id from `CurrentUser.conversationKey` and asks `ConversationMemory` for its
recent turns and spends them in two different places:
`RetrievalQuery.expand` prepends the last `app.chat-memory.retrieval-context-turns` *user*
questions to the text that gets embedded (a follow-up like "e ele fala a que horas?" has nothing
searchable of its own), and `PromptAssembler` emits a `HISTÓRICO` block so the model can tell what
"ele" refers to. Grounding is unchanged: the system instruction forbids treating anything that
appears only in the history as a fact, and the answer still comes from retrieved chunks alone.
Turns are written only after an answer exists, and both rows of an exchange share one instant.
Verified by hand against a running server: "Quem é Salim Ismail?" then "E ele fala a que horas?"
answered "Salim Ismail palestra das 09h10 às 10h00", retrieving the agenda chunk at 0.727
similarity that the bare follow-up could never have matched.

That path is bounded by `rag.max-enumeration` (30), and the bound is handled deliberately:
`ChatService.retrieve` asks for `cap + 1` rows so that hitting the cap is *detectable*, logs a
WARN naming the type, and only then truncates. Presenting a capped list as a whole category is
the exact failure this code path exists to prevent, so a silent `Limit.of(cap)` would defeat it.
`RetrievalProperties` also refuses to start with `rag.max-enumeration < rag.top-k`. If no chunk
of the detected type exists, retrieval falls through to similarity search rather than claiming
the corpus knows nothing.

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
    constructor, or Spring reports `No default constructor found`. `AuthenticationFilter` joins
    every slice the same way, so a `@WebMvcTest` touching a protected path needs a mocked
    `AuthService` stubbed to resolve a test token **and** the `Authorization` header on each
    request — otherwise every call in the slice comes back 401. `ChatController` additionally needs
    a `ConversationMemory` mock and `CurrentUser` imported. A slice that binds
    `RateLimitProperties` must supply **all four** of its limits, or the record's own validation
    fails the context with a message about a zero limit rather than about a missing property.
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
16. **`POST /api/ingest?path=` is confined by two checks, not one.**
    `IngestionService.resolveWithinWorkingDirectory` first rejects the path lexically
    (`normalize()` + `startsWith(base)`), then calls `Path.toRealPath()` and checks containment
    again. Normalization cannot see symbolic links, so a link *inside* the working directory
    pointing outside it passes the first check and fails only the second. Resolving the real
    path also turns a missing file into a `NoSuchFileException` here rather than later in the
    reader — both are `IOException` and map to the same 400. Deleting either check reopens the
    traversal.
17. **The conversation TTL is enforced on read, not by the purge job.**
    `ConversationTurnRepository.findRecent` takes an `after` cutoff, so a turn past
    `app.chat-memory.ttl` cannot reach a prompt even if the scheduler never ran.
    `ConversationMemory.purgeExpired` only reclaims space. Deleting the `after` parameter as
    "redundant with the cleanup task" would make expiry depend on a background thread.
18. **`findRecent` orders by `createdAt desc, id desc`.** Both turns of one exchange are written
    with the same instant on purpose (they are one event), so timestamp alone is not a total
    order — without the id tiebreaker the history can hand the model an answer before its
    question.
19. **The `Clock` bean lives in `TimeConfig`, not next to the scheduling it serves.**
    `ChatMemoryConfig` takes `ConversationMemory` as a constructor parameter to register the purge
    task, and `ConversationMemory` needs the clock; declaring the bean in `ChatMemoryConfig` makes
    the two depend on each other's construction and fails startup with
    `BeanCurrentlyInCreationException`.
20. **The purge is registered in `configureTasks`, not with `@Scheduled(fixedRateString = ...)`.**
    The interval then comes from the typed `Duration` the properties record already validated,
    instead of a second string parsed at annotation time.
21. **`CorsConfig` must list `Authorization` in `allowedHeaders`.** A browser cannot send a header
    the preflight did not allow, so dropping it does not break loudly — every request simply
    arrives unauthenticated, the whole UI sees 401s, and `curl` keeps working fine.
22. **`EnumerationIntent` reads the raw question, never the expanded retrieval text.** The
    expansion carries earlier questions forward, so feeding it to intent detection would let one
    "quais artigos existem?" turn every later question in the conversation into a listing.
23. **Anonymous callers get no memory, deliberately.** `CurrentUser` returns `null` when
    `X-User-Id` is absent rather than falling back to the remote address the way `RateLimitFilter`
    does. An IP is not a person: everyone behind one NAT would share a single conversation. A
    header that is present but malformed is a 400, not a silent downgrade to anonymous.
24. **`AuthenticationFilter` runs *before* `RateLimitFilter`** (order 50 against 100). The limiter
    counts every allowed request against a daily AI budget of 18, so with the order reversed a
    stranger with no account could exhaust the whole day's quota on requests that were going to be
    rejected as 401 anyway.
25. **Authentication has its own rate-limit window** (`app.rate-limit.auth-requests-per-minute-per-client`,
    and no daily cap). Putting `/api/auth/login` under the AI limits would mean failed logins spend
    provider quota, and a global daily cap on logins would let one attacker lock every user out of
    signing in. It is a per-IP throttle only — there is no per-account lockout (§4).
26. **`PasswordPolicy` rejects passwords over 72 *bytes*.** BCrypt hashes the first 72 bytes and
    silently ignores the rest, so accepting a longer one would tell the user their whole passphrase
    was used when it was not. Accented characters are two bytes in UTF-8, so this arrives sooner
    than the character count suggests. Applied at registration only — an existing account must not
    stop working because the policy changed.
27. **A failed login on an unknown address still runs one BCrypt comparison**, against a throwaway
    hash generated at startup. Skipping it would make "no such account" answer in a fraction of the
    time of "wrong password", which turns the endpoint into an account-existence oracle no matter
    how careful the message is.
28. **The session token is stored as a plain SHA-256, not BCrypt.** That is not an oversight: the
    token is 256 bits of `SecureRandom`, so there is no low-entropy secret to brute-force, and
    authentication happens on every request — a deliberately slow hash there would be a
    self-inflicted denial of service. Passwords are the opposite case, and get BCrypt.
29. **`PasswordEncoderConfig` exists for the same reason as `TimeConfig` (§3.1.19).** `AuthConfig`
    injects `AuthService` to schedule the session purge, and `AuthService` needs the encoder;
    declaring the bean in `AuthConfig` fails startup with `BeanCurrentlyInCreationException`.
30. **`RetrievalQuery` expands a query only when the question cannot stand alone.** Found by
    measurement, not taste: with expansion applied unconditionally, "Onde e quando acontece o
    evento?" asked right after a question about exponential technologies retrieved the earlier topic
    (0.803 similarity on the wrong chunk), the event's own chunk never reached the model, and the
    answer was a refusal. A self-contained question already carries its subject, so expansion now
    waits for an anaphoric signal — a pronoun, a demonstrative, a leading "e ..." — and errs towards
    *not* expanding: a missed expansion costs one weak follow-up, a wrong one corrupts a good
    question. Same class of heuristic as `EnumerationIntent`, and both normalise through
    `PortugueseText` so they cannot drift apart.
31. **Two things had to be fixed before `docker compose up --build` worked.** First, `RUN ./mvnw ...`
    failed with **exit 126**: the wrapper's execute bit is not in a fresh checkout (the same trap §2
    documents for local runs). It is now recorded in git (`git update-index --chmod=+x`) *and*
    re-applied in the Dockerfile, because one checkout losing it should not break the image. Second,
    the runtime stage copied only the jar, so ingestion died with
    `NoSuchFileException: /app/data/evento.json` — the corpus is read from the filesystem, not the
    classpath, which makes `COPY data ./data` load-bearing.
32. **`AgendaSlot` reads the session time from `titleRef` *or* the chunk text.** The corpus changed
    shape under this feature: agenda chunks used the slot as `titleRef` (`"09h10 às 10h00"`) and now
    use the session title, with the time inside the text as `"Horário: 09:10 às 10:00"`. Reading only
    one of the two made every session unschedulable and every itinerary empty. Both separators
    (`09h10`, `09:10`) are accepted for the same reason.
33. **The analytics executor drops work instead of rejecting it.** A `ThreadPoolTaskExecutor` with a
    full queue throws `RejectedExecutionException` **in the calling thread** by default, which would
    turn a saturated analytics pool into failed user requests — the exact opposite of what
    best-effort logging is for. `AnalyticsConfig` installs a handler that logs and discards.
34. **`RetrievalLogger` is the only class here that swallows exceptions**, and its javadoc says so.
    It also hid something real during development: a Mockito `UnfinishedStubbingException` leaking
    from another test class surfaced as "analytics silently did nothing" rather than as a failure.
    When rows go missing, read the warning log before assuming the writer is fine.
35. **`EmbeddingService` calls the static `GenerationService.isTransient`.** One classifier, two
    callers — not a copy-paste slip. Under the §6 split both services become adapters in
    `adapter.out.ai`; move the classifier to a shared type there rather than duplicating it.

### 3.2 Frontend (React 19 + Vite 8, `frontend/`)

```text
frontend/
├── index.html, vite.config.js (plugin-react only — no proxy, no port override)
├── .oxlintrc.json                oxlint, react + oxc plugins
├── public/                       favicon.svg, icons.svg
└── src/main.jsx → App.jsx → Chat.jsx   the whole app; styling is inline + App.css
    └── assets/hero.png, react.svg, vite.svg
```

`Chat.jsx` is the only component with behaviour. It calls `POST /api/chat` for real (issue #11);
the `MOCK_MODE` flag that used to short-circuit the fetch is gone. Three things about it:

- The backend base URL is `import.meta.env.VITE_API_URL ?? "http://localhost:8080"`, so pointing
  the UI at another host needs no code edit. Vite reads `frontend/.env`, which is not the repo-root
  `.env` the backend and Compose use — they are separate files with separate variables.
- `fetch` rejects only on network, CORS or abort failures, never on a 4xx/5xx. The `!res.ok`
  branch is what turns a `ProblemDetail` into a visible message, and it is load-bearing: without
  it a 429 renders as `undefined`. Error responses do carry CORS headers, so the body is readable.
- `App.jsx` decides between `Auth.jsx` (register/sign-in, one form for both — they take the same
  two fields) and `Chat.jsx`. `api.js` holds the base URL, the token helpers and `readError`.
- **A stored token is not proof of a session.** On load, `App.jsx` calls `GET /api/auth/me` with it
  and only then shows the chat; anything other than 200 sends the user back to the form. Trusting
  `localStorage` instead would render a chat whose first question returns 401.
- The token lives in `localStorage`, so it survives a reload and is readable by any script on the
  page (§4). `Chat.jsx` treats a 401 mid-session as "signed out" and calls `onSessionExpired`
  rather than showing an error the user cannot act on.
- Password rules are **not** duplicated in the client — the register form shows a hint and the
  displayed message is whatever the backend returned, so there is only one copy of the rule.
- On mount, `Chat.jsx` fetches `GET /api/chat/history` and seeds the message list from it.
  Without it a reloaded page looks empty while the backend keeps answering as if the conversation
  never stopped. A failed history fetch shows a message instead of failing silently — a chat that
  quietly forgot is worse than one that says so.
- A 45s `AbortController` bounds the request. It is deliberately far above the 4-7s the backend
  needs — it exists so a server that never answers does not hang the UI forever, and an abort
  renders its own message ("demorou demais"), distinct from a network failure.
- The retrieved chunks are labelled **"Trechos consultados", not "Fontes"**. `sources` is what was
  retrieved, not what was cited (§4), so it arrives populated even under a refusal. Calling it a
  citation would put references under a "não sei", and detecting the refusal by string-matching is
  what §4 rules out — a neutral label sidesteps both.

`frontend/README.md` is the untouched `create-vite` template and describes nothing about this
project; do not treat it as documentation.

---

The HTTP contract is specified in `API_CONTRACT.md` at the repo root, with payloads captured
from a running server. **Any change to a request/response shape must update that file in the
same commit** — the frontend treats it as authoritative and does not read this code.

---

## 4. Known gaps between this document and the code

Read this before claiming anything works. Closing these gaps *is* the current work:

- **Dockerfiles exist and `docker compose up --build` works** (§1). The frontend image runs
  `vite build` and serves `dist/` through nginx on `80`, published as `3000`; the backend image is
  Maven-build → JRE, carrying the jar plus `data/`. The empty top-level `docker/` directory
  (`.gitkeep`) is **not** where compose looks — the build contexts are `backend/` and `frontend/`.
  Two live consequences of the nginx choice: `VITE_API_URL` is baked in at image build time (default
  `http://localhost:8080`, which is correct for a browser on the host but wrong for any other host),
  and there is no hot reload in the container — use `npm run dev` on `5173` for frontend work.
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
- **Authentication exists, but authorisation does not.** Every account is equal, so any registered
  user can call `POST /api/ingest` — the endpoint that writes the corpus and reads a server file
  path. Registration is open, so "logged in" is a very low bar. A role check on ingest is the
  obvious next step.
- **No HTTPS.** Passwords and bearer tokens cross the wire in clear over `http://localhost`.
  Nothing about this setup is safe outside the local machine until TLS terminates in front of it.
- **The token lives in `localStorage`**, which any script executing on the page can read; an
  httpOnly cookie would not be readable, at the cost of needing CSRF protection. Chosen for a
  JSON API with no cookies at all — but it means an XSS on the frontend is a session compromise.
- **No password change, no reset, no email confirmation.** Losing a password means losing the
  account, and an address is claimed rather than verified (a typo silently registers the typo).
- **Registration reveals whether an address has an account** (409), which login deliberately
  refuses to do. Without a confirmation email there is no better option: silently accepting the
  registration would leave a real user unable to explain why their new account does not work.
- **The login throttle is per IP only.** 10 attempts/minute from one address, with no per-account
  lockout and no CAPTCHA, so an attack spread over many IPs is bounded only by BCrypt's cost per
  guess. The counters are in memory, so they reset on restart and are per instance — the same
  caveat as the AI rate limit below.
- **Rate limit state is in-memory and per-instance.** A restart resets the daily counter, and a
  second instance would double the real spend. Moving it to the database or a cache is the fix
  if this ever runs more than single-node.
- **`agenda.event-date` duplicates a fact that lives in the corpus.** `evento.data_iso` is
  `2026-08-26` and so is the property; nothing checks that they agree, so a new corpus with a
  different date would make the `date` filter answer "there is no programme that day" for the real
  event day. Reading it from the ingested corpus is the fix.
- **The recommender ignores `agenda_subsessao`.** The corpus splits the parallel thematic block into
  three sub-sessions with their own chunks, and the itinerary only searches `type = 'agenda'` — so it
  can recommend "Sessões Temáticas" but never which of the three to attend. Including them needs a
  multi-type variant of `findNearestByType`, and they would all clash with each other by design,
  which is exactly what the planner is for.
- **"Explicit interests win" is positional, not semantic.** The stated text is concatenated ahead of
  the stored profile and embedded once, so a strong profile still pulls the result: asking for
  "networking, nothing technical" on an account with a technical history returned the networking
  session *and* the technical one (measured — `TESTES.md`, Achado 6). Averaging two embeddings would
  be worse; using the stated text alone when present is a one-line change if that reading is wanted.
- **`GET /api/analytics/interest-summary` is unauthenticated.** Deliberately left open as a question
  rather than decided quietly: the payload is aggregate-only (no question text, nothing per-user), so
  it is defensible for a demo, but a ranking of what the audience is asking about is a business
  signal that may not belong in public before the event. Closing it is one line — add the path to
  `AuthenticationFilter.PROTECTED_PATHS`.
- **`distinctSessions` counts requests, not people.** `session_ref` is a fresh id per request. Real
  per-user analytics would need a separate table and its own retention decision.
- **Startup ingestion runs on every instance.** Two backends booting against one database both try
  to ingest; the content-hash check makes that harmless, but they will race and one will lose to the
  unique constraint and log the 409 path.
- **The `vector(768)` round-trip has no automated test.** It was verified by hand against
  real pgvector (23 rows, `vector_dims` 768, cosine neighbours sane). Automating it needs
  Testcontainers, which is not a dependency — see §9.2.
- **The frontend calls the real endpoint, but nothing about it is automatically tested.** There
  is no frontend test runner, so `Chat.jsx` is covered only by `npm run lint`, `npm run build`
  and manual clicking. The states it renders (loading, error, empty answer, retrieved chunks)
  can regress silently.
- **Two Jackson stacks on the classpath.** Boot 4.1 ships Jackson 3
  (`tools.jackson.core:jackson-databind:3.1.4`) and serializes HTTP responses with it, while
  commit `fb51511` added Jackson 2 (`com.fasterxml...:2.21.4`), which `IngestionService` uses to
  parse the source JSON. Harmless today — the `com.fasterxml.jackson.annotation` annotations are
  shared by both — but the Jackson 2 dependency is redundant and should be dropped.
- **`POST /api/ingest?path=` accepts any file under the working directory**, from an
  unauthenticated request. Traversal and symlink escapes are blocked (§3.1.16), so this is no
  longer arbitrary-path — but it is still "any readable file in the app's directory, chosen by
  the caller", and anything that parses as `EventDataDTO` gets embedded. Narrowing it to a known
  resource is the remaining work.
- **The generation error path can put the prompt in the logs.** `EmbeddingService` deliberately
  never reads the Gemini error body, because it can echo back the submitted text — which at
  query time is the end user's question. `GenerationService.callApiOnce` still reads that body
  into the `GenerationException` message, and the prompt it sent contains both the question and
  the retrieved context. The client never sees it (`GlobalExceptionHandler` returns fixed text),
  so this is a server-log privacy gap, not a leak to callers. Make the two services symmetric.
- **Chat history is user text in the database** for up to `app.chat-memory.ttl`, now tied to a
  real account. It is deleted after that, but there is no per-user delete endpoint, no export, and
  no consent flow — and deleting an account does not delete its conversation (`conversation_turn`
  keys by account id as text, with no foreign key, so the rows simply age out).
- **The purges run per instance.** `@EnableScheduling` in `ChatMemoryConfig` means every instance
  deletes expired conversation turns and sessions on its own timer. Harmless (the deletes are
  idempotent) but wasteful, and worth knowing before this is scaled out.
- **Authentication is proven by unit tests plus one manual run, not by an integration test.** The
  BCrypt hashing, token hashing, generic-401 behaviour, expiry filtering and revocation are covered
  by `AuthServiceTest`/`AuthenticationFilterTest` against mocked repositories; the database side
  (unique email, session FK cascade, expiry) was exercised by hand. Same Testcontainers blocker.
- **Conversation memory is proven by unit tests plus one manual run, not by an integration test.**
  `contextLoads` covers the schema (Hibernate `validate` against the real `conversation_turn`
  table), and the TTL read filter, the purge, per-user isolation and the pronoun follow-up were
  verified by hand against a running server. Automating the database side needs Testcontainers —
  same blocker as the `vector(768)` round-trip below.
- **The retrieval-context expansion is a heuristic.** Prepending the last two questions helps a
  pronoun follow-up and slightly blurs the query for an unrelated new question asked mid-
  conversation. `app.chat-memory.retrieval-context-turns=0` turns it off; nothing measures the
  trade-off on a real question set yet.
- **No integration or e2e tests.** 188 tests pass across 19 classes (`ChunkDraft`, `Ingestion`,
  `Embedding`, `Generation`, `Chat`, `PromptAssembler`, `EnumerationIntent`, `RetrievalQuery`,
  `ConversationMemory`, `Auth`, `EmailAddress`, `AppUser`, `PasswordPolicy`, `ChatController`,
  `AuthController`, `CurrentUser`, `AuthenticationFilter`, `RateLimiter`, `RateLimitFilter`, plus
  `contextLoads`) — all unit or `@WebMvcTest` slices. The
  §9 layout (integration / contract / e2e), Testcontainers, JaCoCo floors and PIT are not in
  place, and no test package follows the `unit/ integration/ contract/ e2e/` split yet.
- `pom.xml` has empty `<name>`, `<description>`, `<license>`, `<developer>`, `<scm>` stubs.

---

## 5. Stack and versions

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 21 (LTS) | Records, sealed types, pattern matching are expected, not exotic |
| Framework | Spring Boot 4.1.0 | Recent; most third-party docs are stale. Verify against official docs, not tutorials |
| Persistence | Spring Data JPA + Hibernate + `hibernate-vector` | ORM for CRUD/lifecycle. Vector search is **HQL, not native SQL**: `@Query` calls `cosine_distance(...)`, a function `hibernate-vector` contributes for PostgreSQL and maps to pgvector's `<=>` |
| Database | PostgreSQL 16 + `pgvector` | `vector` column type, cosine distance, HNSW index |
| Build | Maven 3.9.16 via the wrapper | The wrapper is authoritative |
| LLM/Embeddings | Google Gemini API | Single provider for embeddings + generation (+ future multimodal) |
| Auth | Opaque bearer tokens in Postgres + BCrypt via `spring-security-crypto` | **Not** Spring Security: one jar for the password hash, no filter chain. Sessions, the filter and the throttle are in `AuthService`/`AuthenticationFilter` |
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
- **Similarity:** cosine distance (`<=>` in pgvector). *Target, not current:* vectors are to be
  stored normalized, normalized once in the domain at construction time. Today nothing normalizes
  — the raw Gemini vector is written straight to the `vector(768)` column and `cosine_distance`
  normalizes as it compares, which is correct but pays for it on every query.
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
| 0 | Skeleton | `docker compose up` starts Postgres+pgvector and the app; `GET /api/chunks` answers 200; Flyway `V1` applied; CI runs `sh ./mvnw verify` |
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
structured logging yet). Stage 7's UI now consumes the live endpoint with loading, error and
empty states rendered (§3.2), but has no automated coverage. Server-side conversation memory
(§3, `V2__conversation_turn.sql`) extends stage 5 and its `GET /api/chat/history` endpoint extends
stage 6; both are implemented and manually verified, and neither changes the stage gates below.
Email/password authentication (§3, `V3`/`V4`) also belongs to stage 6 — it closes the "endpoints are
open" half of that stage while leaving authorisation, HTTPS and account recovery open (§4). The agenda
recommender and the interest dashboard (`V5`) are additive features on top of stage 5, and stage 0's
gate — `docker compose up` working — is now genuinely met, which unblocks calling stages 0 and 3–7
delivered rather than merely implemented. The functionality works — retry/backoff with timeouts, idempotent
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
# The corpus loads itself on first boot; wait for it before asking anything.
docker compose logs -f backend-java | grep -m1 -E "Loaded the corpus|Corpus already loaded"
curl -fsS localhost:8080/api/chunks | head -c 80   # there is no /actuator/health — see below

# /api/chat requires an account now, so the script has to create one.
TOKEN=$(curl -fsS -X POST localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"coldstart@example.com","password":"senha-bem-boa"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -fsS -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"message":"Quem fala sobre tecnologias exponenciais e a que horas?"}'
curl -fsS -X POST localhost:8080/api/agenda/recommend \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"interests":"agentes de IA em operações","maxSessions":3}'
curl -fsS 'localhost:8080/api/analytics/interest-summary?groupBy=titleRef'
docker compose down -v
```

If any step requires a manual fix not written in `README.md`, the `README.md` is wrong —
fix the documentation in the same commit. **This script passes as of 2026-08-20**, run from a wiped
volume against the containerised stack.

**There is no `/actuator/health`.** `spring-boot-starter-actuator` is not a dependency, so that
URL 404s; `GET /api/chunks` is the cheapest liveness probe that exists today (a database read, no
AI quota). Adding actuator is a dependency decision (§11) — ask before taking it, and if it is
taken, update this script and §8 stage 0 together.

---

## 14. ADRs

Decisions that are expensive to reverse (embedding model, chunking policy, index type,
provider swap, transaction boundaries) are recorded in `docs/adr/NNNN-title.md`:
context, options considered, decision, consequences. Short. One page. If a future change
contradicts an ADR, write a new ADR that supersedes it rather than editing history.
