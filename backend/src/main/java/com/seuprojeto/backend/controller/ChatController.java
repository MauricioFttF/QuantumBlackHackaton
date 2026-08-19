package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.dto.ChatRequest;
import com.seuprojeto.backend.dto.ChatResponse;
import com.seuprojeto.backend.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody(required = false) ChatRequest request) {
        if (request == null) {
            // A literal `null` JSON body binds to null; without this it NPEs into a 500.
            throw new IllegalArgumentException("A mensagem não pode ser vazia");
        }
        return chatService.answer(request.message());
    }
}
