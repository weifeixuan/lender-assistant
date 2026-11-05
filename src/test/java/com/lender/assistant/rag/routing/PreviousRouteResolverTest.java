package com.lender.assistant.rag.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.conversation.model.MessageRow;
import com.lender.assistant.rag.routing.model.QuestionRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PreviousRouteResolverTest {

    private final PreviousRouteResolver resolver = new PreviousRouteResolver(new ObjectMapper());

    @Test
    @DisplayName("取最近一条 DONE 助手消息的路由")
    void takesLatestDoneRoute() {
        assertEquals(QuestionRoute.PLAN, resolver.resolve(List.of(
                assistant("r1", "DONE", "{\"route\":\"KNOWLEDGE\"}"),
                assistant("r2", "DONE", "{\"route\":\"PLAN\"}"))));
    }

    @Test
    @DisplayName("最近一条是 FAILED 时视为无上一轮路由，不向前回溯")
    void failedRoundYieldsNull() {
        assertNull(resolver.resolve(List.of(
                assistant("r1", "DONE", "{\"route\":\"PLAN\"}"),
                assistant("r2", "FAILED", "{\"route\":\"KNOWLEDGE\"}"))));
    }

    @Test
    @DisplayName("没有助手消息或路由缺失时返回 null")
    void missingRouteYieldsNull() {
        assertNull(resolver.resolve(List.of(assistant("r1", "DONE", null))));
        assertNull(resolver.resolve(List.of()));
    }

    private static MessageRow assistant(String roundId, String status, String routeJson) {
        return new MessageRow("m-" + roundId, "conv-1", roundId, "ASSISTANT", "回答",
                status, "[]", "mock", 0, null, "2026-11-05 10:00:00", routeJson, null);
    }
}
