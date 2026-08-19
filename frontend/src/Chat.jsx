import { useState } from "react";

// Base do backend. Sobrescreva com VITE_API_URL (ex.: `VITE_API_URL=http://api:8080 npm run dev`)
// para apontar o frontend a outro host sem editar código.
const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080";

// O backend leva de 4 a 7 segundos para responder (API_CONTRACT.md). O timeout existe só para
// não deixar o usuário preso caso o servidor nunca responda — por isso é folgado.
const TIMEOUT_MS = 45000;

/**
 * Lê o corpo de erro do backend, que segue RFC 7807 (ProblemDetail). Um 429 traz o header
 * Retry-After em segundos; mostramos esse número em vez de convidar o usuário a insistir,
 * porque cada tentativa consome o mesmo limite que acabou de estourar.
 */
async function readError(response) {
  let detail = "";
  try {
    const problem = await response.json();
    detail = problem.detail ?? problem.title ?? "";
  } catch {
    // Um erro sem corpo JSON (proxy, gateway) não deve virar um erro de parsing na tela.
    detail = "";
  }

  if (response.status === 429) {
    const retryAfter = response.headers.get("Retry-After");
    return retryAfter
      ? `${detail || "Limite de requisições atingido."} Tente de novo em ${retryAfter}s.`
      : detail || "Limite de requisições atingido.";
  }
  return detail || `O servidor respondeu ${response.status}.`;
}

function Chat() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);

  const sendMessage = async () => {
    const question = input.trim();
    if (!question || loading) return;

    setMessages((prev) => [...prev, { role: "user", text: question }]);
    setInput("");
    setLoading(true);

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);

    try {
      const res = await fetch(`${API_URL}/api/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: question }),
        signal: controller.signal,
      });

      if (!res.ok) {
        const text = await readError(res);
        setMessages((prev) => [...prev, { role: "error", text }]);
        return;
      }

      const data = await res.json();
      setMessages((prev) => [
        ...prev,
        { role: "bot", text: data.answer, sources: data.sources ?? [] },
      ]);
    } catch (e) {
      // fetch só rejeita em falha de rede, CORS ou abort — nunca em status HTTP de erro.
      const text =
        e.name === "AbortError"
          ? "O servidor demorou demais para responder. Tente novamente."
          : `Não foi possível falar com o servidor em ${API_URL}. Ele está no ar?`;
      setMessages((prev) => [...prev, { role: "error", text }]);
    } finally {
      clearTimeout(timeout);
      setLoading(false);
    }
  };

  return (
    <div style={{ maxWidth: 600, margin: "0 auto", padding: 16 }}>
      <div
        style={{
          minHeight: 300,
          border: "1px solid var(--border)",
          borderRadius: 8,
          padding: 12,
          marginBottom: 12,
        }}
      >
        {messages.length === 0 && !loading && (
          <p style={{ color: "var(--text)" }}>
            Pergunte algo sobre o evento — agenda, palestrantes, artigos ou matérias.
          </p>
        )}

        {messages.map((m, i) => (
          <Message key={i} message={m} />
        ))}

        {loading && (
          <p>
            <i>Bot está digitando...</i>
          </p>
        )}
      </div>

      <input
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && sendMessage()}
        placeholder="Digite sua pergunta..."
        disabled={loading}
        style={{ width: "80%", padding: 8 }}
      />
      <button onClick={sendMessage} disabled={loading || !input.trim()} style={{ padding: 8 }}>
        Enviar
      </button>
    </div>
  );
}

function Message({ message }) {
  if (message.role === "error") {
    return (
      <p style={{ color: "#b3261e" }}>
        <b>Erro:</b> {message.text}
      </p>
    );
  }

  return (
    <div>
      <p>
        <b>{message.role === "user" ? "Você" : "Bot"}:</b> {message.text}
      </p>
      {message.role === "bot" && message.sources?.length > 0 && <Sources sources={message.sources} />}
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
    <details style={{ marginTop: -8, marginBottom: 12, fontSize: "0.85em" }}>
      <summary style={{ cursor: "pointer", color: "var(--text)" }}>
        Trechos consultados ({sources.length})
      </summary>
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
