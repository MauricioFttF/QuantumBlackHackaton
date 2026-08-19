import { useState } from "react";

const MOCK_MODE = true; // troca para false quando o backend estiver pronto

function Chat() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);

  const sendMessage = async () => {
    if (!input.trim()) return;

    const userMessage = { role: "user", text: input };
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

    setMessages((prev) => [...prev, { role: "bot", text: answer }]);
    setLoading(false);
  };

  return (
    <div style={{ maxWidth: 600, margin: "0 auto", padding: 16 }}>
      <div style={{ minHeight: 300, border: "1px solid #ccc", padding: 12, marginBottom: 12 }}>
        {messages.map((m, i) => (
          <p key={i}>
            <b>{m.role === "user" ? "Você" : "Bot"}:</b> {m.text}
          </p>
        ))}
        {loading && <p><i>Bot está digitando...</i></p>}
      </div>
      <input
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && sendMessage()}
        placeholder="Digite sua pergunta..."
        style={{ width: "80%", padding: 8 }}
      />
      <button onClick={sendMessage} style={{ padding: 8 }}>
        Enviar
      </button>
    </div>
  );
}

export default Chat;
