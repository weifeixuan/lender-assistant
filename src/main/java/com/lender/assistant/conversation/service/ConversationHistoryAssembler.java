package com.lender.assistant.conversation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.MessageRow;
import com.lender.assistant.rag.model.ChatTurn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把落库消息装配成模型可回放的有序历史。
 *
 * 规则（spec「历史装配」）：
 * - 排除当前轮；按 roundId 分组，一组必须同时有 USER 与 ASSISTANT 且状态都是 DONE，
 *   缺任一条整轮丢弃——不和相邻轮拼接，避免模型看到"半个对话"。
 * - 最多最近 N 轮；总字符超上限时从最旧一轮开始整轮丢弃。
 * - FAILED / STOPPED 轮次整轮不进上下文，但照常展示在历史接口里。
 * - provider="rule" 的固定提示轮整轮跳过：既不回放，也不作为 previousQuestion 来源。
 * - 含业务工具产物的历史助手回答（如 PLAN 轮中的 customer.profile）追加一行机器标注，
 *   只进 LLM 上下文、不改库里 content，避免模型把合法工具数据当成"与召回资料矛盾"而撤回。
 */
@Component
public class ConversationHistoryAssembler {

    private static final String KB_SEARCH_TOOL = "kb.search";
    private static final String TOOL_NOTE_PREFIX = "[上一轮回答包含业务工具数据：";
    private static final String TOOL_NOTE_SUFFIX = "，非知识库文档]";

    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final int maxRounds;
    private final int maxChars;

    public ConversationHistoryAssembler(
            MessageMapper messageMapper,
            ObjectMapper objectMapper,
            @Value("${rag.qa.history-max-rounds:6}") int maxRounds,
            @Value("${rag.qa.history-max-chars:2000}") int maxChars) {
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.maxRounds = maxRounds;
        this.maxChars = maxChars;
    }

    /** 会话内按 id 升序的原始消息行；供路由读取上一轮 route 用，不做回放过滤 */
    public List<MessageRow> listRows(String conversationId) {
        return messageMapper.listByConversation(conversationId);
    }

    /** @param currentRoundId 本轮 id，装配时排除，避免把刚提问的用户消息当成历史 */
    public List<ChatTurn> assemble(String conversationId, String currentRoundId) {
        List<MessageRow> rows = messageMapper.listByConversation(conversationId);

        Map<String, RoundMessages> rounds = new LinkedHashMap<>();
        for (MessageRow row : rows) {
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

        List<ChatTurn> complete = new ArrayList<>();
        for (RoundMessages round : rounds.values()) {
            if (round.user != null && round.assistant != null && !round.ruleReply) {
                complete.add(new ChatTurn(MessageConstants.ROLE_USER, round.user.content()));
                complete.add(new ChatTurn(MessageConstants.ROLE_ASSISTANT,
                        assistantContent(round.assistant)));
            }
        }

        // 最近 maxRounds 轮（一轮两条消息）
        int roundsToKeep = maxRounds * 2;
        int from = Math.max(0, complete.size() - roundsToKeep);
        List<ChatTurn> selected = new ArrayList<>(complete.subList(from, complete.size()));

        // 超字符上限：从最旧一轮开始整轮丢弃（每次丢 USER + ASSISTANT 两条）
        while (totalChars(selected) > maxChars && !selected.isEmpty()) {
            selected.subList(0, Math.min(2, selected.size())).clear();
        }
        return selected;
    }

    /**
     * 助手正文 + 来源标注：只在计划里出现非 kb.search 工具产物时追加，
     * 让模型知道这段历史结论来自业务工具而非本轮召回资料。标注只存在于装配结果，
     * 库里的 content 与前端展示不受影响。
     */
    private String assistantContent(MessageRow assistant) {
        List<String> tools = businessToolNames(assistant);
        if (tools.isEmpty()) {
            return assistant.content();
        }
        return assistant.content() + "\n" + TOOL_NOTE_PREFIX
                + String.join("、", tools) + TOOL_NOTE_SUFFIX;
    }

    /** 从 execution_plan 快照里取非 kb.search 的工具名（去重、保序）；弃用计划没有工具产物 */
    private List<String> businessToolNames(MessageRow assistant) {
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
    public static String lastUserContent(List<ChatTurn> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (MessageConstants.ROLE_USER.equals(history.get(i).role())) {
                return history.get(i).content();
            }
        }
        return null;
    }

    private static int totalChars(List<ChatTurn> history) {
        int total = 0;
        for (ChatTurn turn : history) {
            total += turn.content() == null ? 0 : turn.content().length();
        }
        return total;
    }

    private static final class RoundMessages {
        private MessageRow user;
        private MessageRow assistant;
        private boolean ruleReply;
    }
}
