// Base do backend. Sobrescreva com VITE_API_URL (ex.: `VITE_API_URL=http://api:8080 npm run dev`)
// para apontar o frontend a outro host sem editar código.
export const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080";

// O token da sessão fica no localStorage para sobreviver a um reload. É o backend que decide se
// ele ainda vale (`app.auth.session-ttl`, hoje 24h); aqui não há como saber, então guardamos e
// deixamos o servidor recusar quando expirar.
const TOKEN_KEY = "chat-session-token";

export function loadToken() {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    // Navegação privada pode bloquear o localStorage: dá para usar o app, só não fica logado
    // entre recarregamentos.
    return null;
  }
}

export function saveToken(token) {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // Ignorado de propósito: a sessão continua válida nesta aba, só não persiste.
  }
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    // Nada a fazer — se não deu para gravar, também não há o que apagar.
  }
}

/** Header de autenticação. Sem token, a requisição vai anônima e o backend responde 401. */
export function authHeaders(token) {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/**
 * Lê o corpo de erro do backend, que segue RFC 7807 (ProblemDetail). Um 429 traz o header
 * Retry-After em segundos; mostramos esse número em vez de convidar o usuário a insistir,
 * porque cada tentativa consome o mesmo limite que acabou de estourar.
 */
export async function readError(response) {
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
