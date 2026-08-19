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
| `gemini.chat-max-output-tokens` | `1024` | Resposta truncada vira erro, não meia resposta |
| `gemini.chat-read-timeout` | `60s` | Geração é bem mais lenta que embedding |
| `rag.top-k` | `5` | Chunks recuperados por pergunta |
| `rag.max-distance` | `0.8` | Acima disso o chunk é considerado irrelevante |
| `rag.max-enumeration` | `30` | Teto de chunks numa pergunta de listagem |
| `gemini.retry-max-attempts` | `3` | Tentativas em falhas transitórias (429/5xx/timeout) |
| `app.rate-limit.requests-per-minute-per-client` | `6` | Limite por IP |
| `app.rate-limit.requests-per-day-total` | `18` | Teto diário global (cota do provedor: 20/dia) |
| `app.web.cors-allowed-origins` | `localhost:3000,5173` | Origens liberadas no navegador |
