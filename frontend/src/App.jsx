import { useCallback, useEffect, useState } from "react";
import Analytics from "./Analytics";
import Auth from "./Auth";
import Chat from "./Chat";
import Itinerary from "./Itinerary";
import "./Chat.css";
import { API_URL, authHeaders, clearToken, loadToken } from "./api";

/**
 * Decide quem está na tela: o formulário de acesso, a conversa ou o painel do organizador.
 *
 * Um token guardado não é prova de sessão válida — ele pode ter expirado ou sido revogado no
 * logout de outro dispositivo. Por isso, ao abrir a página, perguntamos ao backend
 * (`GET /api/auth/me`) antes de mostrar o chat, em vez de confiar no que está no localStorage.
 *
 * O cabeçalho e o `app-shell` vivem aqui, não dentro do chat, para que login e painel apareçam com
 * a mesma moldura.
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
    setView("chat");
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
    <div className="app-shell">
      <header className="event-header">
        <div className="live-badge">
          <span className="live-dot" />
          AO VIVO — AI FORUM
        </div>
        <h1>Pergunte ao AI Forum</h1>
        <p>Agenda, palestrantes e conteúdos do evento, em tempo real.</p>
      </header>

      {account && (
        <p className="account-bar">
          <button type="button" onClick={() => setView("chat")} className={tab(view === "chat")}>
            conversa
          </button>
          {" · "}
          <button
            type="button"
            onClick={() => setView("itinerary")}
            className={tab(view === "itinerary")}
          >
            minha trilha
          </button>
          {" · "}
          <button
            type="button"
            onClick={() => setView("analytics")}
            className={tab(view === "analytics")}
          >
            interesse do público
          </button>
          {" · "}
          <span>{account.email}</span>{" "}
          <button type="button" onClick={signOut} className="link-button">
            sair
          </button>
        </p>
      )}

      {checkingSession ? (
        <p className="typing-indicator">Verificando sessão...</p>
      ) : !token ? (
        <Auth onSignedIn={signIn} />
      ) : view === "analytics" ? (
        <Analytics />
      ) : view === "itinerary" ? (
        <Itinerary token={token} onSessionExpired={signOutLocally} />
      ) : (
        // onSessionExpired: o chat avisa quando o backend recusa o token no meio do uso, e a tela
        // volta para o login em vez de acumular erros que o usuário não pode resolver.
        <Chat token={token} onSessionExpired={signOutLocally} />
      )}
    </div>
  );
}

/** Aba ativa em destaque; as duas continuam clicáveis. */
function tab(active) {
  return active ? "link-button active" : "link-button";
}

export default App;
