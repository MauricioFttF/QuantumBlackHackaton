import { useEffect, useState } from "react";
import { API_URL, authHeaders, readError } from "./api";

// O backend leva de 4 a 7 segundos para responder (API_CONTRACT.md). O timeout existe só para
// não deixar o usuário preso caso o servidor nunca responda — por isso é folgado.
const TIMEOUT_MS = 45000;

function formatTime() {
  const now = new Date();
  const h = String(now.getHours()).padStart(2, "0");
  const m = String(now.getMinutes()).padStart(2, "0");
  return `${h}h${m}`;
}

/**
 * Converte o histórico do backend ("user"/"assistant") para os papéis que a lista usa.
 *
 * Sem horário: `GET /api/chat/history` devolve papel e texto, não o instante de cada turno. Mostrar
 * a hora do carregamento seria inventar informação, então mensagens restauradas aparecem sem
 * timestamp e as desta sessão com.
 */
function toMessages(turns) {
  return turns.map((turn) => ({
    role: turn.role === "user" ? "user" : "bot",
    text: turn.text,
  }));
}

/**
 * A conversa. Todo request vai autenticado: `/api/chat` e `/api/chat/history` exigem sessão, e a
 * memória do assistente é guardada por conta — então a conversa segue o usuário, não o navegador.
 *
 * @param onSessionExpired chamado quando o backend recusa o token (401). Continuar tentando só
 *   produziria erros que o usuário não tem como resolver sem entrar de novo.
 */
function Chat({ token, onSessionExpired }) {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);

  // O servidor lembra da conversa por uma hora; sem isto a tela voltaria vazia depois de um
  // reload enquanto o modelo continuaria respondendo como se a conversa nunca tivesse parado.
  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const res = await fetch(`${API_URL}/api/chat/history`, { headers: authHeaders(token) });
        if (!active) return;

        if (res.status === 401) {
          onSessionExpired();
          return;
        }
        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const turns = await res.json();
        if (turns.length > 0) setMessages(toMessages(turns));
      } catch {
        // Não é fatal: dá para conversar sem o histórico. Mas avisamos, porque uma conversa que
        // "esqueceu" sem dizer nada é pior que uma que avisa.
        if (active) {
          setMessages([
            { role: "error", text: "Não foi possível recuperar a conversa anterior." },
          ]);
        }
      }
    })();

    return () => {
      active = false;
    };
  }, [token, onSessionExpired]);

  const sendMessage = async () => {
    const question = input.trim();
    if (!question || loading) return;

    setMessages((prev) => [...prev, { role: "user", text: question, time: formatTime() }]);
    setInput("");
    setLoading(true);

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);

    try {
      const res = await fetch(`${API_URL}/api/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...authHeaders(token) },
        body: JSON.stringify({ message: question }),
        signal: controller.signal,
      });

      if (res.status === 401) {
        onSessionExpired();
        return;
      }

      if (!res.ok) {
        // fetch não rejeita em 4xx/5xx: sem este ramo um 429 apareceria como "undefined".
        const text = await readError(res);
        setMessages((prev) => [...prev, { role: "error", text, time: formatTime() }]);
        return;
      }

      const data = await res.json();
      setMessages((prev) => [
        ...prev,
        { role: "bot", text: data.answer, sources: data.sources ?? [], time: formatTime() },
      ]);
    } catch (e) {
      // fetch só rejeita em falha de rede, CORS ou abort — nunca em status HTTP de erro.
      const text =
        e.name === "AbortError"
          ? "O servidor demorou demais para responder. Tente novamente."
          : `Não foi possível falar com o servidor em ${API_URL}. Ele está no ar?`;
      setMessages((prev) => [...prev, { role: "error", text, time: formatTime() }]);
    } finally {
      clearTimeout(timeout);
      setLoading(false);
    }
  };

  return (
    <div className="chat-panel">
      <div className="message-list">
        {messages.length === 0 && !loading && (
          <p className="typing-indicator">
            Pergunte algo sobre o evento — agenda, palestrantes, artigos ou matérias.
          </p>
        )}

        {messages.map((m, i) => (
          <Message key={i} message={m} />
        ))}

        {loading && <p className="typing-indicator">respondendo...</p>}
      </div>

      <div className="input-dock">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && sendMessage()}
          placeholder="Pergunte algo sobre o evento..."
          disabled={loading}
        />
        <button onClick={sendMessage} disabled={loading || !input.trim()}>
          Enviar
        </button>
      </div>
    </div>
  );
}

function Message({ message }) {
  if (message.role === "error") {
    return (
      <div className="message-row">
        {message.time && <span className="timestamp">{message.time}</span>}
        <div className="bubble" style={{ color: "#b3261e" }}>
          <b>Erro:</b> {message.text}
        </div>
      </div>
    );
  }

  return (
    <div className={`message-row ${message.role}`}>
      {message.time && <span className="timestamp">{message.time}</span>}
      <div className="bubble">
        {message.text}
        {message.role === "bot" && message.sources?.length > 0 && (
          <Sources sources={message.sources} />
        )}
      </div>
    </div>
  );
}

/**
 * Rotulado "Trechos consultados", não "Fontes": o backend devolve o que foi *recuperado*, não o
 * que foi de fato citado, então numa recusa a lista vem preenchida do mesmo jeito
 * (API_CONTRACT.md). Chamar isso de fonte mostraria referências embaixo de um "não sei", e
 * detectar a recusa por comparação de texto é frágil — a recusa nem sempre é a frase fixa.
 */
function Sources({ sources }) {
  return (
    <details style={{ marginTop: 8, fontSize: "0.85em", opacity: 0.85 }}>
      <summary style={{ cursor: "pointer" }}>Trechos consultados ({sources.length})</summary>
      <ul style={{ margin: "8px 0 0", paddingLeft: 20 }}>
        {sources.map((s) => (
          <li key={s.id}>
            {s.type}
            {s.titleRef ? ` — ${s.titleRef}` : ""} ({s.score})
          </li>
        ))}
      </ul>
    </details>
  );
}

export default Chat;
