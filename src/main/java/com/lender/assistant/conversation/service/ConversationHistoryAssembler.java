package com.lender.assistant.conversation.service;

import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.MessageRow;
import com.lender.assistant.rag.vo.ChatTurn;
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
 */
@Component
public class ConversationHistoryAssembler {

    private final MessageMapper messageMapper;
    private final int maxRounds;
    private final int maxChars;

    public ConversationHistoryAssembler(
            MessageMapper messageMapper,
            @Value("${rag.qa.history-max-rounds:6}") int maxRounds,
            @Value("${rag.qa.history-max-chars:2000}") int maxChars) {
        this.messageMapper = messageMapper;
        this.maxRounds = maxRounds;
        this.maxChars = maxChars;
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
            }
        }

        List<ChatTurn> complete = new ArrayList<>();
        for (RoundMessages round : rounds.values()) {
            if (round.user != null && round.assistant != null) {
                complete.add(new ChatTurn(MessageConstants.ROLE_USER, round.user.content()));
                complete.add(new ChatTurn(MessageConstants.ROLE_ASSISTANT, round.assistant.content()));
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
    }
}
