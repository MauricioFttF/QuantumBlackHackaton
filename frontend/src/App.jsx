import { useCallback, useEffect, useState } from "react";
import Analytics from "./Analytics";
import Auth from "./Auth";
import Chat from "./Chat";
import { API_URL, authHeaders, clearToken, loadToken } from "./api";

/**
 * Decide quem está na tela: o formulário de acesso ou o chat.
 *
 * Um token guardado não é prova de sessão válida — ele pode ter expirado ou sido revogado no
 * logout de outro dispositivo. Por isso, ao abrir a página, perguntamos ao backend
 * (`GET /api/auth/me`) antes de mostrar o chat, em vez de confiar no que está no localStorage.
 */
function App() {
  const [token, setToken] = useState(loadToken);
  const [account, setAccount] = useState(null);
  const [view, setView] = useState("chat");
  const [checkingSession, setCheckingSession] = useState(Boolean(loadToken()));

  const signOutLocally = useCallback(() => {
    clearToken();
    setToken(null);
    setAccount(null);
  }, []);

  useEffect(() => {
    if (!token) return;

    let active = true;
    (async () => {
      try {
        const res = await fetch(`${API_URL}/api/auth/me`, { headers: authHeaders(token) });
        if (!active) return;

        if (res.ok) {
          setAccount(await res.json());
        } else {
          // 401: token expirado ou revogado. Qualquer outro erro também não permite afirmar que
          // há sessão, então em ambos os casos voltamos para o login em vez de mostrar um chat
          // que responderia 401 na primeira pergunta.
          signOutLocally();
        }
      } catch {
        if (active) signOutLocally();
      } finally {
        if (active) setCheckingSession(false);
      }
    })();

    return () => {
      active = false;
    };
  }, [token, signOutLocally]);

  const signIn = (session) => {
    setToken(session.token);
    setAccount({ email: session.email });
    setCheckingSession(false);
  };

  const signOut = async () => {
    try {
      // Revoga a sessão no servidor. Se a chamada falhar, o token local sai de qualquer forma:
      // é melhor deslogar aqui do que deixar o usuário preso numa sessão que ele pediu para sair.
      await fetch(`${API_URL}/api/auth/logout`, { method: "POST", headers: authHeaders(token) });
    } catch {
      // Ignorado de propósito — ver acima.
    } finally {
      signOutLocally();
    }
  };

  return (
    <div>
      <h1 style={{ textAlign: "center", marginBottom: 8 }}>AI Forum Chat</h1>

      {account && (
        <p style={{ textAlign: "center", fontSize: "0.85em", marginTop: 0 }}>
          {account.email}{" "}
          <button
            type="button"
            onClick={signOut}
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
            sair
          </button>
        </p>
      )}

      {account && (
        <p style={{ textAlign: "center", fontSize: "0.85em", marginTop: 0 }}>
          <button type="button" onClick={() => setView("chat")} style={navStyle(view === "chat")}>
            conversa
          </button>
          {" · "}
          <button
            type="button"
            onClick={() => setView("analytics")}
            style={navStyle(view === "analytics")}
          >
            interesse do público
          </button>
        </p>
      )}

      {checkingSession ? (
        <p style={{ textAlign: "center" }}>
          <i>Verificando sessão...</i>
        </p>
      ) : token ? (
        // onSessionExpired: o chat avisa quando o backend recusa o token no meio do uso, e a tela
        // volta para o login em vez de acumular erros que o usuário não pode resolver.
        view === "analytics" ? (
          <Analytics />
        ) : (
          <Chat token={token} onSessionExpired={signOutLocally} />
        )
      ) : (
        <Auth onSignedIn={signIn} />
      )}
    </div>
  );
}

/** Aba ativa em destaque; as duas continuam clicáveis. */
function navStyle(active) {
  return {
    background: "none",
    border: "none",
    padding: 0,
    color: active ? "var(--text-h)" : "var(--accent)",
    fontWeight: active ? 600 : 400,
    cursor: "pointer",
    font: "inherit",
    textDecoration: active ? "none" : "underline",
  };
}

export default App;
