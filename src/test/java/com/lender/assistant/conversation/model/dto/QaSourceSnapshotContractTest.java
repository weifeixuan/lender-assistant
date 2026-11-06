package com.lender.assistant.conversation.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.model.response.QaSourceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * sources 的 JSONB 快照与 SSE 载荷的序列化契约：
 * 会话侧改用 QaSourceSnapshotDTO 后，历史接口返回的 JSON 必须与 QaSourceResponse 逐字段同构，
 * 否则前端渲染引用的字段会静默缺失。
 */
class QaSourceSnapshotContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("快照 JSON 与 SSE sources 载荷同构：字段名一致，null 字段不被省略")
    void snapshotJsonMatchesSseSource() throws Exception {
        QaSourceSnapshotDTO snapshot = new QaSourceSnapshotDTO(1, "资方接口文档", null, 123L,
                "切片正文", null, 2, true, null);
        QaSourceResponse response = new QaSourceResponse(1, "资方接口文档", null, 123L,
                "切片正文", null, 2, true, null);

        JsonNode snapshotJson = objectMapper.readTree(
                objectMapper.writeValueAsString(List.of(snapshot)));
        JsonNode responseJson = objectMapper.readTree(
                objectMapper.writeValueAsString(List.of(response)));

        assertEquals(responseJson, snapshotJson, "快照与 SSE 载荷必须逐字段同构（含 null 字段）");
        assertEquals(9, snapshotJson.get(0).size(), "九个字段都必须写出，null 不能被省略");
    }
}
