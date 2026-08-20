import { useEffect, useState } from "react";
import { API_URL, readError } from "./api";

// A visão do organizador: o que as pessoas mais perguntaram num dia. Só o top 10 — isto é uma
// superfície de demonstração, não um produto de analytics.
const TOP_N = 10;

/** O dia inteiro em UTC, meio-aberto [00:00 do dia, 00:00 do dia seguinte) como o backend espera. */
function dayWindow(isoDate) {
  const from = new Date(`${isoDate}T00:00:00Z`);
  const to = new Date(from);
  to.setUTCDate(to.getUTCDate() + 1);
  return { from: from.toISOString(), to: to.toISOString() };
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Barras em CSS, sem biblioteca de gráficos: o projeto não tem nenhuma, e uma dependência nova para
 * dez linhas de tabela não se paga.
 *
 * Os números são agregados — contagem de recuperações por item. Não há texto de pergunta nem nada
 * por usuário nesta tela porque não há nada disso na tabela (ver `chunk_retrieval_log`).
 */
function Analytics() {
  const [date, setDate] = useState(today);
  const [groupBy, setGroupBy] = useState("titleRef");
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;

    (async () => {
      setLoading(true);
      setError("");
      try {
        const { from, to } = dayWindow(date);
        const query = new URLSearchParams({ from, to, groupBy });
        const res = await fetch(`${API_URL}/api/analytics/interest-summary?${query}`);
        if (!active) return;

        if (!res.ok) {
          setError(await readError(res));
          setSummary(null);
          return;
        }
        setSummary(await res.json());
      } catch {
        if (active) {
          setError(`Não foi possível falar com o servidor em ${API_URL}. Ele está no ar?`);
          setSummary(null);
        }
      } finally {
        if (active) setLoading(false);
      }
    })();

    return () => {
      active = false;
    };
  }, [date, groupBy]);

  const rows = (summary?.results ?? []).slice(0, TOP_N);
  // Escala relativa ao primeiro colocado: a barra compara itens entre si, não com um total.
  const busiest = rows.length > 0 ? rows[0].retrievalCount : 0;

  return (
    <div className="chat-panel form-panel">
      <h2 style={{ marginBottom: 4 }}>Interesse do público</h2>
      <p style={{ marginTop: 0, fontSize: "0.85em" }}>
        Quantas vezes cada item foi usado como contexto nas respostas. Dados agregados — nenhuma
        pergunta ou usuário é registrado.
      </p>

      <div style={{ display: "flex", gap: 12, alignItems: "center", margin: "16px 0" }}>
        <label style={{ fontSize: "0.9em" }}>
          Dia{" "}
          <input
            type="date"
            value={date}
            onChange={(e) => e.target.value && setDate(e.target.value)}
            style={{ padding: 4 }}
          />
        </label>
        <label style={{ fontSize: "0.9em" }}>
          Agrupar por{" "}
          <select value={groupBy} onChange={(e) => setGroupBy(e.target.value)} style={{ padding: 4 }}>
            <option value="titleRef">item (palestra, palestrante)</option>
            <option value="type">tipo</option>
          </select>
        </label>
      </div>

      {loading && (
        <p>
          <i>Carregando...</i>
        </p>
      )}

      {error && (
        <p style={{ color: "#b3261e" }}>
          <b>Erro:</b> {error}
        </p>
      )}

      {!loading && !error && rows.length === 0 && (
        <p>Nenhuma consulta registrada neste dia.</p>
      )}

      {rows.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "0.9em" }}>
          <thead>
            <tr style={{ textAlign: "left", borderBottom: "1px solid var(--border)" }}>
              <th style={{ padding: "6px 4px" }}>Item</th>
              <th style={{ padding: "6px 4px", width: "35%" }}>Consultas</th>
              <th style={{ padding: "6px 4px" }}>Similaridade média</th>
              <th style={{ padding: "6px 4px" }}>Requisições</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.key} style={{ borderBottom: "1px solid var(--border)" }}>
                <td style={{ padding: "6px 4px" }}>{row.key}</td>
                <td style={{ padding: "6px 4px" }}>
                  <span
                    style={{
                      display: "inline-block",
                      height: 10,
                      borderRadius: 5,
                      background: "var(--accent)",
                      width: `${busiest > 0 ? (row.retrievalCount / busiest) * 100 : 0}%`,
                      minWidth: 2,
                      verticalAlign: "middle",
                      marginRight: 8,
                    }}
                  />
                  {row.retrievalCount}
                </td>
                <td style={{ padding: "6px 4px" }}>{row.avgScore}</td>
                <td style={{ padding: "6px 4px" }}>{row.distinctSessions}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {summary?.truncated && (
        <p style={{ fontSize: "0.8em" }}>
          A lista foi cortada pelo limite do servidor; mostrando os itens de maior demanda.
        </p>
      )}
    </div>
  );
}

export default Analytics;
