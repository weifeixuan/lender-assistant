package com.lender.assistant.conversation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.dto.AssembledContextDTO;
import com.lender.assistant.conversation.model.dto.CompleteRoundDTO;
import com.lender.assistant.conversation.model.entity.ConversationEntity;
import com.lender.assistant.conversation.model.entity.MessageEntity;
import com.lender.assistant.rag.model.dto.ChatTurnDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把落库消息装配成模型可回放的有序历史，并同时给出本轮可注入的滚动摘要（PRD §3 / ADR-0020）。
 *
 * 规则（spec「历史装配」）：
 * - 排除当前轮；按 roundId 分组，一组必须同时有 USER 与 ASSISTANT 且状态都是 DONE，
 *   缺任一条整轮丢弃——不和相邻轮拼接，避免模型看到"半个对话"。
 * - 最多最近 N 轮；总字符超上限时从最旧一轮开始整轮丢弃。
 * - FAILED / STOPPED 轮次整轮不进上下文，但照常展示在历史接口里。
 * - provider="rule" 的固定提示轮整轮跳过：既不回放，也不作为 previousQuestion 来源。
 * - 含业务工具产物的历史助手回答（如 PLAN 轮中的 customer.profile）追加一行机器标注，
 *   只进 LLM 上下文、不改库里 content，避免模型把合法工具数据当成"与召回资料矛盾"而撤回。
 * - 滚动摘要（回放窗口之外更早轮次的梗概）：仅当库中摘要边界轮严格早于本轮窗口最旧轮才
 *   随装配结果返回；窗口起点为 0 时不可注入——宁缺勿重。边界轮通常就在完整轮序列内按
 *   下标比较；摘要滞后超出装配行窗口（行序列找不到边界轮）时按消息自增 id 向 DB 判先后
 *   （spec §4.5：滞后越多的会话越需要摘要，不得因行窗口截断而静默失效）。
 */
@Component
public class ConversationHistoryAssembler {

    private static final String KB_SEARCH_TOOL = "kb.search";
    private static final String TOOL_NOTE_PREFIX = "[上一轮回答包含业务工具数据：";
    private static final String TOOL_NOTE_SUFFIX = "，非知识库文档]";

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;
    private final int maxRounds;
    private final int maxChars;

    public ConversationHistoryAssembler(
            MessageMapper messageMapper,
            ConversationMapper conversationMapper,
            ObjectMapper objectMapper,
            @Value("${rag.qa.history-max-rounds:10}") int maxRounds,
            @Value("${rag.qa.history-max-chars:8000}") int maxChars) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.objectMapper = objectMapper;
        this.maxRounds = maxRounds;
        this.maxChars = maxChars;
    }

    /**
     * 装配一轮回答所需的全部上下文：同一请求内 route 解析、历史回放与摘要注入判定共用一次查询结果。
     *
     * @param userId 摘要读取需归属校验（会话行按 conversationId + user_id + ACTIVE 取），与回答链路的
     *               validateOwnership 同源；会话在装配瞬间已不可见时按无摘要处理（宁缺勿重）
     */
    public AssembledContextDTO assembleWithSummary(String conversationId, String userId, String currentRoundId) {
        List<MessageEntity> rows = listRows(conversationId);
        List<CompleteRoundDTO> rounds = completeRounds(rows, currentRoundId);
        int windowStart = windowStart(rounds);
        return new AssembledContextDTO(rows, replayTurns(rounds, windowStart),
                injectableSummary(conversationId, userId, rounds, windowStart));
    }

    /** 会话内最近的原始消息行（id 升序）；路由解析与历史装配共用同一个窗口 */
    private List<MessageEntity> listRows(String conversationId) {
        // 窗口 = 最近 N 轮 × 2 条 + 当前轮 2 条，够装配用且不会把整段会话读进内存
        return messageMapper.listRecentByConversation(conversationId, maxRounds * 2 + 2);
    }

    /**
     * 完整轮序列（ oldest → newest）：USER+ASSISTANT 双 DONE 且非 rule 回复的轮次，
     * 与回放、滚动摘要合并（PRD §4）共用同一套完整轮判定（§3.6：同一算法只允许一处实现）。
     *
     * @param currentRoundId 要排除的当前轮；滚动摘要合并时传 null（不排除任何轮，刚收尾的轮已 DONE）
     */
    public List<CompleteRoundDTO> completeRounds(List<MessageEntity> rows, String currentRoundId) {
        Map<String, RoundMessages> rounds = new LinkedHashMap<>();
        for (MessageEntity row : rows) {
            if (row.roundId().equals(currentRoundId)) {
                continue;
            }
            RoundMessages round = rounds.computeIfAbsent(row.roundId(), ignored -> new RoundMessages());
            if (!MessageConstants.STATUS_DONE.equals(row.status())) {
                continue;
            }
            if (MessageConstants.ROLE_USER.equals(row.role())) {
                round.user = row;
            } else if (MessageConstants.ROLE_ASSISTANT.equals(row.role())) {
                round.assistant = row;
                round.ruleReply = MessageConstants.RULE_PROVIDER.equals(row.provider());
            }
        }

        List<CompleteRoundDTO> complete = new ArrayList<>();
        for (Map.Entry<String, RoundMessages> entry : rounds.entrySet()) {
            RoundMessages round = entry.getValue();
            if (round.user != null && round.assistant != null && !round.ruleReply) {
                complete.add(new CompleteRoundDTO(entry.getKey(), round.user, round.assistant));
            }
        }
        return complete;
    }

    /**
     * 回放窗口起点（完整轮序列下标）：轮数上限与字符预算共同决定实际范围，以更紧者为准；
     * 超字符上限时从最旧一轮开始整轮丢弃。注入判定与滚动摘要合并（PRD §4）共用同一实算。
     */
    public int windowStart(List<CompleteRoundDTO> rounds) {
        int start = Math.max(0, rounds.size() - maxRounds);
        while (start < rounds.size() && windowChars(rounds, start) > maxChars) {
            start++;
        }
        return start;
    }

    /** 从 start 轮到末尾的回放字符总量（含助手回答的工具来源标注，与回放口径一致） */
    private int windowChars(List<CompleteRoundDTO> rounds, int start) {
        int total = 0;
        for (int i = start; i < rounds.size(); i++) {
            CompleteRoundDTO round = rounds.get(i);
            total += round.user().content() == null ? 0 : round.user().content().length();
            String assistant = assistantContent(round.assistant());
            total += assistant == null ? 0 : assistant.length();
        }
        return total;
    }

    /** 窗口内轮次回放为 USER→ASSISTANT 交替的消息序列 */
    private List<ChatTurnDTO> replayTurns(List<CompleteRoundDTO> rounds, int windowStart) {
        List<ChatTurnDTO> history = new ArrayList<>();
        for (int i = windowStart; i < rounds.size(); i++) {
            CompleteRoundDTO round = rounds.get(i);
            history.add(new ChatTurnDTO(MessageConstants.ROLE_USER, round.user().content()));
            history.add(new ChatTurnDTO(MessageConstants.ROLE_ASSISTANT, assistantContent(round.assistant())));
        }
        return history;
    }

    /**
     * 本轮可注入的滚动摘要（PRD §3 / ADR-0020）。
     *
     * 判定（宁缺勿重，避免窗口调大后摘要与回放轮次重复注入）：
     * - 窗口起点为 0：全会话都在窗口内，没有"更早轮次"，无需摘要；
     * - 边界轮在本序列内：下标严格早于窗口起点才注入；
     * - 边界轮不在本序列（滞后超出装配行窗口）：按消息自增 id 向 DB 判先后，查不到按不成立处理。
     */
    private String injectableSummary(String conversationId, String userId,
                                     List<CompleteRoundDTO> rounds, int windowStart) {
        if (windowStart == 0) {
            return null;
        }
        ConversationEntity conversation =
                conversationMapper.findActiveByConversationIdAndUser(conversationId, userId);
        if (conversation == null) {
            return null;
        }
        String summary = conversation.summary();
        String boundaryRoundId = conversation.summaryBoundaryRoundId();
        if (summary == null || summary.isBlank() || boundaryRoundId == null || boundaryRoundId.isBlank()) {
            return null;
        }
        int boundaryIndex = -1;
        for (int i = 0; i < rounds.size(); i++) {
            if (rounds.get(i).roundId().equals(boundaryRoundId)) {
                boundaryIndex = i;
                break;
            }
        }
        if (boundaryIndex >= 0) {
            // 序列按消息 id 升序，下标序即 id 序：下标比较与 DB id 比较结论一致
            return boundaryIndex < windowStart ? summary : null;
        }
        // 边界轮不在本序列：装配行窗口只取最近 ~N+1 轮，摘要合并连续跳过时边界会滞后多轮、
        // 行窗口装不下它——滞后越多的会话越需要摘要，不得静默失效。向 DB 按消息自增 id
        // 判先后（边界轮不存在时比较为 NULL，按不成立处理：宁缺勿重）。
        return Boolean.TRUE.equals(messageMapper.isRoundStrictlyBefore(
                conversationId, boundaryRoundId, rounds.get(windowStart).roundId()))
                ? summary : null;
    }

    /**
     * 助手正文 + 来源标注：只在计划里出现非 kb.search 工具产物时追加，
     * 让模型知道这段历史结论来自业务工具而非本轮召回资料。标注只存在于装配结果，
     * 库里的 content 与前端展示不受影响。
     */
    private String assistantContent(MessageEntity assistant) {
        List<String> tools = businessToolNames(assistant);
        if (tools.isEmpty()) {
            return assistant.content();
        }
        return assistant.content() + "\n" + TOOL_NOTE_PREFIX
                + String.join("、", tools) + TOOL_NOTE_SUFFIX;
    }

    /** 从 execution_plan 快照里取非 kb.search 的工具名（去重、保序）；弃用计划没有工具产物 */
    private List<String> businessToolNames(MessageEntity assistant) {
        String json = assistant.executionPlanJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.path("degraded").asBoolean(false)) {
                return List.of();
            }
            List<String> tools = new ArrayList<>();
            for (JsonNode step : root.path("steps")) {
                String tool = step.path("tool").asText("");
                if (!tool.isBlank() && !KB_SEARCH_TOOL.equals(tool) && !tools.contains(tool)) {
                    tools.add(tool);
                }
            }
            return tools;
        } catch (Exception ex) {
            // 快照解析失败按"无工具产物"处理：宁可少标注，也不能把历史正文弄脏
            return List.of();
        }
    }

    /** 检索 query 用的最近一条历史用户正文；没有可用历史返回 null */
    public static String lastUserContent(List<ChatTurnDTO> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (MessageConstants.ROLE_USER.equals(history.get(i).role())) {
                return history.get(i).content();
            }
        }
        return null;
    }

    private static final class RoundMessages {
        private MessageEntity user;
        private MessageEntity assistant;
        private boolean ruleReply;
    }
}
