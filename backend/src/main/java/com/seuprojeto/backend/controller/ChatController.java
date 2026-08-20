package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.dto.ChatRequest;
import com.seuprojeto.backend.dto.ChatResponse;
import com.seuprojeto.backend.dto.ChatTurn;
import com.seuprojeto.backend.service.ChatService;
import com.seuprojeto.backend.service.ConversationMemory;
import com.seuprojeto.backend.web.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final ConversationMemory conversationMemory;
    private final CurrentUser currentUser;

    public ChatController(ChatService chatService,
                          ConversationMemory conversationMemory,
                          CurrentUser currentUser) {
        this.chatService = chatService;
        this.conversationMemory = conversationMemory;
        this.currentUser = currentUser;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody(required = false) ChatRequest request,
                             HttpServletRequest httpRequest) {
        if (request == null) {
            // A literal `null` JSON body binds to null; without this it NPEs into a 500.
            throw new IllegalArgumentException("A mensagem não pode ser vazia");
        }
        return chatService.answer(currentUser.conversationKey(httpRequest), request.message());
    }

    /**
     * The caller's own conversation, oldest turn first — what the model will be told on the next
     * question. Lets a reloaded page show the chat the server still remembers instead of an empty
     * window while the model answers as if the conversation continued.
     *
     * <p>Reads the same store the prompt does, so an expired or absent conversation comes back as
     * an empty list, never a stale one.
     */
    @GetMapping("/chat/history")
    public List<ChatTurn> history(HttpServletRequest httpRequest) {
        return conversationMemory.recall(currentUser.conversationKey(httpRequest))
                .stream()
                .map(ChatTurn::from)
                .toList();
    }
}
