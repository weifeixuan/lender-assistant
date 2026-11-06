package com.lender.assistant.rag.tool.impl;

import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.tool.AgentTool;
import com.lender.assistant.rag.tool.ToolContext;
import com.lender.assistant.rag.tool.ToolErrorCode;
import com.lender.assistant.rag.tool.ToolResult;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * kb.search：计划里的知识库检索步骤。
 *
 * 只是 SearchService 的编排层适配器（ADR-0012）；执行期 previousQuestion 必须为 null，
 * 因为计划器已把上一轮信息写进自包含 query，再拼一次会让指代实体重复。
 */
@Component
public class KbSearchPlanTool implements AgentTool {

    private static final String NAME = "kb.search";
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_TOP_K = 20;
    private static final String NOT_FOUND = "资料中未提及";

    private final SearchService searchService;

    public KbSearchPlanTool(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolResult call(Map<String, Object> args, ToolContext context) {
        String query = args.get("query") instanceof String text ? text.trim() : "";
        if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
            return ToolResult.failure(ToolErrorCode.ARGS_INVALID, "query 必须为 1–200 字");
        }
        int topK = topKOf(args.get("topK"), context.defaultTopK());
        if (topK < 1 || topK > MAX_TOP_K) {
            return ToolResult.failure(ToolErrorCode.ARGS_INVALID, "topK 必须在 1–20");
        }
        List<SearchItemResponse> hits = searchService.hybridSearch(query, null, topK);
        return ToolResult.success(summarize(hits), chunkIdsOf(hits), hits);
    }

    private static int topKOf(Object raw, int fallback) {
        return raw instanceof Number number ? number.intValue() : fallback;
    }

    private static List<Long> chunkIdsOf(List<SearchItemResponse> hits) {
        List<Long> ids = new ArrayList<>();
        for (SearchItemResponse hit : hits) {
            ids.add(hit.chunkId());
        }
        return ids;
    }

    private static String summarize(List<SearchItemResponse> hits) {
        if (hits.isEmpty()) {
            return NOT_FOUND;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchItemResponse hit = hits.get(i);
            text.append("【来源").append(i + 1).append("】")
                    .append(hit.docTitle()).append(" / ")
                    .append(hit.sectionPath() == null ? "" : hit.sectionPath())
                    .append('\n').append(hit.content()).append('\n');
        }
        return text.toString();
    }
}
