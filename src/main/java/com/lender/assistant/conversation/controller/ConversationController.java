package com.lender.assistant.conversation.controller;

import com.lender.assistant.common.web.UserIdInterceptor;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.conversation.model.response.ConversationMessagesResponse;
import com.lender.assistant.conversation.model.response.ConversationSummaryResponse;
import com.lender.assistant.conversation.model.response.DeleteConversationResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话列表入口。userId 来自拦截器解析后的 request attribute，controller 不解析请求头。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public List<ConversationSummaryResponse> list(
            @RequestAttribute(UserIdInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return conversationService.listConversations(userId);
    }

    @GetMapping("/{conversationId}/messages")
    public ConversationMessagesResponse messages(
            @PathVariable("conversationId") String conversationId,
            @RequestAttribute(UserIdInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return conversationService.getMessages(conversationId, userId);
    }

    @DeleteMapping("/{conversationId}")
    public DeleteConversationResponse delete(
            @PathVariable("conversationId") String conversationId,
            @RequestAttribute(UserIdInterceptor.USER_ID_ATTRIBUTE) String userId) {
        return conversationService.deleteConversation(conversationId, userId);
    }
}
