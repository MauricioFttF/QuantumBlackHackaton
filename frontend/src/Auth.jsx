import { useState } from "react";
import { API_URL, readError, saveToken } from "./api";

/**
 * Criação de conta e login. As duas operações usam o mesmo formulário porque pedem exatamente os
 * mesmos dados — e-mail e senha — e não há confirmação por e-mail: registrar já entra.
 *
 * A validação de verdade é a do backend (formato do e-mail, política de senha). Aqui só evitamos
 * enviar campos vazios; a mensagem exibida é a que o servidor devolve, para não haver duas versões
 * da mesma regra se ela mudar.
 */
function Auth({ onSignedIn }) {
  const [mode, setMode] = useState("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const registering = mode === "register";

  const submit = async (event) => {
    event.preventDefault();
    if (!email.trim() || !password || loading) return;

    setLoading(true);
    setError("");

    try {
      const res = await fetch(`${API_URL}/api/auth/${registering ? "register" : "login"}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: email.trim(), password }),
      });

      if (!res.ok) {
        setError(await readError(res));
        return;
      }

      const session = await res.json();
      saveToken(session.token);
      setPassword("");
      onSignedIn(session);
    } catch {
      // fetch só rejeita em falha de rede, CORS ou abort — nunca em status HTTP de erro.
      setError(`Não foi possível falar com o servidor em ${API_URL}. Ele está no ar?`);
    } finally {
      setLoading(false);
    }
  };

  const switchMode = () => {
    setMode(registering ? "login" : "register");
    setError("");
  };

  return (
    <div style={{ maxWidth: 360, margin: "0 auto", padding: 16 }}>
      <h2 style={{ marginBottom: 8 }}>{registering ? "Criar conta" : "Entrar"}</h2>
      <p style={{ marginTop: 0, fontSize: "0.9em" }}>
        {registering
          ? "Sua conversa com o assistente fica ligada à sua conta."
          : "Entre para conversar sobre o evento."}
      </p>

      <form onSubmit={submit}>
        <label style={{ display: "block", marginBottom: 12 }}>
          E-mail
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            disabled={loading}
            style={{ width: "100%", padding: 8, marginTop: 4 }}
          />
        </label>

        <label style={{ display: "block", marginBottom: 4 }}>
          Senha
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={registering ? "new-password" : "current-password"}
            disabled={loading}
            style={{ width: "100%", padding: 8, marginTop: 4 }}
          />
        </label>
        {registering && (
          <p style={{ margin: "0 0 12px", fontSize: "0.8em" }}>Pelo menos 8 caracteres.</p>
        )}

        {error && (
          <p style={{ color: "#b3261e", fontSize: "0.9em" }}>
            <b>Erro:</b> {error}
          </p>
        )}

        <button
          type="submit"
          disabled={loading || !email.trim() || !password}
          style={{ padding: 8, width: "100%", marginTop: 8 }}
        >
          {loading ? "Enviando..." : registering ? "Criar conta" : "Entrar"}
        </button>
      </form>

      <p style={{ fontSize: "0.9em", marginTop: 16 }}>
        {registering ? "Já tem conta?" : "Ainda não tem conta?"}{" "}
        <button
          type="button"
          onClick={switchMode}
          disabled={loading}
          style={{
            background: "none",
            border: "none",
            padding: 0,
            color: "var(--accent)",
            cursor: "pointer",
            font: "inherit",
            textDecoration: "underline",
          }}
        >
          {registering ? "Entrar" : "Criar conta"}
        </button>
      </p>
    </div>
  );
}

export default Auth;
