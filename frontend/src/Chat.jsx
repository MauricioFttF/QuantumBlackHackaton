import { useState } from "react";
import "./Chat.css";

const MOCK_MODE = false; // troca para false quando o backend estiver pronto

function formatTime() {
  const now = new Date();
  const h = String(now.getHours()).padStart(2, "0");
  const m = String(now.getMinutes()).padStart(2, "0");
  return `${h}h${m}`;
}

function Chat() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);

  const sendMessage = async () => {
    if (!input.trim()) return;

    const userMessage = { role: "user", text: input, time: formatTime() };
    setMessages((prev) => [...prev, userMessage]);
    setInput("");
    setLoading(true);

    let answer;
    if (MOCK_MODE) {
      await new Promise((r) => setTimeout(r, 600));
      answer = "Essa é uma resposta simulada, ainda não conectada ao backend.";
    } else {
      const res = await fetch("http://localhost:8080/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: input }),
      });
      const data = await res.json();
      answer = data.answer;
    }

    setMessages((prev) => [...prev, { role: "bot", text: answer, time: formatTime() }]);
    setLoading(false);
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

      <div className="chat-panel">
        <div className="message-list">
          {messages.map((m, i) => (
            <div key={i} className={`message-row ${m.role}`}>
              <span className="timestamp">{m.time}</span>
              <div className="bubble">{m.text}</div>
            </div>
          ))}
          {loading && <p className="typing-indicator">respondendo...</p>}
        </div>

        <div className="input-dock">
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && sendMessage()}
            placeholder="Pergunte algo sobre o evento..."
          />
          <button onClick={sendMessage}>Enviar</button>
        </div>
      </div>
    </div>
  );
}

export default Chat;