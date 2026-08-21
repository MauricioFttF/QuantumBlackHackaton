# Pergunte ao AI Forum

Assistente conversacional do **AI Forum** (26 de agosto de 2026, JW Marriott — São Paulo).
Responde perguntas sobre agenda, palestrantes, artigos e cobertura de imprensa **usando apenas o
material oficial do evento**, monta uma trilha personalizada de palestras sem conflito de horário e
mostra aos organizadores, em tempo quase real, o que o público está querendo saber.

- 🔎 **RAG de verdade, com recusa honesta.** A pergunta é vetorizada, os trechos mais próximos são
  recuperados do PostgreSQL com `pgvector` e só eles vão para o modelo. Se nada relevante existir, o
  modelo **não é chamado** e a resposta é "não encontrei essa informação no material do evento".
- 🗓️ **Trilha personalizada.** A partir dos seus interesses (digitados ou inferidos da conversa), o
  sistema monta um roteiro do dia já resolvendo choques de horário.
- 📊 **Painel do organizador.** Cada trecho usado como contexto é contabilizado — sem guardar a
  pergunta de ninguém — e vira um ranking: "o que o público mais perguntou hoje".

**Multiusuário por construção:** a API é HTTP stateless e todo estado de sessão vive no PostgreSQL.
Cada requisição carrega um *bearer token* opaco; a conversa é gravada por **conta** (tabela
`conversation_turn`), não em memória do processo. Duas pessoas conversando ao mesmo tempo não se
enxergam, quem recarrega a página recupera a própria conversa, e o pool de conexões do Hibernate mais
o `UNIQUE (content_hash)` da ingestão mantêm a consistência sob concorrência. Há limites de uso por
IP e um teto diário global para proteger a cota do provedor de IA — veja
[Limites que você vai encontrar](#limites-que-você-vai-encontrar).

---

## 1. README Técnico (instruções de execução)

### 1.1 Dependências e versões

O caminho recomendado é o **Docker** (seção [1.4](#14-bônus-execução-via-docker)): ele precisa
apenas de Docker. Para rodar na máquina, instale:

| Ferramenta | Versão exigida | Por quê / como conferir |
|---|---|---|
| **Java JDK** | **21** (LTS) | O projeto usa records e `switch` com pattern matching. `java -version` |
| **Maven** | não precisa instalar | Use o wrapper do repositório (`./mvnw`), que baixa o Maven 3.9.16 |
| **Node.js** | **20.19+** ou **22.12+** | O Vite 8 exige. `node -v` |
| **npm** | 10+ (vem com o Node 20) | `npm -v` |
| **Docker + Docker Compose** | Docker 24+ / Compose v2 | Necessário para o banco (o app espera PostgreSQL com `pgvector`) |
| **PostgreSQL 16 + pgvector** | imagem `pgvector/pgvector:pg16` | Sobe via Compose; não precisa instalar na máquina |

> ⚠️ **Node 18 não funciona.** Todo script do frontend morre com
> `SyntaxError: ... does not provide an export named 'styleText'`, e a mensagem não menciona o Node.
> Se isso aparecer, o problema é a versão do runtime. Se você já rodou `npm install` no Node 18,
> apague `node_modules` e instale de novo. Rodando por Docker, isso não te afeta.

Testado em Linux (Ubuntu) e compatível com macOS. Não há suporte a execução direta no Windows —
use WSL2.

### 1.2 Configuração de variáveis de ambiente

A aplicação precisa de **uma** credencial: a chave da API do Google Gemini.

```bash
# na raiz do repositório
cp .env.example .env
```

Abra o `.env` e preencha:

```dotenv
GEMINI_API_KEY=cole-sua-chave-aqui
```

- Gere a chave em **https://aistudio.google.com/apikey** (o nível gratuito basta para a demo).
- O `.env` é **git-ignored** e nunca deve ser comitado.
- O backend **falha no startup** com mensagem explícita se a chave estiver vazia — de propósito, para
  o erro não aparecer só na primeira pergunta.
- O mesmo `.env` serve para os dois modos de execução: o Docker Compose o lê automaticamente e a
  execução local também (`spring.config.import` procura `./.env` e `../.env`).

### 1.3 Execução local, passo a passo

Quatro comandos, em **três terminais**. Rode na ordem.

**Terminal 1 — banco de dados** (PostgreSQL 16 + pgvector, via Docker):

```bash
docker compose up -d db
docker compose logs -f db   # opcional: espere "database system is ready to accept connections"
```

**Terminal 2 — backend** (porta 8080):

```bash
cd backend
./mvnw spring-boot:run
```

Na **primeira** execução, o backend aplica as 5 migrações do Flyway e **carrega o corpus
automaticamente** (54 chunks, ~40 s, uma chamada de embedding por chunk). Espere ver no log:

```
Loaded the corpus from data/evento.json: 54 chunk(s) created, 0 already present
Started BackendApplication in ... seconds
```

Reinícios seguintes não gastam cota: `Corpus already loaded: 54 chunk(s) present, nothing embedded`.

> Se o `./mvnw` reclamar de permissão, rode `chmod +x mvnw` uma vez.

**Terminal 3 — frontend** (porta 5173):

```bash
cd frontend
npm ci        # use `npm install` só se for mudar dependências
npm run dev
```

Abra **http://localhost:5173**. O CORS do backend já libera `5173` (dev) e `3000` (Docker).

**Use a aplicação:**

1. Clique em **Criar conta**, informe um e-mail e uma senha de **8+ caracteres** (não há
   confirmação por e-mail: criar a conta já entra).
2. Pergunte algo do evento, por exemplo *"Quem fala sobre tecnologias exponenciais e a que horas?"*.
3. Faça uma pergunta de acompanhamento — *"e ele fala sobre o quê?"* — para ver a memória de
   conversa funcionando.
4. Clique em **minha trilha**, descreva um interesse (ex.: *"agentes de IA nos serviços financeiros
   e no varejo"*) e monte o roteiro do dia. Deixando o campo vazio, o sistema usa o que você
   perguntou no chat na última hora.
5. Clique em **interesse do público** para ver o painel do organizador se preencher.

**Verificação rápida por terminal** (opcional, prova que a stack está de pé):

```bash
curl -fsS localhost:8080/api/chunks | head -c 120          # corpus carregado
TOKEN=$(curl -s -X POST localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"avaliador@exemplo.br","password":"senha-bem-boa"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"message":"Quem fala sobre tecnologias exponenciais e a que horas?"}'

curl -s -X POST localhost:8080/api/agenda/recommend -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"interests":"agentes de IA em operações e no varejo","maxSessions":3}'

curl -s "localhost:8080/api/analytics/interest-summary?groupBy=titleRef"
```

**Rodar os testes** (269 casos; nenhum acessa a rede — o Gemini é sempre dublado):

```bash
cd backend
./mvnw verify        # exige o banco de pé: `docker compose up -d db`
```

### 1.4 [BÔNUS] Execução via Docker

Sobe **tudo** — banco, backend e frontend já compilado e servido por nginx — com um comando:

```bash
cp .env.example .env     # preencha GEMINI_API_KEY (se ainda não fez)
docker compose up --build
```

Pronto. Acesse **http://localhost:3000** (a API fica em `http://localhost:8080`).

Na primeira subida o backend carrega o corpus sozinho; acompanhe com:

```bash
docker compose logs -f backend-java | grep -E "Loaded the corpus|Started BackendApplication"
```

Para parar (e, com `-v`, apagar o volume do banco para um teste de partida limpa):

```bash
docker compose down       # para
docker compose down -v    # para e zera o banco (o próximo boot reingere o corpus)
```

| Serviço | Porta | O que é |
|---|---|---|
| `frontend-react` | `3000` | Bundle de produção do Vite servido por nginx |
| `backend-java` | `8080` | API Spring Boot (jar em imagem multi-stage) |
| `db` | `5432` | PostgreSQL 16 + pgvector, volume `pgdata` |

### Limites que você vai encontrar

Não são bugs — são proteções deliberadas de cota. Se aparecer `429`, o `detail` diz qual limite foi
atingido e o header `Retry-After` diz quantos segundos esperar.

| Limite | Padrão | Vale para |
|---|---|---|
| 6 requisições/minuto por IP | `app.rate-limit.requests-per-minute-per-client` | `/api/chat`, `/api/ingest` |
| **18 requisições/dia no total** | `app.rate-limit.requests-per-day-total` | `/api/chat`, `/api/ingest` |
| 10 tentativas/minuto por IP | `app.rate-limit.auth-requests-per-minute-per-client` | login e cadastro |
| 6 requisições/minuto por IP | `app.rate-limit.recommend-requests-per-minute-per-client` | `/api/agenda/recommend` |

O teto diário de 18 existe porque o nível gratuito do Gemini permite **20 chamadas de geração por
dia, por modelo**: preferimos um `429` claro a uma sequência de `502` no meio da demonstração.

---

## 2. Pitch para o AI Forum

Todo evento de tecnologia tem o mesmo ponto cego: a plateia sai com perguntas que ninguém registrou,
e os organizadores descobrem o que interessava de verdade só no formulário de satisfação, quando já
não dá para fazer nada. **"Pergunte ao AI Forum" fecha esse laço em tempo real e no palco.** De um
lado, um assistente que responde sobre a agenda e os palestrantes **sem alucinar** — e a graça é
justamente vê-lo *se recusar* a responder: pergunte a capital da Mongólia e ele diz, com toda a
calma, que isso não está no material do evento, porque o modelo simplesmente não é chamado quando
nenhum trecho relevante existe. Grounding demonstrável, não prometido. Do outro lado, e é aqui que a
plateia se mexe na cadeira, **um painel que se preenche ao vivo enquanto as pessoas perguntam**: peça
ao público para conversar com o assistente durante a apresentação e projete o ranking — "Tecnologias
Exponenciais: 42 consultas" subindo em barras na tela, medido a partir dos trechos que o modelo
realmente usou, sem guardar a pergunta de ninguém. Some a isso uma trilha personalizada que monta o
roteiro do dia resolvendo choques de horário, e o que você tem não é um chatbot: é o evento se
olhando no espelho enquanto acontece. Tudo isso roda com **um `docker compose up`**, com 269 testes
verdes e cada decisão sensível — recusa, cota, privacidade, senha — escrita e justificada no
repositório. É a diferença entre demonstrar uma IA e demonstrar **engenharia**.

---

## 3. Submissão (aviso interno)

- [ ] Conceder acesso ao repositório no GitHub para **@rennanharo**
      (*Settings → Collaborators → Add people → `rennanharo`*)
- [ ] Confirmar que `.env` **não** foi comitado (só o `.env.example`)
- [ ] Rodar o teste de partida limpa: `docker compose down -v && docker compose up --build`
- [ ] Conferir que `http://localhost:3000` abre e responde uma pergunta de ponta a ponta

---

# Documentação técnica detalhada

O que vem abaixo é a documentação de engenharia do projeto: decisões, medições e limitações
conhecidas. O contrato completo da API está em [`API_CONTRACT.md`](API_CONTRACT.md) e os testes
manuais em [`TESTES.md`](TESTES.md).

## Banco de dados vetorial

Usamos PostgreSQL com a extensão [pgvector](https://github.com/pgvector/pgvector) para armazenar embeddings e realizar busca por similaridade semântica (RAG).

Imagem Docker utilizada: `pgvector/pgvector:pg16`.

O schema é gerenciado por **Flyway** (`backend/src/main/resources/db/migration`), aplicado
automaticamente no startup do backend. `V1__init.sql` cria a extensão `vector`, a tabela
`knowledge_chunk` e o índice HNSW (`vector_cosine_ops`). O Hibernate roda com
`ddl-auto=validate` — ele confere o mapeamento, mas não altera o banco.

## Propriedades de configuração (referência)

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
| `agenda.recommend-top-k` | `15` | Pool de candidatos antes de resolver conflitos |
| `agenda.recommend-max-distance` | `0.8` | Acima disso a sessão não é relevante o bastante |
| `agenda.default-max-sessions` | `5` | Usado quando o request não manda `maxSessions` |
| `agenda.open-ended-slot-duration` | `45m` | Duração assumida para sessão sem horário de fim |
| `agenda.recommend-types` | `agenda,agenda_subsessao` | Tipos que contam como "sessão para assistir" |
| `agenda.event-date` | `2026-08-26` | Dia do evento; conferido contra `evento.data_iso` no boot |
| `analytics.default-window` | `24h` | Janela do painel quando `from` não vem no request |
| `analytics.max-results` | `50` | Teto de linhas na resposta do painel |
| `app.ingestion.on-startup` | `true` | Carrega o corpus no boot (idempotente) |
| `app.rate-limit.auth-requests-per-minute-per-client` | `10` | Login e cadastro, por IP |
| `app.rate-limit.recommend-requests-per-minute-per-client` | `6` | Recomendações, por IP |
