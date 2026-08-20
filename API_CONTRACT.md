# API Contract

Contrato de request/response entre o frontend e o backend. Escrito para que quem trabalha no
frontend não precise abrir código Java.

**Todos os exemplos abaixo foram capturados de um servidor rodando de verdade**, não transcritos
do código. Se algum exemplo divergir do comportamento real, o arquivo está errado — abra uma
issue.

- **Base URL (dev):** `http://localhost:8080`
- **Content-Type:** `application/json` em todo request com corpo; todas as respostas são JSON UTF-8
- **Autenticação:** obrigatória em `/api/chat`, `/api/chat/history`, `/api/ingest` e
  `/api/auth/me`. Header `Authorization: Bearer <token>`, obtido em
  [`/api/auth/login`](#post-apiauthlogin). Sem token: `401` com `WWW-Authenticate: Bearer`.
  `GET /api/chunks` continua aberto (só leitura, não gasta cota de IA)

---

## CORS

O backend envia `Access-Control-Allow-Origin` para as origens configuradas em
`app.web.cors-allowed-origins` (padrão: `http://localhost:3000` e `http://localhost:5173`).
Chamar direto do React em dev funciona, sem proxy.

```bash
curl -sD- -o /dev/null localhost:8080/api/chunks -H 'Origin: http://localhost:3000' | grep -i access-control
# Access-Control-Allow-Origin: http://localhost:3000
```

Métodos liberados: `GET, POST, OPTIONS`. Headers liberados: `Content-Type` e `Authorization`. Preflight cacheado
por 1h. Origem não listada recebe `403` — não é wildcard de propósito, porque a API não tem
autenticação. Rodando o frontend em outra porta? Acrescente a origem à propriedade.

---

## Endpoints

## Contas e sessões

Cadastro e login por e-mail e senha. **Não há confirmação por e-mail**: registrar já devolve uma
sessão utilizável, então o endereço é *declarado*, não verificado.

O token é opaco (32 bytes aleatórios), vale `app.auth.session-ttl` (24h) e é o que vai no header
`Authorization`. O servidor guarda apenas o SHA-256 dele — se perder o token, o caminho é logar de
novo, não recuperá-lo.

### `POST /api/auth/register`

**Request**

```json
{ "email": "pedro@usp.br", "password": "senha-bem-boa" }
```

| Campo | Regras |
|---|---|
| `email` | Obrigatório. Normalizado (espaços removidos, minúsculas) antes de gravar, então `Pedro@USP.br` e `pedro@usp.br` são a mesma conta. Máximo 254 caracteres |
| `password` | Obrigatório. Mínimo 8 caracteres e **máximo 72 bytes** — é o que o BCrypt considera; acima disso o resto seria ignorado em silêncio, então recusamos. Acentos contam como 2 bytes |

**Response `201`**

```json
{ "token": "_063CA5Q3Rvfgd79AQMvqTyn_PL23D7seb61kRIb91g",
  "expiresAt": "2026-08-21T02:25:41.866935718Z",
  "email": "pedro@usp.br" }
```

| Status | Quando |
|---|---|
| `400` | E-mail malformado, senha curta ou senha longa demais. O `detail` diz a regra |
| `409` | E-mail já cadastrado |

> Registrar **revela** que um e-mail já tem conta (`409`), coisa que o login se recusa a fazer. Sem
> confirmação por e-mail não há alternativa: aceitar em silêncio deixaria um usuário real sem
> entender por que a conta nova não funciona.

### `POST /api/auth/login`

**Request** — mesmo corpo do registro. **Response `200`** — mesmo corpo da resposta do registro.

| Status | Quando |
|---|---|
| `401` | Qualquer combinação inválida. O `detail` é sempre `E-mail ou senha inválidos` |

O `401` é deliberadamente idêntico para senha errada e e-mail inexistente — a resposta não diz se
um endereço tem conta. O tempo de resposta também não: quando o e-mail não existe o servidor gasta
uma comparação de BCrypt de propósito.

### `POST /api/auth/logout`

Revoga **o token usado nesta chamada** (este dispositivo, não todas as sessões da conta). Sempre
`204`, mesmo com token inválido ou ausente: logout é idempotente.

### `GET /api/auth/me`

Quem é o dono do token. Requer autenticação.

```json
{ "id": 1, "email": "pedro@usp.br" }
```

Use no carregamento da página para decidir entre mostrar o chat ou a tela de login — um token
guardado no navegador pode ter expirado ou sido revogado.

**Rate limit:** `/api/auth/register` e `/api/auth/login` têm janela própria de 10 requisições por
minuto por IP. Não consomem o teto diário de IA (ver [`429`](#429--limite-de-requisições)).

---

### `POST /api/chat`

Requer `Authorization: Bearer <token>`. Pergunta em linguagem natural sobre o evento. A resposta é gerada **somente** a partir do
conteúdo já ingerido (RAG).

**Request**

```json
{ "message": "Quem vai falar sobre tecnologias exponenciais e a que horas?" }
```

| Campo | Tipo | Obrigatório | Regras |
|---|---|---|---|
| `message` | string | sim | Não pode ser vazia nem só espaços |

**Response `200`**

```json
{
  "answer": "Salim Ismail falará sobre esse tema, das 09h10 às 10h00.",
  "sources": [
    { "id": 4, "type": "agenda", "titleRef": "09h10 às 10h00", "score": 0.816 },
    { "id": 5, "type": "agenda", "titleRef": "10h00 às 10h35", "score": 0.712 },
    { "id": 6, "type": "agenda", "titleRef": "10h35 às 11h15", "score": 0.674 }
  ]
}
```

| Campo | Tipo | Nulo? | Descrição |
|---|---|---|---|
| `answer` | string | não | Resposta em pt-BR. Nunca vazia |
| `sources` | array | não | Pode vir `[]`. Ordenado do mais relevante para o menos |
| `sources[].id` | number | não | `id` do chunk, o mesmo de `GET /api/chunks` |
| `sources[].type` | string | não | `evento` \| `agenda` \| `palestrante` \| `artigo` \| `materia` |
| `sources[].titleRef` | string \| null | **sim** | Rótulo legível (nome, horário, título) |
| `sources[].score` | number | não | Similaridade em `[0, 1]`, 3 casas decimais. Maior = mais parecido |

**Quando não há resposta no material**

O backend admite ignorância em vez de inventar. Não é um erro — é `200`:

```json
{ "answer": "Não encontrei essa informação no material do evento.", "sources": [ ... ] }
```

> **Atenção ao renderizar:** `sources` lista os trechos **recuperados**, não os efetivamente
> citados. Numa recusa como a acima, `sources` ainda pode vir preenchido com trechos de baixa
> relevância. Não mostre "Fontes:" sem checar se a resposta é uma recusa, ou o usuário verá
> fontes embaixo de um "não sei".

**Latência:** ~4 a 7 segundos. Mostre estado de carregamento; não use timeout curto no cliente.

---

## Memória de conversa

O backend guarda **uma conversa por conta** no Postgres e a usa nas perguntas seguintes. A
conversa é identificada pela sessão autenticada, então ela segue o usuário — outro navegador, o
mesmo login, a mesma conversa.

| Regra | Valor | Observação |
|---|---|---|
| Chave da conversa | a conta autenticada | Uma conversa por conta; o cliente não escolhe nem informa |
| Janela | 1h (`app.chat-memory.ttl`) | Turnos mais antigos que isso não são lidos e são apagados |
| Turnos enviados ao modelo | 6 (`app.chat-memory.max-turns`) | Os mais recentes |
| O que é gravado | pergunta + resposta | Só depois de uma resposta pronta. Se a geração falhar, nada é gravado |

O histórico serve para o modelo entender **a que a pergunta se refere**; ele não é fonte de
fatos. A resposta continua saindo apenas dos trechos recuperados, então uma pergunta de
acompanhamento sobre algo que não está no material continua recebendo a recusa padrão.

Funciona assim, na prática (mesmo token nas duas chamadas):

```jsonc
// POST /api/chat  {"message":"Quem é Salim Ismail?"}
{"answer":"Salim Ismail é fundador e ex-diretor executivo da Singularity University, ...", "sources":[...]}

// POST /api/chat  {"message":"E ele fala a que horas?"}     <- sem sujeito próprio
{"answer":"Salim Ismail palestra das 09h10 às 10h00.",
 "sources":[{"id":11,"type":"palestrante","titleRef":"Salim Ismail","score":0.736},
            {"id":4,"type":"agenda","titleRef":"09h10 às 10h00","score":0.727}]}
```

A pergunta de acompanhamento também **recupera** melhor: as duas últimas perguntas do usuário
entram no texto que é embedado (`app.chat-memory.retrieval-context-turns`), senão "e ele fala a
que horas?" não teria com o que casar no banco.

---

### `GET /api/chat/history`

A conversa do próprio chamador, do turno mais antigo para o mais recente — exatamente o que o
modelo vai receber na próxima pergunta. Serve para uma tela recarregada não voltar vazia
enquanto o backend continua lembrando.

**Request** — sem corpo. A sessão autenticada identifica a conversa.

**Response `200`**

```json
[
  { "role": "user", "text": "Quem é Salim Ismail?" },
  { "role": "assistant", "text": "Salim Ismail é fundador e ex-diretor executivo da Singularity University, ..." }
]
```

| Campo | Tipo | Descrição |
|---|---|---|
| `role` | string | `user` \| `assistant` |
| `text` | string | O que foi dito. Turnos do assistente não trazem `sources` — o histórico guarda o texto, não a recuperação |

Conversa expirada ou inexistente: `200` com `[]`, nunca um erro. Sem token: `401`.
Não é limitado por rate limit (só lê o banco, não gasta cota de IA).

---

### `POST /api/ingest`

Lê um arquivo JSON do disco **do servidor**, quebra em chunks, gera embeddings e grava.
Operação de manutenção — normalmente o frontend não chama isso.

**Request** — query param, sem corpo:

```
POST /api/ingest?path=data/evento.json
```

| Param | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `path` | string | sim | Caminho relativo ao diretório de trabalho do backend |

**Response `200`**

```json
{ "created": 23, "skipped": 0, "total": 23 }
```

| Campo | Tipo | Descrição |
|---|---|---|
| `created` | number | Chunks novos, embedados e gravados nesta execução |
| `skipped` | number | Já existentes (mesmo hash de conteúdo) — não foram reembedados |
| `total` | number | Chunks encontrados no arquivo |

**É idempotente.** Rodar de novo não duplica nem gasta cota da API:

```json
{ "created": 0, "skipped": 23, "total": 23 }
```

---

### `GET /api/chunks`

Lista todos os chunks armazenados. Útil para depuração e para telas administrativas.

**Response `200`** — array (sem paginação, devolve tudo):

```json
[
  {
    "type": "evento",
    "titleRef": "Informações Gerais",
    "content": "Evento — informações gerais. Tema: A tecnologia está revolucionando negócios. ... | Data: 26 de agosto de 2026 | Local: JW Marriott - Av. das Nações Unidas, 14401",
    "contentHash": "95a6dd3d638d63f04510be356e9ea0541abcc2a59828dd2dfcd7257ef4f87e16",
    "id": 1
  }
]
```

| Campo | Tipo | Nulo? | Descrição |
|---|---|---|---|
| `id` | number | não | Chave primária; casa com `sources[].id` do `/api/chat` |
| `type` | string | não | `evento` \| `agenda` \| `palestrante` \| `artigo` \| `materia` |
| `titleRef` | string \| null | **sim** | Rótulo legível |
| `content` | string | não | Texto usado na busca semântica. Começa com o tipo por extenso ("Artigo: …", "Palestrante: …") — isso é o que faz perguntas por categoria funcionarem |
| `contentHash` | string | não | SHA-256 (64 hex) — identidade do chunk, base da idempotência |

Notas:
- **A ordem das chaves no JSON não é alfabética nem estável** (`id` vem por último hoje). Acesse
  sempre por nome, nunca por posição.
- O vetor `embedding` (768 floats) **não** é serializado, de propósito.
- Sem paginação: hoje são 23 registros. Se o corpus crescer, isso vira um problema — não construa
  a tela assumindo que sempre caberá numa resposta só.

---

## Erros

Todo erro segue [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) (`ProblemDetail`):

```json
{
  "title": "Requisição inválida",
  "status": 400,
  "detail": "A mensagem não pode ser vazia",
  "instance": "/api/chat"
}
```

| Status | `title` | Quando acontece |
|---|---|---|
| `400` | Requisição inválida | `message` vazia/ausente, JSON mal formado, query param obrigatório faltando, e-mail malformado, senha fora da política |
| `401` | Não autenticado | Token ausente, expirado ou revogado num endpoint protegido. Traz `WWW-Authenticate: Bearer` |
| `401` | Credenciais inválidas | Login com e-mail ou senha errados |
| `409` | E-mail já cadastrado | Registro num endereço que já tem conta |
| `400` | Arquivo inacessível | `POST /api/ingest` com `path` que não existe |
| `404` | Recurso não encontrado | Rota inexistente |
| `429` | Muitas requisições | Limite de requisições atingido — ver abaixo |
| `502` | Serviço de IA indisponível | A chamada ao Gemini falhou (ver abaixo) |
| `500` | Erro interno | Qualquer outra falha inesperada |

Exemplos reais:

```jsonc
// POST /api/chat  {"message":"  "}                  -> 400
{"title":"Requisição inválida","status":400,"detail":"A mensagem não pode ser vazia","instance":"/api/chat"}

// POST /api/chat  {                                  -> 400
{"title":"Requisição inválida","status":400,"detail":"Corpo da requisição inválido ou mal formado.","instance":"/api/chat"}

// POST /api/ingest  (sem ?path=)                     -> 400
{"title":"Requisição inválida","status":400,"detail":"O parâmetro obrigatório 'path' não foi informado.","instance":"/api/ingest"}

// GET /api/nope                                      -> 404
{"title":"Recurso não encontrado","status":404,"detail":"Nenhum endpoint corresponde a essa rota.","instance":"/api/nope"}
```

### `429` — limite de requisições

`POST /api/chat` e `POST /api/ingest` são limitados (o `GET /api/chunks` não é, porque só lê
o banco). Dois limites independentes:

| Limite | Padrão | Aplica-se a | Protege contra |
|---|---|---|---|
| Por cliente (IP), por minuto | 6 | `/api/chat`, `/api/ingest` | um usuário monopolizar o serviço |
| Global, por dia | 18 | `/api/chat`, `/api/ingest` | estourar a cota diária do provedor de IA (free tier: 20/dia) |
| Autenticação, por IP, por minuto | 10 | `/api/auth/login`, `/api/auth/register` | tentativa de adivinhar senha |

A janela de autenticação é separada de propósito: tentar entrar não pode consumir o orçamento
diário de IA, senão errar a senha viraria uma forma de gastar a cota de todo mundo. O `401` de um
endpoint protegido é decidido **antes** do rate limit pelo mesmo motivo.

A resposta traz o header **`Retry-After`** em segundos — respeite-o em vez de tentar de novo
imediatamente:

```jsonc
// HTTP 429, Retry-After: 59
{"title":"Muitas requisições","status":429,
 "detail":"Limite de requisições (por cliente) atingido. Tente novamente em 59 segundo(s).",
 "instance":"/api/ingest"}
```

O `detail` diz qual limite foi atingido: `por cliente` (espere alguns segundos) ou
`diário global` (a cota do dia acabou; o `Retry-After` será de horas).

### `502` é transitório — o backend já tenta de novo

O provedor de IA responde `503 high demand` ou `429` de tempos em tempos. O backend agora
**tenta até 3 vezes** com backoff exponencial antes de desistir, então um `502` que chega ao
frontend significa que 3 tentativas falharam — o provedor está realmente indisponível.

O frontend ainda pode tentar de novo depois de alguns segundos, mas sem insistir: cada
tentativa consome o limite de requisições.

O `detail` de um `502` é sempre genérico de propósito — o corpo de erro do provedor fica só no
log do servidor, nunca na resposta.

---

## Limitações

Lista honesta do que ainda não existe, para ninguém descobrir na integração:

- **Sem HTTPS.** O token e a senha viajam em texto claro em `http://localhost`. Para qualquer uso
  fora da máquina local, isso precisa de TLS antes de qualquer outra coisa.
- **Sem confirmação de e-mail, sem "esqueci minha senha", sem troca de senha.** Perder a senha
  hoje significa perder a conta.
- **O limite diário é por instância e em memória.** Reiniciar o backend zera o contador, e
  rodar duas instâncias dobra o gasto real.
- **`POST /api/ingest` aceita qualquer arquivo dentro do diretório de trabalho** do backend.
  Travessia (`../`) e symlink que escapa são recusados, mas ainda é o cliente quem escolhe o
  arquivo, sem autenticação.
- **O histórico guarda o texto das perguntas e respostas** por até 1h. É apagado depois disso,
  mas até lá está no banco.
- **Perguntas de listagem são detectadas por palavra-chave** ("quais artigos", "programação",
  "quem são os palestrantes"). Funciona bem para as formulações comuns, mas é heurística: uma
  pergunta de listagem redigida de forma incomum cai na busca por similaridade e pode devolver
  uma lista parcial.
- **`GET /api/chunks` não pagina.**

---

## Divergências em relação ao texto da issue #13

A issue foi escrita antes das issues #7 e #8 existirem, então os formatos que ela lista não
correspondem mais ao backend. Este documento reflete o comportamento real, verificado por
execução. As diferenças:

| Issue #13 dizia | Real hoje | Motivo |
|---|---|---|
| `/api/ingest` → `{"status":"ok","chunksCreated":N}` | `{"created","skipped","total"}` | A ingestão virou idempotente na #7; `skipped` passou a ser informação relevante |
| `/api/chat` ← `{"message":...}` | `{"message":...}` ✅ | O código usava `question` e foi renomeado para bater com a issue |
| `/api/chat` → `{"answer":...}` | `{"answer","sources"}` | `sources` foi adicionado na #8 para permitir citar as fontes na UI |
| `/api/chunks` → `{id,type,titleRef,content}` | `+ contentHash` | Coluna criada na #7 para idempotência |
