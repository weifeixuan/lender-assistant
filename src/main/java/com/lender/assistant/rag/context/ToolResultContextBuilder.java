package com.lender.assistant.rag.context;

import com.lender.assistant.rag.model.response.SearchItemResponse;

import java.util.List;

/**
 * 一次工具调用的结果。
 *
 * @param ok       调用是否成功（零命中仍算成功）
 * @param content  给汇总合成用的文本产物
 * @param chunkIds 命中的知识库块 id；mock 工具恒为空
 * @param sources  命中的完整来源条目；只有 kb.search 会填充，mock 工具恒为空
 * @param errorCode 失败时的固定错误码；成功为 null
 */
public record ToolResultContextBuilder(boolean ok, String content, List<Long> chunkIds,
                         List<SearchItemResponse> sources, String errorCode) {

    public static ToolResultContextBuilder success(String content, List<Long> chunkIds) {
        return success(content, chunkIds, List.of());
    }

    public static ToolResultContextBuilder success(String content, List<Long> chunkIds,
                                     List<SearchItemResponse> sources) {
        return new ToolResultContextBuilder(true, content, chunkIds, sources, null);
    }

    public static ToolResultContextBuilder failure(ToolErrorCodeContextBuilder code, String content) {
        return new ToolResultContextBuilder(false, content, List.of(), List.of(), code.name());
    }
}
