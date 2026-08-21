import { useState } from "react";
import { API_URL, authHeaders, readError } from "./api";

/**
 * Trilha personalizada: pede uma recomendação e mostra o roteiro do dia.
 *
 * O campo de interesses é opcional de propósito — deixá-lo vazio faz o backend usar o que a conta já
 * perguntou no chat. É a demonstração mais interessante das duas, então a tela diz isso em voz alta.
 *
 * Não há geração de texto aqui, só embedding e busca, então costuma responder em menos de um segundo.
 */
function Itinerary({ token, onSessionExpired }) {
  const [interests, setInterests] = useState("");
  const [maxSessions, setMaxSessions] = useState(3);
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const submit = async (event) => {
    event.preventDefault();
    if (loading) return;

    setLoading(true);
    setError("");

    try {
      const body = { maxSessions };
      // Só manda `interests` se houver texto: string vazia não é "sem interesses", e o backend
      // recusaria em vez de cair no perfil da conta.
      if (interests.trim()) body.interests = interests.trim();

      const res = await fetch(`${API_URL}/api/agenda/recommend`, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...authHeaders(token) },
        body: JSON.stringify(body),
      });

      if (res.status === 401) {
        onSessionExpired();
        return;
      }
      if (!res.ok) {
        setError(await readError(res));
        setResult(null);
        return;
      }
      setResult(await res.json());
    } catch {
      setError(`Não foi possível falar com o servidor em ${API_URL}. Ele está no ar?`);
      setResult(null);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="chat-panel form-panel">
      <h2 style={{ marginBottom: 4 }}>Minha trilha</h2>
      <p style={{ marginTop: 0, fontSize: "0.85em" }}>
        As sessões que mais combinam com você, já sem choque de horário. Deixe o campo vazio para usar
        o que você perguntou no chat <b>na última hora</b> — passado esse prazo o histórico é apagado e
        aí é preciso descrever os interesses aqui.
      </p>

      <form onSubmit={submit} style={{ display: "flex", flexWrap: "wrap", gap: 8, margin: "16px 0" }}>
        <input
          value={interests}
          onChange={(e) => setInterests(e.target.value)}
          placeholder="ex.: agentes de IA em operações e no varejo"
          disabled={loading}
          style={{ flex: "1 1 260px" }}
        />
        <label style={{ fontSize: "0.85em", alignSelf: "center" }}>
          sessões{" "}
          <select
            value={maxSessions}
            onChange={(e) => setMaxSessions(Number(e.target.value))}
            disabled={loading}
          >
            {[2, 3, 4, 5].map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" disabled={loading} className="primary-button" style={{ width: "auto" }}>
          {loading ? "Montando..." : "Montar trilha"}
        </button>
      </form>

      {error && (
        <p style={{ color: "#b3261e", fontSize: "0.9em" }}>
          <b>Erro:</b> {error}
        </p>
      )}

      {result && result.itinerary.length > 0 && (
        <ol className="itinerary">
          {result.itinerary.map((slot) => (
            <li key={slot.id}>
              <span className="itinerary-time">
                {slot.startsAt}–{slot.endsAt}
              </span>
              <span className="itinerary-title">{slot.titleRef}</span>
              <span className="itinerary-score">afinidade {slot.score}</span>
            </li>
          ))}
        </ol>
      )}

      {result && result.itinerary.length === 0 && !error && (
        <p>{result.message ?? "Nenhuma sessão combinou com esses interesses."}</p>
      )}

      {/* A mensagem explica um resultado menor que o pedido — conflito de horário, por exemplo. */}
      {result && result.itinerary.length > 0 && result.message && (
        <p style={{ fontSize: "0.8em", opacity: 0.8 }}>{result.message}</p>
      )}

      {result && (
        <p style={{ fontSize: "0.75em", opacity: 0.6 }}>
          {result.consideredCount} sessões consideradas · {result.acceptedCount} na trilha
        </p>
      )}
    </div>
  );
}

export default Itinerary;
