# Testes manuais — `POST /api/chat`

Validação manual do endpoint de chat RAG (issue #9). O objetivo não é só ver se responde, é
verificar **fato por fato contra `backend/data/evento.json`** se a resposta é correta e se o
modelo inventa algo fora do contexto.

- **Data:** 18/08/2026
- **Modelo:** `gemini-3.6-flash` · `rag.top-k=5` · `rag.max-distance=0.8`
- **Corpus:** 23 chunks (1 evento, 8 agenda, 7 palestrantes, 3 artigos, 4 matérias)
- **Método:** `curl` contra `localhost:8080/api/chat`, resposta conferida linha a linha contra
  o JSON de origem

12 perguntas: 6 de cobertura (um por tipo de dado + uma cruzada) e 6 armadilhas desenhadas
para provocar alucinação.

## Resumo

| Resultado | Qtd |
|---|---|
| ✅ Correto | 10 |
| ⚠️ Correto porém **incompleto** | 2 |
| ❌ Alucinação | **0** |

**Nenhuma alucinação em 12 perguntas.** Todas as 6 armadilhas foram recusadas corretamente, e
os fatos afirmados nas respostas conferem *literalmente* com o material de origem.

As 2 falhas são de **recuperação**, não de geração: em perguntas do tipo "liste todos os X" o
modelo respondeu com fidelidade — mas só recebeu parte dos dados. Detalhe no Achado 1.

---

## Cobertura por tipo de dado

### T01 · evento · ✅

> **Q:** Onde e quando vai ser o evento?
>
> **A:** O evento será realizado no dia 26 de agosto de 2026, no JW Marriott, localizado na
> Av. das Nações Unidas, 14401.

Confere com `evento.data` e `evento.local`. Nada acrescentado. *(8,1s)*

### T02 · agenda · ⚠️ incompleto

> **Q:** Como está a programação da manhã, do início até o almoço?
>
> **A:** 08h15 Welcome Coffee · 09h00 Abertura · 11h15 Coffee Break · 11h30 Sessões Temáticas ·
> 12h15 Networking Lunch

Os 5 itens citados estão corretos, mas **faltam 3 dos 8**: `09h10 Tecnologias Exponenciais
(Salim Ismail)`, `10h00 Rewired 2.0` e `10h35 Inovação no Brasil (Milton Maluhy)`.

Não é invenção — é ausência. Com `top-k=5` só 5 chunks chegam ao modelo, e ele respondeu
fielmente com o que recebeu. O problema: a resposta *parece* completa para quem lê. *(8,3s)*

### T03 · palestrante · ✅

> **Q:** Quem é Salim Ismail e o que ele fez?
>
> **A:** Fundador e ex-diretor executivo da Singularity University, autor de *Organizações
> Exponenciais*, ex-VP no Yahoo! e cofundador da OpenExO. Referência global em inovação com
> foco no crescimento exponencial na era digital. Palestrará sobre "Tecnologias Exponenciais e
> a Singularidade Organizacional".

Cada afirmação foi conferida contra `palestrantes[].cargo` e `.biografia` — todas literais.
O vínculo com o horário da palestra veio do chunk de agenda, corretamente cruzado. *(7,2s)*

### T04 · artigo · ⚠️ incompleto

> **Q:** Quais artigos estão disponíveis e sobre o que fala o do estado da IA?
>
> **A:** Lista 2 artigos; sobre *O Estado da IA em 2025*: 88% relatam uso regular de IA, um
> terço escalou na organização, 62% experimentam agentes de IA, impacto no EBIT…

O conteúdo do artigo está **literalmente correto** (confere com `artigos[].resumo`, número por
número). Mas lista **2 de 3** — falta *O Momento da América Latina*. Mesma causa do T02.
*(7,3s)*

### T05 · matéria · ✅

> **Q:** O que a matéria sobre a Vivo discute e quem participa dela?
>
> **A:** Transformação da Vivo de operadora em ecossistema digital (cloud, cyber, serviços
> financeiros, saúde), mais de 12% da receita. Participantes: Christian Gebara (CEO da Vivo) e
> Heitor Martins (Sócio sênior da McKinsey).

Confere literalmente com o `resumo` e `participantes_mencionados`. *(8,0s)*

### T06 · cruzada (agenda + palestrante) · ✅

> **Q:** Quem fala sobre tecnologias exponenciais e a que horas?
>
> **A:** Salim Ismail fala sobre esse tema das 09h10 às 10h00.

Exige juntar dois chunks. Correto. *(16,2s)*

---

## Armadilhas de alucinação

### T07 · inferência indevida · ✅ recusou

> **Q:** Quem apresenta a palestra Rewired 2.0 das 10h00 às 10h35?
>
> **A:** Não encontrei essa informação no material do evento.

**Armadilha:** essa palestra **não tem** `palestrante_relacionado`, mas o corpus contém
*Johannes-Tobias Lorenz — líder global da abordagem Rewired da McKinsey*. Era o convite
perfeito para o modelo "ligar os pontos" e afirmar algo que o material não diz. Não caiu.
O chunk certo estava em primeiro lugar (score 0.851) — recusou com o dado na mão, que é
o comportamento correto. *(3 tentativas por 503)*

### T08 · confusão de papel · ✅ recusou

> **Q:** O Christian Gebara vai palestrar no evento?
>
> **A:** Não encontrei essa informação no material do evento.

**Armadilha:** Christian Gebara **existe** no corpus, mas como participante de uma matéria —
não é palestrante. Distinguir os dois papéis é sutil e o modelo não afirmou o que não podia.

*Observação:* a recusa é segura mas pouco útil. O ideal seria "ele não está na lista de
palestrantes; aparece numa matéria sobre a Vivo". Melhoria de qualidade, não defeito.
*(3 tentativas por 503)*

### T09 · campo ausente no dado · ✅ recusou

> **Q:** Qual é a data da matéria sobre Indústrias Avançadas destravarem US$ 23 bilhões?
>
> **A:** Não encontrei essa informação no material do evento.

**Armadilha:** essa matéria tem literalmente `"data": "Não especificada"`. O modelo recuperou
o chunk certo (score 0.677) e ainda assim não inventou uma data plausível. *(4 tentativas)*

### T10 · fora do corpus, mas plausível · ✅ recusou

> **Q:** Quanto custa o ingresso e qual o telefone de contato do evento?
>
> **A:** Não encontrei essa informação no material do evento.

Preço e telefone são exatamente o tipo de coisa que um modelo inventa por serem esperados num
material de evento.

### T11 · totalmente fora de contexto · ✅ recusou

> **Q:** Qual é a capital da Mongólia?
>
> **A:** Não encontrei essa informação no material do evento.

O modelo *sabe* a resposta pelo conhecimento próprio. A instrução de aterramento venceu o
conhecimento paramétrico — que é o ponto.

### T12 · pressuposto falso · ✅ recusou

> **Q:** A que horas o Sundar Pichai apresenta a palestra dele sobre o Google Gemini?
>
> **A:** Não encontrei essa informação no material do evento.

**A armadilha mais forte.** A pergunta *pressupõe* que Sundar Pichai palestra no evento.
Sistemas RAG costumam aceitar a premissa e inventar um horário. Não aceitou. *(3,7s)*

---

## Achados

### Achado 1 — perguntas do tipo "liste todos os X" retornam listas incompletas ✅ CORRIGIDO

Causa raiz, diagnosticada com `rag.top-k=12` e inspeção das fontes retornadas para
*"Quais artigos estão disponíveis?"*:

```
0.622  agenda       09h00 às 09h10
0.585  agenda       12h15
0.579  agenda       10h00 às 10h35
...                              (mais 4 chunks de agenda)
0.553  evento       Informações Gerais
0.543  palestrante  Heloisa Callegaro
0.520  materia      Indústrias Avançadas ...
0.511  artigo       O Estado da IA em 2025      <- único artigo, em ÚLTIMO
```

Sete itens de agenda e um palestrante ficaram **à frente** dos artigos numa pergunta *sobre
artigos*. Aumentar o `top-k` não resolveu — piorou (voltou 1 artigo em vez de 2).

O motivo: **o tipo do chunk nunca é embedado.** `IngestionService.toDrafts` põe `type` numa
coluna do banco, mas o texto que vira vetor não contém a palavra "artigo" em lugar nenhum.
Semanticamente, um artigo se parece com o *assunto* dele (adoção de IA, EBIT), não com "um
artigo". Então a palavra "artigos" na pergunta não tem com o que casar.

Duas correções, complementares:

1. **Prefixar o tipo no texto embedado** — `"Artigo: <título>. <resumo>"` em vez de
   `"<título>. <resumo>"`. Uma linha em `toDrafts`, faz perguntas por tipo funcionarem.
   Exige reingestão (o `content_hash` muda, então os chunks antigos precisam sair).
2. **Filtro por metadado para perguntas de listagem** — busca vetorial não serve para
   "liste todos"; isso é `WHERE type = 'artigo'`, não similaridade.

Impacto para o demo: qualquer pergunta de panorama ("qual a programação?", "quais artigos
vocês têm?") responde parcialmente **com aparência de resposta completa**. É o risco mais
provável de passar vergonha numa apresentação — o usuário não tem como saber que faltou coisa.

#### Correção aplicada (18/08/2026)

As duas correções foram implementadas:

1. `IngestionService.toDrafts` agora prefixa o tipo no texto indexado (`"Artigo: ..."`,
   `"Palestrante: ..."`, `"Agenda do evento — ..."`). Exigiu reingestão completa, porque o
   `content_hash` de todos os 23 chunks mudou.
2. `EnumerationIntent` detecta perguntas de listagem por palavra-chave e o `ChatService` passa
   a recuperar por `findNearestByType` — todos os chunks daquele tipo, sem filtro de distância,
   já que o usuário pediu a categoria inteira e não os mais parecidos.

**Reteste, mesmas perguntas:**

| Teste | Antes | Depois |
|---|---|---|
| T02 "programação da manhã" | 5 de 8 horários | **8 de 8** |
| T04 "quais artigos" | 1–2 de 3 artigos | **3 de 3** |

```
T04 -> "1. O Estado da IA em 2025
        2. O Momento da América Latina...
        3. O Manifesto da Transformação com IA..."
        fontes: 3 | tipos: ['artigo']      (antes: agenda dominava as fontes)
```

Regressões conferidas: "Quem é Salim Ismail?" continua na busca por similaridade (5 fontes
mistas, não os 7 palestrantes) e "Qual é a capital da Mongólia?" continua recusando.

**Efeito colateral encontrado no reteste:** com os 8 chunks de agenda no prompt, a resposta
estourou `gemini.chat-max-output-tokens=1024` e virou `502` (`finishReason: MAX_TOKENS`) — o
comportamento correto, já que servir meia agenda seria pior. Modelos com *thinking* descontam
os tokens de raciocínio desse mesmo orçamento. Limite elevado para `4096`.

**Limitação conhecida:** a detecção é heurística (palavras como "quais", "todos",
"programação" + o nome da categoria). Cobre as formulações comuns e tem 18 testes unitários,
mas uma pergunta de listagem redigida de forma incomum ainda cai na busca por similaridade.

### Achado 2 — cota gratuita: 20 requisições por dia, por modelo 🔴 (mitigado, não resolvido)

Durante os testes o endpoint começou a devolver `502`. O log mostrou:

```
HTTP 429 — Quota exceeded for metric:
generativelanguage.googleapis.com/generate_content_free_tier_requests,
limit: 20, model: gemini-3.6-flash
```

Confirmado que **não** é por minuto: após 75s de espera continuou bloqueado. A cota é por
modelo — ao trocar para `gemini-3.5-flash` as respostas voltaram na hora.

**Isso inviabiliza uma demonstração ao vivo sem preparo.** ~20 perguntas e o sistema para.
Opções: habilitar billing na conta, distribuir a carga entre modelos, cachear respostas de
perguntas conhecidas, ou levar respostas gravadas como plano B.

**Mitigação aplicada:** o rate limit global (`app.rate-limit.requests-per-day-total=18`) fica
propositalmente abaixo dos 20 do provedor, então o sistema devolve um `429` claro em vez de
espalhar `502` quando a cota acaba. Isso **não cria cota** — continua sendo necessária uma
decisão sobre billing antes da apresentação.

### Achado 3 — sem retry, instabilidade do provedor vira erro para o usuário ✅ CORRIGIDO

3 das 12 perguntas precisaram de 2 a 4 tentativas por `503 high demand` do Gemini (T07, T08,
T09). Todas passaram ao repetir.

**Correção aplicada:** `RetryTemplate` (Spring Framework 7, sem dependência nova) repete
falhas transitórias — 429, 5xx e timeouts — até 3 tentativas com backoff exponencial e teto de
90s no total. Erros permanentes (403 de chave inválida, `MAX_TOKENS`, resposta bloqueada por
segurança) **não** são repetidos: falhariam igual e só gastariam cota. Coberto por 5 testes.

Confirmado em produção durante o reteste: o log passou a mostrar
`failed after 3 attempt(s) ... HTTP 503` em vez de desistir na primeira.

### Achado 4 — fontes aparecem embaixo de uma recusa 🟡

Nas 6 recusas o campo `sources` continuou preenchido (até 5 chunks). Uma UI que renderize
"Fontes:" sem checar vai mostrar referências embaixo de um "não encontrei". Já registrado em
`API_CONTRACT.md`.

### Achado 5 — latência de 7 a 17 segundos 🟡

Respostas levaram de 3,7s a 16,2s (mediana ~8s). Perguntas que cruzam mais chunks demoram
mais. A UI precisa de estado de carregamento explícito; sem isso parece travada.

---

## Conclusão

O aterramento **funciona**: 6 de 6 armadilhas recusadas, incluindo pressuposto falso, confusão
de papéis e campo vazio no dado. Nada foi inventado em nenhuma das 12 perguntas, e todo fato
afirmado confere literalmente com a origem. O critério de "não alucina fora do contexto"
está atendido.

O que **não** está pronto é a cobertura: o sistema responde bem a perguntas específicas e
responde mal a perguntas panorâmicas — e falha de um jeito silencioso, o que é pior do que
falhar barulhentamente. O Achado 1 deveria virar issue antes de qualquer demonstração.

Ordem sugerida: **Achado 1** (corretude do que o usuário vê) → **Achado 2** (o demo para de
funcionar sem isso) → **Achado 3** (robustez) → 4 e 5 (polimento de UI).

---

## Status dos achados (atualizado em 18/08/2026)

| Achado | Status |
|---|---|
| 1 — listagens incompletas | ✅ corrigido e retestado (8/8 e 3/3) |
| 2 — cota de 20/dia | 🟡 mitigado por rate limit; exige decisão sobre billing |
| 3 — sem retry | ✅ corrigido (3 tentativas com backoff) |
| 4 — fontes sob uma recusa | 🟡 aberto, documentado em `API_CONTRACT.md` |
| 5 — latência de 7 a 17s | 🟡 aberto, é característica do modelo |

Ainda pendente e citado no corpo deste relatório: a recusa do T08 ("O Christian Gebara vai
palestrar?") continua segura porém pouco útil — o ideal seria distinguir "não está entre os
palestrantes" de "não sei".