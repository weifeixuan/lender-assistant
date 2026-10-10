package com.lender.assistant.controller;

import com.lender.assistant.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 最简对话接口（阶段 0）。阶段 2 会在此基础上加 SSE 流式与混合检索上下文注入。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public record ChatRequest(String message) {}

    @PostMapping
    public Map<String, String> chat(@RequestBody ChatRequest request) {
        String answer = chatService.chat(request.message());
        return Map.of("answer", answer);
    }
}
