package com.lender.assistant.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 最简对话服务：验证 Spring AI -> DeepSeek 链路连通（阶段 0 验收项）。
 *
 * 设计说明：
 * - 用 ChatClient 而不是直接拿 ChatModel：ChatClient 是 Spring AI 的门面，
 *   后面 RAG 的 Advisor 链、结构化输出（entity 转换）都挂在它上面，
 *   阶段 0 就用它能少走回头路。
 * - ChatClient.Builder 由 deepseek starter 自动装配注入，api-key 为空时
 *   应用照常启动，只是调用时报错——开发期没 key 也不阻塞骨架。
 */
@Service
public class ChatService {

    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是融资租赁资金方对接领域的助手，回答需严谨，不确定时明确说明。")
                .build();
    }

    public String chat(String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
