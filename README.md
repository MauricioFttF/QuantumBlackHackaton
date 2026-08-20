# QuantumBlackHackaton

> **Resultados dos testes manuais do `/api/chat`:** [`TESTES.md`](TESTES.md) — 12 perguntas,
> nenhuma alucinação, mas dois achados que valem ler antes de demonstrar o sistema.
>
> **Integrando o frontend?** O contrato de request/response de todos os endpoints está em
> [`API_CONTRACT.md`](API_CONTRACT.md). CORS já está liberado para `localhost:3000` e
> `localhost:5173`.

## Banco de dados vetorial

Usamos PostgreSQL com a extensão [pgvector](https://github.com/pgvector/pgvector) para armazenar embeddings e realizar busca por similaridade semântica (RAG).

Imagem Docker utilizada: `pgvector/pgvector:pg16`.

O schema é gerenciado por **Flyway** (`backend/src/main/resources/db/migration`), aplicado
automaticamente no startup do backend. `V1__init.sql` cria a extensão `vector`, a tabela
`knowledge_chunk` e o índice HNSW (`vector_cosine_ops`). O Hibernate roda com
`ddl-auto=validate` — ele confere o mapeamento, mas não altera o banco.

## Configuração (variáveis de ambiente)

```bash
cp .env.example .env      # preencha GEMINI_API_KEY
```

O backend **não sobe** sem `GEMINI_API_KEY` — a falha acontece no startup, com mensagem
explícita, em vez de na primeira requisição.

| Variável / propriedade | Padrão | Descrição |
|---|---|---|
| `GEMINI_API_KEY` | — (obrigatório) | Chave da API Gemini ([aistudio.google.com/apikey](https://aistudio.google.com/apikey)) |
| `gemini.base-url` | `https://generativelanguage.googleapis.com/v1beta` | Base da API |
| `gemini.embedding-model` | `gemini-embedding-001` | Modelo de embeddings |
| `gemini.embedding-dimensions` | `768` | Dimensão do vetor (≤ 2000 por causa do índice HNSW do pgvector) |
| `gemini.connect-timeout` / `gemini.read-timeout` | `5s` / `20s` | Timeouts da chamada HTTP |

## Embeddings

`EmbeddingService.embed(String text)` retorna um `float[]` com
`gemini.embedding-dimensions` posições. Erros (HTTP 4xx/5xx, timeout, dimensão
inesperada) lançam `EmbeddingException` — nunca um vetor de zeros, que
contaminaria silenciosamente a busca por similaridade.

Verificação automatizada (não usa rede, não precisa de chave):

```bash
cd backend && sh ./mvnw test -Dtest=EmbeddingServiceTest
```

Verificação manual contra a API real (confirma que a chave funciona e que a dimensão
retornada é a esperada):

```bash
curl -s "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent" \
  -H "Content-Type: application/json" \
  -H "x-goog-api-key: ${GEMINI_API_KEY}" \
  -d '{"model":"models/gemini-embedding-001",
       "content":{"parts":[{"text":"teste"}]},
       "outputDimensionality":768}' \
  | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["embedding"]["values"]))'
# esperado: 768
```


## Pipeline de ingestão

`POST /api/ingest?path=data/evento.json` executa três estágios:

1. **texto** — `IngestionService.toDrafts` achata o JSON em `ChunkDraft`s (puro e determinístico:
   mesmo JSON → mesmos chunks → mesmo hash SHA-256).
2. **embeddings** — cada chunk novo passa por `EmbeddingService.embed`, gerando um `float[768]`.
3. **banco** — um único `saveAll` grava tudo em `knowledge_chunk.embedding` (`vector(768)`).

Resposta: `{"created":23,"skipped":0,"total":23}`.

**Idempotente.** Cada chunk tem um `content_hash` (SHA-256 de type + titleRef + content) com
constraint `UNIQUE`. Chunks já presentes são descartados **antes** de serem embedados, então
reexecutar o ingest não duplica linhas nem gasta cota da API:

```bash
curl -X POST 'localhost:8080/api/ingest?path=data/evento.json'
# 1a vez: {"created":23,"skipped":0,"total":23}   (~12s, 23 chamadas à API)
# 2a vez: {"created":0,"skipped":23,"total":23}   (~13ms, 0 chamadas)
```

Se qualquer embedding falhar, a exceção sobe e **nada** é gravado — um corpus parcialmente
embedado é pior que nenhum.

Conferindo o resultado:

```bash
docker exec hackathon-db psql -U postgres -d hackathondb -c \
  "SELECT count(*), min(vector_dims(embedding)) FROM knowledge_chunk;"   # 23 | 768

# vizinhos mais próximos de um palestrante (distância cosseno)
docker exec hackathon-db psql -U postgres -d hackathondb -c \
  "SELECT type, title_ref, embedding <=> (SELECT embedding FROM knowledge_chunk
     WHERE title_ref='Salim Ismail') AS dist
   FROM knowledge_chunk ORDER BY dist LIMIT 5;"
```

O índice HNSW existe, mas com 23 linhas o planner ainda prefere sequential scan — o que é
correto nesse tamanho. Para conferir que o índice e o opclass estão certos:

```bash
docker exec hackathon-db psql -U postgres -d hackathondb -c \
  "SET enable_seqscan=off; EXPLAIN SELECT id FROM knowledge_chunk
   ORDER BY embedding <=> (SELECT embedding FROM knowledge_chunk LIMIT 1) LIMIT 5;"
# Index Scan using idx_knowledge_chunk_embedding_hnsw
```


## Chat com RAG

`POST /api/chat` responde perguntas sobre o evento usando apenas o conteúdo ingerido.

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Quem vai falar sobre tecnologias exponenciais e a que horas?"}'
```

```json
{
  "answer": "Salim Ismail falará sobre tecnologias exponenciais, e a palestra será das 09h10 às 10h00.",
  "sources": [
    {"id": 4, "type": "agenda", "titleRef": "09h10 às 10h00", "score": 0.816}
  ]
}
```

Fluxo: a pergunta é embedada → busca os `rag.top-k` chunks mais próximos por distância
cosseno → descarta o que passar de `rag.max-distance` → monta um prompt com esse contexto →
o Gemini responde **somente** com base nele.

**Perguntas de listagem** ("quais artigos existem?", "qual a programação?") seguem outro
caminho: são detectadas por palavra-chave e recuperam **todos** os chunks daquele tipo por
filtro de metadado, em vez dos `top-k` mais parecidos. Busca vetorial ordena por semelhança e
nunca garante que viu a categoria inteira — antes desse ajuste, "quais artigos?" devolvia 1 de
3. Detalhes em [`TESTES.md`](TESTES.md).

**Resiliência:** falhas transitórias do Gemini (429, 5xx, timeout) são repetidas até
`gemini.retry-max-attempts` vezes com backoff exponencial. Erros permanentes (chave inválida,
resposta bloqueada) falham na primeira tentativa. `/api/chat` e `/api/ingest` têm rate limit
por IP e um teto diário global que protege a cota do provedor.

`sources` lista os chunks enviados como contexto, com `score` = similaridade (`1 - distância`).

## Contas e login

Cadastro e login por e-mail e senha. **Não há confirmação por e-mail**: registrar já entra. Os
endpoints que gastam cota de IA (`/api/chat`, `/api/ingest`), o histórico e `/api/auth/me` exigem
`Authorization: Bearer <token>`; `GET /api/chunks` continua aberto.

```bash
# criar conta (já devolve sessão)
TOKEN=$(curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"pedro@usp.br","password":"senha-bem-boa"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -s localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
# {"id":1,"email":"pedro@usp.br"}

curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -d '{"message":"Quem é Salim Ismail?"}'
# 401 — sem token não passa, e o 401 é decidido antes do rate limit para não gastar cota de IA

curl -s -X POST localhost:8080/api/auth/logout -H "Authorization: Bearer $TOKEN"   # 204, revoga
```

O que está garantido, e por quê:

- **Senha só existe como hash BCrypt** (`spring-security-crypto`, cost 10). O `app_user` nunca vê
  o texto puro, e o `toString` do request é sobrescrito para a senha não chegar a um log.
- **Senha aceita de 8 caracteres até 72 bytes.** O teto não é preferência: o BCrypt usa só os
  primeiros 72 bytes e ignora o resto sem avisar — aceitar mais seria dizer que a senha inteira
  foi usada quando não foi. Acentos contam 2 bytes.
- **O token nunca é gravado.** `user_session` guarda o SHA-256 de 32 bytes aleatórios; um dump da
  tabela não dá sessão a ninguém. Expira em `app.auth.session-ttl` (24h), e a expiração é filtrada
  na consulta — encurtar o TTL vale imediatamente para sessões já abertas.
- **Login não diz qual metade errou.** E-mail inexistente e senha errada devolvem o mesmo `401`
  com a mesma mensagem, e o caminho do e-mail inexistente gasta uma comparação de BCrypt de
  propósito, para não responder mais rápido e virar um detector de contas.
- **`/api/auth/login` e `/api/auth/register` têm rate limit próprio** (10/min por IP) que não
  consome o teto diário de IA.

`E-mail já cadastrado` no registro (`409`) **revela** que o endereço tem conta — o login se recusa
a fazer isso. Sem confirmação por e-mail não há alternativa razoável; está registrado como
limitação conhecida no `CLAUDE.md` §4.

| Propriedade | Padrão | Descrição |
|---|---|---|
| `app.auth.session-ttl` | `24h` | Validade da sessão, conferida a cada requisição |
| `app.auth.bcrypt-strength` | `10` | Custo do hash; cada passo dobra o trabalho por tentativa |
| `app.auth.session-purge-period` | `1h` | De quanto em quanto tempo sessões vencidas são apagadas |
| `app.rate-limit.auth-requests-per-minute-per-client` | `10` | Tentativas de login/registro por IP por minuto |

## Memória de conversa

O backend guarda **uma conversa por conta** no Postgres (`conversation_turn`, migração `V2`) e a
usa para entender perguntas de acompanhamento. A conversa é identificada pela sessão autenticada,
então ela segue o login — outro navegador, o mesmo usuário, a mesma conversa.

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d '{"message":"Quem é Salim Ismail?"}'
# {"answer":"Salim Ismail é fundador e ex-diretor executivo da Singularity University, ...", ...}

curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d '{"message":"E ele fala a que horas?"}'
# {"answer":"Salim Ismail palestra das 09h10 às 10h00.", ...}   <- "ele" resolvido pelo histórico

curl -s localhost:8080/api/chat/history -H "Authorization: Bearer $TOKEN"
# [{"role":"user","text":"Quem é Salim Ismail?"}, {"role":"assistant","text":"..."}]
```

A pergunta de acompanhamento funciona em duas frentes: as últimas perguntas do usuário entram no
texto que é **embedado** (senão "e ele fala a que horas?" não casa com nada no banco), e o
histórico entra no **prompt** para o modelo saber quem é "ele".

**O histórico não é fonte de fatos.** A instrução de sistema manda tratar como inexistente
qualquer dado que apareça só no histórico e não no contexto recuperado — sem isso o modelo repete
como verdade algo que ele mesmo inventou dois turnos antes.

**Expira em 1 hora.** Turnos mais antigos que `app.chat-memory.ttl` não são lidos (o filtro está
na consulta, então a expiração vale mesmo se a limpeza não rodar) e são apagados por uma tarefa
agendada a cada `app.chat-memory.cleanup-interval`:

```bash
docker exec hackathon-db psql -U postgres -d hackathondb -c \
  "SELECT user_id, role, left(content, 40), created_at FROM conversation_turn ORDER BY id;"
# no log do backend, a cada 15 min: "Purged N expired conversation turn(s) older than PT1H"
```

O histórico é o texto das perguntas e respostas, guardado por até uma hora. É apagado depois
disso, mas até lá está no banco — e não há endpoint para o usuário apagar a própria conversa.

**Aterramento (grounding).** O modelo é instruído a admitir quando o contexto não cobre a
pergunta, e o serviço nem chama o modelo quando nenhum chunk passa do limiar:

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Qual é a capital da Mongólia?"}'
# {"answer":"Não encontrei essa informação no material do evento.", ...}
```

Erros seguem RFC 7807 (`ProblemDetail`): pergunta vazia → `400`; falha do Gemini → `502`
(sem vazar corpo de erro nem stack trace para o cliente).

| Propriedade | Padrão | Descrição |
|---|---|---|
| `gemini.chat-model` | `gemini-3.6-flash` | Modelo de geração |
| `gemini.chat-temperature` | `0.2` | Baixa, para respostas factuais |
| `gemini.chat-max-output-tokens` | `4096` | Resposta truncada vira erro, não meia resposta |
| `gemini.chat-read-timeout` | `60s` | Geração é bem mais lenta que embedding |
| `rag.top-k` | `5` | Chunks recuperados por pergunta |
| `rag.max-distance` | `0.8` | Acima disso o chunk é considerado irrelevante |
| `rag.max-enumeration` | `30` | Teto de chunks numa pergunta de listagem |
| `gemini.retry-max-attempts` | `3` | Tentativas em falhas transitórias (429/5xx/timeout) |
| `app.rate-limit.requests-per-minute-per-client` | `6` | Limite por IP |
| `app.rate-limit.requests-per-day-total` | `18` | Teto diário global (cota do provedor: 20/dia) |
| `app.web.cors-allowed-origins` | `http://localhost:3000,http://localhost:5173` | Origens liberadas no navegador |
| `app.chat-memory.enabled` | `true` | `false` responde tudo sem histórico |
| `app.chat-memory.ttl` | `1h` | Até onde o histórico alcança — e o horizonte de exclusão |
| `app.chat-memory.max-turns` | `6` | Turnos recentes enviados ao modelo |
| `app.chat-memory.retrieval-context-turns` | `2` | Perguntas anteriores que entram no texto embedado (`0` desliga) |
| `app.chat-memory.cleanup-interval` | `15m` | De quanto em quanto tempo os turnos vencidos são apagados |
